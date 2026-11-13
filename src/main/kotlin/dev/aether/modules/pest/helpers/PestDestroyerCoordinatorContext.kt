package dev.aether.modules.pest.helpers

import dev.aether.macro.MacroWorkerThread
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

// keeps controller wiring out of the state-machine coordinator
internal class PestDestroyerCoordinatorContext(private val runtime: PestDestroyerRuntime) :
    PestTargetController.Context,
    PestEquipmentController.Context,
    PestHuntController.Context,
    PestHuntingController.Context,
    PestNavigationCoordinator.Context,
    PestCombatCoordinator.Context,
    PestDestroyerProgressController.Context {

    override fun runtime(): PestDestroyerRuntime = runtime

    override fun getStateEnteredAt(): Long = runtime.stateEnteredAt

    override fun setStateEnteredAt(value: Long) {
        runtime.stateEnteredAt = value
    }

    override fun getVacuumSlot(): Int = runtime.vacuumSlot

    override fun setVacuumSlot(value: Int) {
        runtime.vacuumSlot = value
    }

    override fun getVacuumRange(): Double = runtime.vacuumRange.toDouble()

    override fun getStuckTicks(): Int = runtime.stuckTicks

    override fun setStuckTicks(value: Int) {
        runtime.stuckTicks = value
    }

    override fun setState(state: PestDestroyer.State) {
        PestDestroyer.setState(state)
    }

    override fun beginTerminalState(client: Minecraft) {
        PestTargetController.beginTerminalState(client, runtime, this)
    }

    override fun finish(client: Minecraft) {
        PestDestroyer.finish(client)
    }

    override fun tryLeaveOneOnCurrentPlot(client: Minecraft): Boolean =
        PestDestroyer.tryLeaveOneOnCurrentWhitelistedPlot(client)

    override fun tryLeaveOneOnCurrentWhitelistedPlot(client: Minecraft): Boolean =
        tryLeaveOneOnCurrentPlot(client)

    override fun findVacuumHotbarSlot(client: Minecraft): Int = PestLoadoutHelper.findVacuumHotbarSlot(client)

    override fun findAotvHotbarSlot(client: Minecraft): Int = PestLoadoutHelper.findAotvHotbarSlot(client)

    override fun findClosestPest(client: Minecraft): Entity? =
        PestTargetController.findClosestPest(client, runtime, this)

    override fun engagePestTarget(client: Minecraft, pest: Entity) {
        PestTargetController.engage(client, runtime, this, pest)
    }

    override fun tryNextPlot(client: Minecraft): Boolean = PestDestroyer.tryNextPlot(client)

    override fun startRoofAotv(client: Minecraft, plot: String) {
        setState(PestDestroyer.State.AOTV_TO_ROOF)
        PestAotvManager.setSneakingForAotv(true)
        MacroWorkerThread.getInstance().submit(
            "PestAotv-Roof-Arrival-$plot",
            Runnable {
                try {
                    PestAotvManager.performAotvToRoof(client)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        )
    }

    override fun startBallsackShredder(client: Minecraft, plot: String) {
        PestDestroyer.startBallsackShredder(client, plot)
    }

    override fun isLookingAt(client: Minecraft, targetPosition: Vec3, tolerance: Float): Boolean =
        PestTargetController.isLookingAt(client, targetPosition, tolerance)

    override fun startPathToPest(client: Minecraft, pest: Entity) {
        PestTargetController.startPathToPest(client, pest)
    }

    override fun switchToNextQueuedTarget(client: Minecraft): Boolean =
        PestTargetController.switchToNextQueuedTarget(client, runtime, this)

    override fun peekNextQueuedPest(client: Minecraft): Entity? =
        PestTargetController.peekNextQueuedPest(client, runtime)

    override fun maybePreMoveToNextTarget(client: Minecraft, nextTarget: Entity, currentDistance: Double) {
        PestTargetController.maybePreMoveToNextTarget(client, nextTarget, currentDistance)
    }

    override fun hasPestSkullMarkerForTarget(client: Minecraft, target: Entity): Boolean =
        PestTargetController.hasPestSkullMarkerForTarget(client, target)

    override fun markKilled(entity: Entity) {
        runtime.killedEntities.add(entity)
    }

    override fun deferTarget(entity: Entity) {
        runtime.deferredTargets.defer(entity)
    }

    override fun recordTrackedPestKill(client: Minecraft, entity: Entity): Boolean =
        PestTargetController.recordTrackedKill(client, runtime, this, entity)

    override fun shouldTemporarilyReleaseKillVacuum(client: Minecraft, vacuumReady: Boolean, targetInRange: Boolean): Boolean =
        PestDestroyerInputController.shouldTemporarilyReleaseKillVacuum(runtime, vacuumReady, targetInRange)

    override fun shouldFinishForAliveCount(client: Minecraft, aliveCount: Int): Boolean =
        PestDestroyer.shouldFinishForAliveCount(client, aliveCount)

    override fun getAliveFinishReason(aliveCount: Int): String = PestDestroyer.getAliveFinishReason(aliveCount)

    override fun filterSkippedInfestedPlots(plots: Set<String>): Set<String> =
        PestDestroyer.filterSkippedInfestedPlots(plots).toSet()

    override fun getEffectivePlot(client: Minecraft): String = PestDestroyer.getEffectivePlot(client) ?: ""

    override fun plotsEqual(first: String, second: String): Boolean = PestPlotId.equals(first, second)
}
