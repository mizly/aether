package dev.aether.renderer

import java.util.ArrayList

class AetherRenderQueue private constructor() {
    companion object {
        private val TASKS: MutableList<Runnable> = ArrayList()
        private val BEFORE_GUI_TASKS: MutableList<Runnable> = ArrayList()

        @JvmStatic fun enqueue(task: Runnable) { enqueue(TASKS, task) }
        @JvmStatic fun enqueueBeforeGui(task: Runnable) { enqueue(BEFORE_GUI_TASKS, task) }

        private fun enqueue(queue: MutableList<Runnable>, task: Runnable?) {
            if (task == null) return
            synchronized(queue) { queue.add(task) }
        }

        @JvmStatic fun flush() { flush(TASKS) }
        @JvmStatic fun flushBeforeGui() { flush(BEFORE_GUI_TASKS) }

        private fun flush(queue: MutableList<Runnable>) {
            while (true) {
                val tasks: List<Runnable>
                synchronized(queue) {
                    if (queue.isEmpty()) return
                    tasks = ArrayList(queue)
                    queue.clear()
                }
                for (task in tasks) {
                    try { task.run() } catch (e: RuntimeException) { e.printStackTrace() } catch (e: LinkageError) { e.printStackTrace() }
                }
            }
        }

        @JvmStatic fun clear() {
            synchronized(BEFORE_GUI_TASKS) { BEFORE_GUI_TASKS.clear() }
            synchronized(TASKS) { TASKS.clear() }
        }
    }
}
