package dev.aether.macro;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// one queue for every macro task, so modules don't each spawn their own thread
// tasks may block freely, but anything touching game state has to go through client.execute
public final class MacroWorkerThread {

    // -- Singleton ------------------------------------------------------------

    private static final MacroWorkerThread INSTANCE = new MacroWorkerThread();

    public static MacroWorkerThread getInstance() {
        return INSTANCE;
    }

    // -- State ----------------------------------------------------------------

    private static final String THREAD_NAME = "aether-worker";

    private final LinkedBlockingQueue<TaskEntry> queue = new LinkedBlockingQueue<>();

    private volatile boolean cancelRequested = false;
    private final AtomicLong cancellationGeneration = new AtomicLong();
    private volatile long currentTaskGeneration;

    private volatile String currentTaskName = "(idle)";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread workerThread;

    // -- Public API ------------------------------------------------------------

    // no-op if the thread is already alive
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

    // runs after everything already queued; the task may block freely
    public void submit(String taskName, Runnable task) {
        long generation = Thread.currentThread() == workerThread
                ? currentTaskGeneration : cancellationGeneration.get();
        if (generation != cancellationGeneration.get()) return;
        debugLog("Queuing task: [" + taskName + "] (queue size before: " + queue.size() + ")");
        queue.add(new TaskEntry(taskName, task, generation));
    }

    // also drains everything still pending
    public void cancelCurrent() {
        cancellationGeneration.incrementAndGet();
        cancelRequested = true;
        int drained = queue.size();
        queue.clear();
        debugLog("Cancel requested for [" + currentTaskName + "]; drained " + drained + " pending task(s).");
    }

    public void replaceCurrent(String taskName, Runnable task) {
        long generation = cancellationGeneration.incrementAndGet();
        cancelRequested = true;
        int drained = queue.size();
        queue.clear();
        queue.add(new TaskEntry(taskName, task, generation));
        debugLog("Replacing [" + currentTaskName + "] with [" + taskName + "]; drained " + drained
                + " pending task(s).");
    }

    // leaves the running task alone
    public void clearPendingTasks() {
        int drained = queue.size();
        queue.clear();
        debugLog("Cleared " + drained + " pending task(s) from queue.");
    }

    public boolean isCancelled() {
        return cancelRequested || (Thread.currentThread() == workerThread
                && currentTaskGeneration != cancellationGeneration.get());
    }

    public Runnable cancellable(Runnable action) {
        long generation = Thread.currentThread() == workerThread
                ? currentTaskGeneration : cancellationGeneration.get();
        return () -> {
            if (generation == cancellationGeneration.get()) action.run();
        };
    }

    public static void runOnClient(Minecraft client, Runnable action) {
        client.execute(getInstance().cancellable(action));
    }

    // abort on cancel, macro stopped, or no client/player
    public static boolean shouldAbortTask(Minecraft client) {
        return getInstance().isCancelled()
                || !MacroStateManager.isMacroRunning()
                || client == null
                || client.player == null;
    }

    // same, plus the macro has to still be in the given state
    public static boolean shouldAbortTask(Minecraft client, MacroState.State requiredState) {
        return shouldAbortTask(client) || MacroStateManager.getCurrentState() != requiredState;
    }

    public boolean isBusy() {
        return queue.size() > 0 || !currentTaskName.equals("(idle)");
    }

    public boolean hasActiveWork() {
        return !queue.isEmpty() || (!cancelRequested && !currentTaskName.equals("(idle)")
                && currentTaskGeneration == cancellationGeneration.get());
    }

    public String getCurrentTaskName() {
        return currentTaskName;
    }

    // -- Internal -------------------------------------------------------------

    private void loop() {
        debugLog("Worker loop started on thread: " + Thread.currentThread().getName());
        while (running.get()) {
            try {
                TaskEntry entry = queue.take(); // blocks until a task is available
                currentTaskGeneration = entry.generation;
                if (currentTaskGeneration != cancellationGeneration.get()) continue;
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

    // false means interrupted, so the task should abort
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
        final long generation;

        TaskEntry(String name, Runnable task, long generation) {
            this.name = name;
            this.task = task;
            this.generation = generation;
        }
    }
}
