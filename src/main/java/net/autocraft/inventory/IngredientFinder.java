/**
 * Строит список IngredientRequirement из списка ингредиентов рецепта.
 * Одинаковые предметы суммируются.
 * null и Items.AIR — пропускаются (пустые слоты SHAPED рецептов).
 *
 * Вызывается из AutoCraftScreen перед передачей в CraftingManager.start().
 */
public static List<IngredientRequirement> buildRequirements(List<net.minecraft.item.Item> ingredients) {
    java.util.Map<net.minecraft.item.Item, Integer> map = new java.util.LinkedHashMap<>();
    for (net.minecraft.item.Item item : ingredients) {
        if (item == null || item == net.minecraft.item.Items.AIR) continue;
        map.merge(item, 1, Integer::sum);
    }
    return map.entrySet().stream()
            .map(e -> new IngredientRequirement(e.getKey(), e.getValue()))
            .toList();
}
