package dev.aether.macro;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton worker thread that serialises all macro tasks through a single
 * queue, eliminating the race conditions that arise when multiple modules each
 * spawn their own raw {@code new Thread(...)}.
 *
 * <p>
 * Any module that previously did {@code new Thread(() -> { ... }).start()}
 * should instead call {@link #submit(String, Runnable)}. The runnable runs
 * on this shared thread, so:
 * <ul>
 * <li>It is free to block (Thread.sleep, waitFor*, etc.).</li>
 * <li>Any Minecraft client API call that must read/write game state
 * (slots, screens, player, etc.) must be dispatched via
 * {@code client.execute(() -> { ... })} (optionally with a
 * {@link java.util.concurrent.CountDownLatch} if you need to wait for
 * the result).</li>
 * </ul>
 *
 * <p>
 * Only one task runs at a time. If a new task is submitted while one is
 * running the old task is NOT interrupted - the new task waits in the queue.
 * To cancel in-flight work, call {@link #cancelCurrent()}.
 */
public final class MacroWorkerThread {

    // -- Singleton ------------------------------------------------------------

    private static final MacroWorkerThread INSTANCE = new MacroWorkerThread();

    public static MacroWorkerThread getInstance() {
        return INSTANCE;
    }

    // -- State ----------------------------------------------------------------

    private static final String THREAD_NAME = "aether-worker";

    /**
     * Queue of pending tasks. Each entry carries a human-readable label for
     * debugging.
     */
    private final LinkedBlockingQueue<TaskEntry> queue = new LinkedBlockingQueue<>();

    /** Set to true when the current task should abort at its next check-point. */
    private volatile boolean cancelRequested = false;

    /** Name of the task that is currently executing (for debug messages). */
    private volatile String currentTaskName = "(idle)";

    /** Whether the worker thread is alive. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread workerThread;

    // -- Public API ------------------------------------------------------------

    /**
     * Start the worker thread. Safe to call multiple times - it is a no-op
     * if the thread is already alive.
     */
    public synchronized void start() {
        if (running.get() && workerThread != null && workerThread.isAlive()) {
            return;
        }
        running.set(true);
        workerThread = new Thread(this::loop, THREAD_NAME);
        workerThread.setDaemon(true);
        workerThread.start();
        debugLog("Worker thread started.");
    }

    /**
     * Submit a named task to the queue. The task will be executed on the
     * single worker thread after all previously queued tasks finish.
     *
     * @param taskName Human-readable name shown in debug chat messages.
     * @param task     The work to do (may block freely).
     */
    public void submit(String taskName, Runnable task) {
        debugLog("Queuing task: [" + taskName + "] (queue size before: " + queue.size() + ")");
        queue.add(new TaskEntry(taskName, task));
    }

    /**
     * Request that the currently-executing task abort at its next
     * cancellation check-point (see {@link #isCancelled()}).
     * Also drains all pending tasks from the queue.
     */
    public void cancelCurrent() {
        cancelRequested = true;
        int drained = queue.size();
        queue.clear();
        debugLog("Cancel requested for [" + currentTaskName + "]; drained " + drained + " pending task(s).");
    }

    /**
     * Drains all pending tasks from the queue but does NOT request that the
     * currently-executing task abort.
     */
    public void clearPendingTasks() {
        int drained = queue.size();
        queue.clear();
        debugLog("Cleared " + drained + " pending task(s) from queue.");
    }

    /**
     * Called inside a task's body to check whether it should stop early.
     * Example usage:
     * 
     * <pre>
     * if (MacroWorkerThread.getInstance().isCancelled())
     *     return;
     * </pre>
     */
    public boolean isCancelled() {
        return cancelRequested;
    }

    /**
     * Common checkpoint for long-running worker tasks.
     * Abort when task cancellation is requested, macro is not running,
     * or the client/player context is unavailable.
     */
    public static boolean shouldAbortTask(Minecraft client) {
        return getInstance().isCancelled()
                || !MacroStateManager.isMacroRunning()
                || client == null
                || client.player == null;
    }

    /**
     * Common checkpoint for tasks that are only valid in a specific macro state.
     */
    public static boolean shouldAbortTask(Minecraft client, MacroState.State requiredState) {
        return shouldAbortTask(client) || MacroStateManager.getCurrentState() != requiredState;
    }

    /** @return true if any task is currently running or pending. */
    public boolean isBusy() {
        return queue.size() > 0 || !currentTaskName.equals("(idle)");
    }

    /** @return the name of the task currently executing, or {@code "(idle)"}. */
    public String getCurrentTaskName() {
        return currentTaskName;
    }

    // -- Internal -------------------------------------------------------------

    private void loop() {
        debugLog("Worker loop started on thread: " + Thread.currentThread().getName());
        while (running.get()) {
            try {
                TaskEntry entry = queue.take(); // blocks until a task is available
                cancelRequested = false;
                currentTaskName = entry.name;
                debugLog("Executing task: [" + entry.name + "] on thread: " + Thread.currentThread().getName());
                try {
                    entry.task.run();
                } catch (Exception e) {
                    debugLog("Task [" + entry.name + "] threw exception: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    debugLog("Task [" + entry.name + "] finished. Queue remaining: " + queue.size());
                    currentTaskName = "(idle)";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                debugLog("Worker thread interrupted - exiting loop.");
                break;
            }
        }
        running.set(false);
        debugLog("Worker loop exited.");
    }

    private static void debugLog(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            ClientUtils.sendDebugMessage("[Worker] " + message);
        }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Convenience: sleep on the worker thread while honouring cancellation.
     * Returns {@code false} if the thread was interrupted (task should abort).
     */
    public static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean sleepRandom(long base, long spread) {
        return sleep(base + (long)(Math.random() * spread));
    }

    // -- Inner types -----------------------------------------------------------

    private static final class TaskEntry {
        final String name;
        final Runnable task;

        TaskEntry(String name, Runnable task) {
            this.name = name;
            this.task = task;
        }
    }
}
