package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.minecraft.client.Minecraft

internal class PestPreStage private constructor() {
    class Result(private val successfulValue: Boolean, private val ballsackResultValue: PestBallsackShredder.Result?) {
        fun successful(): Boolean = successfulValue
        fun ballsackResult(): PestBallsackShredder.Result? = ballsackResultValue
        companion object {
            @JvmStatic fun success(): Result = Result(true, null)
            @JvmStatic fun failure(): Result = Result(false, null)
        }
    }

    companion object {
        @JvmStatic fun run(client: Minecraft, plot: String, pestCount: Int, sessionId: Int): Result {
            if (shouldAbort(client, sessionId)) return Result.failure()
            if (AetherConfig.SUNSET_PESTS.get() && !PestLifecycleManager.prepareSunsetPestsDaytime(client)) return Result.failure()
            if (!swapToPestLoadout(client, sessionId)) return Result.failure()
            PestPrepSwapManager.clearCycleState()
            if (!moveToTargetPlot(client, plot, sessionId)) return Result.failure()
            if (PestManager.isBallsackShredderActiveForCurrentCycle()) {
                val result = PestBallsackShredder.run(client, sessionId, pestCount)
                return Result(result.successful(), result)
            }
            return if (moveToRoofIfNeeded(client, plot, sessionId)) Result.success() else Result.failure()
        }

        private fun moveToTargetPlot(client: Minecraft, plot: String, sessionId: Int): Boolean {
            if (!PestPlotId.isUsable(plot)) return !shouldAbort(client, sessionId)
            val currentPlot = ClientUtils.getCurrentPlot()
            val scoreboardMatch = currentPlot != null && currentPlot.equals(plot, ignoreCase = true)
            val freshChatPlot = CommandUtils.getFreshKnownPlotChat()
            val chatMatch = freshChatPlot != null && freshChatPlot.equals(plot, ignoreCase = true)
            var alreadyOnPlot = scoreboardMatch
            if (!alreadyOnPlot && (currentPlot == null || currentPlot.equals("Unknown", ignoreCase = true))) alreadyOnPlot = chatMatch
            val forcePlotTp = alreadyOnPlot && (AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get() || PestManager.isBallsackShredderActiveForCurrentCycle())
            if (alreadyOnPlot && !forcePlotTp) {
                val source = if (chatMatch) "chat" else "scoreboard"
                ClientUtils.sendDebugMessage("Already on plot $plot (via $source), skipping plottp.")
                return !shouldAbort(client, sessionId)
            }
            ClientUtils.sendDebugMessage((if (forcePlotTp) "Forcing" else "Running") + " plottp to $plot during pest PRE stage.")
            CommandUtils.plotTp(plot)
            MacroWorkerThread.sleep(200)
            return !shouldAbort(client, sessionId)
        }

        private fun moveToRoofIfNeeded(client: Minecraft, plot: String, sessionId: Int): Boolean {
            if (!PestAotvManager.shouldDoAotvOnCurrentPlot(client, plot, true) || !PestAotvManager.hasRoofAbove(client)) return !shouldAbort(client, sessionId)
            ClientUtils.sendDebugMessage("Pest PRE stage: using AOTV to reach the roof on plot $plot.")
            PestAotvManager.startPreparationAotv(client)
            while (PestAotvManager.isPreparationAotvActive()) {
                if (shouldAbort(client, sessionId)) { PestAotvManager.cancelPreparationAotv(client); return false }
                MacroWorkerThread.sleep(25)
            }
            if (PestAotvManager.consumePreparationPlotRecovery()) {
                ClientUtils.sendDebugMessage("Pest PRE stage: AOTV timed out; using plottp to recover.")
                CommandUtils.initiatePlotTp(plot)
                MacroWorkerThread.sleep(2500)
            } else PestAotvManager.rotateDownAfterAotv(client, PestManager.isBallsackShredderActiveForCurrentCycle())
            return !shouldAbort(client, sessionId)
        }

        private fun swapToPestLoadout(client: Minecraft, sessionId: Int): Boolean {
            val targetSlot = AetherConfig.LOADOUT_SLOT_PEST_KILL.get()
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || targetSlot <= 0 || LoadoutManager.trackedLoadoutSlot == targetSlot) return !shouldAbort(client, sessionId)
            ClientUtils.sendMessage("\u00A7eSwapping to pest kill loadout (slot $targetSlot)...", true)
            client.execute(Runnable { GearManager.ensureLoadoutSlot(client, targetSlot) })
            val startWait = System.currentTimeMillis()
            while (!LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - startWait < 2000) {
                if (shouldAbort(client, sessionId)) return false
                MacroWorkerThread.sleep(25)
            }
            ClientUtils.waitForWardrobeGui()
            val finishWait = System.currentTimeMillis()
            while ((LoadoutManager.isSwappingLoadout || !LoadoutManager.loadoutGuiCloseComplete) && System.currentTimeMillis() - finishWait < 7000) MacroWorkerThread.sleep(50)
            if (LoadoutManager.isSwappingLoadout || !LoadoutManager.loadoutGuiCloseComplete) {
                ClientUtils.sendDebugMessage("Loadout swap timed out in pest PRE stage; triggering completion failsafe.")
                LoadoutManager.forceLoadoutCompletionFailsafe(client)
                val failsafeWait = System.currentTimeMillis()
                while (!LoadoutManager.loadoutGuiCloseComplete && System.currentTimeMillis() - failsafeWait < 2000) MacroWorkerThread.sleep(25)
            }
            while (LoadoutManager.loadoutCleanupTicks > 0) MacroWorkerThread.sleep(50)
            MacroWorkerThread.sleep(250)
            return !shouldAbort(client, sessionId)
        }

        private fun shouldAbort(client: Minecraft, sessionId: Int): Boolean = MacroWorkerThread.shouldAbortTask(client) || sessionId != PestManager.getCurrentPestSessionId() || !PestManager.isCleaningInProgress()
    }
}
