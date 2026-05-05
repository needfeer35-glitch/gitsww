package net.autocraft.crafting;

import net.autocraft.config.CraftingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CraftingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/CraftingManager");

    private boolean active = false;
    private int craftedCount = 0;
    private int targetCount = 0;
    private CraftingConfig config;
    private ActionQueue actionQueue;
    private String itemName = "";

    private static CraftingManager INSTANCE;

    public static CraftingManager getInstance() {
        if (INSTANCE == null) INSTANCE = new CraftingManager();
        return INSTANCE;
    }

    private CraftingManager() {}

    public void start(CraftingConfig cfg, String itemName, int amount) {
        this.config = cfg;
        this.itemName = itemName;
        this.targetCount = amount;
        this.craftedCount = 0;
        this.active = true;
        this.actionQueue = new ActionQueue(this::onActionFail);
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
            LOGGER.warn("[AutoCraft] Крафт остановлен досрочно. Создано: {} x{}", itemName, craftedCount);
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
            craftedCount++;
            sendChatMessage("§e[Авто крафт] Крафт... " + craftedCount + "/" + targetCount);
            if (craftedCount >= targetCount) {
                stop(true);
            } else {
                buildNextCraftAction();
            }
        }
    }

    private void buildNextCraftAction() {
        MinecraftClient client = MinecraftClient.getInstance();
        actionQueue.clear();

        // Открыть верстак (если настроено)
        if (config.isAutoOpenCrafting()) {
            actionQueue.enqueue("Открыть верстак", () -> {
                // Если уже открыт нужный экран — успех
                return client.currentScreen instanceof CraftingScreen;
            });
        }

        // Разместить ингредиенты
        actionQueue.enqueue("Разместить ингредиенты", () -> placeIngredients(client));

        // Взять результат
        actionQueue.enqueue("Взять результат", () -> takeResult(client));
    }

    private boolean placeIngredients(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen)) return false;
        CraftingScreen screen = (CraftingScreen) client.currentScreen;
        CraftingScreenHandler handler = screen.getScreenHandler();

        // Проверяем что слот результата не пуст (ингредиенты уже на месте или автоматически встали)
        ItemStack result = handler.slots.get(0).getStack();
        if (!result.isEmpty()) return true;

        // Ингредиенты ещё не размещены — возвращаем false для retry
        LOGGER.warn("[AutoCraft] Слот результата пуст, ингредиенты не размещены");
        return false;
    }

    private boolean takeResult(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen)) return false;
        CraftingScreen screen = (CraftingScreen) client.currentScreen;
        CraftingScreenHandler handler = screen.getScreenHandler();

        ItemStack result = handler.slots.get(0).getStack();
        if (result.isEmpty()) {
            LOGGER.warn("[AutoCraft] Нечего брать из слота результата");
            return false;
        }

        // Shift+click для быстрого перемещения в инвентарь
        client.interactionManager.clickSlot(
            handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player
        );
        return true;
    }

    private void onActionFail() {
        LOGGER.error("[AutoCraft] Ошибка действия в ActionQueue — крафт остановлен");
        stop(false);
    }

    private void sendChatMessage(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(msg), false);
        }
    }

    public boolean isActive() { return active; }
    public int getCraftedCount() { return craftedCount; }
    public int getTargetCount() { return targetCount; }
    public String getItemName() { return itemName; }
}
