package dev.aether.macro

import dev.aether.util.ClientUtils
import dev.aether.config.AetherConfig
import dev.aether.modules.ComposterManager
import dev.aether.modules.GreenhouseManager
import dev.aether.modules.SupercraftManager
import dev.aether.modules.TablistSetupManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.farming.BedrockPlotMaker
import dev.aether.modules.forge.ForgeManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.farming.UngrabMouse
import dev.aether.modules.gear.GearManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.inventorymanager.BookCombineManager
import dev.aether.modules.inventorymanager.GeorgeManager
import dev.aether.modules.inventorymanager.JunkManager
import dev.aether.modules.metaldetector.MetalDetectorSolver
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.modules.movement.MovementPlaybackManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.performance.MuteManager
import dev.aether.modules.performance.PerformanceModeManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.pest.helpers.AutoSprayonatorManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.PestExchangeManager
import dev.aether.modules.pest.helpers.PestOnTheTrackManager
import dev.aether.modules.pest.helpers.PestTrapManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.session.DailyFarmTimeTracker
import dev.aether.modules.session.DynamicRestManager
import dev.aether.modules.session.RecoveryManager
import dev.aether.modules.session.RestartManager
import dev.aether.modules.visitor.VisitorsMacro
import dev.aether.telemetry.AetherTelemetryService
import dev.aether.util.BpsTracker
import dev.aether.util.BazaarUtils
import net.minecraft.client.Minecraft

class MacroStateManager {
    companion object {
        @Volatile @JvmField internal var currentState = MacroState.State.OFF
        @Volatile private var intentionalDisconnect = false
        @Volatile private var sessionAccumulated = 0L
        @Volatile private var lifetimeAccumulated = 0L
        @Volatile private var lastSessionStartTime = 0L
        private var lastPeriodicSaveTime = 0L

        @JvmStatic fun resetSession() {
            if (isMacroRunning()) lastSessionStartTime = System.currentTimeMillis() else lastSessionStartTime = 0
            sessionAccumulated = 0
            ProfitManager.reset(); AutoCarnivalManager.resetTokenSession(); DynamicRestManager.reset(); BpsTracker.reset()
        }
        @JvmStatic fun syncFromConfig() {
            lifetimeAccumulated = AetherConfig.LIFETIME_ACCUMULATED.get().toLong()
            DailyFarmTimeTracker.syncFromConfig()
        }
        @JvmStatic fun periodicUpdate() {
            if (currentState == MacroState.State.OFF || currentState == MacroState.State.RECOVERING) return
            val now = System.currentTimeMillis()
            if (lastSessionStartTime <= 0) { lastPeriodicSaveTime = now; return }
            if (now - lastPeriodicSaveTime > 60000) {
                lastPeriodicSaveTime = now
                val diff = Math.max(0L, now - lastSessionStartTime)
                DailyFarmTimeTracker.periodicSave()
                AetherConfig.LIFETIME_ACCUMULATED.set((lifetimeAccumulated + diff).toDouble())
                AetherConfig.save()
            }
        }
        @JvmStatic fun getSessionRunningTime(): Long = if (currentState != MacroState.State.OFF && currentState != MacroState.State.RECOVERING && lastSessionStartTime != 0L) sessionAccumulated + (System.currentTimeMillis() - lastSessionStartTime) else sessionAccumulated
        @JvmStatic fun getLifetimeRunningTime(): Long = if (currentState != MacroState.State.OFF && currentState != MacroState.State.RECOVERING && lastSessionStartTime != 0L) lifetimeAccumulated + (System.currentTimeMillis() - lastSessionStartTime) else lifetimeAccumulated
        @JvmStatic fun isMacroRunning() = currentState != MacroState.State.OFF
        @JvmStatic fun isAutomationRunning() = isMacroRunning() || PathfindingManager.isNavigating() || PestDestroyer.isActive() || PestExchangeManager.isExchanging() || PestTrapManager.isRunning() || VisitorsMacro.isRunning || BedrockPlotMaker.isRunning() || ForgeManager.isRunning || ComposterManager.isRunning() || GreenhouseManager.isRunning() || SupercraftManager.isRunning() || AutoSellManager.isSelling || AutoSellManager.isPreparingToSell || TablistSetupManager.isActive() || RotationManager.isRotating() || MovementPlaybackManager.isPlaying() || MacroWorkerThread.getInstance().hasActiveWork()
        @JvmStatic fun isIntentionalDisconnect() = intentionalDisconnect
        @JvmStatic fun setIntentionalDisconnect(intentional: Boolean) { intentionalDisconnect = intentional }
        @JvmStatic fun getCurrentState() = currentState

        @JvmStatic fun setCurrentState(state: MacroState.State) {
            val prevState = currentState
            val client = Minecraft.getInstance()
            if (prevState == MacroState.State.RECOVERING && state != MacroState.State.OFF && state != MacroState.State.RECOVERING && !RecoveryManager.isResumeReady()) return
            if (state == MacroState.State.FARMING && prevState != MacroState.State.FARMING) { MacroWorkerThread.getInstance().clearPendingTasks(); PathfindingManager.stop() }
            currentState = state
            ProfitManager.updateSessionGraphClock()
            if (prevState == MacroState.State.FARMING && state != MacroState.State.FARMING) runOnClientThread(client, Runnable { FarmingMacroManager.releaseInputs(client) })
            if (prevState == MacroState.State.OFF && state != MacroState.State.OFF && state != MacroState.State.RECOVERING) {
                lastSessionStartTime = System.currentTimeMillis(); DailyFarmTimeTracker.onMacroStart()
                if (!AetherConfig.PERSIST_SESSION_TIMER.get()) { sessionAccumulated = 0; ProfitManager.reset(); AutoCarnivalManager.resetTokenSession() }
                lastPeriodicSaveTime = System.currentTimeMillis()
            } else if (prevState == MacroState.State.RECOVERING && state != MacroState.State.OFF && state != MacroState.State.RECOVERING) {
                lastSessionStartTime = System.currentTimeMillis(); DailyFarmTimeTracker.onMacroStart(); FailsafeManager.syncExpectedRotationFromClient(client); FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong())
            } else if (prevState != MacroState.State.OFF && prevState != MacroState.State.RECOVERING && (state == MacroState.State.OFF || state == MacroState.State.RECOVERING)) {
                if (lastSessionStartTime != 0L) {
                    val diff = System.currentTimeMillis() - lastSessionStartTime
                    sessionAccumulated += diff; lifetimeAccumulated += diff; lastSessionStartTime = 0
                    AetherConfig.LIFETIME_ACCUMULATED.set(lifetimeAccumulated.toDouble()); AetherConfig.save()
                }
                DailyFarmTimeTracker.onMacroStop()
            }
            if (state != MacroState.State.OFF) runOnClientThread(client, Runnable {
                if (AetherConfig.MACRO_UNGRAB_MOUSE.get()) UngrabMouse.requestMacroUngrab()
                PerformanceModeManager.start(client); MuteManager.start(client)
            }) else runOnClientThread(client, Runnable { UngrabMouse.clearMacroUngrab(); PerformanceModeManager.stop(client); MuteManager.stop(client) })
            if ((prevState == MacroState.State.OFF) != (state == MacroState.State.OFF)) AetherTelemetryService.onModEnabledChanged(state != MacroState.State.OFF)
        }

