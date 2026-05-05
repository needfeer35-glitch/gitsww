package net.autocraft.crafting;

import net.autocraft.AutoCraftMod;
import net.autocraft.action.ActionQueue;
import net.autocraft.config.AutoCraftConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Orchestrates the full crafting loop.
 *
 * PHASES PER CYCLE:
 *  IDLE      → waiting for a task
 *  OPEN      → waiting for workbench screen to open
 *  CLEAR     → clearing leftover items from crafting grid
 *  PLACE     → placing ingredients into the grid
 *  WAIT      → waiting 2 ticks for server to update result slot
 *  TAKE      → shift-clicking result
 *  CHECK     → verify result received, loop or finish
 */
public class CraftingManager {

    public enum Phase { IDLE, OPEN, CLEAR, PLACE, WAIT, TAKE, CHECK }

    private Phase phase = Phase.IDLE;
    private CraftingTask currentTask = null;
    private final ActionQueue actionQueue = new ActionQueue();

    private int waitTicks = 0;
    private int stallTicks = 0;
    private static final int MAX_STALL_TICKS = 100; // ~5 sec safety abort

    // Slot layout for 3x3 crafting grid in CraftingScreenHandler
    // Slots 0 = result, 1-9 = grid (row-major), 10+ = player inventory
    private static final int RESULT_SLOT   = 0;
    private static final int GRID_START    = 1;
    private static final int GRID_END      = 9;
    private static final int INV_START     = 10;
    private static final int INV_END       = 45;
    private static final int HOTBAR_START  = 46;
    private static final int HOTBAR_END    = 54;

    public void startTask(CraftingTask task) {
        cancelCurrent();
        currentTask = task;
        phase = Phase.OPEN;
        actionQueue.clear();
        actionQueue.setDelay(task.getMode().getDelayMs());
        stallTicks = 0;
        AutoCraftMod.LOGGER.info("[AutoCraft] Task started: {}", task.getRecipe()
                .getResult(null).getItem());
    }

    public void cancelCurrent() {
        if (currentTask != null) {
            currentTask.cancel();
        }
        currentTask = null;
        phase = Phase.IDLE;
        actionQueue.clear();
        stallTicks = 0;
    }

    public CraftingTask getCurrentTask() { return currentTask; }
    public Phase getPhase()              { return phase; }
    public boolean isRunning()           { return phase != Phase.IDLE; }

    // -------------------------------------------------------------------------
    // Main tick — called every client tick
    // -------------------------------------------------------------------------
    public void tick(MinecraftClient client) {
        if (phase == Phase.IDLE || currentTask == null) return;
        if (currentTask.isCancelled()) { finish(client, false); return; }

        // Execute queued actions first (one per tick window)
        if (!actionQueue.isEmpty()) {
            actionQueue.tick(client);
            return;
        }

        // Stall protection
        stallTicks++;
        if (stallTicks > MAX_STALL_TICKS) {
            AutoCraftMod.LOGGER.warn("[AutoCraft] Stall detected, aborting task.");
            sendChat(client, "§c[AutoCraft] Остановка: зависание (stall).");
            finish(client, false);
            return;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) { cancelCurrent(); return; }

        switch (phase) {

            case OPEN -> handleOpen(client, player);
            case CLEAR -> handleClear(client, player);
            case PLACE -> handlePlace(client, player);
            case WAIT  -> handleWait();
            case TAKE  -> handleTake(client, player);
            case CHECK -> handleCheck(client, player);
        }
    }

    // -------------------------------------------------------------------------
    // PHASE: OPEN — wait until a CraftingScreenHandler is active
    // -------------------------------------------------------------------------
    private void handleOpen(MinecraftClient client, ClientPlayerEntity player) {
        if (player.currentScreenHandler instanceof CraftingScreenHandler) {
            stallTicks = 0;
            phase = Phase.CLEAR;
            return;
        }
        // Screen not open — nothing we can do from client side without server interaction
        // The GUI should have opened the workbench before calling startTask
        // If AutoOpen is enabled, this is handled by AutoCraftScreen
    }

    // -------------------------------------------------------------------------
    // PHASE: CLEAR — shift-click any leftover items out of the grid
    // -------------------------------------------------------------------------
    private void handleClear(MinecraftClient client, ClientPlayerEntity player) {
        CraftingScreenHandler handler = getCraftingHandler(player);
        if (handler == null) { cancelCurrent(); return; }

        int syncId = handler.syncId;
        boolean hadLeftovers = false;

        for (int slot = GRID_START; slot <= GRID_END; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty()) {
                actionQueue.shiftClick(syncId, slot, "CLEAR grid slot " + slot);
                hadLeftovers = true;
            }
        }

