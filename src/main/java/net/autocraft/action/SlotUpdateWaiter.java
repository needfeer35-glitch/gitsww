package net.autocraft.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

/**
 * Следит за изменением конкретного слота после clickSlot.
 * Используется ActionQueue для защиты от десинхронизации.
 */
public class SlotUpdateWaiter {

    public enum State { IDLE, WAITING, DONE, TIMEOUT }

    private static final int MAX_WAIT_TICKS = 20; // ~1 секунда

    private State state = State.IDLE;
    private int watchSlot = -1;
    private ItemStack snapshotBefore = ItemStack.EMPTY;
    private int waitedTicks = 0;

    // -------------------------------------------------------------------------
    // Начать ожидание: запомнить что было в слоте ДО действия
    // -------------------------------------------------------------------------
    public void beginWatch(ScreenHandler handler, int slot) {
        if (slot < 0 || slot >= handler.slots.size()) {
            state = State.DONE; // нечего ждать
            return;
        }
        this.watchSlot      = slot;
        this.snapshotBefore = handler.getSlot(slot).getStack().copy();
        this.waitedTicks    = 0;
        this.state          = State.WAITING;
    }

    // -------------------------------------------------------------------------
    // Вызывается каждый тик пока state == WAITING
    // Возвращает true когда можно продолжать
    // -------------------------------------------------------------------------
    public boolean tick(MinecraftClient client) {
        if (state == State.IDLE || state == State.DONE || state == State.TIMEOUT) {
            return true;
        }

        if (client.player == null) {
            state = State.DONE;
            return true;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || watchSlot >= handler.slots.size()) {
            state = State.DONE;
            return true;
        }

        ItemStack current = handler.getSlot(watchSlot).getStack();

        // Слот изменился — сервер ответил
        if (!ItemStack.areEqual(current, snapshotBefore)) {
            state = State.DONE;
            return true;
        }

        // Таймаут защита
        waitedTicks++;
        if (waitedTicks >= MAX_WAIT_TICKS) {
            state = State.TIMEOUT;
            return true; // продолжаем несмотря на таймаут, чтобы не зависнуть
        }

        return false; // ещё ждём
    }

    public void reset() {
        state          = State.IDLE;
        watchSlot      = -1;
        snapshotBefore = ItemStack.EMPTY;
        waitedTicks    = 0;
    }

    public State getState()    { return state; }
    public boolean isWaiting() { return state == State.WAITING; }
    public boolean isDone()    { return state == State.DONE || state == State.TIMEOUT; }
    public boolean isTimeout() { return state == State.TIMEOUT; }
}
