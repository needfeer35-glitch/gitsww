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
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CraftingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/CraftingManager");

    // ── Состояние ─────────────────────────────────────────────────────────────

    private boolean active       = false;
    private boolean paused       = false;
    private int craftedCount     = 0;
    private int targetCount      = 0;
    private String itemName      = "";
    private CraftingRecipe currentRecipe;
    private List<IngredientRequirement> requirements = List.of();

    private ActionQueue actionQueue;
    private final StabilityGuard stabilityGuard = new StabilityGuard();

    private static CraftingManager INSTANCE;

    public static CraftingManager getInstance() {
        if (INSTANCE == null) INSTANCE = new CraftingManager();
        return INSTANCE;
    }

    private CraftingManager() {}

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Запустить крафт.
     */
    public void start(String itemName,
                      int amount,
                      List<IngredientRequirement> requirements,
                      CraftingRecipe recipe) {

        MinecraftClient client = MinecraftClient.getInstance();

        // Проверка ресурсов перед стартом
        if (!IngredientFinder.hasEnoughForCrafts(
                client.player.getInventory(), requirements, amount)) {
            LOGGER.warn("[AutoCraft] Недостаточно ресурсов для {} x{}", itemName, amount);
            sendChat("§c[Авто крафт] Недостаточно ресурсов для: " + itemName + " x" + amount);
            return;
        }

        this.itemName     = itemName;
        this.targetCount  = amount;
        this.requirements = requirements;
        this.currentRecipe = recipe;
        this.craftedCount  = 0;
        this.active        = true;
        this.paused        = false;

        // Создаём очередь с двумя колбэками: onFail и onFinish
        this.actionQueue = new ActionQueue(this::onActionFail, this::onActionQueueFinished);

        stabilityGuard.reset();

        LOGGER.info("[AutoCraft] Начало крафта: {} x{}", itemName, amount);
        sendChat("§a[Авто крафт] Начало крафта: " + itemName + " x" + amount);

        buildNextCraftCycle();
    }

    /**
     * Полная остановка крафта.
     */
    public void stop() {
        if (!active) return;
        active = false;
        paused = false;
        if (actionQueue != null) actionQueue.clear();

        LOGGER.warn("[AutoCraft] Крафт остановлен. Создано: {} x{}", itemName, craftedCount);
        sendChat("§c[Авто крафт] Остановлено. Создано: " + itemName + " x" + craftedCount);
    }

    /**
     * Пауза — крафт замораживается на текущем шаге.
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

    public void tick(MinecraftClient client) {
        if (!active || actionQueue == null) return;
        if (paused) return;

        actionQueue.tick(client);

        // Провал обрабатывается через колбэк onActionFail
        // Завершение обрабатывается через колбэк onActionQueueFinished
    }

    // ── Построение цикла крафта ───────────────────────────────────────────────

    private void buildNextCraftCycle() {
        actionQueue.clear();

        // Шаг 1: проверить чистоту grid, при необходимости очистить
        actionQueue.enqueue("Проверка/очистка grid", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (stabilityGuard.isStateClean(mc)) return true;
            LOGGER.warn("[AutoCraft] Grid не чистая — очищаю");
            return GridCleaner.clearGrid(mc);
        }, 2);

        // Шаг 2: снапшот инвентаря ДО крафта (всегда true, просто запоминаем)
        actionQueue.enqueue("Снапшот инвентаря", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            stabilityGuard.snapshotBefore(mc, currentRecipe.getResult());
            return true;
        }, 1);

        // Шаг 3: разместить ингредиенты в сетке
        // Подтверждение: результат появился в слоте 0
        actionQueue.enqueue(
                "Разместить ингредиенты",
                () -> GridPlacer.place(MinecraftClient.getInstance(), currentRecipe),
                () -> resultReady(MinecraftClient.getInstance()),
                3
        );

        // Шаг 4: взять результат через shift-click
        // Подтверждение: слот 0 стал пустым (предмет ушёл в инвентарь)
        actionQueue.enqueue(
                "Взять результат",
                () -> takeResult(MinecraftClient.getInstance()),
                () -> resultTaken(MinecraftClient.getInstance()),
                2
        );
    }

    // ── Колбэки ActionQueue ───────────────────────────────────────────────────

    /**
     * Вызывается когда ActionQueue успешно завершила ВСЕ шаги одного цикла.
     */
    private void onActionQueueFinished() {
        MinecraftClient client = MinecraftClient.getInstance();

        // Проверяем через StabilityGuard что предмет реально попал в инвентарь
        CheckResult check = stabilityGuard.verifyAfter(
                client,
                currentRecipe.getResult(),
                currentRecipe.getResultCount()
        );

        switch (check) {
            case OK -> {
                craftedCount++;
                LOGGER.info("[AutoCraft] Крафт {}/{}", craftedCount, targetCount);
                sendChat("§e[Авто крафт] Крафт... " + craftedCount + "/" + targetCount);

                if (craftedCount >= targetCount) {
                    // Всё готово!
                    finishSuccess();
                } else {
                    // Проверить ресурсы на следующий цикл
                    int remaining = targetCount - craftedCount;
                    if (!IngredientFinder.hasEnoughForCrafts(
                            client.player.getInventory(), requirements, remaining)) {
                        LOGGER.warn("[AutoCraft] Ресурсы закончились на {}/{}", craftedCount, targetCount);
                        sendChat("§c[Авто крафт] Ресурсы закончились! Создано: "
                                + craftedCount + "/" + targetCount);
                        stop();
                        return;
                    }
                    // Следующий цикл
                    buildNextCraftCycle();
                }
            }

            case ROLLBACK -> {
                LOGGER.warn("[AutoCraft] Откат #{} — повтор цикла",
                        stabilityGuard.getRollbackCount());
                sendChat("§e[Авто крафт] Повтор крафта...");
                buildNextCraftCycle();
            }

            case FATAL -> {
                LOGGER.error("[AutoCraft] Фатальная рассинхронизация — остановка");
                sendChat("§c[Авто крафт] Ошибка стабильности! Крафт остановлен.");
                stop();
            }
        }
    }

    /**
     * Вызывается когда ActionQueue провалила действие после MAX_RETRIES.
     */
    private void onActionFail() {
        LOGGER.error("[AutoCraft] ActionQueue провалила действие — аварийная остановка");
        // Пробуем очистить grid перед остановкой
        GridCleaner.clearGrid(MinecraftClient.getInstance());
        stop();
    }

    // ── Действия крафта ───────────────────────────────────────────────────────

    /**
     * Shift-click по слоту результата (слот 0).
     */
    private boolean takeResult(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) {
            LOGGER.warn("[AutoCraft] Верстак не открыт при попытке взять результат");
            return false;
        }

        CraftingScreenHandler handler = screen.getScreenHandler();
        ItemStack result = handler.slots.get(0).getStack();

        if (result.isEmpty()) {
            LOGGER.warn("[AutoCraft] Слот результата пуст");
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player
        );

        LOGGER.info("[AutoCraft] Взят результат: {}", result.getItem());
        return true;
    }

    /**
     * Подтверждение: результат взят — слот 0 пуст.
     */
    private boolean resultTaken(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return true;
        return screen.getScreenHandler().slots.get(0).getStack().isEmpty();
    }

    /**
     * Проверка: результат появился в слоте 0.
     */
    private boolean resultReady(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return false;
        return !screen.getScreenHandler().slots.get(0).getStack().isEmpty();
    }

    // ── Завершение ────────────────────────────────────────────────────────────

    private void finishSuccess() {
        active = false;
        paused = false;

        // Звуковое уведомление
        if (AutoCraftConfig.get().soundNotification) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.playSound(
                        net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        1f, 1f
                );
            }
        }

        LOGGER.info("[AutoCraft] Завершено! Создано: {} x{}", itemName, craftedCount);
        sendChat("§a[Авто крафт] Завершено! Создано: " + itemName + " x" + craftedCount);
    }

    // ── Геттеры для GUI ───────────────────────────────────────────────────────

    public boolean isActive()          { return active; }
    public boolean isPaused()          { return paused; }
    public int getCraftedCount()       { return craftedCount; }
    public int getTargetCount()        { return targetCount; }
    public String getItemName()        { return itemName; }
    public int getRollbackCount()      { return stabilityGuard.getRollbackCount(); }
    public ActionQueue getActionQueue(){ return actionQueue; }

    public float getProgress() {
        if (targetCount == 0) return 0f;
        return (float) craftedCount / targetCount;
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    private void sendChat(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
        }
    }
}