        stallTicks = 0;
        phase = Phase.PLACE;
    }

    // -------------------------------------------------------------------------
    // PHASE: PLACE — move ingredients from inventory into grid slots
    // -------------------------------------------------------------------------
    private void handlePlace(MinecraftClient client, ClientPlayerEntity player) {
        CraftingScreenHandler handler = getCraftingHandler(player);
        if (handler == null) { cancelCurrent(); return; }

        int syncId = handler.syncId;
        List<Ingredient> ingredients = currentTask.getRecipe().getIngredients();

        // Build a copy of inventory stacks so we can "consume" virtually
        Map<Integer, ItemStack> invCopy = buildInventoryCopy(handler);

        for (int gridIndex = 0; gridIndex < ingredients.size(); gridIndex++) {
            Ingredient ingredient = ingredients.get(gridIndex);
            if (ingredient.isEmpty()) continue;

            int gridSlot = GRID_START + gridIndex;

            // Find matching item in inventory
            int invSlot = findIngredientSlot(invCopy, ingredient);
            if (invSlot == -1) {
                // Missing ingredient
                if (!AutoCraftConfig.get().continueOnMissingResources) {
                    sendChat(client, "§e[AutoCraft] Нет ресурсов для крафта.");
                    finish(client, false);
                    return;
                }
                continue;
            }

            // Pick up from inventory → place in grid
            actionQueue.leftClick(syncId, invSlot, "PLACE pick inv " + invSlot);
            actionQueue.leftClick(syncId, gridSlot, "PLACE put grid " + gridSlot);

            // Mark as used in virtual copy
            ItemStack used = invCopy.get(invSlot);
            if (used != null) {
                used.decrement(1);
                if (used.isEmpty()) invCopy.remove(invSlot);
            }
        }

        stallTicks = 0;
        phase = Phase.WAIT;
        waitTicks = 0;
    }

    // -------------------------------------------------------------------------
    // PHASE: WAIT — give server 3 ticks to compute result
    // -------------------------------------------------------------------------
    private void handleWait() {
        waitTicks++;
        if (waitTicks >= 3) {
            stallTicks = 0;
            phase = Phase.TAKE;
        }
    }

    // -------------------------------------------------------------------------
    // PHASE: TAKE — shift-click result slot
    // -------------------------------------------------------------------------
    private void handleTake(MinecraftClient client, ClientPlayerEntity player) {
        CraftingScreenHandler handler = getCraftingHandler(player);
        if (handler == null) { cancelCurrent(); return; }

        ItemStack result = handler.getSlot(RESULT_SLOT).getStack();
        if (result.isEmpty()) {
            // Result not ready yet — wait a bit more
            waitTicks++;
            if (waitTicks > 10) {
                sendChat(client, "§e[AutoCraft] Результат не появился, проверьте ресурсы.");
                finish(client, false);
            }
            return;
        }

        int syncId = handler.syncId;
        actionQueue.shiftClick(syncId, RESULT_SLOT, "TAKE result");
        stallTicks = 0;
        phase = Phase.CHECK;
    }

    // -------------------------------------------------------------------------
    // PHASE: CHECK — verify we got an item, loop if needed
    // -------------------------------------------------------------------------
    private void handleCheck(MinecraftClient client, ClientPlayerEntity player) {
        CraftingScreenHandler handler = getCraftingHandler(player);
        if (handler == null) { cancelCurrent(); return; }

        ItemStack result = currentTask.getRecipe().getResult(null);
        int outputPerCraft = result.isEmpty() ? 1 : result.getCount();
        currentTask.addCrafted(outputPerCraft);

        stallTicks = 0;

        if (currentTask.isFinished()) {
            finish(client, true);
        } else {
            // Another cycle
            phase = Phase.CLEAR;
        }
    }

    // -------------------------------------------------------------------------
    // Finish
    // -------------------------------------------------------------------------
    private void finish(MinecraftClient client, boolean success) {
        if (success) {
            if (AutoCraftConfig.get().soundNotification && client.player != null) {
                client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
            if (AutoCraftConfig.get().chatNotification) {
                ItemStack result = currentTask.getRecipe().getResult(null);
                String name = result.isEmpty() ? "?" :
                        result.getItem().getName().getString();
                sendChat(client, "§a[AutoCraft] Завершено! Создано: "
                        + name + " x" + currentTask.getCraftedAmount());
            }
            AutoCraftMod.LOGGER.info("[AutoCraft] Task finished successfully. Crafted={}",
                    currentTask.getCraftedAmount());
        }

        currentTask = null;
        phase = Phase.IDLE;
        actionQueue.clear();
        stallTicks = 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private CraftingScreenHandler getCraftingHandler(ClientPlayerEntity player) {
        if (player.currentScreenHandler instanceof CraftingScreenHandler h) return h;
        return null;
    }

    /** Build a mutable copy of inventory slots (inv + hotbar) for virtual planning */
    private Map<Integer, ItemStack> buildInventoryCopy(CraftingScreenHandler handler) {
        Map<Integer, ItemStack> map = new LinkedHashMap<>();
        for (int i = INV_START; i <= HOTBAR_END; i++) {
            if (i >= handler.slots.size()) break;
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty()) {
                map.put(i, stack.copy());
            }
        }
        return map;
    }

    /** Find the first inventory slot that satisfies the ingredient */
    private int findIngredientSlot(Map<Integer, ItemStack> inv, Ingredient ingredient) {
        for (Map.Entry<Integer, ItemStack> entry : inv.entrySet()) {
            if (!entry.getValue().isEmpty() && ingredient.test(entry.getValue())) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private void sendChat(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
