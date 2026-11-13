package dev.aether.util

import dev.aether.config.AetherConfig
import dev.aether.macro.farming.FarmingMacroManager
import java.util.ArrayDeque
import java.util.Deque

class BpsTracker {
    companion object {
        private val breakTimes: Deque<Long> = ArrayDeque()
        private var farmingClockMs: Long = 0

        @JvmStatic fun onBlockBreak() {
            if (!FarmingMacroManager.isActive()) return
            val active = FarmingMacroManager.getActiveMacro()
            if (active == null || !active.isFarmingState()) return
            synchronized(breakTimes) {
                breakTimes.addLast(farmingClockMs)
                cleanup()
            }
        }

        // call every tick
        @JvmStatic fun tick() {
            if (FarmingMacroManager.isActive()) {
                val active = FarmingMacroManager.getActiveMacro()
                if (active != null && active.isFarmingState()) farmingClockMs += 50
            }
            synchronized(breakTimes) { cleanup() }
        }

        private fun cleanup() {
            val windowMs = AetherConfig.BPS_AVERAGE_WINDOW.get() * 1000L
            while (!breakTimes.isEmpty() && farmingClockMs - breakTimes.peekFirst() > windowMs) breakTimes.pollFirst()
        }

        @JvmStatic fun reset() {
            synchronized(breakTimes) {
                breakTimes.clear()
                farmingClockMs = 0
            }
        }

        @JvmStatic fun getBreakCount(): Int = synchronized(breakTimes) { breakTimes.size }

        // the configured window or the total farming time, whichever is smaller
        @JvmStatic fun getActualWindowSeconds(): Float {
            val window = AetherConfig.BPS_AVERAGE_WINDOW.get().toFloat()
            val elapsed = farmingClockMs / 1000.0f
            return Math.max(0.1f, Math.min(window, elapsed))
        }

        @JvmStatic fun getBps(): Float = synchronized(breakTimes) {
            cleanup()
            val window = getActualWindowSeconds()
            breakTimes.size.toFloat() / window
        }
    }
}
