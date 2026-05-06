package net.autocraft.recipe;

import net.minecraft.item.Item;

import java.util.List;

/**
 * Универсальное описание рецепта.
 *
 * SHAPED:    ingredients[0..8] — позиции 0-8 сетки 3x3, null = пустой слот
 * SHAPELESS: ingredients — просто список предметов без привязки к позиции
 */
public class CraftingRecipe {

    private final RecipeType type;
    private final List<Item> ingredients; // для SHAPED длина всегда 9, null = пусто
    private final Item result;
    private final int resultCount;

    // SHAPED конструктор
    public CraftingRecipe(Item[] grid9, Item result, int resultCount) {
        if (grid9.length != 9)
            throw new IllegalArgumentException("SHAPED рецепт должен иметь ровно 9 слотов");
        this.type = RecipeType.SHAPED;
        this.ingredients = List.of(grid9); // null-элементы сохраняются
        this.result = result;
        this.resultCount = resultCount;
    }

    // SHAPELESS конструктор
    public CraftingRecipe(List<Item> ingredients, Item result, int resultCount) {
        this.type = RecipeType.SHAPELESS;
        this.ingredients = List.copyOf(ingredients);
        this.result = result;
        this.resultCount = resultCount;
    }

    public RecipeType getType()          { return type; }
    public List<Item> getIngredients()   { return ingredients; }
    public Item getResult()              { return result; }
    public int getResultCount()          { return resultCount; }
}
