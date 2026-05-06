package net.autocraft.recipe;

import net.autocraft.inventory.IngredientFinder;
import net.autocraft.inventory.IngredientFinder.SlotResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Отвечает за физическое размещение ингредиентов в сетке верстака.
 * Поддерживает SHAPED и SHAPELESS рецепты.
 */
public class GridPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/GridPlacer");

    // Слоты сетки верстака в CraftingScreenHandler:
    // 0         = результат
    // 1..9      = сетка крафта (1=топ-лево, 9=низ-право)
    // 10..45    = инвентарь игрока
    private static final int GRID_OFFSET = 1; // первый слот сетки в хендлере
    private static final int GRID_SIZE   = 9;

    private GridPlacer() {}

    // -------------------------------------------------------------------------
    // Публичная точка входа
    // -------------------------------------------------------------------------

    /**
     * Разместить ингредиенты рецепта в сетке верстака.
     *
     * @return true если всё успешно размещено и результат появился
     */
    public static boolean place(MinecraftClient client, CraftingRecipe recipe) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) {
            LOGGER.warn("[GridPlacer] Верстак не открыт");
            return false;
        }

        CraftingScreenHandler handler = screen.getScreenHandler();

        // Если результат уже есть — предыдущий размест не был убран, ждём
        if (!handler.slots.get(0).getStack().isEmpty()) return true;

        return switch (recipe.getType()) {
            case SHAPED    -> placeShaped(client, handler, recipe);
            case SHAPELESS -> placeShapeless(client, handler, recipe);
        };
    }

    // -------------------------------------------------------------------------
    // SHAPED — позиции строго по рецепту
    // -------------------------------------------------------------------------

    private static boolean placeShaped(MinecraftClient client,
                                        CraftingScreenHandler handler,
                                        CraftingRecipe recipe) {
        PlayerInventory inv = client.player.getInventory();
        List<Item> grid = recipe.getIngredients(); // длина 9, null = пустой слот

        for (int pos = 0; pos < GRID_SIZE; pos++) {
            Item required = grid.get(pos);
            if (required == null) continue; // пустой слот — пропускаем

            int gridHandlerSlot = GRID_OFFSET + pos; // слот 1..9 в хендлере

            // Если в этом слоте сетки уже правильный предмет — не трогаем
            ItemStack current = handler.slots.get(gridHandlerSlot).getStack();
            if (!current.isEmpty() && current.getItem().equals(required)) continue;

            // Ищем лучший слот инвентаря (максимальный стак)
            SlotResult best = IngredientFinder.findBestSlot(inv, required);
            if (best == null) {
                LOGGER.warn("[GridPlacer] SHAPED: нет ингредиента {} для позиции {}",
                        required, pos);
                return false;
            }

            // Инвентарный слот в хендлере = best.slot() + 10
            int invHandlerSlot = best.slot() + 10;

            // Клик по инвентарному слоту → берём предмет на курсор
            client.interactionManager.clickSlot(
                    handler.syncId, invHandlerSlot, 0, SlotActionType.PICKUP, client.player);

            // Клик правой кнопкой по слоту сетки → кладём 1 штуку
            client.interactionManager.clickSlot(
                    handler.syncId, gridHandlerSlot, 1, SlotActionType.PICKUP, client.player);

            // Если на курсоре что-то осталось — возвращаем обратно в инвентарь
            returnCursorStack(client, handler);
        }

        return resultReady(handler);
    }

    // -------------------------------------------------------------------------
    // SHAPELESS — заполняем grid слева направо, не привязываясь к позиции
    // -------------------------------------------------------------------------

    private static boolean placeShapeless(MinecraftClient client,
                                           CraftingScreenHandler handler,
                                           CraftingRecipe recipe) {
        PlayerInventory inv = client.player.getInventory();

        // Строим мультисет: сколько каждого предмета нужно
        Map<Item, Integer> needed = buildNeededMap(recipe.getIngredients());

        // Для каждого уникального предмета находим минимальный набор слотов
        // и раскладываем по свободным позициям сетки слева направо
        int gridPos = 0; // текущая позиция в сетке 0..8

        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            Item item = entry.getKey();
            int count = entry.getValue();

            List<IngredientFinder.SlotResult> slots =
                    IngredientFinder.findSlotsForAmount(inv, item, count);

            if (slots.isEmpty()) {
                LOGGER.warn("[GridPlacer] SHAPELESS: нет ингредиента {} x{}", item, count);
                return false;
            }

            int remaining = count;

            for (SlotResult slotResult : slots) {
                if (remaining <= 0) break;
                if (gridPos >= GRID_SIZE) {
                    LOGGER.warn("[GridPlacer] SHAPELESS: сетка переполнена (>9 слотов)");
                    return false;
                }

                int gridHandlerSlot = GRID_OFFSET + gridPos;
                int invHandlerSlot  = slotResult.slot() + 10;

                // Кладём нужное количество по 1 через right-click
                int toPut = Math.min(remaining, slotResult.count());

                // Берём весь стак на курсор
                client.interactionManager.clickSlot(
                        handler.syncId, invHandlerSlot, 0, SlotActionType.PICKUP, client.player);

                // Кладём toPut штук по одной в текущий слот сетки
                for (int i = 0; i < toPut; i++) {
                    client.interactionManager.clickSlot(
                            handler.syncId, gridHandlerSlot, 1, SlotActionType.PICKUP, client.player);
                }

                // Остаток со курсора — обратно
                returnCursorStack(client, handler);

                remaining  -= toPut;
                gridPos++;
            }
        }

        return resultReady(handler);
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    /**
     * Сгруппировать список ингредиентов в Map<Item, количество>.
     * Сохраняет порядок вставки (LinkedHashMap) для детерминированного обхода.
     */
    private static Map<Item, Integer> buildNeededMap(List<Item> ingredients) {
        Map<Item, Integer> map = new LinkedHashMap<>();
        for (Item item : ingredients) {
            if (item != null) {
                map.merge(item, 1, Integer::sum);
            }
        }
        return map;
    }

    /**
     * Если на курсоре что-то осталось — возвращаем в инвентарь
     * через клик по любому свободному слоту инвентаря.
     */
    private static void returnCursorStack(MinecraftClient client,
                                           CraftingScreenHandler handler) {
        ItemStack cursor = client.player.currentScreenHandler.getCursorStack();
        if (cursor.isEmpty()) return;

        // Ищем первый пустой слот инвентаря (слоты 10..45 в хендлере)
        for (int i = 10; i < handler.slots.size(); i++) {
            if (handler.slots.get(i).getStack().isEmpty()) {
                client.interactionManager.clickSlot(
                        handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                return;
            }
        }

        // Нет пустого слота — бросить на землю (слот -999)
        LOGGER.warn("[GridPlacer] Нет пустого слота, предмет будет выброшен");
        client.interactionManager.clickSlot(
                handler.syncId, -999, 0, SlotActionType.PICKUP, client.player);
    }

    /**
     * Проверить что результат крафта появился в слоте 0.
     */
    private static boolean resultReady(CraftingScreenHandler handler) {
        boolean ready = !handler.slots.get(0).getStack().isEmpty();
        if (!ready) LOGGER.warn("[GridPlacer] Результат не появился после размещения");
        return ready;
    }
}
