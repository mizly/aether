package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.config.UnflyMode
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.GreenhouseManager
import dev.aether.modules.ComposterManager
import dev.aether.modules.SupercraftManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.visitor.VisitorManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.visitor.VisitorsMacro
import net.minecraft.client.Minecraft

class PestReturnManager private constructor() {
    companion object {
        @Volatile private var isReturningFromPestVisitor = false
        @Volatile private var isReturnToLocationActive = false
        @Volatile private var isStoppingFlight = false
        @Volatile private var isFinishingInProgress = false
        @Volatile private var finishingStartedAtMs = 0L
        private const val FINISHING_STALL_TIMEOUT_MS = 60_000L
        @Volatile private var finishingStage = "idle"
        private var flightStopStage = 0
        private var flightStopTicks = 0

        @JvmStatic fun isReturningFromPestVisitor(): Boolean = isReturningFromPestVisitor
        @JvmStatic fun setReturningFromPestVisitor(returning: Boolean) { isReturningFromPestVisitor = returning }
        @JvmStatic fun isReturnToLocationActive(): Boolean = isReturnToLocationActive
        @JvmStatic fun setReturnToLocationActive(active: Boolean) { isReturnToLocationActive = active }
        @JvmStatic fun isFinishingInProgress(): Boolean = isFinishingInProgress

        @JvmStatic fun updateFlightStop(client: Minecraft?) {
            if (!isStoppingFlight || client == null || client.options == null) return
            flightStopTicks++
            val jumpDown = flightStopStage == 0 || flightStopStage == 2
            if (client.options.keyJump != null) ClientUtils.setKeyMappingState(client.options.keyJump, jumpDown)
            val stageTicks = when (flightStopStage) { 0, 2 -> 2; 1 -> 3; else -> 0 }
            if (flightStopStage >= 3) isStoppingFlight = false
            else if (flightStopTicks >= stageTicks) { flightStopStage++; flightStopTicks = 0 }
        }

        @JvmStatic fun resetState() {
            isReturningFromPestVisitor = false; isReturnToLocationActive = false; isStoppingFlight = false
            isFinishingInProgress = false; finishingStartedAtMs = 0L; finishingStage = "idle"
            flightStopStage = 0; flightStopTicks = 0
        }

        @Synchronized private fun tryBeginFinishingSequence(): Boolean {
            if (isFinishingInProgress) return false
            isFinishingInProgress = true; finishingStartedAtMs = System.currentTimeMillis(); finishingStage = "starting"
            return true
        }
        private fun setFinishingStage(stage: String) { finishingStage = stage }
        private fun clearCleaningFlags() {
            PestPrepSwapManager.clearCycleState(); PestManager.setCleaningInProgress(false)
            isReturningFromPestVisitor = false; isReturnToLocationActive = false
        }
        @JvmStatic fun tickFinishingWatchdog() {
            if (!isFinishingInProgress || finishingStartedAtMs == 0L || System.currentTimeMillis() - finishingStartedAtMs < FINISHING_STALL_TIMEOUT_MS || MacroWorkerThread.getInstance().currentTaskName.startsWith("PestFinish")) return
            ClientUtils.sendDebugMessage("Finisher watchdog: stuck at stage $finishingStage for ${FINISHING_STALL_TIMEOUT_MS}ms with no finisher task running; releasing it.")
            clearCleaningFlags(); releaseFinishingSequence()
        }
        private fun releaseFinishingSequence() {
            isFinishingInProgress = false; finishingStartedAtMs = 0L; finishingStage = "idle"
            PestLifecycleManager.completePostStage()
        }
        private fun abortFinisherIfNeeded(client: Minecraft, stage: String): Boolean {
            if (!MacroWorkerThread.shouldAbortTask(client)) return false
            clearCleaningFlags(); releaseFinishingSequence()
            ClientUtils.sendDebugMessage("Finisher aborted at $stage. Cleared cleaning flags.")
            return true
        }
        private fun recoverToFarming(client: Minecraft, stage: String, e: Exception?) {
            if (e != null) { e.printStackTrace(); ClientUtils.sendDebugMessage("CRITICAL ERROR in $stage: ${e.message}") }
            clearCleaningFlags()
            if (MacroStateManager.isMacroRunning() && client.player != null) {
                ClientUtils.sendDebugMessage("Triggering failsafe: Returning to farming...")
                MacroStateManager.setCurrentState(MacroState.State.FARMING)
                ClientUtils.sendDebugMessage("Failsafe: Warping to garden...")
                CommandUtils.warpGarden(); MacroWorkerThread.sleep(250); SqueakyMousematManager.armReapplyAttempt()
                client.execute(Runnable { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) })
            }
            releaseFinishingSequence()
        }
        private fun handOffToVisitors(client: Minecraft, stage: String) {
            clearCleaningFlags()
            if (MacroStateManager.isMacroRunning()) MacroStateManager.setCurrentState(MacroState.State.VISITING)
            releaseFinishingSequence(); ClientUtils.sendDebugMessage("$stage: Handing off from pest cleaning to visitors.")
            client.execute(Runnable { VisitorsMacro.start(client) })
        }

