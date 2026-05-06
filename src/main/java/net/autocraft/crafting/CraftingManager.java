package net.autocraft.crafting;

import net.autocraft.action.ActionQueue;
import net.autocraft.config.AutoCraftConfig;
import net.autocraft.inventory.IngredientFinder;
import net.autocraft.inventory.IngredientFinder.IngredientRequirement;
import net.autocraft.recipe.CraftingRecipe;
import net.autocraft.recipe.GridCleaner;
import net.autocraft.recipe.GridPlacer;
import net.autocraft.stability.StabilityGuard;
import net.autocraft.stability.StabilityGuard.CheckResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * CraftingManager — центральный контроллер авто-крафта.
 *
 * Жизненный цикл:
 *   start() → buildNextCraftCycle() → [тики] → onActionQueueFinished()
 *           → buildNextCraftCycle() (повтор) или finishSuccess() / stop()
 *
 * Потокобезопасность: все вызовы происходят из главного потока Minecraft (client tick).
 * Не вызывать методы из других потоков.
 */
public class CraftingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/CraftingManager");

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static CraftingManager INSTANCE;

    public static CraftingManager getInstance() {
        if (INSTANCE == null) INSTANCE = new CraftingManager();
        return INSTANCE;
    }

    private CraftingManager() {}

    // ── Внутреннее состояние ──────────────────────────────────────────────────

    private boolean active        = false;  // крафт запущен
    private boolean paused        = false;  // крафт на паузе
    private boolean finishing     = false;  // флаг защиты от двойного вызова finish

    private int craftedCount      = 0;      // сколько циклов уже выполнено
    private int targetCount       = 0;      // сколько нужно выполнить
    private String itemName       = "";     // название предмета для GUI/чата

    private CraftingRecipe currentRecipe;
    private List<IngredientRequirement> requirements = List.of();

    private ActionQueue    actionQueue;
    private StabilityGuard stabilityGuard = new StabilityGuard();

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Запустить новый крафт.
     * Если крафт уже идёт — старый будет полностью остановлен.
     *
     * @param itemName     название предмета (только для отображения)
     * @param amount       сколько раз выполнить крафт
     * @param requirements список требуемых ингредиентов (для предварительной проверки)
     * @param recipe       рецепт крафта
     */
    public void start(String itemName,
                      int amount,
                      List<IngredientRequirement> requirements,
                      CraftingRecipe recipe) {

        // Защита от некорректных аргументов
        if (amount <= 0) {
            LOGGER.warn("[AutoCraft] start() вызван с amount <= 0, игнорируем.");
            return;
        }
        if (recipe == null) {
            LOGGER.error("[AutoCraft] start() вызван с null рецептом, игнорируем.");
            return;
        }

        // Если предыдущий крафт ещё идёт — тихо остановим его без сообщения
        if (active) {
            stopInternal(false);
        }

        MinecraftClient client = MinecraftClient.getInstance();

        // Проверка наличия ресурсов ДО старта
        if (!IngredientFinder.hasEnoughForCrafts(
                client.player.getInventory(), requirements, amount)) {
            LOGGER.warn("[AutoCraft] Недостаточно ресурсов для {} x{}", itemName, amount);
            sendChat("§c[Авто крафт] Недостаточно ресурсов для: " + itemName + " x" + amount);
            return;
        }

        // Инициализация состояния
        this.itemName       = itemName != null ? itemName : "?";
        this.targetCount    = amount;
        this.requirements   = requirements != null ? requirements : List.of();
        this.currentRecipe  = recipe;
        this.craftedCount   = 0;
        this.active         = true;
        this.paused         = false;
        this.finishing      = false;

        // Пересоздаём очередь и guard для чистого старта
        this.actionQueue    = new ActionQueue(this::onActionFail, this::onActionQueueFinished);
        this.stabilityGuard = new StabilityGuard();

        LOGGER.info("[AutoCraft] Старт крафта: {} x{}", this.itemName, targetCount);
        sendChat("§a[Авто крафт] Начало крафта: " + this.itemName + " x" + targetCount);

        buildNextCraftCycle();
    }

    /**
     * Полная остановка с уведомлением игрока.
     */
    public void stop() {
        if (!active) return;
        stopInternal(true);
    }

    /**
     * Пауза — замораживает выполнение на текущем шаге.
     * Безопасно вызывать в любой момент во время крафта.
     */
    public void pause() {
        if (!active || paused) return;
        paused = true;
        if (actionQueue != null) actionQueue.pause();
        LOGGER.info("[AutoCraft] Пауза.");
        sendChat("§e[Авто крафт] Пауза.");
    }

    /**
     * Возобновить после паузы.
     */
    public void resume() {
        if (!active || !paused) return;
        paused = false;
        if (actionQueue != null) actionQueue.resume();
        LOGGER.info("[AutoCraft] Возобновлено.");
        sendChat("§a[Авто крафт] Возобновлено.");
    }

    /**
     * Переключатель паузы — удобно вешать на кнопку GUI.
     */
    public void togglePause() {
        if (paused) resume();
        else pause();
    }

    // ── Тик ──────────────────────────────────────────────────────────────────

    /**
     * Вызывается каждый клиентский тик из AutoCraftMod.
     * Здесь происходит вся логика выполнения очереди.
     */
    public void tick(MinecraftClient client) {
        if (!active || paused || actionQueue == null) return;

        // Если игрок вышел или умер — аварийная остановка
        if (client.player == null) {
            LOGGER.warn("[AutoCraft] Игрок null — аварийная остановка.");
            stopInternal(false);
            return;
        }

        // Тикаем очередь — колбэки onActionFail/onActionQueueFinished
        // вызываются изнутри actionQueue.tick()
        actionQueue.tick(client);
    }

    // ── Построение цикла крафта ───────────────────────────────────────────────

    /**
     * Строит ActionQueue для одного цикла крафта (4 шага).
     * Вызывается при старте и после каждого успешного цикла.
     */
    private void buildNextCraftCycle() {
        // Сбрасываем очередь (на случай если осталось что-то от предыдущего цикла)
        actionQueue.clear();

        // ── Шаг 1: Проверить и очистить grid ─────────────────────────────────
        // Если grid загрязнена с прошлого раза — вернуть предметы в инвентарь.
        // Confirmation: grid пуста → можно продолжать.
        actionQueue.enqueue(
                "Проверка/очистка grid",
                () -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    // isStateClean проверяет и слот результата, и саму сетку
                    if (stabilityGuard.isStateClean(mc)) return true;
                    LOGGER.warn("[AutoCraft] Grid не чистая перед крафтом — очищаю");
                    return GridCleaner.clearGrid(mc);
                },
                () -> GridCleaner.isGridEmpty(MinecraftClient.getInstance()),
                2  // 2 тика задержки перед выполнением
        );

        // ── Шаг 2: Сохранить снапшот инвентаря ───────────────────────────────
        // Запомнить количество целевого предмета ДО того как взять результат.
        // Нужно StabilityGuard для проверки после крафта.
        // Confirmation не нужно — это чисто локальная операция.
        actionQueue.enqueue(
                "Снапшот инвентаря",
                () -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    // Проверяем что игрок всё ещё у верстака
                    if (!(mc.currentScreen instanceof CraftingScreen)) {
                        LOGGER.warn("[AutoCraft] Верстак закрыт перед снапшотом");
                        return false;
                    }
                    stabilityGuard.snapshotBefore(mc, currentRecipe.getResult());
                    return true;
                },
                1  // минимальная задержка
        );

        // ── Шаг 3: Разместить ингредиенты в сетке ────────────────────────────
        // GridPlacer перекладывает предметы из инвентаря в grid клик за кликом.
        // Confirmation: слот 0 (результат) стал непустым — сервер рассчитал рецепт.
        actionQueue.enqueue(
                "Разместить ингредиенты",
                () -> GridPlacer.place(MinecraftClient.getInstance(), currentRecipe),
                () -> resultReady(MinecraftClient.getInstance()),
                3  // 3 тика — дать серверу время на обработку предыдущего шага
        );

        // ── Шаг 4: Взять результат ────────────────────────────────────────────
        // Shift-click по слоту 0 → предмет уходит в инвентарь.
        // Confirmation: слот 0 стал пустым — предмет действительно ушёл.
        actionQueue.enqueue(
                "Взять результат",
                () -> takeResult(MinecraftClient.getInstance()),
                () -> resultTaken(MinecraftClient.getInstance()),
                2
        );
    }

    // ── Колбэки ActionQueue ───────────────────────────────────────────────────

    /**
     * Вызывается ActionQueue когда ВСЕ 4 шага одного цикла выполнены.
     * Здесь проверяем результат через StabilityGuard и решаем — продолжать или нет.
     */
    private void onActionQueueFinished() {
        // Защита от повторного вызова (например если колбэк сработал дважды)
        if (!active || finishing) return;

        MinecraftClient client = MinecraftClient.getInstance();

        // StabilityGuard сравнивает инвентарь ДО и ПОСЛЕ
        CheckResult check = stabilityGuard.verifyAfter(
                client,
                currentRecipe.getResult(),
                currentRecipe.getResultCount()
        );

        switch (check) {

            case OK -> {
                craftedCount++;
                LOGGER.info("[AutoCraft] ✓ Цикл {}/{} завершён", craftedCount, targetCount);
                sendChat("§e[Авто крафт] Крафт... " + craftedCount + "/" + targetCount);

                if (craftedCount >= targetCount) {
                    // Достигли нужного количества
                    finishSuccess();
                } else {
                    // Нужно ещё — проверяем ресурсы на следующий цикл
                    int remaining = targetCount - craftedCount;
                    if (!IngredientFinder.hasEnoughForCrafts(
                            client.player.getInventory(), requirements, remaining)) {
                        LOGGER.warn("[AutoCraft] Ресурсы закончились на {}/{}", craftedCount, targetCount);
                        sendChat("§c[Авто крафт] Ресурсы закончились! Создано: "
                                + craftedCount + "/" + targetCount);
                        stopInternal(false); // не выводим повторное сообщение об остановке
                        return;
                    }
                    // Запускаем следующий цикл
                    buildNextCraftCycle();
                }
            }

            case ROLLBACK -> {
                // Рассинхронизация — StabilityGuard уже очистил grid
                // Повторяем тот же цикл заново
                LOGGER.warn("[AutoCraft] Откат #{} — повтор цикла",
                        stabilityGuard.getRollbackCount());
                sendChat("§e[Авто крафт] Повтор крафта...");
                buildNextCraftCycle();
            }

            case FATAL -> {
                // Слишком много откатов подряд — что-то серьёзно не так
                LOGGER.error("[AutoCraft] Фатальная ошибка стабильности — остановка");
                sendChat("§c[Авто крафт] Ошибка стабильности! Крафт остановлен.");
                stopInternal(false);
            }
        }
    }

    /**
     * Вызывается ActionQueue когда действие провалилось после MAX_RETRIES попыток.
     * Это аварийная ситуация — пытаемся очистить grid и останавливаемся.
     */
    private void onActionFail() {
        if (!active) return;
        LOGGER.error("[AutoCraft] Действие провалилось после всех попыток — аварийная остановка");
        sendChat("§c[Авто крафт] Ошибка! Крафт остановлен.");

        // Пробуем вернуть предметы из grid в инвентарь
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof CraftingScreen) {
            GridCleaner.clearGrid(mc);
        }

        stopInternal(false); // сообщение уже отправили выше
    }

    // ── Действия над верстаком ────────────────────────────────────────────────

    /**
     * Shift-click по слоту результата (слот 0).
     * Переносит готовый предмет в инвентарь игрока.
     */
    private boolean takeResult(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) {
            LOGGER.warn("[AutoCraft] takeResult: верстак не открыт");
            return false;
        }

        CraftingScreenHandler handler = screen.getScreenHandler();
        ItemStack result = handler.slots.get(0).getStack();

        if (result.isEmpty()) {
            LOGGER.warn("[AutoCraft] takeResult: слот результата пуст");
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        LOGGER.debug("[AutoCraft] takeResult: взят {}", result.getItem());
        return true;
    }

    /**
     * Проверка подтверждения для takeResult:
     * слот 0 стал пустым → предмет успешно ушёл в инвентарь.
     */
    private boolean resultTaken(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) {
            // Экран закрылся — считаем что предмет взят (возможно игрок закрыл сам)
            return true;
        }
        return screen.getScreenHandler().slots.get(0).getStack().isEmpty();
    }

    /**
     * Проверка: результат появился в слоте 0 после размещения ингредиентов.
     * Используется как confirmation для шага "Разместить ингредиенты".
     */
    private boolean resultReady(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return false;
        return !screen.getScreenHandler().slots.get(0).getStack().isEmpty();
    }

    // ── Внутренняя остановка ──────────────────────────────────────────────────

    /**
     * Внутренняя остановка. Если notify=true — отправит сообщение игроку.
     * Используется как для штатной, так и для аварийной остановки.
     */
    private void stopInternal(boolean notify) {
        if (finishing) return; // предотвращаем двойной вызов
        finishing = true;
        active    = false;
        paused    = false;

        if (actionQueue != null) {
            actionQueue.clear();
        }

        if (notify) {
            LOGGER.info("[AutoCraft] Крафт остановлен. Создано: {} x{}", itemName, craftedCount);
            sendChat("§c[Авто крафт] Остановлено. Создано: " + itemName + " x" + craftedCount);
        }

        finishing = false;
    }

    /**
     * Успешное завершение всего задания.
     */
    private void finishSuccess() {
        if (finishing) return;
        finishing = true;
        active    = false;
        paused    = false;

        if (actionQueue != null) actionQueue.clear();

        // Звуковое уведомление (если включено в конфиге)
        if (AutoCraftConfig.get().soundNotification) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
        }

        LOGGER.info("[AutoCraft] ✓ Завершено! Создано: {} x{}", itemName, craftedCount);
        sendChat("§a[Авто крафт] Завершено! Создано: " + itemName + " x" + craftedCount);

        finishing = false;
    }

    // ── Геттеры для GUI ───────────────────────────────────────────────────────

    /** Крафт активен (запущен и не остановлен) */
    public boolean isActive()           { return active; }

    /** Крафт на паузе */
    public boolean isPaused()           { return paused; }

    /** Сколько циклов уже выполнено */
    public int getCraftedCount()        { return craftedCount; }

    /** Сколько циклов нужно выполнить всего */
    public int getTargetCount()         { return targetCount; }

    /** Название предмета (для GUI) */
    public String getItemName()         { return itemName; }

    /** Количество откатов (для отладки в GUI) */
    public int getRollbackCount()       { return stabilityGuard.getRollbackCount(); }

    /** Прямой доступ к очереди (для GUI — pause/resume кнопки) */
    public ActionQueue getActionQueue() { return actionQueue; }

    /** Прогресс 0.0 – 1.0 (для прогресс-бара в GUI) */
    public float getProgress() {
        if (targetCount <= 0) return 0f;
        return Math.min(1f, (float) craftedCount / targetCount);
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    private void sendChat(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
        }
    }
}
