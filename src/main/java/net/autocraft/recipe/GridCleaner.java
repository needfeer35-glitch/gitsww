package net.autocraft.recipe;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Очищает сетку верстака при откате крафта.
 * Возвращает все предметы из grid обратно в инвентарь.
 */
public class GridCleaner {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/GridCleaner");

    // Слоты 1..9 — сетка верстака в хендлере
    private static final int GRID_START = 1;
    private static final int GRID_END   = 9;

    private GridCleaner() {}

    /**
     * Очистить все слоты сетки верстака.
     * Каждый непустой слот перемещается в инвентарь через QUICK_MOVE.
     *
     * @return true если сетка чистая (или стала чистой после очистки)
     */
    public static boolean clearGrid(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) {
            LOGGER.warn("[GridCleaner] Верстак не открыт — очистка невозможна");
            return false;
        }

        CraftingScreenHandler handler = screen.getScreenHandler();
        boolean allCleared = true;

        for (int slot = GRID_START; slot <= GRID_END; slot++) {
            ItemStack stack = handler.slots.get(slot).getStack();
            if (stack.isEmpty()) continue;

            LOGGER.info("[GridCleaner] Очищаю слот {} ({})", slot, stack.getItem());

            client.interactionManager.clickSlot(
                handler.syncId,
                slot,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
            );

            // Проверяем что слот стал пустым
            if (!handler.slots.get(slot).getStack().isEmpty()) {
                LOGGER.warn("[GridCleaner] Слот {} не очистился — инвентарь переполнен?", slot);
                allCleared = false;
            }
        }

        if (allCleared) {
            LOGGER.info("[GridCleaner] Сетка полностью очищена");
        } else {
            LOGGER.error("[GridCleaner] Не удалось очистить все слоты сетки");
        }

        return allCleared;
    }

    /**
     * Проверить — пуста ли сетка верстака прямо сейчас.
     */
    public static boolean isGridEmpty(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return false;

        CraftingScreenHandler handler = screen.getScreenHandler();
        for (int slot = GRID_START; slot <= GRID_END; slot++) {
            if (!handler.slots.get(slot).getStack().isEmpty()) return false;
        }
        return true;
    }
}
