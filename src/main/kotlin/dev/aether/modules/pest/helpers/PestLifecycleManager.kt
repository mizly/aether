package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pest.ManualPestManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.minecraft.client.Minecraft

internal object PestLifecycleManager {
    enum class Stage { IDLE, PRE, CLEANING, POST }
    @Volatile private var stage = Stage.IDLE
    @Volatile private var sunsetPestsRestoreNight = false
    private const val SETSPAWN_RETRY_MS = 1_000L
    const val SETSPAWN_MAX_ATTEMPTS = 2

    @JvmStatic fun getStage(): Stage = stage
    @JvmStatic fun reset() { stage = Stage.IDLE; sunsetPestsRestoreNight = false }

    @JvmStatic fun restorePendingSunsetPestsNight(client: Minecraft): Boolean {
        if (!sunsetPestsRestoreNight) return true
        sunsetPestsRestoreNight = false
        val switched = GardenTimeManager.switchToNightTime(client)
        if (!switched) ClientUtils.sendDebugMessage("Sunset Pests: failed to switch garden time to night.")
        return switched
    }

    @JvmStatic fun prepareSunsetPestsDaytime(client: Minecraft): Boolean {
        if (!AetherConfig.SUNSET_PESTS.get()) return true
        sunsetPestsRestoreNight = true
        val switched = GardenTimeManager.switchToDaytime(client)
        if (!switched) ClientUtils.sendDebugMessage("Sunset Pests: failed to switch garden time to day.")
        return switched
    }

    @JvmStatic fun start(client: Minecraft, plot: String, pestCount: Int, sessionId: Int): Boolean {
        if (stage != Stage.IDLE || PestManager.isCleaningInProgress() || LoadoutManager.isSwappingLoadout) {
            PestManager.clearCleaningTriggerPending(); return false
        }
        val manualMode = AetherConfig.MANUAL_PEST_MODE.get()
        stage = Stage.PRE
        ClientUtils.sendDebugMessage("Pest lifecycle: waiting for /setspawn while farming continues.")
        MacroWorkerThread.getInstance().submit("PestSetSpawn-$plot") {
            for (attempt in 0 until SETSPAWN_MAX_ATTEMPTS) {
                if (!isSetSpawnRetryPending(client, sessionId)) break
                if (CommandUtils.setSpawn(SETSPAWN_RETRY_MS)) { client.execute { beginPreStage(client, plot, pestCount, sessionId, manualMode) }; return@submit }
                if (attempt + 1 < SETSPAWN_MAX_ATTEMPTS) ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn not confirmed; farming and retrying once.")
            }
            if (isSetSpawnRetryPending(client, sessionId)) { ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn failed twice; continuing farming."); PestManager.startPestReentryCooldown() }
            cancelPendingStart(sessionId)
        }
        return true
    }

    private fun beginPreStage(client: Minecraft, plot: String, pestCount: Int, sessionId: Int, manualMode: Boolean) {
        if (!isSetSpawnRetryPending(client, sessionId)) { cancelPendingStart(sessionId); return }
        ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn confirmed; entering PRE stage for plot $plot.")
        FarmingMacroManager.disable(client); PestManager.setCleaningInProgress(true); PestManager.clearCleaningTriggerPending()
        LoadoutManager.shouldRestartFarmingAfterSwap = false; MacroStateManager.setCurrentState(MacroState.State.CLEANING)
        MacroWorkerThread.getInstance().submit("PestPre-$plot") {
            try {
                val preResult = PestPreStage.run(client, plot, pestCount, sessionId)
                if (!preResult.successful()) { abortPreStage(client, sessionId); return@submit }
                client.execute { startCleaningStage(client, plot, pestCount, sessionId, manualMode, preResult) }
            } catch (e: Exception) { e.printStackTrace(); abortPreStage(client, sessionId) }
        }
    }

    @JvmStatic fun isSetSpawnRetryPending(client: Minecraft, sessionId: Int): Boolean =
        shouldRetrySetSpawn(stage, sessionId, PestManager.getCurrentPestSessionId(), PestManager.isCleaningInProgress(), MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING))
    @JvmStatic fun shouldRetrySetSpawn(currentStage: Stage, sessionId: Int, currentSessionId: Int, cleaning: Boolean, abort: Boolean): Boolean =
        currentStage == Stage.PRE && sessionId == currentSessionId && !cleaning && !abort

