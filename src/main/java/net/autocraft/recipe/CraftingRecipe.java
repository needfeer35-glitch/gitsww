package net.autocraft.recipe;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Универсальное описание рецепта.
 *
 * SHAPED:    ingredients[0..8] — позиции 0-8 сетки 3x3, null = пустой слот
 * SHAPELESS: ingredients — просто список предметов без привязки к позиции
 */
public class CraftingRecipe {

    private final RecipeType type;
    private final List<Item> ingredients;
    private final Item result;
    private final int  resultCount;

    /**
     * SHAPED конструктор — ровно 9 слотов, null = пустой слот.
     */
    public CraftingRecipe(RecipeType type, List<Item> ingredients, Item result, int resultCount) {
        if (type == RecipeType.SHAPED) {
            // Нормализуем до 9 слотов
            List<Item> grid = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                Item item = i < ingredients.size() ? ingredients.get(i) : null;
                // FIX: Items.AIR нормализуем в null для единообразия
                grid.add((item == Items.AIR) ? null : item);
            }
            this.ingredients = List.copyOf(grid);
        } else {
            // SHAPELESS: копируем, фильтруем AIR
            List<Item> cleaned = new ArrayList<>();
            for (Item item : ingredients) {
                if (item != null && item != Items.AIR) cleaned.add(item);
            }
            this.ingredients = List.copyOf(cleaned);
        }
        this.type        = type;
        this.result      = result;
        this.resultCount = resultCount;
    }

    public RecipeType getType()        { return type; }
    public List<Item> getIngredients() { return ingredients; }
    public Item getResult()            { return result; }
    public int  getResultCount()       { return resultCount; }
}
