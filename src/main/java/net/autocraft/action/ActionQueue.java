package net.autocraft.action;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;
import java.util.function.BooleanSupplier;

/**
 * ActionQueue Pro — очередь действий с состояниями,
 * задержками, подтверждением и pause/resume.
 */
public class ActionQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/ActionQueue");
    private static final int MAX_RETRIES = 3;
    private static final int CONFIRM_TIMEOUT_TICKS = 20; // 1 секунда

    // ── Состояние одного действия ─────────────────────────────────────────────

    public enum ActionState {
        PENDING,    // ожидает выполнения
        EXECUTING,  // выполняется
        CONFIRMING, // ждёт подтверждения от сервера
        DONE,       // успешно завершено
        FAILED      // провалилось
    }

    // ── Состояние всей очереди ────────────────────────────────────────────────

    public enum QueueState {
        IDLE,     // пусто, нет задач
        RUNNING,  // активно работает
        PAUSED,   // на паузе
        FINISHED, // всё выполнено успешно
        FAILED    // остановлена из-за ошибки
    }

    // ── Одно действие в очереди ───────────────────────────────────────────────

    public static class ActionEntry {
        public final String name;
        public final BooleanSupplier executor;    // выполнить действие → true если успех
        public final BooleanSupplier confirm;     // null = подтверждение не нужно
        public final int delayBefore;             // тиков задержки ПЕРЕД выполнением

        public ActionState state = ActionState.PENDING;
        public int retries      = 0;
        public int waitedTicks  = 0;

        public ActionEntry(String name,
                           BooleanSupplier executor,
                           BooleanSupplier confirm,
                           int delayBefore) {
            this.name       = name;
            this.executor   = executor;
            this.confirm    = confirm;
            this.delayBefore = delayBefore;
        }
    }

    // ── Поля очереди ──────────────────────────────────────────────────────────

    private final Queue<ActionEntry> queue = new LinkedList<>();
    private ActionEntry current           = null;
    private QueueState queueState         = QueueState.IDLE;
    private int delayCounter              = 0;
    private final Runnable onFail;
    private final Runnable onFinish;

    // ── Конструктор ───────────────────────────────────────────────────────────

    public ActionQueue(Runnable onFail, Runnable onFinish) {
        this.onFail    = onFail;
        this.onFinish  = onFinish;
    }

    // ── Добавление действий ───────────────────────────────────────────────────

    /** Простое действие без подтверждения и без задержки */
    public void enqueue(String name, BooleanSupplier executor) {
        enqueue(name, executor, null, 2);
    }

    /** Действие с задержкой перед выполнением (в тиках) */
    public void enqueue(String name, BooleanSupplier executor, int delayBefore) {
        enqueue(name, executor, null, delayBefore);
    }

    /**
     * Полная версия — действие + подтверждение + задержка.
     *
     * @param name        название для логов
     * @param executor    выполнить действие, вернуть true если успех
     * @param confirm     проверка подтверждения от сервера (null = не ждать)
     * @param delayBefore тиков паузы перед выполнением
     */
    public void enqueue(String name,
                        BooleanSupplier executor,
                        BooleanSupplier confirm,
                        int delayBefore) {
        queue.add(new ActionEntry(name, executor, confirm, delayBefore));
        if (queueState == QueueState.IDLE || queueState == QueueState.FINISHED) {
            queueState = QueueState.RUNNING;
        }
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    public void pause() {
        if (queueState == QueueState.RUNNING) {
            queueState = QueueState.PAUSED;
            LOGGER.info("[ActionQueue] Поставлена на паузу.");
        }
    }

    public void resume() {
        if (queueState == QueueState.PAUSED) {
            queueState = QueueState.RUNNING;
            LOGGER.info("[ActionQueue] Возобновлена.");
        }
    }

    public void clear() {
        queue.clear();
        current      = null;
        delayCounter = 0;
        queueState   = QueueState.IDLE;
    }

    // ── Геттеры состояния ─────────────────────────────────────────────────────

    public QueueState getQueueState() { return queueState; }
    public boolean isPaused()         { return queueState == QueueState.PAUSED; }
    public boolean isFailed()         { return queueState == QueueState.FAILED; }
    public boolean isFinished()       { return queueState == QueueState.FINISHED; }
    public boolean isEmpty()          { return queue.isEmpty() && current == null; }
    public int remaining()            { return queue.size() + (current != null ? 1 : 0); }

    // ── Основной тик ──────────────────────────────────────────────────────────

    /**
     * Вызывать каждый тик.
     * Возвращает true когда вся очередь успешно выполнена.
     */
    public boolean tick(MinecraftClient client) {
        if (queueState == QueueState.PAUSED
                || queueState == QueueState.FAILED
                || queueState == QueueState.IDLE) {
            return false;
        }

        if (queueState == QueueState.FINISHED) return true;

        // Загрузить следующее действие
        if (current == null) {
            if (queue.isEmpty()) {
                queueState = QueueState.FINISHED;
                LOGGER.info("[ActionQueue] Все действия выполнены.");
                if (onFinish != null) onFinish.run();
                return true;
            }
            current = queue.poll();
            current.state   = ActionState.PENDING;
            delayCounter    = current.delayBefore;
            LOGGER.info("[ActionQueue] Следующее действие: '{}'", current.name);
        }

        // ── Фаза PENDING: отсчёт задержки перед выполнением ──────────────────
        if (current.state == ActionState.PENDING) {
            if (delayCounter > 0) {
                delayCounter--;
                return false;
            }
            current.state = ActionState.EXECUTING;
        }

        // ── Фаза EXECUTING: выполнить действие ───────────────────────────────
        if (current.state == ActionState.EXECUTING) {
            boolean success = false;
            try {
                success = current.executor.getAsBoolean();
            } catch (Exception e) {
                LOGGER.error("[ActionQueue] Исключение в '{}': {}", current.name, e.getMessage());
            }

            if (success) {
                LOGGER.info("[ActionQueue] '{}' выполнено.", current.name);
                // Нужно ли ждать подтверждения?
                if (current.confirm != null) {
                    current.state       = ActionState.CONFIRMING;
                    current.waitedTicks = 0;
                } else {
                    current.state = ActionState.DONE;
                }
            } else {
                current.retries++;
                LOGGER.warn("[ActionQueue] '{}' не сработало. Попытка {}/{}",
                        current.name, current.retries, MAX_RETRIES);

                if (current.retries >= MAX_RETRIES) {
                    LOGGER.error("[ActionQueue] '{}' провалилось после {} попыток.",
                            current.name, MAX_RETRIES);
                    fail();
                    return false;
                }
                // Повтор через 5 тиков
                delayCounter  = 5;
                current.state = ActionState.PENDING;
            }
            return false;
        }

        // ── Фаза CONFIRMING: ждём подтверждения от сервера ───────────────────
        if (current.state == ActionState.CONFIRMING) {
            current.waitedTicks++;

            boolean confirmed = false;
            try {
                confirmed = current.confirm.getAsBoolean();
            } catch (Exception e) {
                LOGGER.error("[ActionQueue] Ошибка подтверждения '{}': {}", current.name, e.getMessage());
            }

            if (confirmed) {
                LOGGER.info("[ActionQueue] '{}' подтверждено сервером.", current.name);
                current.state = ActionState.DONE;
            } else if (current.waitedTicks >= CONFIRM_TIMEOUT_TICKS) {
                // Таймаут — продолжаем (не фейлим, чтобы не зависнуть на лагающем сервере)
                LOGGER.warn("[ActionQueue] Таймаут подтверждения '{}' — продолжаем.", current.name);
                current.state = ActionState.DONE;
            } else {
                return false; // ещё ждём
            }
        }

        // ── Фаза DONE: перейти к следующему действию ─────────────────────────
        if (current.state == ActionState.DONE) {
            current = null;
        }

        return false;
    }

    // ── Приватные вспомогательные ─────────────────────────────────────────────

    private void fail() {
        queueState = QueueState.FAILED;
        current    = null;
        queue.clear();
        if (onFail != null) onFail.run();
    }
}
