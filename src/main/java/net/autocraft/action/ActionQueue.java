package net.autocraft.crafting;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;
import java.util.function.BooleanSupplier;

public class ActionQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/ActionQueue");
    private static final int MAX_RETRIES = 3;

    public record Action(String name, BooleanSupplier executor) {}

    private final Queue<Action> queue = new LinkedList<>();
    private Action currentAction = null;
    private int retryCount = 0;
    private int tickDelay = 0;
    private boolean failed = false;

    private final Runnable onFailCallback;

    public ActionQueue(Runnable onFailCallback) {
        this.onFailCallback = onFailCallback;
    }

    public void enqueue(String name, BooleanSupplier executor) {
        queue.add(new Action(name, executor));
    }

    public void clear() {
        queue.clear();
        currentAction = null;
        retryCount = 0;
        tickDelay = 0;
        failed = false;
    }

    public boolean isFailed() {
        return failed;
    }

    public boolean isEmpty() {
        return queue.isEmpty() && currentAction == null;
    }

    /**
     * Вызывать каждый тик. Возвращает true, если очередь завершена успешно.
     */
    public boolean tick(MinecraftClient client) {
        if (failed) return false;

        if (tickDelay > 0) {
            tickDelay--;
            return false;
        }

        if (currentAction == null) {
            if (queue.isEmpty()) return true; // всё выполнено
            currentAction = queue.poll();
            retryCount = 0;
            LOGGER.info("[AutoCraft] Выполняю действие: {}", currentAction.name());
        }

        boolean success = false;
        try {
            success = currentAction.executor().getAsBoolean();
        } catch (Exception e) {
            LOGGER.error("[AutoCraft] Ошибка при выполнении действия '{}': {}", currentAction.name(), e.getMessage());
        }

        if (success) {
            LOGGER.info("[AutoCraft] Действие '{}' выполнено успешно", currentAction.name());
            currentAction = null;
            retryCount = 0;
            tickDelay = 2; // пауза 2 тика между действиями
        } else {
            retryCount++;
            LOGGER.warn("[AutoCraft] Действие '{}' не сработало. Попытка {}/{}", currentAction.name(), retryCount, MAX_RETRIES);
            if (retryCount >= MAX_RETRIES) {
                LOGGER.error("[AutoCraft] Действие '{}' провалилось после {} попыток. Крафт остановлен.", currentAction.name(), MAX_RETRIES);
                failed = true;
                clear();
                if (onFailCallback != null) onFailCallback.run();
                return false;
            }
            tickDelay = 5; // подождать 5 тиков перед retry
        }

        return false;
    }
}
