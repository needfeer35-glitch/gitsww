package net.autocraft.crafting;

import net.autocraft.config.CraftingConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CraftingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/CraftingManager");

    private boolean active = false;
    private int craftedCount = 0;
    private int targetCount = 0;
    private CraftingConfig config;
    private ActionQueue actionQueue;
    private String itemName = "";
    private CraftingRecipe currentRecipe;
    private List<IngredientRequirement> requirements = List.of();

    private final StabilityGuard stabilityGuard = new StabilityGuard();

    private static CraftingManager INSTANCE;

    public static CraftingManager getInstance() {
        if (INSTANCE == null) INSTANCE = new CraftingManager();
        return INSTANCE;
    }

    private CraftingManager() {}

    // -------------------------------------------------------------------------
    // Публичный API
    // -------------------------------------------------------------------------

    public void start(CraftingConfig cfg, String itemName, int amount,
                      List<IngredientRequirement> requirements,
                      CraftingRecipe recipe) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!IngredientFinder.hasEnoughForCrafts(
                client.player.getInventory(), requirements, amount)) {
            LOGGER.warn("[AutoCraft] Недостаточно ресурсов для {} x{}", itemName, amount);
            sendChatMessage("§c[Авто крафт] Недостаточно ресурсов для: " + itemName + " x" + amount);
            return;
        }

        this.config       = cfg;
        this.itemName     = itemName;
        this.targetCount  = amount;
        this.requirements = requirements;
        this.currentRecipe = recipe;
        this.craftedCount  = 0;
        this.active        = true;
        this.actionQueue   = new ActionQueue(this::onActionFail);

        stabilityGuard.reset();

        LOGGER.info("[AutoCraft] Начало крафта: {} x{}", itemName, amount);
        sendChatMessage("§a[Авто крафт] Начало крафта: " + itemName + " x" + amount);

        buildNextCraftAction();
    }

    public void stop(boolean success) {
        active = false;
        if (actionQueue != null) actionQueue.clear();

        if (success) {
            LOGGER.info("[AutoCraft] Завершено! Создано: {} x{}", itemName, craftedCount);
            sendChatMessage("§a[Авто крафт] Завершено! Создано: " + itemName + " x" + craftedCount);
        } else {
            LOGGER.warn("[AutoCraft] Крафт остановлен. Создано: {} x{}", itemName, craftedCount);
            sendChatMessage("§c[Авто крафт] Остановлено. Создано: " + itemName + " x" + craftedCount);
        }
    }

    public void tick(MinecraftClient client) {
        if (!active || actionQueue == null) return;

        boolean done = actionQueue.tick(client);

        if (actionQueue.isFailed()) {
            stop(false);
            return;
        }

        if (done) {
            // Проверка результата через StabilityGuard
            CheckResult check = stabilityGuard.verifyAfter(
                client,
                currentRecipe.getResult(),
                currentRecipe.getResultCount()
            );

            switch (check) {
                case OK -> {
                    craftedCount++;
                    sendChatMessage("§e[Авто крафт] Крафт... " + craftedCount + "/" + targetCount);

                    if (craftedCount >= targetCount) {
                        stop(true);
                    } else {
                        int remaining = targetCount - craftedCount;
                        if (!IngredientFinder.hasEnoughForCrafts(
                                client.player.getInventory(), requirements, remaining)) {
                            LOGGER.warn("[AutoCraft] Ресурсы закончились на {}/{}", craftedCount, targetCount);
                            sendChatMessage("§c[Авто крафт] Ресурсы закончились! Создано: "
                                    + craftedCount + "/" + targetCount);
                            stop(false);
                            return;
                        }
                        buildNextCraftAction();
                    }
                }

                case ROLLBACK -> {
                    // Grid уже очищена в StabilityGuard — повторяем крафт
                    LOGGER.warn("[AutoCraft] Повтор крафта после отката (попытка {})",
                            stabilityGuard.getRollbackCount());
                    sendChatMessage("§e[Авто крафт] Повтор крафта...");
                    buildNextCraftAction();
                }

                case FATAL -> {
                    LOGGER.error("[AutoCraft] Фатальная рассинхронизация — крафт остановлен");
                    sendChatMessage("§c[Авто крафт] Ошибка стабильности! Крафт остановлен.");
                    stop(false);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Построение очереди действий для одного крафта
    // -------------------------------------------------------------------------

    private void buildNextCraftAction() {
        MinecraftClient client = MinecraftClient.getInstance();
        actionQueue.clear();

        // Шаг 0: проверить что состояние чистое перед стартом
        actionQueue.enqueue("Проверка чистоты grid", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (stabilityGuard.isStateClean(mc)) return true;
            // Grid грязная — принудительно очищаем
            LOGGER.warn("[AutoCraft] Grid не чистая перед крафтом — очищаю");
            return GridCleaner.clearGrid(mc);
        });

        // Шаг 1: открыть верстак (если нужно по настройкам)
        if (config.isAutoOpenCrafting()) {
            actionQueue.enqueue("Открыть верстак", () -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                return mc.currentScreen instanceof CraftingScreen;
            });
        }

        // Шаг 2: снапшот инвентаря ДО крафта
        actionQueue.enqueue("Снапшот инвентаря", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            stabilityGuard.snapshotBefore(mc, currentRecipe.getResult());
            return true; // всегда успех, просто запоминаем
        });

        // Шаг 3: разместить ингредиенты
        actionQueue.enqueue("Разместить ингредиенты", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            return GridPlacer.place(mc, currentRecipe);
        });

        // Шаг 4: взять результат
        actionQueue.enqueue("Взять результат", () -> takeResult());
    }

    // -------------------------------------------------------------------------
    // Взятие результата
    // -------------------------------------------------------------------------

    private boolean takeResult() {
        MinecraftClient client = MinecraftClient.getInstance();

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

        LOGGER.info("[AutoCraft] Взят результат: {}", result.getItem().toString());
        return true;
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    private void onActionFail() {
        LOGGER.error("[AutoCraft] ActionQueue провалил действие — крафт остановлен");
        // Попытка откатить grid перед полной остановкой
        MinecraftClient client = MinecraftClient.getInstance();
        GridCleaner.clearGrid(client);
        stop(false);
    }

    private void sendChatMessage(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(msg), false);
        }
    }

    // -------------------------------------------------------------------------
    // Геттеры для GUI
    // -------------------------------------------------------------------------

    public boolean isActive()      { return active; }
    public int getCraftedCount()   { return craftedCount; }
    public int getTargetCount()    { return targetCount; }
    public String getItemName()    { return itemName; }
    public int getRollbackCount()  { return stabilityGuard.getRollbackCount(); }
    public float getProgress() {
        if (targetCount == 0) return 0f;
        return (float) craftedCount / targetCount;
    }
}
