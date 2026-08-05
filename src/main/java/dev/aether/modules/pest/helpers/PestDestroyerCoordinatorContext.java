package dev.aether.modules.pest.helpers;

import dev.aether.macro.MacroWorkerThread;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Bridges the destroyer runtime to its specialized controllers. This keeps
 * controller wiring out of the state-machine coordinator.
 */
final class PestDestroyerCoordinatorContext
        implements PestTargetController.Context,
                PestEquipmentController.Context,
                PestHuntController.Context,
                PestNavigationCoordinator.Context,
                PestCombatCoordinator.Context,
                PestDestroyerProgressController.Context {
    private final PestDestroyerRuntime runtime;

    PestDestroyerCoordinatorContext(PestDestroyerRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public PestDestroyerRuntime runtime() {
        return runtime;
    }

    @Override
    public long getStateEnteredAt() {
        return runtime.stateEnteredAt;
    }

    @Override
    public void setStateEnteredAt(long value) {
        runtime.stateEnteredAt = value;
    }

    @Override
    public int getVacuumSlot() {
        return runtime.vacuumSlot;
    }

    @Override
    public void setVacuumSlot(int value) {
        runtime.vacuumSlot = value;
    }

    @Override
    public double getVacuumRange() {
        return runtime.vacuumRange;
    }

    @Override
    public int getStuckTicks() {
        return runtime.stuckTicks;
    }

    @Override
    public void setStuckTicks(int value) {
        runtime.stuckTicks = value;
    }

    @Override
    public void setState(PestDestroyer.State state) {
        PestDestroyer.setState(state);
    }

    @Override
    public void finish(Minecraft client) {
        PestDestroyer.finish(client);
    }

    @Override
    public boolean tryLeaveOneOnCurrentPlot(Minecraft client) {
        return PestDestroyer.tryLeaveOneOnCurrentWhitelistedPlot(client);
    }

    @Override
    public boolean tryLeaveOneOnCurrentWhitelistedPlot(Minecraft client) {
        return tryLeaveOneOnCurrentPlot(client);
    }

    @Override
    public int findVacuumHotbarSlot(Minecraft client) {
        return PestLoadoutHelper.findVacuumHotbarSlot(client);
    }

    @Override
    public int findAotvHotbarSlot(Minecraft client) {
        return PestLoadoutHelper.findAotvHotbarSlot(client);
    }

    @Override
    public Entity findClosestPest(Minecraft client) {
        return PestTargetController.findClosestPest(client, runtime, this);
    }

    @Override
    public void engagePestTarget(Minecraft client, Entity pest) {
        PestTargetController.engage(client, runtime, this, pest);
    }

    @Override
    public int countVisiblePestSkulls(Minecraft client) {
        return PestTargetController.countVisiblePestSkulls(client);
    }

    @Override
    public boolean tryNextPlot(Minecraft client) {
        return PestDestroyer.tryNextPlot(client);
    }

    @Override
    public void startRoofAotv(Minecraft client, String plot) {
        setState(PestDestroyer.State.AOTV_TO_ROOF);
        PestAotvManager.setSneakingForAotv(true);
        MacroWorkerThread.getInstance().submit(
                "PestAotv-Roof-Arrival-" + plot,
                () -> {
                    try {
                        PestAotvManager.performAotvToRoof(client);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    @Override
    public void startBallsackShredder(Minecraft client, String plot) {
        PestDestroyer.startBallsackShredder(client, plot);
    }

    @Override
    public boolean isLookingAt(
            Minecraft client, Vec3 targetPosition, float tolerance) {
        return PestTargetController.isLookingAt(client, targetPosition, tolerance);
    }

    @Override
    public void startPathToPest(Minecraft client, Entity pest) {
        PestTargetController.startPathToPest(client, pest);
    }

    @Override
    public boolean switchToNextQueuedTarget(Minecraft client) {
        return PestTargetController.switchToNextQueuedTarget(client, runtime, this);
    }

    @Override
    public Entity peekNextQueuedPest(Minecraft client) {
        return PestTargetController.peekNextQueuedPest(client, runtime);
    }

    @Override
    public void maybePreMoveToNextTarget(
            Minecraft client, Entity nextTarget, double currentDistance) {
        PestTargetController.maybePreMoveToNextTarget(
                client, nextTarget, currentDistance);
    }

    @Override
    public boolean hasPestSkullMarkerForTarget(
            Minecraft client, Entity target) {
        return PestTargetController.hasPestSkullMarkerForTarget(client, target);
    }

    @Override
    public void markKilled(Entity entity) {
        runtime.killedEntities.add(entity);
    }

    @Override
    public boolean recordTrackedPestKill(Minecraft client, Entity entity) {
        return PestTargetController.recordTrackedKill(client, runtime, this, entity);
    }

    @Override
    public boolean shouldTemporarilyReleaseKillVacuum(
            Minecraft client, boolean vacuumReady, boolean targetInRange) {
        return PestDestroyerInputController.shouldTemporarilyReleaseKillVacuum(
                runtime, vacuumReady, targetInRange);
    }

    @Override
    public boolean shouldFinishForAliveCount(
            Minecraft client, int aliveCount) {
        return PestDestroyer.shouldFinishForAliveCount(client, aliveCount);
    }

    @Override
    public String getAliveFinishReason(int aliveCount) {
        return PestDestroyer.getAliveFinishReason(aliveCount);
    }

    @Override
    public Set<String> filterSkippedInfestedPlots(Set<String> plots) {
        return PestDestroyer.filterSkippedInfestedPlots(plots);
    }

    @Override
    public String getEffectivePlot(Minecraft client) {
        return PestDestroyer.getEffectivePlot(client);
    }

    @Override
    public boolean plotsEqual(String first, String second) {
        return PestPlotId.equals(first, second);
    }
}
