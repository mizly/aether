package dev.aether.modules.pest

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.failsafe.DesktopNotificationManager
import dev.aether.modules.failsafe.FailsafeColourFlashManager
import dev.aether.modules.failsafe.FailsafeSoundManager
import dev.aether.modules.failsafe.FailsafeWindowFocusManager
import dev.aether.modules.farming.UngrabMouse
import dev.aether.modules.performance.MuteManager
import dev.aether.modules.performance.PerformanceModeManager
import dev.aether.modules.pest.helpers.PestCompletionGuard
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

// manual version of the CLEANING stage; the shared PRE and POST stages still own travel, loadouts, traps, visitors and the return to farming
class ManualPestManager private constructor() {
    private enum class Phase { IDLE, WAITING }

    companion object {
        @Volatile private var phase = Phase.IDLE
        @Volatile private var waitingStartedAt = 0L
        @Volatile private var clearedPestTabTicks = 0

        @JvmStatic fun reset() {
            phase = Phase.IDLE
            waitingStartedAt = 0L
            clearedPestTabTicks = 0
            PestManager.clearCleaningTriggerPending()
        }

        @JvmStatic fun isActive(): Boolean = phase != Phase.IDLE

        @JvmStatic fun requestEarlyFinish(client: Minecraft?) {
            if (client == null || phase != Phase.WAITING || !AetherConfig.MANUAL_PEST_MODE.get() || MacroStateManager.getCurrentState() != MacroState.State.CLEANING || !PestManager.isCleaningInProgress()) return
            ClientUtils.sendMessage("\u00A7eManual Pest Mode: early finish requested. Running pest post-actions.", false)
            finishCleaningStage(client)
        }

        @JvmStatic fun startCleaningStage(client: Minecraft?, count: Int): Boolean {
            if (!AetherConfig.MANUAL_PEST_MODE.get() || phase != Phase.IDLE) return false
            if (client == null || client.player == null || client.connection == null || !MacroStateManager.isMacroRunning()) return false
            beginWaiting(client, Math.max(1, count))
            return true
        }

        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            if (client == null || client.player == null || client.connection == null) return
            if (!AetherConfig.MANUAL_PEST_MODE.get()) {
                if (phase != Phase.IDLE) {
                    phase = Phase.IDLE
                    waitingStartedAt = 0L
                    clearedPestTabTicks = 0
                    PestManager.handlePestCleaningFinished(client)
                }
                return
            }
            if (phase == Phase.WAITING) tickWaiting(client)
        }

        private fun tickWaiting(client: Minecraft) {
            if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.CLEANING || !PestManager.isCleaningInProgress()) {
                reset()
                return
            }
            if (currentPestCount(client) <= 0) {
                if (!PestCompletionGuard.shouldAcceptFinishReading(waitingStartedAt, false)) {
                    if (clearedPestTabTicks == 0) ClientUtils.sendDebugMessage("Manual pest: ignoring clear tab reading during startup grace.")
                    clearedPestTabTicks = 0
                    return
                }
                clearedPestTabTicks++
                if (PestCompletionGuard.isConfirmed(clearedPestTabTicks)) finishCleaningStage(client)
            } else clearedPestTabTicks = 0
        }

        private fun beginWaiting(client: Minecraft, count: Int) {
            ClientUtils.sendMessage("\u00A7eManual Pest Mode: \u00A7f$count pest(s) detected. \u00A7ePausing for manual kill.", false)
            ClientUtils.forceReleaseKeys()
            client.execute {
                UngrabMouse.clearMacroUngrab()
                PerformanceModeManager.stop(client)
                MuteManager.stop(client)
            }
            phase = Phase.WAITING
            waitingStartedAt = System.currentTimeMillis()
            clearedPestTabTicks = 0
            fireAlert(count)
        }

        private fun finishCleaningStage(client: Minecraft) {
            phase = Phase.IDLE
            waitingStartedAt = 0L
            clearedPestTabTicks = 0
            ClientUtils.sendMessage("\u00A7aManual Pest Mode: pests cleared. Running pest post-actions.", false)
            UngrabMouse.ungrabMouse()
            PestManager.handlePestCleaningFinished(client)
        }

        private fun fireAlert(count: Int) {
            FailsafeColourFlashManager.trigger()
            FailsafeSoundManager.playConfiguredSoundFile(AetherConfig.MANUAL_PEST_SOUND_FILE.get())
            FailsafeWindowFocusManager.bringWindowToFront()
            if (AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) DesktopNotificationManager.notify("Pests Detected", "$count pest(s) spawned - macro paused for manual kill.", true)
        }

        private fun currentPestCount(client: Minecraft): Int {
            val rawTab = PestManager.getTabAliveCountNow(client)
            if (rawTab >= 0) return rawTab
            return if (PestManager.getInfestedPlotsFromTab(client).isEmpty()) 0 else 1
        }
    }
}
