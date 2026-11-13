package dev.aether.macro

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import java.util.ArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// one queue for every macro task, so modules don't each spawn their own thread
// tasks may block freely, but anything touching game state has to go through client.execute
class MacroWorkerThread internal constructor() {
    private val queue = LinkedBlockingQueue<TaskEntry>()
    @Volatile private var cancelRequested = false
    private val cancellationGeneration = AtomicLong()
    @Volatile private var currentTaskGeneration = 0L
    @JvmField @Volatile var currentTaskName: String = "(idle)"
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    @Synchronized fun start() {
        if (running.get() && workerThread != null && workerThread!!.isAlive) return
        running.set(true)
        workerThread = Thread({ loop() }, THREAD_NAME)
        workerThread!!.isDaemon = true
        workerThread!!.start()
        debugLog("Worker thread started.")
    }
    fun submit(taskName: String, task: Runnable) {
        val generation = if (Thread.currentThread() === workerThread) currentTaskGeneration else cancellationGeneration.get()
        if (generation != cancellationGeneration.get()) return
        debugLog("Queuing task: [$taskName] (queue size before: ${queue.size})")
        queue.add(TaskEntry(taskName, task, generation))
    }
    fun submitCritical(taskName: String, task: Runnable) {
        val generation = if (Thread.currentThread() === workerThread) currentTaskGeneration else cancellationGeneration.get()
        if (generation != cancellationGeneration.get()) return
        debugLog("Queuing critical task: [$taskName] (queue size before: ${queue.size})")
        queue.add(TaskEntry(taskName, task, generation, true))
    }
    fun cancelCurrent() {
        cancellationGeneration.incrementAndGet()
        cancelRequested = true
        val drained = queue.size
        queue.clear()
        debugLog("Cancel requested for [$currentTaskName]; drained $drained pending task(s).")
    }
    fun replaceCurrent(taskName: String, task: Runnable) {
        val generation = cancellationGeneration.incrementAndGet()
        cancelRequested = true
        val drained = queue.size
        queue.clear()
        queue.add(TaskEntry(taskName, task, generation))
        debugLog("Replacing [$currentTaskName] with [$taskName]; drained $drained pending task(s).")
    }
    fun clearPendingTasks() {
        val pending = ArrayList<TaskEntry>()
        queue.drainTo(pending)
        var dropped = 0
        for (entry in pending) if (entry.critical) queue.add(entry) else dropped++
        debugLog("Cleared $dropped pending task(s) from queue." + if (queue.isEmpty()) "" else " Kept ${queue.size} critical task(s).")
    }
    fun isCancelled() = cancelRequested || (Thread.currentThread() === workerThread && currentTaskGeneration != cancellationGeneration.get())
    fun cancellable(action: Runnable): Runnable {
        val generation = if (Thread.currentThread() === workerThread) currentTaskGeneration else cancellationGeneration.get()
        return Runnable { if (generation == cancellationGeneration.get()) action.run() }
    }
    fun isBusy() = queue.size > 0 || currentTaskName != "(idle)"
    fun hasActiveWork() = !queue.isEmpty() || (!cancelRequested && currentTaskName != "(idle)" && currentTaskGeneration == cancellationGeneration.get())
    fun getCurrentTaskName(): String = currentTaskName

    private fun loop() {
        debugLog("Worker loop started on thread: ${Thread.currentThread().name}")
        while (running.get()) try {
            val entry = queue.take()
            currentTaskGeneration = entry.generation
            if (currentTaskGeneration != cancellationGeneration.get()) continue
            cancelRequested = false
            currentTaskName = entry.name
            debugLog("Executing task: [${entry.name}] on thread: ${Thread.currentThread().name}")
            try { entry.task.run() } catch (e: Exception) {
                debugLog("Task [${entry.name}] threw exception: ${e.message}")
                e.printStackTrace()
            } finally {
                debugLog("Task [${entry.name}] finished. Queue remaining: ${queue.size}")
                currentTaskName = "(idle)"
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            debugLog("Worker thread interrupted - exiting loop.")
            break
        }
        running.set(false)
        debugLog("Worker loop exited.")
    }
    private fun debugLog(message: String) {
        try {
            val mc = Minecraft.getInstance()
            if (mc != null) ClientUtils.sendDebugMessage("[Worker] $message")
        } catch (ignored: Throwable) {
            // outside a running game (unit tests) the client instance is unavailable
        }
    }
    private class TaskEntry(val name: String, val task: Runnable, val generation: Long, val critical: Boolean = false)

    companion object {
        private const val THREAD_NAME = "aether-worker"
        private val INSTANCE = MacroWorkerThread()
        @JvmStatic fun getInstance(): MacroWorkerThread = INSTANCE
        @JvmStatic fun runOnClient(client: Minecraft, action: Runnable) { client.execute(getInstance().cancellable(action)) }
        @JvmStatic fun shouldAbortTask(client: Minecraft): Boolean = getInstance().isCancelled() || !MacroStateManager.isMacroRunning() || client == null || client.player == null
        @JvmStatic fun shouldAbortTask(client: Minecraft, requiredState: MacroState.State): Boolean = shouldAbortTask(client) || MacroStateManager.getCurrentState() != requiredState
        // false means interrupted, so the task should abort
        @JvmStatic fun sleep(ms: Long): Boolean = try { Thread.sleep(ms); true } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        @JvmStatic fun sleepRandom(base: Long, spread: Long): Boolean = sleep(base + (Math.random() * spread).toLong())
    }
}
