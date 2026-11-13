package dev.aether.modules.session

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.ReconnectScheduler
import dev.aether.ui.DynamicRestScreen
import dev.aether.ui.FunnyDynamicRestScreen
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import java.util.Random
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

// timer counts down while the macro is enabled; when it expires the rest is queued and the staged shutdown starts once farming resumes
// after the break the reconnect recovery path warps back to the garden and restarts
class DynamicRestManager {
    private data class RestWindow(val durationMs: Long, val triggerMs: Long)

    companion object {
        private fun isEnabled(): Boolean = AetherConfig.DYNAMIC_REST_ENABLED.get()

        // 0 = not scheduled yet
        private var nextRestTriggerMs: Long = 0
        private var scheduledDurationMs: Long = 0
        private var restSequencePending: Boolean = false
        private var restSequenceStage: Int = 0
        private var nextStageActionTime: Long = 0
        private var lastTimerUpdateMs: Long = 0
        private var restSetSpawnWindow: CommandUtils.ChatWindow? = null
        private var dailyThresholdTriggered: Boolean = false

        // called on macro start or after a recovery reconnect
        @JvmStatic
        fun scheduleNextRest() {
            if (!isEnabled()) {
                reset()
                return
            }

            if (AetherConfig.PERSIST_SESSION_TIMER.get() && nextRestTriggerMs != 0L) {
                return
            }

            val restWindow = createRestWindow(System.currentTimeMillis(), 0L)
            scheduledDurationMs = restWindow.durationMs
            nextRestTriggerMs = restWindow.triggerMs
            restSequencePending = false
            restSequenceStage = 0
            nextStageActionTime = 0
            dailyThresholdTriggered = false
            lastTimerUpdateMs = System.currentTimeMillis()
        }

        // keeps the elapsed session time
        @JvmStatic
        fun refreshCurrentSession() {
            if (!isEnabled()) {
                reset()
                return
            }

            if (nextRestTriggerMs == 0L || scheduledDurationMs <= 0L || restSequencePending) {
                return
            }

            val now = System.currentTimeMillis()
            val elapsedMs = Math.max(0L, scheduledDurationMs - Math.max(0L, nextRestTriggerMs - now))
            val restWindow = createRestWindow(now, elapsedMs)
            scheduledDurationMs = restWindow.durationMs
            nextRestTriggerMs = restWindow.triggerMs
            lastTimerUpdateMs = now
        }

        @JvmStatic
        fun reset() {
            nextRestTriggerMs = 0
            scheduledDurationMs = 0
            restSequencePending = false
            restSequenceStage = 0
            nextStageActionTime = 0
            lastTimerUpdateMs = 0
            restSetSpawnWindow = null
            dailyThresholdTriggered = false
        }

        @JvmStatic
        fun isRestPending(): Boolean = isEnabled() && restSequencePending

        // 0 when not set
        @JvmStatic
        fun getNextRestTriggerMs(): Long = if (isEnabled()) nextRestTriggerMs else 0

        // 0 when not set
        @JvmStatic
        fun getScheduledDurationMs(): Long = if (isEnabled()) scheduledDurationMs else 0

        private fun createRestWindow(nowMs: Long, elapsedMs: Long): RestWindow {
            val baseMs = AetherConfig.REST_SCRIPTING_TIME.get() * 60L * 1000L
            val offsetMs = AetherConfig.REST_SCRIPTING_TIME_OFFSET.get() * 60L * 1000L
            val randomOffsetMs = if (offsetMs > 0) (Random().nextDouble() * offsetMs).toLong() else 0
            val durationMs = baseMs + randomOffsetMs
            val remainingMs = Math.max(0L, durationMs - Math.max(0L, elapsedMs))
            return RestWindow(durationMs, nowMs + remainingMs)
        }

        // call every END_CLIENT_TICK while player != null; drives both the countdown hud and the shutdown sequence
        @JvmStatic
        fun update() {
            val client = Minecraft.getInstance()
            if (client.player == null) {
                return
            }

            if (!isEnabled()) {
                if (nextRestTriggerMs != 0L || scheduledDurationMs != 0L || restSequencePending) {
                    reset()
                }
                return
            }

            val currentState = MacroStateManager.getCurrentState()
            val isFarming = currentState == MacroState.State.FARMING
            val isMacroEnabled = currentState != MacroState.State.OFF
            val now = System.currentTimeMillis()

            checkDailyThreshold(client)

            if (nextRestTriggerMs > 0L && !restSequencePending) {
                if (!isMacroEnabled) {
                    if (lastTimerUpdateMs == 0L) {
                        lastTimerUpdateMs = now
                    } else {
                        val pausedForMs = now - lastTimerUpdateMs
                        if (pausedForMs > 0L) {
                            nextRestTriggerMs += pausedForMs
                        }
                        lastTimerUpdateMs = now
                    }
                } else {
                    lastTimerUpdateMs = now
                    if (now >= nextRestTriggerMs) {
                        restSequencePending = true
                        restSequenceStage = 0
                        nextStageActionTime = now
                        ClientUtils.sendMessage("\u00A7eDynamic Rest queued. Waiting for farming to resume before resting...", false)
                    }
                }
            }

            if (!restSequencePending) {
                return
            }

            if (now < nextStageActionTime) {
                return
            }

            when (restSequenceStage) {
                0 -> {
                    if (!isFarming) {
                        return
                    }

                    ClientUtils.sendDebugMessage("Disabling farming macro: Initiating dynamic rest sequence")
                    client.execute { dev.aether.macro.farming.FarmingMacroManager.disable(client) }
                    ClientUtils.forceReleaseKeys()
                    ClientUtils.sendMessage(
                        if (CommandUtils.shouldSkipSetSpawn()) "\u00A7eDynamic Rest: preparing disconnect..."
                        else "\u00A7eDynamic Rest: running /setspawn...",
                        false
                    )
                    restSetSpawnWindow = CommandUtils.beginChatWindow()
                    dev.aether.util.CommandUtils.initiateSetSpawn()
                    MacroStateManager.setCurrentState(MacroState.State.OFF)

                    restSequenceStage = 1
                    nextStageActionTime = System.currentTimeMillis() + 3000
                }
                1 -> {
                    if (!dev.aether.util.CommandUtils.hasSpawnBeenSet(restSetSpawnWindow)
                        && System.currentTimeMillis() < nextStageActionTime) {
                        return
                    }

                    val baseSecs = AetherConfig.REST_BREAK_TIME.get() * 60L
                    val offsetSecs = AetherConfig.REST_BREAK_TIME_OFFSET.get() * 60L
                    val randomOffsetSecs = if (offsetSecs > 0) (Random().nextDouble() * offsetSecs).toLong() else 0
                    val breakSeconds = baseSecs + randomOffsetSecs

                    ClientUtils.sendMessage(String.format(
                        "\u00A7eDynamic Rest: disconnecting. Reconnecting in %.1f minutes...",
                        breakSeconds.toDouble() / 60.0), false)

                    MacroStateManager.setIntentionalDisconnect(true)
                    ReconnectScheduler.scheduleReconnect(breakSeconds, true)

                    val durationMs = breakSeconds * 1000
                    val restEndTimeMs = System.currentTimeMillis() + durationMs

                    nextRestTriggerMs = 0

                    val restScreen: Screen = if (AetherConfig.FUNNY_DYNAMIC_REST.get())
                        FunnyDynamicRestScreen(restEndTimeMs)
                    else DynamicRestScreen(restEndTimeMs, durationMs)
                    ClientUtils.disconnectWithScreen(
                        restScreen,
                        net.minecraft.network.chat.Component.literal("Dynamic rest"))

                    restSequenceStage = 2
                    nextStageActionTime = Long.MAX_VALUE
                }
                else -> Unit
            }
        }

        private fun checkDailyThreshold(client: Minecraft) {
            val thresholdHours = AetherConfig.DAILY_FARM_THRESHOLD_HOURS.get()
            if (thresholdHours <= 0.0) {
                dailyThresholdTriggered = false
                return
            }

            val thresholdMs = (thresholdHours * 3_600_000.0).toLong()
            val todayMs = DailyFarmTimeTracker.getTodayMs()
            if (dailyThresholdTriggered) {
                if (todayMs < thresholdMs) {
                    dailyThresholdTriggered = false
                }
                return
            }

            if (!MacroStateManager.isMacroRunning()) {
                return
            }

            if (todayMs < thresholdMs) {
                return
            }

            dailyThresholdTriggered = true
            ClientUtils.sendMessage("\u00A7e" + String.format(
                AetherLang.localize("Daily farming threshold reached (%.2f hours). Stopping macro..."),
                thresholdHours), false)
            MacroStateManager.stopMacro(client, "Dynamic Rest: daily farming threshold reached")
            ReconnectScheduler.cancel()

            if (AetherConfig.CLOSE_GAME_ON_DAILY_THRESHOLD.get()) {
                MacroWorkerThread.getInstance().submit("DailyThreshold-CloseGame", Runnable {
                    MacroWorkerThread.sleep(1000L)
                    client.execute { client.stop() }
                })
            }
        }
    }
}
