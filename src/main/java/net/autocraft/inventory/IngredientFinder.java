package net.autocraft.inventory;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.*;

/**
 * Утилиты для работы с инвентарём игрока: поиск ингредиентов, подсчёт.
 */
public class IngredientFinder {

    private IngredientFinder() {}

    // ── Типы ─────────────────────────────────────────────────────────────────

    /** Требование: предмет + количество для одного крафта */
    public record IngredientRequirement(Item item, int count) {}

    /** Результат поиска слота: индекс в PlayerInventory + количество в стаке */
    public record SlotResult(int slot, int count) {}

    // ── Построение требований ─────────────────────────────────────────────────

    /**
     * Построить список требований из списка ингредиентов рецепта.
     * null и Items.AIR пропускаются. Одинаковые предметы суммируются.
     */
    public static List<IngredientRequirement> buildRequirements(List<Item> ingredients) {
        Map<Item, Integer> map = new LinkedHashMap<>();
        for (Item item : ingredients) {
            if (item == null || item == Items.AIR) continue;
            map.merge(item, 1, Integer::sum);
        }
        return map.entrySet().stream()
                .map(e -> new IngredientRequirement(e.getKey(), e.getValue()))
                .toList();
    }

    // ── Проверка наличия ресурсов ─────────────────────────────────────────────

    /**
     * Проверить, достаточно ли у игрока ресурсов для craftCount крафтов.
     *
     * @param inv          инвентарь игрока
     * @param requirements список требований на ОДИН крафт
     * @param craftCount   сколько раз крафтить
     */
    public static boolean hasEnoughForCrafts(PlayerInventory inv,
                                              List<IngredientRequirement> requirements,
                                              int craftCount) {
        if (requirements == null || requirements.isEmpty()) return true;
        for (IngredientRequirement req : requirements) {
            int available = countItem(inv, req.item());
            if (available < req.count() * craftCount) return false;
        }
        return true;
    }

    // ── Поиск слотов ──────────────────────────────────────────────────────────

    /**
     * Найти лучший слот инвентаря для предмета (с наибольшим количеством).
     * Возвращает null если предмет не найден.
     *
     * FIX: ограничиваем поиск основным инвентарём (0..35), исключаем броню/оффхенд.
     */
    public static SlotResult findBestSlot(PlayerInventory inv, Item item) {
        int bestSlot  = -1;
        int bestCount = 0;
        // Слоты 0..35: основной инвентарь + хотбар
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                if (stack.getCount() > bestCount) {
                    bestCount = stack.getCount();
                    bestSlot  = i;
                }
            }
        }
        return bestSlot >= 0 ? new SlotResult(bestSlot, bestCount) : null;
    }

    /**
     * Найти все слоты с нужным предметом, набирающие суммарно amount штук.
     * Возвращает пустой список если недостаточно.
     */
    public static List<SlotResult> findSlotsForAmount(PlayerInventory inv,
                                                       Item item,
                                                       int amount) {
        List<SlotResult> result = new ArrayList<>();
        int collected = 0;
        for (int i = 0; i < 36; i++) {
            if (collected >= amount) break;
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                result.add(new SlotResult(i, stack.getCount()));
                collected += stack.getCount();
            }
        }
        return collected >= amount ? result : List.of();
    }

    // ── Подсчёт ───────────────────────────────────────────────────────────────

    /**
     * Подсчитать общее количество предмета в основном инвентаре (0..35).
     * FIX: не трогаем броню и оффхенд.
     */
    public static int countItem(PlayerInventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
