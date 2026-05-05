package net.autocraft.inventory;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Умный поиск слотов с ингредиентами в инвентаре.
 * Стратегия: предпочитать слоты с наибольшим количеством предметов,
 * чтобы минимизировать количество задействованных слотов.
 */
public class IngredientFinder {

    /**
     * Результат поиска: слот + сколько можно взять.
     */
    public record SlotResult(int slot, int count) {}

    /**
     * Найти лучший слот для одного ингредиента.
     * Возвращает слот с максимальным количеством предметов нужного типа.
     * Учитывает частично использованные стаки.
     *
     * @param inventory инвентарь игрока
     * @param item      нужный предмет
     * @return SlotResult или null если ингредиент не найден
     */
    public static SlotResult findBestSlot(PlayerInventory inventory, Item item) {
        SlotResult best = null;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.getItem().equals(item)) continue;

            if (best == null || stack.getCount() > best.count()) {
                best = new SlotResult(i, stack.getCount());
            }
        }

        return best;
    }

    /**
     * Найти минимальный набор слотов, чтобы набрать нужное количество предметов.
     * Слоты сортируются от большего к меньшему — сначала берём самые полные.
     * Это минимизирует количество задействованных слотов.
     *
     * @param inventory инвентарь игрока
     * @param item      нужный предмет
     * @param needed    сколько штук нужно
     * @return список SlotResult, покрывающий нужное количество, или пустой список если ресурсов не хватает
     */
    public static List<SlotResult> findSlotsForAmount(PlayerInventory inventory, Item item, int needed) {
        // Собрать все подходящие слоты
        List<SlotResult> candidates = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                candidates.add(new SlotResult(i, stack.getCount()));
            }
        }

        // Сортировка: сначала самые полные стаки
        candidates.sort(Comparator.comparingInt(SlotResult::count).reversed());

        // Набираем минимальный набор слотов покрывающий needed
        List<SlotResult> result = new ArrayList<>();
        int collected = 0;
        for (SlotResult slot : candidates) {
            if (collected >= needed) break;
            result.add(slot);
            collected += slot.count();
        }

        // Если ресурсов не хватило — вернуть пустой список
        if (collected < needed) return List.of();

        return result;
    }

    /**
     * Подсчитать суммарное количество предмета во всём инвентаре.
     *
     * @param inventory инвентарь игрока
     * @param item      нужный предмет
     * @return суммарное количество
     */
    public static int countTotal(PlayerInventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Проверить, хватает ли ресурсов на N крафтов по заданному рецепту.
     *
     * @param inventory    инвентарь игрока
     * @param requirements список: что нужно и сколько на 1 крафт
     * @param craftCount   сколько раз крафтить
     * @return true если всего хватает
     */
    public static boolean hasEnoughForCrafts(
            PlayerInventory inventory,
            List<IngredientRequirement> requirements,
            int craftCount
    ) {
        for (IngredientRequirement req : requirements) {
            int totalNeeded = req.amountPerCraft() * craftCount;
            int available = countTotal(inventory, req.item());
            if (available < totalNeeded) return false;
        }
        return true;
    }

    /**
     * Пара: предмет + сколько нужно на 1 крафт.
     */
    public record IngredientRequirement(Item item, int amountPerCraft) {}
}
