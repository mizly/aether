package dev.aether.renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Queues immediate-mode GL/NanoVG work discovered during GUI extraction and
 * runs it during the actual render pass.
 */
public final class AetherRenderQueue {
    private static final List<Runnable> TASKS = new ArrayList<>();
    private static final List<Runnable> BEFORE_GUI_TASKS = new ArrayList<>();
    private AetherRenderQueue() {
    }

    public static void enqueue(Runnable task) {
        enqueue(TASKS, task);
    }

    public static void enqueueBeforeGui(Runnable task) {
        enqueue(BEFORE_GUI_TASKS, task);
    }

    private static void enqueue(List<Runnable> queue, Runnable task) {
        if (task == null) {
            return;
        }
        synchronized (queue) {
            queue.add(task);
        }
    }

    public static void flush() {
        flush(TASKS);
    }

    public static void flushBeforeGui() {
        flush(BEFORE_GUI_TASKS);
    }

    private static void flush(List<Runnable> queue) {
        while (true) {
            List<Runnable> tasks;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    return;
                }
                tasks = new ArrayList<>(queue);
                queue.clear();
            }

            for (Runnable task : tasks) {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void clear() {
        synchronized (BEFORE_GUI_TASKS) {
            BEFORE_GUI_TASKS.clear();
        }
        synchronized (TASKS) {
            TASKS.clear();
        }
    }
}