        @JvmStatic fun stopMacro(client: Minecraft) = stopMacro(client, "Macro stopped by user")
        @JvmStatic fun stopMacro() = stopMacro(Minecraft.getInstance())
        @JvmStatic fun stopMacro(client: Minecraft, debugReason: String) = stopMacro(client, debugReason, true)
        @JvmStatic fun stopMacro(client: Minecraft, debugReason: String, closeScreen: Boolean) {
            MacroWorkerThread.getInstance().cancelCurrent()
            if (BedrockPlotMaker.isRunning()) BedrockPlotMaker.stop(client)
            if (ForgeManager.isRunning) ForgeManager.stop()
            if (MovementPlaybackManager.isPlaying()) MovementPlaybackManager.stop()
            TablistSetupManager.stop(); BazaarUtils.cancel()
            runOnClientThread(client, Runnable { FarmingMacroManager.disable(client) })
            MetalDetectorSolver.stopForMacro(client); AutoCarnivalManager.stopForMacro(client); FailsafeManager.reset(); SqueakyMousematManager.clearReapplyAttempt()
            if (client != null) client.execute {
                if (closeScreen && client.gui.screen() != null) client.gui.setScreen(null)
                UngrabMouse.clearMacroUngrab()
            }
            setCurrentState(MacroState.State.OFF); ClientUtils.forceReleaseKeys(); ClientUtils.sendDebugMessage(debugReason); PestManager.reset(); PestOnTheTrackManager.getInstance().reset(client); PestExchangeManager.stop(); PestDestroyer.stop(client); PestTrapManager.cancel(client); AutoSellManager.cancel(client); AutoSprayonatorManager.cancel(); AutoSprayonatorManager.reset(); AutoPestExchangeManager.reset(); GreenhouseManager.reset(); ComposterManager.reset(); SupercraftManager.reset(); GearManager.reset(); GeorgeManager.reset(); BookCombineManager.reset(); JunkManager.reset(); RecoveryManager.reset(); RestartManager.reset()
            if (!AetherConfig.PERSIST_SESSION_TIMER.get()) { DynamicRestManager.reset(); ProfitManager.reset(); AutoCarnivalManager.resetTokenSession() }
            ReconnectScheduler.cancel(); PathfindingManager.stop(); VisitorsMacro.stop(client); RotationManager.cancelRotation(); ClientUtils.forceReleaseKeys()
        }
        private fun runOnClientThread(client: Minecraft?, action: Runnable?) {
            if (client == null || action == null) return
            if (client.isSameThread()) { action.run(); return }
            client.execute(action)
        }
    }
}
