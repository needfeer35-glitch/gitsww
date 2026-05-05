package net.autocraft.crafting;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;

/**
 * Represents one queued crafting job.
 */
public class CraftingTask {

    public enum CraftMode {
        FAST(100L),
        MEDIUM(300L);

        private final long delayMs;
        CraftMode(long delayMs) { this.delayMs = delayMs; }
        public long getDelayMs() { return delayMs; }
    }

    private final CraftingRecipe recipe;
    private final int targetAmount;
    private int craftedAmount = 0;
    private final CraftMode mode;
    private boolean cancelled = false;

    public CraftingTask(CraftingRecipe recipe, int targetAmount, CraftMode mode) {
        this.recipe = recipe;
        this.targetAmount = targetAmount;
        this.mode = mode;
    }

    public CraftingRecipe getRecipe()      { return recipe; }
    public int getTargetAmount()           { return targetAmount; }
    public int getCraftedAmount()          { return craftedAmount; }
    public CraftMode getMode()             { return mode; }
    public boolean isCancelled()           { return cancelled; }
    public void cancel()                   { this.cancelled = true; }
    public void addCrafted(int n)          { this.craftedAmount += n; }

    public boolean isFinished() {
        return cancelled || craftedAmount >= targetAmount;
    }

    public float getProgress() {
        if (targetAmount <= 0) return 1f;
        return Math.min(1f, (float) craftedAmount / targetAmount);
    }
}
