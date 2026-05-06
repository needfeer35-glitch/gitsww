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
 * Шаг 15: AUTO OPEN WORKBENCH
 *
 * Новая фаза WAITING_FOR_WORKBENCH добавлена между start() и buildNextCraftCycle().
 *
 * Если config.openWorkbenchAuto == true:
 *   → WorkbenchFinder.findAndOpen() ищет ближайший верстак и открывает его.
 *   → Если верстак не найден — ждём пока игрок откроет сам (режим WAIT_FOR_PLAYER).
 *
 * Если config.openWorkbenchAuto == false:
 *   → Сразу ждём пока игрок откроет верстак (режим WAIT_FOR_PLAYER).
 *
 * После того как CraftingScreen появился — переходим к buildNextCraftCycle() как раньше.
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

    /**
     * Фаза жизненного цикла крафта.
     *
     * IDLE                   — ничего не происходит
     * WAITING_FOR_WORKBENCH  — ждём пока верстак откроется (после авто-открытия или вручную)
     * CRAFTING               — активный крафт (ActionQueue тикает)
     * FINISHING              — защита от двойного вызова finish
     */
    private enum Phase {
        IDLE,
        WAITING_FOR_WORKBENCH,
        CRAFTING,
        FINISHING
    }

    private Phase   phase          = Phase.IDLE;
    private boolean paused         = false;

    private int craftedCount       = 0;
    private int targetCount        = 0;
    private String itemName        = "";

    private CraftingRecipe currentRecipe;
    private List<IngredientRequirement> requirements = List.of();

    private ActionQueue    actionQueue;
    private StabilityGuard stabilityGuard = new StabilityGuard();

    // Тики ожидания верстака (защита от бесконечного висения)
    private int workbenchWaitTicks = 0;
    private static final int WORKBENCH_WAIT_TIMEOUT_TICKS = 20 * 30; // 30 секунд

    // Был ли уже выполнен авто-открытие в текущей сессии ожидания
    private boolean autoOpenAttempted = false;

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Запустить новый крафт.
     * Шаг 15: сначала входим в фазу WAITING_FOR_WORKBENCH.
     */
    public void start(String itemName,
                      int amount,
                      List<IngredientRequirement> requirements,
                      CraftingRecipe recipe) {

        if (amount <= 0) {
            LOGGER.warn("[AutoCraft] start() вызван с amount <= 0, игнорируем.");
            return;
        }
        if (recipe == null) {
            LOGGER.error("[AutoCraft] start() вызван с null рецептом, игнорируем.");
            return;
        }

        // Если предыдущий крафт ещё идёт — тихо остановим его
        if (phase != Phase.IDLE) {
            stopInternal(false);
        }

        MinecraftClient client = MinecraftClient.getInstance();

        // Проверка ресурсов ДО старта
        if (!IngredientFinder.hasEnoughForCrafts(
                client.player.getInventory(), requirements, amount)) {
            LOGGER.warn("[AutoCraft] Недостаточно ресурсов для {} x{}", itemName, amount);
            sendChat("§c[Авто крафт] Недостаточно ресурсов для: " + itemName + " x" + amount);
            return;
        }

        // Инициализация
        this.itemName              = itemName != null ? itemName : "?";
        this.targetCount           = amount;
        this.requirements          = requirements != null ? requirements : List.of();
        this.currentRecipe         = recipe;
        this.craftedCount          = 0;
        this.paused                = false;
        this.workbenchWaitTicks    = 0;
        this.autoOpenAttempted     = false;

        this.actionQueue    = new ActionQueue(this::onActionFail, this::onActionQueueFinished);
        this.stabilityGuard = new StabilityGuard();

        LOGGER.info("[AutoCraft] Старт крафта: {} x{}", this.itemName, targetCount);
        sendChat("§a[Авто крафт] Начало крафта: " + this.itemName + " x" + targetCount);

        // ── ШАГ 15: Переходим в фазу ожидания верстака ────────────────────────
        enterWaitingForWorkbench(client);
    }

    /**
     * Полная остановка с уведомлением.
     */
    public void stop() {
        if (phase == Phase.IDLE) return;
        stopInternal(true);
    }

    public void pause() {
        if (phase == Phase.IDLE || paused) return;
        paused = true;
        if (actionQueue != null) actionQueue.pause();
        LOGGER.info("[AutoCraft] Пауза.");
        sendChat("§e[Авто крафт] Пауза.");
    }

    public void resume() {
        if (phase == Phase.IDLE || !paused) return;
        paused = false;
        if (actionQueue != null) actionQueue.resume();
        LOGGER.info("[AutoCraft] Возобновлено.");
        sendChat("§a[Авто крафт] Возобновлено.");
    }

    public void togglePause() {
        if (paused) resume();
        else pause();
    }

    // ── Тик ──────────────────────────────────────────────────────────────────

    /**
     * Вызывается каждый клиентский тик из AutoCraftMod.
     *
     * Диспетчер по фазам:
     *   IDLE               → ничего
     *   WAITING_FOR_WORKBENCH → tickWaitingForWorkbench()
     *   CRAFTING           → tickCrafting()
     *   FINISHING          → ничего
     */
    public void tick(MinecraftClient client) {
        if (paused) return;
        if (client.player == null) {
            if (phase != Phase.IDLE) {
                LOGGER.warn("[AutoCraft] Игрок null — аварийная остановка.");
                stopInternal(false);
            }
            return;
        }

        switch (phase) {
            case WAITING_FOR_WORKBENCH -> tickWaitingForWorkbench(client);
            case CRAFTING              -> tickCrafting(client);
            default                    -> {} // IDLE и FINISHING — молча
        }
    }

    // ── Фаза WAITING_FOR_WORKBENCH ────────────────────────────────────────────

    /**
     * Шаг 15 — логика ожидания верстака.
     *
     * Каждый тик:
     *   1. Если верстак уже открыт → переходим к крафту
     *   2. Если авто-открытие включено и ещё не пробовали → пробуем открыть
     *   3. Ждём (workbenchWaitTicks++)
     *   4. По таймауту — останавливаемся с сообщением
     */
    private void tickWaitingForWorkbench(MinecraftClient client) {
        // Верстак открыт — отлично, начинаем крафт
        if (WorkbenchFinder.isWorkbenchOpen(client)) {
            LOGGER.info("[AutoCraft] Верстак открыт — начинаю крафт.");
            sendChat("§a[Авто крафт] Верстак открыт. Крафтю...");
            phase = Phase.CRAFTING;
            buildNextCraftCycle();
            return;
        }

        // Авто-открытие: один раз за сессию ожидания
        if (AutoCraftConfig.get().openWorkbenchAuto && !autoOpenAttempted) {
            autoOpenAttempted = true;
            LOGGER.info("[AutoCraft] Пытаюсь найти и открыть верстак...");
            boolean found = WorkbenchFinder.findAndOpen(client);
            if (!found) {
                sendChat("§e[Авто крафт] Верстак не найден рядом. Открой верстак вручную.");
            }
            // Не выходим — ждём следующего тика когда screen появится
            return;
        }

        // Таймаут ожидания
        workbenchWaitTicks++;
        if (workbenchWaitTicks >= WORKBENCH_WAIT_TIMEOUT_TICKS) {
            LOGGER.error("[AutoCraft] Таймаут ожидания верстака ({} тиков).",
                    WORKBENCH_WAIT_TIMEOUT_TICKS);
            sendChat("§c[Авто крафт] Верстак не открыт за 30 сек. Крафт отменён.");
            stopInternal(false);
            return;
        }

        // Каждые 5 секунд — напоминание игроку (если авто не включено)
        if (!AutoCraftConfig.get().openWorkbenchAuto
                && workbenchWaitTicks % (20 * 5) == 0) {
            sendChat("§e[Авто крафт] Жду открытия верстака... ("
                    + (workbenchWaitTicks / 20) + "с)");
        }
    }

    // ── Фаза CRAFTING ─────────────────────────────────────────────────────────

    private void tickCrafting(MinecraftClient client) {
        if (actionQueue == null) return;

        // Если во время крафта игрок закрыл верстак — возвращаемся в ожидание
        if (!WorkbenchFinder.isWorkbenchOpen(client)) {
            LOGGER.warn("[AutoCraft] Верстак закрыт во время крафта — жду повторного открытия.");
            sendChat("§e[Авто крафт] Верстак закрыт. Открой снова для продолжения.");
            phase               = Phase.WAITING_FOR_WORKBENCH;
            workbenchWaitTicks  = 0;
            autoOpenAttempted   = false;
            if (actionQueue != null) actionQueue.pause();
            return;
        }

        // Если пауза была снята — возобновляем очередь
        if (!paused && actionQueue.isPaused()) {
            actionQueue.resume();
        }

        actionQueue.tick(client);
    }

    // ── Построение цикла крафта ───────────────────────────────────────────────

    private void buildNextCraftCycle() {
        actionQueue.clear();

        // Шаг 1: Проверить и очистить grid
        actionQueue.enqueue(
                "Проверка/очистка grid",
                () -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (stabilityGuard.isStateClean(mc)) return true;
                    LOGGER.warn("[AutoCraft] Grid не чистая перед крафтом — очищаю");
                    return GridCleaner.clearGrid(mc);
                },
                () -> GridCleaner.isGridEmpty(MinecraftClient.getInstance()),
                2
        );

        // Шаг 2: Снапшот инвентаря
        actionQueue.enqueue(
                "Снапшот инвентаря",
                () -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (!(mc.currentScreen instanceof CraftingScreen)) {
                        LOGGER.warn("[AutoCraft] Верстак закрыт перед снапшотом");
                        return false;
                    }
                    stabilityGuard.snapshotBefore(mc, currentRecipe.getResult());
                    return true;
                },
                1
        );

        // Шаг 3: Разместить ингредиенты
        actionQueue.enqueue(
                "Разместить ингредиенты",
                () -> GridPlacer.place(MinecraftClient.getInstance(), currentRecipe),
                () -> resultReady(MinecraftClient.getInstance()),
                3
        );

        // Шаг 4: Взять результат
        actionQueue.enqueue(
                "Взять результат",
                () -> takeResult(MinecraftClient.getInstance()),
                () -> resultTaken(MinecraftClient.getInstance()),
                2
        );
    }

    // ── Колбэки ActionQueue ───────────────────────────────────────────────────

    private void onActionQueueFinished() {
        if (phase == Phase.FINISHING || phase == Phase.IDLE) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        CheckResult check = stabilityGuard.verifyAfter(
                mc,
                currentRecipe.getResult(),
                currentRecipe.getResultCount()
        );

        switch (check) {
            case OK -> {
                craftedCount++;
                LOGGER.info("[AutoCraft] Цикл {} / {} завершён.", craftedCount, targetCount);

                if (craftedCount >= targetCount) {
                    finishSuccess();
                } else {
                    buildNextCraftCycle();
                }
            }
            case ROLLBACK -> {
                LOGGER.warn("[AutoCraft] Откат #{} — повтор цикла.", stabilityGuard.getRollbackCount());
                buildNextCraftCycle();
            }
            case FATAL -> {
                LOGGER.error("[AutoCraft] Фатальная ошибка StabilityGuard — остановка.");
                sendChat("§c[Авто крафт] Критическая ошибка. Крафт остановлен.");
                stopInternal(false);
            }
        }
    }

    private void onActionFail() {
        LOGGER.error("[AutoCraft] Действие провалилось — аварийная остановка");
        sendChat("§c[Авто крафт] Ошибка! Крафт остановлен.");

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof CraftingScreen) {
            GridCleaner.clearGrid(mc);
        }

        stopInternal(false);
    }

    // ── Действия над верстаком ────────────────────────────────────────────────

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
                handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
        LOGGER.debug("[AutoCraft] takeResult: взят {}", result.getItem());
        return true;
    }

    private boolean resultTaken(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return true;
        return screen.getScreenHandler().slots.get(0).getStack().isEmpty();
    }

    private boolean resultReady(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return false;
        return !screen.getScreenHandler().slots.get(0).getStack().isEmpty();
    }

    // ── Старт/стоп ────────────────────────────────────────────────────────────

    /**
     * Инициализировать фазу ожидания верстака.
     * Шаг 15: единая точка входа после start().
     */
    private void enterWaitingForWorkbench(MinecraftClient client) {
        workbenchWaitTicks = 0;
        autoOpenAttempted  = false;
        phase              = Phase.WAITING_FOR_WORKBENCH;

        // Если верстак уже открыт — незачем ждать, сразу крафтим
        if (WorkbenchFinder.isWorkbenchOpen(client)) {
            LOGGER.info("[AutoCraft] Верстак уже открыт — сразу к крафту.");
            phase = Phase.CRAFTING;
            buildNextCraftCycle();
            return;
        }

        if (AutoCraftConfig.get().openWorkbenchAuto) {
            sendChat("§e[Авто крафт] Ищу верстак рядом...");
        } else {
            sendChat("§e[Авто крафт] Открой верстак для начала крафта.");
        }
    }

    private void stopInternal(boolean notify) {
        if (phase == Phase.FINISHING) return;
        phase  = Phase.FINISHING;
        paused = false;

        if (actionQueue != null) actionQueue.clear();

        if (notify) {
            LOGGER.info("[AutoCraft] Крафт остановлен. Создано: {} x{}", itemName, craftedCount);
            sendChat("§c[Авто крафт] Остановлено. Создано: " + itemName + " x" + craftedCount);
        }

        phase = Phase.IDLE;
    }

    private void finishSuccess() {
        if (phase == Phase.FINISHING) return;
        phase  = Phase.FINISHING;
        paused = false;

        if (actionQueue != null) actionQueue.clear();

        if (AutoCraftConfig.get().soundNotification) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
        }

        LOGGER.info("[AutoCraft] ✓ Завершено! Создано: {} x{}", itemName, craftedCount);
        sendChat("§a[Авто крафт] Завершено! Создано: " + itemName + " x" + craftedCount);

        phase = Phase.IDLE;
    }

    // ── Геттеры для GUI ───────────────────────────────────────────────────────

    /** Крафт активен (не IDLE) */
    public boolean isActive()           { return phase != Phase.IDLE; }

    /** Ждёт открытия верстака */
    public boolean isWaitingForWorkbench() { return phase == Phase.WAITING_FOR_WORKBENCH; }

    public boolean isPaused()           { return paused; }
    public int getCraftedCount()        { return craftedCount; }
    public int getTargetCount()         { return targetCount; }
    public String getItemName()         { return itemName; }
    public int getRollbackCount()       { return stabilityGuard.getRollbackCount(); }
    public ActionQueue getActionQueue() { return actionQueue; }

    public float getProgress() {
        if (targetCount <= 0) return 0f;
        return Math.min(1f, (float) craftedCount / targetCount);
    }

    /**
     * Статус в одну строку — для GUI.
     */
    public String getStatusString() {
        return switch (phase) {
            case IDLE                   -> "§8Ожидание...";
            case WAITING_FOR_WORKBENCH  -> "§eЖду верстак... (" + (workbenchWaitTicks / 20) + "с)";
            case CRAFTING               -> paused ? "§ePAUSED " : "§aCRAFTING ";
            case FINISHING              -> "§7Завершение...";
        };
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    private void sendChat(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
        }
    }
}