        @JvmStatic fun handlePestCleaningFinished(client: Minecraft) {
            if (!tryBeginFinishingSequence()) {
                val ageMs = if (finishingStartedAtMs <= 0L) 0L else System.currentTimeMillis() - finishingStartedAtMs
                ClientUtils.sendDebugMessage("Pest cleaning finish already in progress at stage $finishingStage (${ageMs}ms), ignoring duplicate trigger.")
                return
            }
            setFinishingStage("starting"); ClientUtils.sendDebugMessage("Pest cleaning finished sequence started.")
            ClientUtils.sendMessage("Pest cleaning finished detected.", true)
            MacroWorkerThread.getInstance().submitCritical("PestFinish-Initial", Runnable {
                try {
                    setFinishingStage("initial checks"); if (abortFinisherIfNeeded(client, "initial finish")) return@Runnable
                    setFinishingStage("unfly"); ClientUtils.sendDebugMessage("Finisher: Stopping flight before post-actions...")
                    performUnfly(client); if (abortFinisherIfNeeded(client, "unfly")) return@Runnable
                    if (PestTrapManager.isBlockedByPestExchange()) ClientUtils.sendDebugMessage("Finisher: skipping trap clear/refill while pest exchange is active.")
                    else if (PestManager.arePestTrapsEnabled() && AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() && !PestTrapManager.getFullTrapsFromTab(client).isEmpty()) {
                        setFinishingStage("clear traps"); ClientUtils.sendDebugMessage("Finisher: Clearing full pest traps...")
                        PestTrapManager.runBlocking(client, AetherConfig.PEST_TRAPS_PLOT.get(), PestTrapManager.Operation.CLEAR)
                        PathfindingManager.stop(); if (abortFinisherIfNeeded(client, "trap clear")) return@Runnable
                    }
                    if (!PestTrapManager.isBlockedByPestExchange() && PestManager.arePestTrapsEnabled() && AetherConfig.AUTO_REFILL_PEST_TRAPS.get() && !PestTrapManager.getNoBaitTrapsFromTab(client).isEmpty()) {
                        setFinishingStage("refill traps"); ClientUtils.sendDebugMessage("Finisher: Refilling empty pest traps...")
                        PestTrapManager.runBlocking(client, AetherConfig.PEST_TRAPS_PLOT.get(), PestTrapManager.Operation.REFILL)
                        PathfindingManager.stop(); if (abortFinisherIfNeeded(client, "trap refill")) return@Runnable
                    }
                    MacroWorkerThread.sleep(200); if (abortFinisherIfNeeded(client, "pre-greenhouse")) return@Runnable
                    setFinishingStage("greenhouse handoff")
                    GreenhouseManager.runAutoGreenhouseIfDue(Runnable {
                        setFinishingStage("composter handoff")
                        ComposterManager.runAutoComposterIfDue(Runnable {
                            setFinishingStage("supercraft handoff")
                            SupercraftManager.runAutoSupercraftIfDue(Runnable { continueAfterCleaningIntermediaries(client) })
                        })
                    })
                } catch (e: Exception) { recoverToFarming(client, "handlePestCleaningFinished", e) }
            })
        }

        @JvmStatic @Throws(InterruptedException::class) fun performUnfly(client: Minecraft) {
            val player = client.player ?: return
            if (!player.abilities.flying) { ClientUtils.sendDebugMessage("Finisher: player is already grounded; skipping unfly."); return }
            if (ConfigHelpers.getUnflyMode() == UnflyMode.DOUBLE_TAP_SPACE) {
                isStoppingFlight = true; flightStopStage = 0; flightStopTicks = 0
                val deadline = System.currentTimeMillis() + 3000
                while (isStoppingFlight && System.currentTimeMillis() < deadline) Thread.sleep(50)
            } else {
                client.execute(Runnable { if (client.options != null) ClientUtils.setKeyMappingState(client.options.keyShift, true) })
                Thread.sleep(150)
                client.execute(Runnable { if (client.options != null) ClientUtils.setKeyMappingState(client.options.keyShift, false) })
            }
        }

