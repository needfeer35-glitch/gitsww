package net.autocraft.action;

import net.autocraft.AutoCraftMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Очередь действий с полной синхронизацией:
 *  - каждое действие выполняется только после того, как предыдущее
 *    подтверждено сервером (слот обновился)
 *  - защита от десинхронизации через SlotUpdateWaiter
 *  - задержка между действиями настраивается через setDelay()
 */
public class ActionQueue {

    // -------------------------------------------------------------------------
    // Внутренний класс: одно действие в очереди
    // -------------------------------------------------------------------------
    private static class QueuedAction {
        final Consumer<MinecraftClient> action;
        final String debugLabel;
        /** Слот который нужно отслеживать после выполнения (-1 = не ждать) */
        final int watchSlot;

        QueuedAction(Consumer<MinecraftClient> action, String debugLabel, int watchSlot) {
            this.action     = action;
            this.debugLabel = debugLabel;
            this.watchSlot  = watchSlot;
        }
    }

    // -------------------------------------------------------------------------
    private final Deque<QueuedAction> queue = new ArrayDeque<>();
    private final SlotUpdateWaiter    waiter = new SlotUpdateWaiter();

    private int delayMs      = 100;
    private long lastActionTime = 0;

    // Текущее выполняемое действие (уже вызвано, ждём подтверждения)
    private QueuedAction pending = null;

    // -------------------------------------------------------------------------
    // Публичный API — добавление действий
    // -------------------------------------------------------------------------

    /** Левый клик — отслеживаем watchSlot после выполнения */
    public void leftClick(int syncId, int slot, String label) {
        enqueue(client -> clickSlot(client, syncId, slot, 0, SlotActionType.PICKUP),
                label, slot);
    }

    /** Правый клик — кладёт 1 предмет с курсора */
    public void rightClick(int syncId, int slot, String label) {
        enqueue(client -> clickSlot(client, syncId, slot, 1, SlotActionType.PICKUP),
                label, slot);
    }

    /** Shift+клик */
    public void shiftClick(int syncId, int slot, String label) {
        enqueue(client -> clickSlot(client, syncId, slot, 0, SlotActionType.QUICK_MOVE),
                label, slot);
    }

    /** Произвольное действие без отслеживания слота */
    public void enqueue(Consumer<MinecraftClient> action, String label) {
        queue.add(new QueuedAction(action, label, -1));
    }

    /** Произвольное действие с отслеживанием конкретного слота */
    public void enqueue(Consumer<MinecraftClient> action, String label, int watchSlot) {
        queue.add(new QueuedAction(action, label, watchSlot));
    }

    public void setDelay(int ms) { this.delayMs = ms; }
    public boolean isEmpty()     { return queue.isEmpty() && pending == null; }

    public void clear() {
        queue.clear();
        pending = null;
        waiter.reset();
        lastActionTime = 0;
    }

    // -------------------------------------------------------------------------
    // Главный тик — вызывается из CraftingManager.tick() каждый тик
    // -------------------------------------------------------------------------
    public void tick(MinecraftClient client) {
        if (client.player == null) return;

        // --- 1. Если есть pending action — ждём подтверждения от сервера ---
        if (pending != null) {
            if (pending.watchSlot >= 0) {
                // Есть слот для отслеживания
                boolean ready = waiter.tick(client);
                if (!ready) return; // ещё ждём

                if (waiter.isTimeout()) {
                    AutoCraftMod.LOGGER.warn("[AutoCraft] SlotWaiter TIMEOUT on slot {} ({})",
                            pending.watchSlot, pending.debugLabel);
                }
            }
            // Подтверждение получено (или таймаут) — сбрасываем pending
            pending = null;
            waiter.reset();
            lastActionTime = System.currentTimeMillis(); // начинаем отсчёт задержки
            return;
        }

        // --- 2. Задержка между действиями ---
        if (System.currentTimeMillis() - lastActionTime < delayMs) return;

        // --- 3. Берём следующее действие из очереди ---
        if (queue.isEmpty()) return;

        QueuedAction next = queue.poll();

        // Снимаем snapshot слота ДО действия
        if (next.watchSlot >= 0) {
            ScreenHandler handler = client.player.currentScreenHandler;
            if (handler != null) {
                waiter.beginWatch(handler, next.watchSlot);
            }
        }

        // Выполняем действие
        AutoCraftMod.LOGGER.debug("[AutoCraft] Action: {}", next.debugLabel);
        try {
            next.action.accept(client);
        } catch (Exception e) {
            AutoCraftMod.LOGGER.error("[AutoCraft] Action failed: {}", next.debugLabel, e);
            pending = null;
            waiter.reset();
            return;
        }

        // Если нужно ждать подтверждения — выставляем pending
        if (next.watchSlot >= 0) {
            pending = next;
        } else {
            // Без отслеживания — просто фиксируем время
            lastActionTime = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------------------
    // Внутренний helper — clickSlot через interactionManager
    // -------------------------------------------------------------------------
    private void clickSlot(MinecraftClient client, int syncId, int slot,
                           int button, SlotActionType type) {
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.clickSlot(syncId, slot, button, type, client.player);
        }
    }
}
