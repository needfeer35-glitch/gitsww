package net.autocraft.action;

import net.autocraft.AutoCraftMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Очередь действий с синхронизацией + retry системой.
 *
 * RETRY ЛОГИКА:
 *  - после clickSlot ждём изменения слота (SlotUpdateWaiter)
 *  - если слот не изменился (TIMEOUT) → повторяем действие
 *  - максимум MAX_RETRIES попыток на одно действие
 *  - если все попытки исчерпаны → onFailure callback → CraftingManager останавливает крафт
 */
public class ActionQueue {

    private static final int MAX_RETRIES = 3;

    // -------------------------------------------------------------------------
    // Внутренний класс: одно действие в очереди
    // -------------------------------------------------------------------------
    private static class QueuedAction {
        final Consumer<MinecraftClient> action;
        final String                    debugLabel;
        final int                       watchSlot;
        int                             attempts = 0;

        QueuedAction(Consumer<MinecraftClient> action, String debugLabel, int watchSlot) {
            this.action     = action;
            this.debugLabel = debugLabel;
            this.watchSlot  = watchSlot;
        }
    }

    // -------------------------------------------------------------------------
    private final Deque<QueuedAction> queue  = new ArrayDeque<>();
    private final SlotUpdateWaiter    waiter = new SlotUpdateWaiter();

    private int  delayMs        = 100;
    private long lastActionTime = 0;

    private QueuedAction pending   = null;

    /** Вызывается когда действие провалилось после MAX_RETRIES попыток */
    private Runnable onFailure = null;

    // -------------------------------------------------------------------------
    // Публичный API
    // -------------------------------------------------------------------------

    public void setOnFailure(Runnable callback) { this.onFailure = callback; }
    public void setDelay(int ms)                { this.delayMs = ms; }
    public boolean isEmpty()                    { return queue.isEmpty() && pending == null; }

    public void leftClick(int syncId, int slot, String label) {
        enqueue(client -> clickSlot(client, syncId, slot, 0, SlotActionType.PICKUP),
                label, slot);
    }

    public void rightClick(int syncId, int slot, String label) {
        enqueue(client -> clickSlot(client, syncId, slot, 1, SlotActionType.PICKUP),
                label, slot);
    }

    public void shiftClick(int syncId, int slot, String label) {
        enqueue(client -> clickSlot(client, syncId, slot, 0, SlotActionType.QUICK_MOVE),
                label, slot);
    }

    public void enqueue(Consumer<MinecraftClient> action, String label) {
        queue.add(new QueuedAction(action, label, -1));
    }

    public void enqueue(Consumer<MinecraftClient> action, String label, int watchSlot) {
        queue.add(new QueuedAction(action, label, watchSlot));
    }

    public void clear() {
        queue.clear();
        pending        = null;
        lastActionTime = 0;
        waiter.reset();
    }

    // -------------------------------------------------------------------------
    // Главный тик
    // -------------------------------------------------------------------------
    public void tick(MinecraftClient client) {
        if (client.player == null) return;

        // --- 1. Ожидаем подтверждения pending действия ---
        if (pending != null) {
            if (pending.watchSlot >= 0) {
                boolean ready = waiter.tick(client);
                if (!ready) return; // ещё ждём ответа сервера

                if (waiter.isTimeout()) {
                    // Сервер не ответил — пробуем retry
                    handleRetry(client);
                    return;
                }
            }

            // Успех — сервер подтвердил изменение слота
            AutoCraftMod.LOGGER.debug("[AutoCraft] Action confirmed: {} (attempt {})",
                    pending.debugLabel, pending.attempts);
            pending        = null;
            waiter.reset();
            lastActionTime = System.currentTimeMillis();
            return;
        }

        // --- 2. Задержка между действиями ---
        if (System.currentTimeMillis() - lastActionTime < delayMs) return;

        // --- 3. Берём следующее действие ---
        if (queue.isEmpty()) return;

        executeNext(client, queue.poll());
    }

    // -------------------------------------------------------------------------
    // Retry логика
    // -------------------------------------------------------------------------
    private void handleRetry(MinecraftClient client) {
        if (pending == null) return;

        pending.attempts++;

        if (pending.attempts >= MAX_RETRIES) {
            // Исчерпали все попытки
            AutoCraftMod.LOGGER.error(
                "[AutoCraft] FAILED after {} retries: slot={} action=\"{}\"",
                MAX_RETRIES, pending.watchSlot, pending.debugLabel
            );
            QueuedAction failed = pending;
            pending = null;
            waiter.reset();
            queue.clear();

            if (onFailure != null) {
                onFailure.run();
            }
            return;
        }

        // Повторяем то же действие
        AutoCraftMod.LOGGER.warn(
            "[AutoCraft] Retry {}/{}: slot={} action=\"{}\"",
            pending.attempts, MAX_RETRIES,
            pending.watchSlot, pending.debugLabel
        );

        waiter.reset();
        executeNext(client, pending);
    }

    // -------------------------------------------------------------------------
    // Выполнить действие и настроить ожидание
    // -------------------------------------------------------------------------
    private void executeNext(MinecraftClient client, QueuedAction action) {
        // Снимаем snapshot слота ДО действия
        if (action.watchSlot >= 0) {
            ScreenHandler handler = client.player.currentScreenHandler;
            if (handler != null) {
                waiter.beginWatch(handler, action.watchSlot);
            }
        }

        try {
            action.action.accept(client);
        } catch (Exception e) {
            AutoCraftMod.LOGGER.error("[AutoCraft] Action threw exception: \"{}\"",
                    action.debugLabel, e);
            pending = action;
            handleRetry(client);
            return;
        }

        if (action.watchSlot >= 0) {
            pending = action;
        } else {
            lastActionTime = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------------------
    private void clickSlot(MinecraftClient client, int syncId, int slot,
                           int button, SlotActionType type) {
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.clickSlot(syncId, slot, button, type, client.player);
        }
    }
}
