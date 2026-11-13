package dev.aether.modules.session

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DailyFarmTimeTracker private constructor() {
    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        @Volatile private var accumulatedMs: Long = 0L
        @Volatile private var segmentStartMs: Long = 0L
        @Volatile private var trackedDate: String = ""

        @JvmStatic
        fun syncFromConfig() {
            val today = today()
            val savedDate = AetherConfig.DAILY_FARM_DATE.get()
            if (today == savedDate) {
                accumulatedMs = Math.max(0L, AetherConfig.DAILY_FARM_ACCUMULATED.get().toLong())
            } else {
                accumulatedMs = 0L
                AetherConfig.DAILY_FARM_DATE.set(today)
                AetherConfig.DAILY_FARM_ACCUMULATED.set(0.0)
                AetherConfig.save()
            }
            trackedDate = today
            segmentStartMs = if (MacroStateManager.isMacroRunning()) System.currentTimeMillis() else 0L
        }

        @JvmStatic
        fun onMacroStart() {
            maybeRolloverDate()
            if (segmentStartMs == 0L) {
                segmentStartMs = System.currentTimeMillis()
            }
        }

        @JvmStatic
        fun onMacroStop() {
            if (segmentStartMs != 0L) {
                accumulatedMs += Math.max(0L, System.currentTimeMillis() - segmentStartMs)
                segmentStartMs = 0L
                persist(true)
            }
        }

        @JvmStatic
        fun periodicSave() {
            maybeRolloverDate()
            persist(false)
        }

        @JvmStatic
        fun getTodayMs(): Long {
            maybeRolloverDate()
            if (segmentStartMs != 0L) {
                return accumulatedMs + Math.max(0L, System.currentTimeMillis() - segmentStartMs)
            }
            return accumulatedMs
        }

        // call on shutdown: onMacroStop only runs on an explicit stop, so a macro left running until the game closes would lose the day's uncommitted time
        // the segment origin moves to now, so calling twice cannot double-count
        @JvmStatic
        fun persistNow() {
            maybeRolloverDate()
            if (segmentStartMs != 0L) {
                val now = System.currentTimeMillis()
                accumulatedMs += Math.max(0L, now - segmentStartMs)
                segmentStartMs = now
            }
            persist(true)
        }

        @JvmStatic
        fun resetToday() {
            accumulatedMs = 0L
            trackedDate = today()
            segmentStartMs = if (MacroStateManager.isMacroRunning()) System.currentTimeMillis() else 0L
            persist(true)
        }

        private fun persist(flush: Boolean) {
            AetherConfig.DAILY_FARM_DATE.set(trackedDate)
            AetherConfig.DAILY_FARM_ACCUMULATED.set(getCurrentAccumulatedMs().toDouble())
            if (flush) {
                AetherConfig.save()
            }
        }

        private fun getCurrentAccumulatedMs(): Long {
            if (segmentStartMs == 0L) {
                return accumulatedMs
            }
            return accumulatedMs + Math.max(0L, System.currentTimeMillis() - segmentStartMs)
        }

        private fun maybeRolloverDate() {
            val today = today()
            if (today == trackedDate) {
                return
            }

            accumulatedMs = 0L
            trackedDate = today
            segmentStartMs = if (MacroStateManager.isMacroRunning()) System.currentTimeMillis() else 0L
            persist(true)
        }

        private fun today(): String = LocalDate.now().format(DATE_FORMAT)
    }
}
