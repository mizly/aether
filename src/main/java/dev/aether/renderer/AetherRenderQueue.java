package dev.aether.renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Queues immediate-mode GL/NanoVG work discovered during GUI extraction and
 * runs it during the actual render pass.
 */
public final class AetherRenderQueue {
    private static final List<Runnable> TASKS = new ArrayList<>();
    private AetherRenderQueue() {
    }

    public static void enqueue(Runnable task) {
        if (task == null) {
            return;
        }
        synchronized (TASKS) {
            TASKS.add(task);
        }
    }

    public static void flush() {
        while (true) {
            List<Runnable> tasks;
            synchronized (TASKS) {
                if (TASKS.isEmpty()) {
                    return;
                }
                tasks = new ArrayList<>(TASKS);
                TASKS.clear();
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
        synchronized (TASKS) {
            TASKS.clear();
        }
    }
}
