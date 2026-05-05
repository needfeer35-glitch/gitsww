package net.autocraft.action;

import net.autocraft.AutoCraftMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Queue of simulated player slot-click actions.
 * One action is executed per tick window when the delay has elapsed.
 */
public class ActionQueue {

    public record SlotAction(
            int syncId,
            int slotIndex,
            int button,
            SlotActionType actionType,
            String debugLabel
    ) {}

    private final Deque<SlotAction> queue = new ArrayDeque<>();
    private long lastActionTime = 0L;
    private long delayMs = 150L;

    public void setDelay(long ms) {
        this.delayMs = Math.max(50L, ms);
    }

    public void leftClick(int syncId, int slot, String label) {
        queue.add(new SlotAction(syncId, slot, 0, SlotActionType.PICKUP, label));
    }

    public void shiftClick(int syncId, int slot, String label) {
        queue.add(new SlotAction(syncId, slot, 0, SlotActionType.QUICK_MOVE, label));
    }

    public void rightClick(int syncId, int slot, String label) {
        queue.add(new SlotAction(syncId, slot, 1, SlotActionType.PICKUP, label));
    }

    public boolean isEmpty() { return queue.isEmpty(); }
    public int size()        { return queue.size(); }
    public void clear()      { queue.clear(); }

    /**
     * Execute the next pending action if delay has elapsed.
     * Returns true if an action was fired.
     */
    public boolean tick(MinecraftClient client) {
        if (queue.isEmpty()) return false;

        long now = System.currentTimeMillis();
        if (now - lastActionTime < delayMs) return false;

        SlotAction action = queue.poll();
        if (action == null) return false;

        ClientPlayerEntity player = client.player;
        if (player == null || player.currentScreenHandler == null) {
            queue.clear();
            return false;
        }

        // If sync ID no longer matches — screen changed, abort
        if (player.currentScreenHandler.syncId != action.syncId()) {
            queue.clear();
            return false;
        }

        client.interactionManager.clickSlot(
                action.syncId(),
                action.slotIndex(),
                action.button(),
                action.actionType(),
                player
        );

        lastActionTime = now;
        AutoCraftMod.LOGGER.debug("[ActionQueue] {} | slot={} btn={} type={}",
                action.debugLabel(), action.slotIndex(),
                action.button(), action.actionType());
        return true;
    }
}