    private fun cancelPendingStart(sessionId: Int) {
        if (stage == Stage.PRE && sessionId == PestManager.getCurrentPestSessionId() && !PestManager.isCleaningInProgress()) {
            stage = Stage.IDLE; PestManager.clearCleaningTriggerPending()
        }
    }

    @JvmStatic fun startPostStage(client: Minecraft) {
        if (stage == Stage.POST) return
        stage = Stage.POST; ClientUtils.sendDebugMessage("Pest lifecycle: entering POST stage."); PestReturnManager.handlePestCleaningFinished(client)
    }
    @JvmStatic fun completePostStage() { stage = Stage.IDLE }
    @JvmStatic fun releaseStuckPreStage() { if (stage == Stage.PRE) { ClientUtils.sendDebugMessage("Pest lifecycle: releasing stuck PRE stage."); stage = Stage.IDLE } }

    private fun startCleaningStage(client: Minecraft, plot: String, pestCount: Int, sessionId: Int, manualMode: Boolean, preResult: PestPreStage.Result) {
        if (stage != Stage.PRE || sessionId != PestManager.getCurrentPestSessionId() || !PestManager.isCleaningInProgress()) return
        val ballsackResult = preResult.ballsackResult()
        if (ballsackResult != null && ballsackResult.measured()) PestManager.decrementPredictedAliveCount(client, ballsackResult.estimatedKilled())
        if (ballsackResult != null && ballsackResult.measured() && shouldSkipCleaningAfterBallsack(client, plot, ballsackResult.estimatedRemaining())) {
            ClientUtils.sendDebugMessage("Ballsack Shredder: ${ballsackResult.estimatedRemaining()} pest(s) estimated remaining; skipping CLEANING and entering POST.")
            startPostStage(client); return
        }
        stage = Stage.CLEANING
        ClientUtils.sendDebugMessage("Pest lifecycle: entering CLEANING stage (${if (manualMode) "manual" else "automatic"}).")
        if (manualMode) {
            switchToVacuumForManualStart(client)
            if (!ManualPestManager.startCleaningStage(client, pestCount)) PestManager.handlePestCleaningFinished(client)
            return
        }
        ClientUtils.sendMessage("\u00A76Starting Pest Cleaner script ($plot)...", true)
        if (PestBonusManager.isBonusInactive()) { ClientUtils.sendMessage("\u00A7dBonus is INACTIVE! Triggering Phillip reactivation...", true); PestBonusManager.beginReactivation() }
        client.execute { PestDestroyer.start(client, plot) }
    }

    private fun switchToVacuumForManualStart(client: Minecraft) {
        if (!AetherConfig.VACCUM_WHEN_START.get()) return
        val vacuumSlot = PestLoadoutHelper.findVacuumHotbarSlot(client)
        if (vacuumSlot < 0) { ClientUtils.sendMessage("\u00A7cManual Pest Mode: no vacuum found in hotbar.", false); ClientUtils.sendDebugMessage("Manual Pest Mode: Switch to Vacuum When Start enabled, but no vacuum was found."); return }
        FailsafeManager.selectHotbarSlot(client, vacuumSlot)
        ClientUtils.sendDebugMessage("Manual Pest Mode: switched to vacuum slot ${vacuumSlot + 1}.")
    }

    @JvmStatic fun shouldSkipCleaningAfterBallsack(client: Minecraft, targetPlot: String, estimatedRemaining: Int): Boolean {
        val targetLeavesOnePest = AetherConfig.LEAVE_ONE_PEST_ALIVE.get() && AetherConfig.LEAVE_ONE_PEST_PLOTS.get().any { PestPlotId.normalize(it) == PestPlotId.normalize(targetPlot) }
        return shouldSkipCleaningAfterBallsack(estimatedRemaining, targetLeavesOnePest, PestDestroyer.shouldFinishForAliveCount(client, estimatedRemaining))
    }
    @JvmStatic fun shouldSkipCleaningAfterBallsack(estimatedRemaining: Int, targetLeavesOnePest: Boolean, rememberedLeavesCanFinish: Boolean): Boolean =
        estimatedRemaining == 0 || estimatedRemaining == 1 && (targetLeavesOnePest || rememberedLeavesCanFinish)

    private fun abortPreStage(client: Minecraft, sessionId: Int) {
        if (sessionId != PestManager.getCurrentPestSessionId() || stage != Stage.PRE) return
        ClientUtils.sendDebugMessage("Pest lifecycle: PRE stage aborted; returning through POST stage."); startPostStage(client)
    }
}
