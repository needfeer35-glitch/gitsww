package net.autocraft.crafting;

import net.autocraft.config.CraftingConfig;
import net.autocraft.inventory.IngredientFinder;
import net.autocraft.inventory.IngredientFinder.IngredientRequirement;
import net.autocraft.inventory.IngredientFinder.SlotResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.Item;
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

    // Ингредиенты текущего рецепта — устанавливаются перед стартом
    private List<IngredientRequirement> requirements = List.of();

    private static CraftingManager INSTANCE;

    public static CraftingManager getInstance() {
        if (INSTANCE == null) INSTANCE = new CraftingManager();
        return INSTANCE;
    }

    private CraftingManager() {}

    // -------------------------------------------------------------------------
    // Публичный API
    // -------------------------------------------------------------------------

    /**
     * Запустить крафт.
     *
     * @param cfg          настройки
     * @param itemName     название предмета (для сообщений)
     * @param amount       сколько штук скрафтить
     * @param requirements список ингредиентов с их количеством на 1 крафт
     */
    public void start(CraftingConfig cfg, String itemName, int amount,
                      List<IngredientRequirement> requirements) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Проверка ресурсов до старта
        if (!IngredientFinder.hasEnoughForCrafts(
                client.player.getInventory(), requirements, amount)) {
            LOGGER.warn("[AutoCraft] Недостаточно ресурсов для {} x{}", itemName, amount);
            sendChatMessage("§c[Авто крафт] Недостаточно ресурсов для: " + itemName + " x" + amount);
            return;
        }

        this.config = cfg;
        this.itemName = itemName;
        this.targetCount = amount;
        this.requirements = requirements;
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
            craftedCount++;
            sendChatMessage("§e[Авто крафт] Крафт... " + craftedCount + "/" + targetCount);

            if (craftedCount >= targetCount) {
                stop(true);
            } else {
                // Перед следующей итерацией проверяем ресурсы
                MinecraftClient mc = MinecraftClient.getInstance();
                int remaining = targetCount - craftedCount;
                if (!IngredientFinder.hasEnoughForCrafts(
                        mc.player.getInventory(), requirements, remaining)) {
                    LOGGER.warn("[AutoCraft] Ресурсы закончились на {}/{}", craftedCount, targetCount);
                    sendChatMessage("§c[Авто крафт] Ресурсы закончились! Создано: "
                            + craftedCount + "/" + targetCount);
                    stop(false);
                    return;
                }
                buildNextCraftAction();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Построение очереди действий для одного крафта
    // -------------------------------------------------------------------------

    private void buildNextCraftAction() {
        actionQueue.clear();

        // Шаг 1: открыть верстак (если нужно по настройкам)
        if (config.isAutoOpenCrafting()) {
            actionQueue.enqueue("Открыть верстак", () -> {
                MinecraftClient client = MinecraftClient.getInstance();
                return client.currentScreen instanceof CraftingScreen;
            });
        }

        // Шаг 2: разместить ингредиенты через умный поиск слотов
        actionQueue.enqueue("Разместить ингредиенты", () -> placeIngredients());

        // Шаг 3: взять результат
        actionQueue.enqueue("Взять результат", () -> takeResult());
    }

    // -------------------------------------------------------------------------
    // Размещение ингредиентов — умный выбор слотов
    // -------------------------------------------------------------------------

    private boolean placeIngredients() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!(client.currentScreen instanceof CraftingScreen screen)) {
            LOGGER.warn("[AutoCraft] Верстак не открыт");
            return false;
        }

        CraftingScreenHandler handler = screen.getScreenHandler();

        // Если результат уже есть — ингредиенты на месте, шаг пропускаем
        ItemStack result = handler.slots.get(0).getStack();
        if (!result.isEmpty()) return true;

        // Для каждого ингредиента находим минимальный набор слотов
        for (IngredientRequirement req : requirements) {
            int needed = req.amountPerCraft();
            List<SlotResult> slots = IngredientFinder.findSlotsForAmount(
                    client.player.getInventory(), req.item(), needed);

            if (slots.isEmpty()) {
                LOGGER.warn("[AutoCraft] Не хватает ингредиента: {} x{}",
                        req.item().toString(), needed);
                return false;
            }

            // Перекладываем из минимального числа слотов
            int remaining = needed;
            for (SlotResult slotResult : slots) {
                if (remaining <= 0) break;

                // Индекс слота инвентаря в хендлере верстака:
                // слоты 0 = результат, 1-9 = сетка крафта, 10+ = инвентарь игрока
                int handlerSlot = slotResult.slot() + 10;

                // Количество которое берём из этого слота
                int take = Math.min(remaining, slotResult.count());

                // Shift+click перемещает стак в сетку крафта автоматически
                client.interactionManager.clickSlot(
                        handler.syncId,
                        handlerSlot,
                        0,
                        SlotActionType.QUICK_MOVE,
                        client.player
                );

                remaining -= take;
            }
        }

        // Проверяем что результат появился после размещения
        result = handler.slots.get(0).getStack();
        if (result.isEmpty()) {
            LOGGER.warn("[AutoCraft] Результат не появился после размещения ингредиентов");
            return false;
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Взятие результата из верстака
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

        // Shift+click — мгновенно переместить в инвентарь
        client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        LOGGER.info("[AutoCraft] Взят результат: {}", result.getItem().toString());
        return true;
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    private void onActionFail() {
        LOGGER.error("[AutoCraft] ActionQueue провалил действие — крафт остановлен");
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

    public boolean isActive()        { return active; }
    public int getCraftedCount()     { return craftedCount; }
    public int getTargetCount()      { return targetCount; }
    public String getItemName()      { return itemName; }
    public float getProgress()       {
        if (targetCount == 0) return 0f;
        return (float) craftedCount / targetCount;
    }
}
