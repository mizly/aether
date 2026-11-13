package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashSet
import java.util.Set as JavaSet

// flies to pest entities with PathfindingManager, then aims and fires the vacuum
// started by PestLifecycleManager after the shared PRE stage; hands back to PestManager.handlePestCleaningFinished when everything is dead or stuck
class PestDestroyer private constructor() {

    // -- State machine --------------------------------------------------------
    enum class State {
        IDLE,
        TELEPORT_TO_PLOT,
        EQUIP_VACUUM,
        FLY_UP,
        FLY_TO_PEST,
        APPROACH_PEST,
        KILL_PEST,
        HUNT_PEST,
        CHECK_NEXT,
        GET_LOCATION,
        FLY_TO_WAYPOINT,
        AOTV_BETWEEN_PESTS,
        AOTV_TO_ROOF,
        AOTV_TO_ROOF_RETURN,
        AOTV_POST_LOOKDOWN,
        BALLSACK_SHREDDER,
        FINISH
    }

    companion object {
        private val runtime = PestDestroyerRuntime()
        private val CONTEXT: PestDestroyerCoordinatorContext = PestDestroyerCoordinatorContext(runtime)

        private const val STATE_TIMEOUT_MS = 30_000L
        private const val STUCK_TIMEOUT_MS = (5 * 60 * 1000).toLong()
        private const val TARGET_REACH_DISTANCE = 12.0
        private const val PATHFINDER_STUCK_RETRY_TICKS = 20
        private const val APPROACH_TIMEOUT_TICKS = 120
        private const val MAX_PLOT_SWEEPS = 2
        private const val MAX_SCAN_WAYPOINTS = 10
        private const val PLOT_TP_WAIT_MS = 2500L
        private const val SKULL_MISSING_CONFIRM_TICKS = 3
        private const val ROOF_RESCAN_INTERVAL_MS = 1000L

        // -- Public API -----------------------------------------------------------

        @JvmStatic
        fun isActive(): Boolean {
            return runtime.active
        }

        @JvmStatic
        fun getState(): State {
            return runtime.state
        }

        @JvmStatic
        fun getPlannedPestRoute(client: Minecraft?): List<Entity> {
            if (!runtime.active || client == null || client.player == null) {
                return java.util.List.of()
            }
            return PestTargetController.buildPlannedRoute(client, runtime)
        }

        @JvmStatic
        fun start(client: Minecraft) {
            start(client, null)
        }

        @JvmStatic
        fun start(client: Minecraft, initialPlot: String?) {
            if (runtime.active) return
            PestTrackerAbility.clear()
            val vacuumSlots = PestLoadoutHelper.findAutomaticVacuumSlots(client)
            runtime.beginRun(
                vacuumSlots[1],
                System.currentTimeMillis()
            )
            runtime.stunVacuumSlot = vacuumSlots[0]
            runtime.killVacuumSlot = vacuumSlots[1]
            enforceWalkModeGrounded(client)

            // Build plot queue from tab list (always fresh read)
            runtime.navigation.plotQueue.clear()
            val infested: MutableSet<String> = java.util.LinkedHashSet<String>()
            infested.addAll(PestManager.getInfestedPlotsFromTab(client))
            if (infested.isEmpty()) {
                // Fallback to cached value
                infested.addAll(PestManager.getCurrentInfestedPlots())
            }
            infested.retainAll(PestLeaveOneController.filterSkippedPlots(runtime, infested.toSet()))

            if (initialPlot != null && PestPlotId.isUsable(initialPlot)) {
                runtime.navigation.plotQueue.add(initialPlot)
                runtime.navigation.lastTargetPlot = initialPlot
                // The PRE stage has just run /plottp here and climbed onto the roof. Asking the
                // (lagging) sidebar would send us back down to the plot spawn and strand us in a loop.
                if (CommandUtils.isFreshKnownPlotChat(initialPlot)) {
                    runtime.navigation.trustPlot(initialPlot, System.currentTimeMillis())
                }
            }

            if (!infested.isEmpty()) {
                for (p in infested) {
                    if (!containsPlot(runtime.navigation.plotQueue, p)) {
                        runtime.navigation.plotQueue.add(p)
                    }
                }
                // Ensure PestManager's cache reflects the ordered list
                PestManager.setCurrentInfestedPlots(runtime.navigation.plotQueue.toCollection(LinkedHashSet<String>()) as JavaSet<String>)
            }
            runtime.navigation.currentPlotIdx = 0

            ClientUtils.sendDebugMessage("[PestDestroyer] Started in-client pest killer. Plots: " + runtime.navigation.plotQueue)
            ClientUtils.sendMessage("\u00A7ePest destroyer active. Hunting pests...", false)

            beginInitialPestState(client)
        }

        private fun beginInitialPestState(client: Minecraft) {
            // Check if we need to TP to an infested plot
            if (runtime.navigation.plotQueue.isNotEmpty()) {
                val currentPlot: String? = getEffectivePlot(client)
                val firstPlot: String = runtime.navigation.plotQueue[0]
                // A fresh chat confirmation means the PRE stage already did the forced /plottp;
                // doing it again here would drop us off the roof it just climbed.
                val forceCurrentPlotTeleport = plotsEqual(firstPlot, currentPlot) &&
                    AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get() &&
                    !CommandUtils.isFreshKnownPlotChat(firstPlot)
                if (forceCurrentPlotTeleport) {
                    runtime.state = State.TELEPORT_TO_PLOT
                    return
                }
                val onFirstPlot = plotsEqual(firstPlot, currentPlot)
                if (!onFirstPlot) {
                    runtime.state = State.TELEPORT_TO_PLOT
                    return
                } else {
                    // Already on the selected first plot - check if we need to AOTV to roof.
                    if (PestAotvManager.shouldDoAotvOnCurrentPlot(client, currentPlot, true)) {
                        ClientUtils.sendDebugMessage("[PestDestroyer] Already on plot " + currentPlot + ", but AOTV to roof needed.")
                        runtime.state = State.AOTV_TO_ROOF
                        PestAotvManager.setSneakingForAotv(true)
                        MacroWorkerThread.getInstance().submit("PestAotv-Roof-Start-" + currentPlot) {
                            try {
                                PestAotvManager.performAotvToRoof(client)
                            } catch (_: InterruptedException) {
                            }
                        }
                        return
                    }
                }
            }
            runtime.state = State.EQUIP_VACUUM
        }

        @JvmStatic
        fun stop(client: Minecraft?) {
            if (!runtime.active) return
            PestHuntingController.clearHunt(client, runtime)
            runtime.navigation.trackerSearch.stopLooking()
            PestTrackerAbility.clear()
            runtime.stopRun()
            PathfindingManager.stop()
            PestAotvManager.resetState()
            PestAimTracker.reset()
            RotationManager.cancelRotation()
            if (client != null && client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
                ClientUtils.setKeyMappingState(client.options.keyAttack, false)
                ClientUtils.setKeyMappingState(client.options.keyShift, false)
                ClientUtils.setKeyMappingState(client.options.keyJump, false)
                ClientUtils.setKeyMappingState(client.options.keySprint, false)
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] Stopped.")
        }

        @JvmStatic
        fun reset() {
            runtime.navigation.trackerSearch.stopLooking()
            PestTrackerAbility.clear()
            PestLeaveOneController.clearRememberedPlots(runtime)
            runtime.resetAll()
        }

        @JvmStatic
        fun update() {
            val client = Minecraft.getInstance()
            if (!runtime.active || client.player == null || client.level == null) return

            enforceWalkModeGrounded(client)
            updateWalkModeSprint(client)

            val killSlot = if (runtime.killVacuumSlot >= 0) runtime.killVacuumSlot else runtime.vacuumSlot
            if (killSlot >= 0) runtime.vacuumRange = PestLoadoutHelper.detectVacuumRange(client, killSlot)

            if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
                RotationManager.cancelRotation()
            }

            if (ClientUtils.isInventoryScreenOpen()) {
                runtime.navigation.trackerSearch.stopLooking()
                ClientUtils.forceReleaseMovementKeys()
                return
            }

            if (PestRoofAotvController.tick(client, runtime)) {
                return
            }

            // While AOTV sneak is held (rotation + swap phase), pause tick processing
            if (PestAotvManager.isSneakingForAotv()) {
                return
            }

            if (tryStartPeriodicRoofAotv(client)) {
                return
            }

            if (PestTargetController.reconcileTrackedKills(
                    client, runtime, CONTEXT
                )
            ) {
                return
            }

            if (PestDestroyerProgressController.tick(
                    client, runtime, STUCK_TIMEOUT_MS, CONTEXT
                )
            ) {
                return
            }

            // Turbo: process up to 3 state transitions in a single tick if they are fast
            for (i in 0 until 3) {
                val prevState = runtime.state
                processState(client)
                // Break if state didn't change or we entered a "long" or delicate state
                if (!runtime.active || runtime.state == prevState || runtime.state == State.IDLE || runtime.state == State.KILL_PEST ||
                    runtime.state == State.HUNT_PEST ||
                    runtime.state == State.FLY_TO_PEST || runtime.state == State.FLY_TO_WAYPOINT ||
                    runtime.state == State.GET_LOCATION || runtime.state == State.AOTV_BETWEEN_PESTS ||
                    runtime.state == State.TELEPORT_TO_PLOT ||
                    runtime.state == State.AOTV_TO_ROOF ||
                    runtime.state == State.AOTV_TO_ROOF_RETURN ||
                    runtime.state == State.AOTV_POST_LOOKDOWN ||
                    runtime.state == State.BALLSACK_SHREDDER ||
                    runtime.state == State.FLY_UP
                ) {
                    break
                }
            }

            PestDestroyerInputController.update(
                client,
                runtime,
                PestDestroyerInputController.isVacuumTemporarilyReleased(runtime)
            )

            PestDestroyerInputController.updateVacuumRetryPulse(client, runtime)
            updateWalkModeSprint(client)
            if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
                PestCombatCoordinator.updateEtherwarpAltitudeHold(client, runtime)
            }
        }

        private fun updateWalkModeSprint(client: Minecraft?) {
            if (client == null || client.player == null || client.options == null) {
                return
            }

            val sprint = AetherConfig.PEST_DESTROYER_WALK_MODE.get() &&
                PathfindingManager.isWalkingNavigation() &&
                !runtime.pestEtherwarpActive
            ClientUtils.setKeyMappingState(client.options.keySprint, sprint)
        }

        private fun enforceWalkModeGrounded(client: Minecraft?) {
            if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() ||
                client == null || client.player == null
            ) {
                return
            }
            val player = client.player ?: return

            val stoppedFlyNavigation = PathfindingManager.isFlyingNavigation()
            if (stoppedFlyNavigation) {
                PathfindingManager.stop()
            }
            if (runtime.flyTapTicks != 0) {
                PestFlightTapper.release(client)
                runtime.flyTapTicks = 0
            }
            PestCombatCoordinator.updateEtherwarpAltitudeHold(client, runtime)
            val wasFlying = player.abilities.flying
            if (wasFlying) {
                player.abilities.flying = false
                player.onUpdateAbilities()
            }
            if ((stoppedFlyNavigation || wasFlying) && client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false)
                ClientUtils.setKeyMappingState(client.options.keyShift, false)
                player.setShiftKeyDown(false)
            }

            if (runtime.state == State.FLY_UP) {
                if (client.options != null) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, false)
                    ClientUtils.setKeyMappingState(client.options.keyJump, false)
                    ClientUtils.setKeyMappingState(client.options.keyShift, false)
                    player.setShiftKeyDown(false)
                }
                PestAotvManager.resetState()
                setState(if (runtime.vacuumSlot < 0) State.EQUIP_VACUUM else State.CHECK_NEXT)
            }
        }

        private fun processState(client: Minecraft) {
            when (runtime.state) {
                State.TELEPORT_TO_PLOT -> handleTeleportToPlot(client)
                State.EQUIP_VACUUM -> handleEquipVacuum(client)
                State.FLY_UP -> handleFlyUp(client)
                State.FLY_TO_PEST -> handleFlyToPest(client)
                State.APPROACH_PEST -> handleApproachPest(client)
                State.KILL_PEST -> handleKillPest(client)
                State.HUNT_PEST -> PestHuntingController.handleHuntPest(client, CONTEXT)
                State.CHECK_NEXT -> handleCheckNext(client)
                State.GET_LOCATION -> handleGetLocation(client)
                State.FLY_TO_WAYPOINT -> handleFlyToWaypoint(client)
                State.AOTV_BETWEEN_PESTS -> handleAotvBetweenPests(client)
                State.AOTV_TO_ROOF -> {
                } // Handled by worker thread
                State.AOTV_TO_ROOF_RETURN -> {
                } // Handled early in update()
                State.AOTV_POST_LOOKDOWN -> handlePostAotvLookdown(client)
                State.BALLSACK_SHREDDER -> {
                } // Handled by its dedicated worker task.
                State.FINISH -> finish(client)
                else -> {
                }
            }
        }

        private fun tryStartPeriodicRoofAotv(client: Minecraft): Boolean {
            if (!AetherConfig.AOTV_TO_ROOF.get() || !isPeriodicRoofRescanState(runtime.state)) {
                return false
            }

            val now = System.currentTimeMillis()
            if (now - runtime.lastRoofRescanAt < ROOF_RESCAN_INTERVAL_MS) {
                return false
            }
            runtime.lastRoofRescanAt = now

            val currentPlot = getEffectivePlot(client)
            if (!shouldAotvToRoofOnPlot(currentPlot) || !PestAotvManager.hasRoofAbove(client)) {
                return false
            }

            val returnState = runtime.state
            PathfindingManager.stop(false)
            val options = client.options
            ClientUtils.setKeyMappingState(options.keyUse, false)
            ClientUtils.setKeyMappingState(options.keyAttack, false)
            ClientUtils.setKeyMappingState(options.keyUp, false)
            ClientUtils.setKeyMappingState(options.keyDown, false)
            ClientUtils.sendDebugMessage("PestDestroyer: roof detected during cleaning. Pausing navigation for roof AOTV.")
            startRoofAotv(client, currentPlot, returnState, "PestAotv-Roof-Periodic-" + currentPlot)
            return true
        }

        private fun shouldAotvToRoofOnPlot(plot: String?): Boolean {
            if (AetherConfig.AOTV_ROOF_PLOTS.get().isEmpty()) {
                return true
            }
            return plot != null && AetherConfig.AOTV_ROOF_PLOTS.get().contains(plot)
        }

        private fun isPeriodicRoofRescanState(state: State): Boolean {
            return state == State.CHECK_NEXT ||
                state == State.GET_LOCATION ||
                state == State.FLY_TO_WAYPOINT ||
                state == State.FLY_TO_PEST ||
                state == State.APPROACH_PEST
        }

        private fun startRoofAotv(client: Minecraft, plot: String?, returnState: State, taskName: String) {
            runtime.roofAotvReturnState = returnState
            runtime.aotvStartY = Double.NaN
            runtime.pestEtherwarpMaintainHeight = false
            runtime.pestEtherwarpJumpHeld = false
            ClientUtils.setKeyMappingState(client.options.keyJump, false)
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
            setState(State.AOTV_TO_ROOF)
            PestAotvManager.setSneakingForAotv(true)
            MacroWorkerThread.getInstance().submit(taskName) {
                try {
                    PestAotvManager.performAotvToRoof(client)
                } catch (_: InterruptedException) {
                }
            }
        }

        @JvmStatic
        fun completeRoofAotv() {
            val returnState = runtime.roofAotvReturnState
            runtime.roofAotvReturnState = null
            if (!runtime.active) {
                return
            }
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
                PestAotvManager.resetState()
                setState(if (runtime.vacuumSlot < 0) State.EQUIP_VACUUM else State.CHECK_NEXT)
                return
            }
            if (runtime.state == State.AOTV_POST_LOOKDOWN) {
                runtime.roofAotvReturnState = null
                setState(State.FLY_UP)
                return
            }
            if (returnState == null || returnState == State.IDLE || returnState == State.FINISH ||
                returnState == State.AOTV_TO_ROOF
            ) {
                setState(if (runtime.vacuumSlot < 0) State.EQUIP_VACUUM else State.CHECK_NEXT)
                return
            }

            setState(returnState)
            val client = Minecraft.getInstance()
            if (client.player == null) return
            val currentTarget = runtime.currentTarget
            val waypoint = runtime.navigation.calculatedWaypoint
            if ((returnState == State.FLY_TO_PEST || returnState == State.APPROACH_PEST) && currentTarget != null) {
                PestTargetController.startPathToPest(client, currentTarget)
            } else if (returnState == State.FLY_TO_WAYPOINT && waypoint != null) {
                PathfindingManager.startPathfind(
                    client, waypoint.x.toInt(), waypoint.y.toInt(), waypoint.z.toInt(),
                    !AetherConfig.PEST_DESTROYER_WALK_MODE.get()
                )
            }
        }

        private fun handleEquipVacuum(client: Minecraft) {
            PestEquipmentController.equipVacuum(client, runtime, CONTEXT)
        }

        private fun handleFlyUp(client: Minecraft) {
            PestEquipmentController.enterFlight(client, runtime, CONTEXT)
        }

        private fun handleFlyToPest(client: Minecraft) {
            PestCombatCoordinator.handleFlyToPest(
                client, CONTEXT, TARGET_REACH_DISTANCE, PATHFINDER_STUCK_RETRY_TICKS, STATE_TIMEOUT_MS
            )
        }

        private fun handleApproachPest(client: Minecraft) {
            PestCombatCoordinator.handleApproachPest(client, CONTEXT, TARGET_REACH_DISTANCE, APPROACH_TIMEOUT_TICKS)
        }

        private fun handleKillPest(client: Minecraft) {
            PestCombatCoordinator.handleKillPest(client, CONTEXT, SKULL_MISSING_CONFIRM_TICKS, STATE_TIMEOUT_MS)
        }

        private fun handleCheckNext(client: Minecraft) {
            PestHuntController.checkNext(client, runtime, CONTEXT, CONTEXT)
        }

        private fun handleTeleportToPlot(client: Minecraft) {
            PestNavigationCoordinator.handleTeleportToPlot(client, runtime.navigation, CONTEXT, PLOT_TP_WAIT_MS)
        }

        private fun handleGetLocation(client: Minecraft) {
            PestNavigationCoordinator.handleGetLocation(
                client, runtime.navigation, CONTEXT, MAX_PLOT_SWEEPS, MAX_SCAN_WAYPOINTS
            )
        }

        private fun handleFlyToWaypoint(client: Minecraft) {
            PestNavigationCoordinator.handleFlyToWaypoint(
                client, runtime.navigation, CONTEXT, PATHFINDER_STUCK_RETRY_TICKS, STATE_TIMEOUT_MS
            )
        }

        private fun handleAotvBetweenPests(client: Minecraft) {
            PestCombatCoordinator.handleAotvBetweenPests(
                client, CONTEXT, PestTargetController.AOTV_RANGE, PestTargetController.AOTV_GAP_MULTIPLIER, STATE_TIMEOUT_MS
            )
        }

        private fun handlePostAotvLookdown(client: Minecraft) {
            if (RotationManager.isRotating()) return
            if (runtime.vacuumSlot < 0) {
                setState(State.EQUIP_VACUUM)
                return
            }
            val selected = (client.player!!.inventory as dev.aether.mixin.AccessorInventory).getSelected()
            if (selected != runtime.vacuumSlot) {
                client.execute { FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot) }
                return
            }
            ClientUtils.setKeyMappingState(client.options.keyUse, true)
            if (System.currentTimeMillis() - runtime.stateEnteredAt >= AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.get()) {
                completeRoofAotv()
            }
        }

        @JvmStatic
        fun startBallsackShredder(client: Minecraft, plot: String) {
            if (!PestManager.isBallsackShredderActiveForCurrentCycle()) return
            setState(State.BALLSACK_SHREDDER)
            val sessionId = PestManager.getCurrentPestSessionId()
            val startingPests = Math.max(0, PestManager.getTabAliveCountNow(client))
            ClientUtils.sendDebugMessage("[PestDestroyer] Running Ballsack Shredder after arrival on plot " + plot + ".")
            MacroWorkerThread.getInstance().submit("PestBallsack-Arrival-" + plot) {
                try {
                    PestBallsackShredder.run(client, sessionId, startingPests)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    client.execute {
                        if (!runtime.active || runtime.state != State.BALLSACK_SHREDDER) return@execute
                        if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() && !client.player!!.abilities.flying && client.player!!.abilities.mayfly) {
                            setState(State.FLY_UP)
                        } else {
                            setState(State.CHECK_NEXT)
                        }
                    }
                }
            }
        }

        @JvmStatic
        fun tryNextPlot(client: Minecraft): Boolean {
            if (!PestPlotNavigator.tryNextPlot(client, runtime.navigation)) return false
            runtime.pestEtherwarpMaintainHeight = false
            runtime.pestEtherwarpJumpHeld = false
            ClientUtils.setKeyMappingState(client.options.keyJump, false)
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
            setState(State.TELEPORT_TO_PLOT)
            return true
        }

        @JvmStatic
        fun finish(client: Minecraft) {
            runtime.navigation.trackerSearch.stopLooking()
            PestTrackerAbility.clear()
            ClientUtils.setKeyMappingState(client.options.keyUse, false)
            ClientUtils.setKeyMappingState(client.options.keyDown, false)
            ClientUtils.setKeyMappingState(client.options.keyAttack, false)
            ClientUtils.setKeyMappingState(client.options.keyUp, false)
            ClientUtils.setKeyMappingState(client.options.keyJump, false)
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
            val killed = runtime.killedEntities.size
            ClientUtils.sendMessage("\u00A7aPest destroyer finished. Tracked " + killed + " pest(s).", false)
            runtime.resetAll()
            PathfindingManager.stop()
            PestAimTracker.reset()
            RotationManager.cancelRotation()
            PestManager.handlePestCleaningFinished(client)
        }

        @JvmStatic
        fun shouldFinishForAliveCount(client: Minecraft, aliveCount: Int): Boolean {
            return PestLeaveOneController.shouldFinishForAliveCount(client, runtime, aliveCount)
        }

        @JvmStatic
        fun getAliveFinishReason(aliveCount: Int): String {
            return if (aliveCount == 0) "0 pests alive" else "only whitelisted leave-one plot(s) remaining"
        }

        @JvmStatic
        fun tryLeaveOneOnCurrentWhitelistedPlot(client: Minecraft): Boolean {
            return PestLeaveOneController.tryLeaveCurrentPlot(client, runtime, CONTEXT)
        }

        @JvmStatic
        fun filterSkippedInfestedPlots(infested: Collection<String>): JavaSet<String> {
            val filtered: MutableSet<String> = LinkedHashSet()
            filtered.addAll(PestLeaveOneController.filterSkippedPlots(runtime, infested))
            return filtered as JavaSet<String>
        }

        @JvmStatic
        fun filterRememberedLeaveOnePlots(infested: Collection<String>): JavaSet<String> {
            val filtered: MutableSet<String> = LinkedHashSet()
            filtered.addAll(PestLeaveOneController.filterSkippedPlots(runtime, infested))
            return filtered as JavaSet<String>
        }

        @JvmStatic
        fun isLeaveOneSatisfied(aliveCount: Int): Boolean {
            return PestLeaveOneController.shouldFinishForCounts(aliveCount, PestLeaveOneController.rememberedPlotCount(runtime))
        }

        @JvmStatic
        fun onPestsSpawnedInPlot(plot: String) {
            PestLeaveOneController.forgetPlot(runtime, plot)
        }

        @JvmStatic
        fun clearRememberedLeaveOnePlots() {
            PestLeaveOneController.clearRememberedPlots(runtime)
        }

        private fun plotsEqual(first: String?, second: String?): Boolean {
            return PestPlotId.equals(first, second)
        }

        private fun containsPlot(plots: Collection<String>, plot: String): Boolean {
            return plots.any { plotsEqual(it, plot) }
        }

        @JvmStatic
        fun getVacuumSlot(): Int {
            return runtime.vacuumSlot
        }

        @JvmStatic
        fun onPestCaught() {
            if (isCatchInProgress()) PestHuntingController.onPestCaught(runtime)
        }

        @JvmStatic
        fun onLassoOverlayMessage(message: String) {
            if (isCatchInProgress()) PestHuntingController.onOverlayMessage(runtime, message)
        }

        @JvmStatic
        fun onLassoEscapeMessage(message: String) {
            if (isCatchInProgress()) PestHuntingController.onEscapeMessage(runtime, message)
        }

        @JvmStatic
        fun isCatchInProgress(): Boolean {
            return runtime.active && runtime.state == State.HUNT_PEST
        }

        @JvmStatic
        fun setAotvStartY(startY: Double) {
            runtime.aotvStartY = startY
        }

        @JvmStatic
        fun getAotvStartY(): Double {
            return runtime.aotvStartY
        }

        @JvmStatic
        fun setState(newState: State) {
            var nextState = newState
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get() && nextState == State.FLY_UP) {
                nextState = if (runtime.vacuumSlot < 0) State.EQUIP_VACUUM else State.CHECK_NEXT
            }
            if (nextState != State.GET_LOCATION) runtime.navigation.trackerSearch.stopLooking()
            if (nextState != State.GET_LOCATION && nextState != State.FLY_TO_WAYPOINT) PestTrackerAbility.clear()
            runtime.transitionTo(nextState, System.currentTimeMillis())
        }

        @JvmStatic
        fun onEntityDeath(entity: Entity) {
            PestTargetController.onEntityDeath(Minecraft.getInstance(), runtime, CONTEXT, entity)
        }

        @JvmStatic
        fun getEffectivePlot(client: Minecraft?): String? {
            return PestPlotNavigator.getEffectivePlot(client, runtime.navigation)
        }
    }
}