        private fun continueAfterCleaningIntermediaries(client: Minecraft) {
            MacroWorkerThread.getInstance().submit("PestFinish-Post", Runnable {
                try {
                    setFinishingStage("post intermediaries"); if (abortFinisherIfNeeded(client, "post-intermediaries start")) return@Runnable
                    val visitors = VisitorManager.getVisitorCount(client)
                    ClientUtils.sendDebugMessage("Finisher: Visitor count check: $visitors (Threshold: ${AetherConfig.VISITOR_THRESHOLD.get()})")
                    if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) ClientUtils.sendDebugMessage("Finisher: Visitor threshold met, but Jacob's Contest window is active. Returning to farm.")
                    else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && !VisitorManager.isVisitorReentryCooldownActive(client, true)) { handOffToVisitors(client, "Finisher"); return@Runnable }
                    if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) ClientUtils.sendDebugMessage("Finisher: Visitor threshold met, but cooldown is active. Returning to farm.")
                    MacroWorkerThread.sleep(150); if (abortFinisherIfNeeded(client, "pre-return warp")) return@Runnable
                    setFinishingStage("warp garden"); ClientUtils.sendDebugMessage("Finisher: Warping to garden (Return to Farm)...")
                    CommandUtils.warpGarden(); MacroWorkerThread.sleep(250); if (abortFinisherIfNeeded(client, "post-return warp")) return@Runnable
                    setFinishingStage("restore sunset pests night")
                    if (!AetherConfig.SUNSET_PESTS_NIGHT_BEFORE_SPAWN.get()) PestLifecycleManager.restorePendingSunsetPestsNight(client)
                    if (abortFinisherIfNeeded(client, "restore sunset pests night")) return@Runnable
                    isReturningFromPestVisitor = true; setFinishingStage("finalize return")
                    ClientUtils.sendDebugMessage("Finisher: Calling finalizeReturnToFarm..."); finalizeReturnToFarm(client)
                } catch (e: Exception) { recoverToFarming(client, "continueAfterCleaningIntermediaries", e) }
            })
        }

        private fun finalizeReturnToFarm(client: Minecraft) {
            if (!MacroStateManager.isMacroRunning()) { clearCleaningFlags(); releaseFinishingSequence(); return }
            var releaseFinishingOnExit = true
            try {
                setFinishingStage("finalize"); ClientUtils.sendDebugMessage("Finalize: Starting return sequence.")
                val visitors = VisitorManager.getVisitorCount(client); ClientUtils.sendDebugMessage("Finalize: Visitor count check: $visitors")
                if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) ClientUtils.sendDebugMessage("Finalize: Visitor threshold met, but Jacob's Contest window is active. Continuing farming.")
                else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && !VisitorManager.isVisitorReentryCooldownActive(client, true)) { handOffToVisitors(client, "Finalize"); return }
                if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) ClientUtils.sendDebugMessage("Finalize: Visitor threshold met, but cooldown is active. Continuing farming.")
                setFinishingStage("swap farming tool"); if (!restoreFarmingLoadout(client)) return
                setFinishingStage("wait for menu close"); ClientUtils.sendDebugMessage("Finalize: Waiting for menus to close before farming resume...")
                if (!FarmingMacroManager.waitForFarmingResumeReady(client, 10_000L)) {
                    ClientUtils.sendDebugMessage("Finalize: Farming resume delayed because a GUI/container is still open. Retrying...")
                    releaseFinishingOnExit = false
                    MacroWorkerThread.getInstance().submit("PestFinalize-RetryAfterMenuClose", Runnable { MacroWorkerThread.sleep(500); finalizeReturnToFarm(client) })
                    return
                }
                ClientUtils.sendDebugMessage("Finalize: Swapping to farming tool..."); GearManager.swapToFarmingToolSync(client); ClientUtils.sendDebugMessage("Finalize: Tool swap done.")
                setFinishingStage("resume farming"); ClientUtils.sendDebugMessage("Pest cleaning sequence completed. Next state: FARMING")
                MacroStateManager.setCurrentState(MacroState.State.FARMING); clearCleaningFlags(); PestManager.startPestReentryCooldown()
                if (client.player != null) ClientUtils.sendDebugMessage("Pest cleaner finished.")
                if (AutoPestExchangeManager.tryTriggerPending(client)) { ClientUtils.sendDebugMessage("Pest cleaning handoff: starting queued pest exchange before farming resume."); return }
                ClientUtils.sendDebugMessage("Pest cleaning sequence finished. Restarting farming..."); ClientUtils.sendDebugMessage("Starting farming macro")
                SqueakyMousematManager.armReapplyAttempt()
                client.execute(Runnable { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) })
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                recoverToFarming(client, "finalizeReturnToFarm", e); return
            } finally { if (releaseFinishingOnExit) releaseFinishingSequence() }
        }

        @JvmStatic @Throws(InterruptedException::class) fun restoreFarmingLoadout(client: Minecraft): Boolean {
            val targetSlot = AetherConfig.LOADOUT_SLOT_FARMING.get()
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || targetSlot <= 0 || LoadoutManager.trackedLoadoutSlot == targetSlot) return true
            ClientUtils.sendMessage("\u00A7eRestoring farming loadout (slot $targetSlot)...", true)
            GearManager.ensureLoadoutSlot(client, targetSlot)
            if (LoadoutManager.isSwappingLoadout) {
                ClientUtils.sendDebugMessage("Pest finalize: Waiting for loadout GUI..."); ClientUtils.waitForWardrobeGui()
                ClientUtils.sendDebugMessage("Pest finalize: Loadout GUI detected, waiting for swap to complete...")
                while (LoadoutManager.isSwappingLoadout) MacroWorkerThread.sleep(50)
                while (LoadoutManager.loadoutCleanupTicks > 0) MacroWorkerThread.sleep(50)
                MacroWorkerThread.sleep(350); ClientUtils.sendDebugMessage("Pest finalize: Farming loadout restore complete.")
            }
            return !abortFinisherIfNeeded(client, "restore farming loadout")
        }
    }
}
