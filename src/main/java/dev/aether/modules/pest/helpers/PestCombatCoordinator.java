package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pathfinding.execution.FlightPathClearance;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

final class PestCombatCoordinator {
    private static final long AOTV_POST_CLICK_GRACE_MS = 250L;
    private static final double AOTV_CONFIRM_DISTANCE = 2.0;
    private static final double AOTV_CONFIRM_DISTANCE_SQ = AOTV_CONFIRM_DISTANCE * AOTV_CONFIRM_DISTANCE;
    private static final float AOTV_AIM_TOLERANCE_DEGREES = 2.0f;
    // A pest that never settles inside the tight tolerance must not stall the hop
    // chain. Held just past the aim's own worst case (a half turn, ~280ms) so a
    // normal hop still gets the tight tolerance and a stubborn one gives up fast.
    private static final long AOTV_AIM_SETTLE_TIMEOUT_MS = 400L;
    private static final float AOTV_AIM_FALLBACK_TOLERANCE_DEGREES = 6.0f;
    // The hop aim is not a chase: it points at a spot and warps, and every extra
    // millisecond here lands on top of the configured AOTV delay. Kept fast
    // enough that settling inside AOTV_AIM_TOLERANCE_DEGREES stays in the same
    // ballpark as that delay even from a half-turn away.
    private static final float AOTV_AIM_SMOOTHING_MS = 40.0f;
    private static final double VACUUM_REAPPROACH_BUFFER = 6.0;
    private static final double TARGET_REACQUIRE_CONE_DEGREES = 120.0;
    private static final double AIRBORNE_RECOVERY_TRIGGER_GAP = 2.5;
    private static final double AIRBORNE_RECOVERY_EXIT_GAP = 1.25;
    private static final double AIRBORNE_RECOVERY_HORIZONTAL_BUFFER = 2.0;
    private static final double AIRBORNE_RECOVERY_EXIT_HORIZONTAL_BUFFER = 4.0;
    private static final long AIRBORNE_RECOVERY_AIM_REFRESH_MS = 100L;
    private static final double AIRBORNE_RECOVERY_AIM_BLEND = 0.45;
    private static final float AIRBORNE_RECOVERY_AIM_SMOOTHING_MS = 240.0f;
    private static final float ETHERWARP_AIM_TOLERANCE_DEGREES = 2.0f;
    private static final long ETHERWARP_AIM_MIN_DURATION_MS = 120L;
    private static final long ETHERWARP_CONFIRM_TIMEOUT_MS = 900L;
    private static final long ETHERWARP_RETRY_COOLDOWN_MS = 1500L;
    private static final long ETHERWARP_FAILED_BLOCK_MEMORY_MS = 15_000L;
    private static final int ETHERWARP_BLOCK_SCAN_DEPTH = 20;
    private static final double ETHERWARP_MIN_TRAVEL_DISTANCE = 3.0;
    private static final double ETHERWARP_FRONT_OFFSET = 10.0;
    private static final double ETHERWARP_FRONT_MATCH_TOLERANCE = 0.75;
    private static final double ETHERWARP_POST_HOVER_MIN_CLEARANCE = 3.0;
    private static final double ETHERWARP_POST_HOVER_RELEASE_CLEARANCE = 3.35;
    private static final int ETHERWARP_POST_HOVER_GROUND_SCAN_DEPTH = 32;
    interface Context {
        PestDestroyerRuntime runtime();

        default Entity getCurrentTarget() { return runtime().currentTarget; }
        default int getVacuumSlot() {
            return runtime().killVacuumSlot >= 0 ? runtime().killVacuumSlot : runtime().vacuumSlot;
        }
        default void setVacuumSlot(int slot) { runtime().vacuumSlot = slot; }
        default double getVacuumRange() { return runtime().vacuumRange; }
        default int getAotvSlot() { return runtime().aotvSlot; }
        default void setAotvSlot(int slot) { runtime().aotvSlot = slot; }
        default int getAotvUseCount() { return runtime().aotvUseCount; }
        default void setAotvUseCount(int count) { runtime().aotvUseCount = count; }
        default long getAotvLastUseAt() { return runtime().aotvLastUseAt; }
        default void setAotvLastUseAt(long value) { runtime().aotvLastUseAt = value; }
        default long getAotvNextUseAt() { return runtime().aotvNextUseAt; }
        default void setAotvNextUseAt(long value) { runtime().aotvNextUseAt = value; }
        default long getAotvPostClickGraceUntil() { return runtime().aotvPostClickGraceUntil; }
        default void setAotvPostClickGraceUntil(long value) { runtime().aotvPostClickGraceUntil = value; }
        default long getAotvPendingUseAt() { return runtime().aotvPendingUseAt; }
        default void setAotvPendingUseAt(long value) { runtime().aotvPendingUseAt = value; }
        default long getAotvAimStartedAt() { return runtime().aotvAimStartedAt; }
        default void setAotvAimStartedAt(long value) { runtime().aotvAimStartedAt = value; }
        default double getAotvLastUsePlayerX() { return runtime().aotvLastUsePlayerX; }
        default void setAotvLastUsePlayerX(double value) { runtime().aotvLastUsePlayerX = value; }
        default double getAotvLastUsePlayerY() { return runtime().aotvLastUsePlayerY; }
        default void setAotvLastUsePlayerY(double value) { runtime().aotvLastUsePlayerY = value; }
        default double getAotvLastUsePlayerZ() { return runtime().aotvLastUsePlayerZ; }
        default void setAotvLastUsePlayerZ(double value) { runtime().aotvLastUsePlayerZ = value; }
        default boolean didArriveAtCurrentTargetViaAotv() { return runtime().arrivedAtCurrentTargetViaAotv; }
        default void setArrivedAtCurrentTargetViaAotv(boolean value) { runtime().arrivedAtCurrentTargetViaAotv = value; }
        default long getStateEnteredAt() { return runtime().stateEnteredAt; }
        default void setStateEnteredAt(long value) { runtime().stateEnteredAt = value; }
        default int getStuckTicks() { return runtime().stuckTicks; }
        default void setStuckTicks(int value) { runtime().stuckTicks = value; }
        default int getApproachTicks() { return runtime().approachTicks; }
        default void setApproachTicks(int value) { runtime().approachTicks = value; }
        default int getTargetWithoutSkullTicks() { return runtime().targetWithoutSkullTicks; }
        default void setTargetWithoutSkullTicks(int value) { runtime().targetWithoutSkullTicks = value; }
        boolean isLookingAt(Minecraft client, Vec3 targetPos, float tolerance);
        void setState(PestDestroyer.State state);
        void beginTerminalState(Minecraft client);
        void startPathToPest(Minecraft client, Entity pest);
        boolean switchToNextQueuedTarget(Minecraft client);
        Entity peekNextQueuedPest(Minecraft client);
        void maybePreMoveToNextTarget(Minecraft client, Entity nextTarget, double currentDist);
        boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target);
        void markKilled(Entity entity);
        void deferTarget(Entity entity);
        boolean recordTrackedPestKill(Minecraft client, Entity entity);
        boolean shouldTemporarilyReleaseKillVacuum(
                Minecraft client, boolean vacuumReady, boolean targetInRange);
        int findVacuumHotbarSlot(Minecraft client);
        int findAotvHotbarSlot(Minecraft client);
    }

    private PestCombatCoordinator() {
    }

    static void handleFlyToPest(
            Minecraft client,
            Context context,
            double targetReachDistance,
            int pathfinderStuckRetryTicks,
            long stateTimeoutMs
    ) {
        Entity currentTarget = context.getCurrentTarget();
        if (currentTarget == null || currentTarget.isRemoved() || (currentTarget instanceof LivingEntity le && le.isDeadOrDying())) {
            PathfindingManager.stop();
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        // Following a pest across the plot line makes the plot check teleport us back mid-route.
        if (!PestPlotNavigator.currentPlotFilter(client, context.runtime().navigation).test(currentTarget)) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Target drifted off the plot. Picking another.");
            PathfindingManager.stop();
            context.runtime().currentTarget = null;
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        double dist = client.player.distanceTo(currentTarget);
        // The fly executor owns the camera until the route reaches its handoff.
        // Tracking the moving pest here overwrote the path heading after only a
        // few movement ticks, making every stuck recovery forget its goal.

        boolean lassoTarget = PestHuntingController.shouldLassoTarget(client, currentTarget);
        boolean directApproach = !lassoTarget && context.runtime().flightController.canApproachDirectly(client, currentTarget, context.getVacuumRange());
        if (directApproach || lassoTarget && dist <= targetReachDistance) {
            PathfindingManager.stop();
            context.setState(PestDestroyer.State.APPROACH_PEST);
            if (directApproach) {
                context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), true);
            }
            return;
        }

        switch (context.runtime().flightRecovery.update(PathfindingManager.isNavigating(),
                client.player.position(), System.currentTimeMillis(), context.getStateEnteredAt(), stateTimeoutMs)) {
            case GIVE_UP -> {
                ClientUtils.sendDebugMessage("[PestDestroyer] Fly route exhausted recovery. Checking for next pest.");
                PathfindingManager.stop();
                context.deferTarget(currentTarget);
                context.setState(PestDestroyer.State.CHECK_NEXT);
            }
            case RETRY -> {
                ClientUtils.sendDebugMessage("[PestDestroyer] Fly route ended before reaching pest. Repathing.");
                context.setStuckTicks(0);
                context.startPathToPest(client, currentTarget);
            }
            case CONTINUE -> context.setStuckTicks(0);
            case WAIT -> {
            }
        }
    }

    static void handleApproachPest(
            Minecraft client,
            Context context,
            double targetReachDistance,
            int approachTimeoutTicks
    ) {
        Entity currentTarget = context.getCurrentTarget();
        if (currentTarget == null || currentTarget.isRemoved() || (currentTarget instanceof LivingEntity le && le.isDeadOrDying())) {
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        double dist = client.player.distanceTo(currentTarget);
        context.setApproachTicks(context.getApproachTicks() + 1);

        boolean lassoTarget = PestHuntingController.shouldLassoTarget(client, currentTarget);
        double terminalRange = PestHuntingController.handoffRange(client, currentTarget, context.getVacuumRange());
        double approachHorizontalDistance = Math.hypot(
                currentTarget.getX() - client.player.getX(),
                currentTarget.getZ() - client.player.getZ());
        double approachVerticalGap = getEntityEyePosition(currentTarget).y - client.player.getEyePosition().y;
        if (!lassoTarget
                && client.player.getAbilities().flying
                && approachHorizontalDistance <= terminalRange
                && approachVerticalGap > AIRBORNE_RECOVERY_TRIGGER_GAP) {
            PathfindingManager.stop(false);
            context.beginTerminalState(client);
            return;
        }
        boolean directApproach = !lassoTarget && context.runtime().flightController.canApproachDirectly(client, currentTarget, context.getVacuumRange());
        if (dist <= terminalRange && (lassoTarget || directApproach)) {
            context.beginTerminalState(client);
            if (!lassoTarget) {
                context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), true);
            }
            return;
        }

        if (directApproach) {
            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop();
            }
            context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), true);
        } else if (!PathfindingManager.isNavigating()) {
            RotationManager.cancelRotation();
            context.startPathToPest(client, currentTarget);
        }

        if (context.getApproachTicks() > approachTimeoutTicks) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Approach timed out.");
            PathfindingManager.stop();
            context.deferTarget(currentTarget);
            context.setState(PestDestroyer.State.CHECK_NEXT);
        }
    }

    static void handleKillPest(
            Minecraft client,
            Context context,
            int skullMissingConfirmTicks,
            long stateTimeoutMs
    ) {
        Entity currentTarget = context.getCurrentTarget();
        if (currentTarget == null || currentTarget.isRemoved() || (currentTarget instanceof LivingEntity le && le.isDeadOrDying())) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            if (currentTarget != null) {
                if (context.recordTrackedPestKill(client, currentTarget)) {
                    return;
                }
                if (context.switchToNextQueuedTarget(client)) {
                    return;
                }
            }
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        if (client.player == null) {
            return;
        }

        double dist = client.player.distanceTo(currentTarget);
        double horizontalDistance = Math.hypot(
                currentTarget.getX() - client.player.getX(),
                currentTarget.getZ() - client.player.getZ());
        double verticalGap = getEntityEyePosition(currentTarget).y - client.player.getEyePosition().y;
        PestDestroyerRuntime runtime = context.runtime();
        boolean sameAirborneTarget = runtime.airborneRecoveryActive
                && runtime.airborneRecoveryTargetEntityId == currentTarget.getId();
        boolean shouldEnterAirborneRecovery = client.player.getAbilities().flying
                && verticalGap > AIRBORNE_RECOVERY_TRIGGER_GAP
                && horizontalDistance <= context.getVacuumRange() + AIRBORNE_RECOVERY_HORIZONTAL_BUFFER;

        if (!sameAirborneTarget && shouldEnterAirborneRecovery) {
            runtime.airborneRecoveryActive = true;
            runtime.airborneRecoveryTargetEntityId = currentTarget.getId();
            runtime.airborneRecoveryAimPoint = getEntityEyePosition(currentTarget);
            runtime.airborneRecoveryAimUpdatedAt = System.currentTimeMillis();
            context.setTargetWithoutSkullTicks(0);
            PathfindingManager.stop(false);
            RotationManager.cancelRotation();
            sameAirborneTarget = true;
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Pest jumped vertically. Recovering on target " + currentTarget.getId() + ".");
        }

        if (sameAirborneTarget) {
            boolean keepRecovering = client.player.getAbilities().flying
                    && verticalGap > AIRBORNE_RECOVERY_EXIT_GAP
                    && horizontalDistance <= context.getVacuumRange() + AIRBORNE_RECOVERY_EXIT_HORIZONTAL_BUFFER;
            if (keepRecovering) {
                handleAirbornePestRecovery(client, context, currentTarget, dist, verticalGap);
                return;
            }
            runtime.resetAirborneRecovery();
            context.setTargetWithoutSkullTicks(0);
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
            ClientUtils.sendDebugMessage("[PestDestroyer] Airborne pest stabilized. Resuming normal combat.");
        }

        boolean directApproach = context.runtime().flightController.canApproachDirectly(client, currentTarget, context.getVacuumRange());
        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
        }
        context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), directApproach);
        if (context.getVacuumSlot() == -1) {
            context.setVacuumSlot(context.findVacuumHotbarSlot(client));
        }
        if (context.getVacuumSlot() != -1
                && ((AccessorInventory) client.player.getInventory()).getSelected() != context.getVacuumSlot()) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()));
            return;
        }

        if (dist <= context.getVacuumRange() && directApproach) {
            boolean retryingUse =
                    context.shouldTemporarilyReleaseKillVacuum(client, true, true);
            ClientUtils.setKeyMappingState(client.options.keyUse, !retryingUse
                    && !isOutsideForwardCone(client, currentTarget, TARGET_REACQUIRE_CONE_DEGREES));

            if (!context.hasPestSkullMarkerForTarget(client, currentTarget)) {
                context.setTargetWithoutSkullTicks(context.getTargetWithoutSkullTicks() + 1);
                if (context.getTargetWithoutSkullTicks() >= skullMissingConfirmTicks) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    ClientUtils.setKeyMappingState(client.options.keyDown, false);
                    context.markKilled(currentTarget);
                    if (context.recordTrackedPestKill(client, currentTarget)) {
                        return;
                    }
                    ClientUtils.sendDebugMessage("[PestDestroyer] Pest skull disappeared. Switching target immediately.");
                    if (!context.switchToNextQueuedTarget(client)) {
                        context.setState(PestDestroyer.State.CHECK_NEXT);
                    }
                    return;
                }
            } else {
                context.setTargetWithoutSkullTicks(0);
            }
        } else {
            context.shouldTemporarilyReleaseKillVacuum(client, true, false);
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            context.setTargetWithoutSkullTicks(0);
            if (!directApproach || dist > context.getVacuumRange() + VACUUM_REAPPROACH_BUFFER) {
                RotationManager.cancelRotation();
                context.setState(PestDestroyer.State.APPROACH_PEST);
                return;
            }
        }

        if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.sendDebugMessage("[PestDestroyer] Kill pest timed out. Moving on.");
            context.deferTarget(currentTarget);
            context.setTargetWithoutSkullTicks(0);
            if (!context.switchToNextQueuedTarget(client)) {
                context.setState(PestDestroyer.State.CHECK_NEXT);
            }
        }
    }

    static void handleAotvBetweenPests(
            Minecraft client,
            Context context,
            double aotvRange,
            double aotvGapMultiplier,
            long stateTimeoutMs
    ) {
        Entity currentTarget = context.getCurrentTarget();
        if (currentTarget == null || currentTarget.isRemoved() || (currentTarget instanceof LivingEntity le && le.isDeadOrDying())) {
            clearAotvBetweenPests(client, context);
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        if (context.getAotvSlot() == -1) {
            context.setAotvSlot(context.findAotvHotbarSlot(client));
            if (context.getAotvSlot() == -1) {
                clearAotvBetweenPests(client, context);
                ClientUtils.sendDebugMessage("[PestDestroyer] No AOTV found. Falling back to pathfinding.");
                context.startPathToPest(client, currentTarget);
                context.setState(PestDestroyer.State.FLY_TO_PEST);
                return;
            }
            context.setStateEnteredAt(System.currentTimeMillis());
        }

        long now = System.currentTimeMillis();
        // Measured from the last hop, not from state entry: a long chain of hops is
        // progress. Checked up front because waiting on an aim or a climb returns
        // early, so a timeout further down never runs.
        long lastProgressAt = Math.max(
                context.getStateEnteredAt(),
                Math.max(context.getAotvLastUseAt(), context.getAotvPendingUseAt()));
        if (now - lastProgressAt > stateTimeoutMs) {
            clearAotvBetweenPests(client, context);
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV state timed out. Falling back to pathfinding.");
            context.startPathToPest(client, currentTarget);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
            return;
        }

        double stopDistance = PestTargetController.getAotvStopDistance(
                client, currentTarget, context.getVacuumRange());
        double dist = client.player.distanceTo(currentTarget);
        if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
            return;
        }

        if ((AetherConfig.PEST_ETHERWARP_TO_PEST.get() || context.runtime().pestEtherwarpActive)
                && handleEtherwarpNearPest(client, context, currentTarget, stopDistance, now)) {
            return;
        }

        Vec3 aimPos = getEntityEyePosition(currentTarget);

        Vec3 hopDirection = aimPos.subtract(client.player.getEyePosition()).normalize();
        if (!hasClearAotvHop(client, hopDirection, aotvRange)) {
            pathAroundAotvObstacle(client, context, currentTarget);
            return;
        }
        ClientUtils.setKeyMappingState(client.options.keyJump, false);

        if (context.getAotvSlot() != -1 && ((AccessorInventory) client.player.getInventory()).getSelected() != context.getAotvSlot()) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getAotvSlot()));
            return;
        }

        boolean suppressRotation = FailsafeManager.shouldSuppressPestCleanerRotation(client);
        if (!suppressRotation) {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.setKeyMappingState(client.options.keySprint, false);

            if (context.getAotvAimStartedAt() == 0L) {
                context.setAotvAimStartedAt(now);
            }
            // A one-shot rotation lands where the pest was and has to restart, which is
            // what froze the hop chain staring at the pest. Retarget every tick instead.
            RotationManager.trackRotation(
                    client, aimPos, AOTV_AIM_SMOOTHING_MS,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());

            float tolerance = now - context.getAotvAimStartedAt() > AOTV_AIM_SETTLE_TIMEOUT_MS
                    ? AOTV_AIM_FALLBACK_TOLERANCE_DEGREES
                    : AOTV_AIM_TOLERANCE_DEGREES;
            if (!context.isLookingAt(client, aimPos, tolerance)) {
                return;
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV aim settled in "
                    + (now - context.getAotvAimStartedAt()) + "ms (tolerance "
                    + String.format("%.0f", tolerance) + "\u00B0).");
            context.setAotvAimStartedAt(0L);
        }

        if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get() && context.getAotvPendingUseAt() != 0L) {
            double movedDistance = getAotvMovedDistance(client, context);
            if (movedDistance >= AOTV_CONFIRM_DISTANCE) {
                context.setAotvLastUseAt(context.getAotvPendingUseAt());
                context.setAotvPendingUseAt(0L);
                context.setAotvPostClickGraceUntil(0L);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
                ClientUtils.sendDebugMessage("[PestDestroyer] AOTV confirmed by movement: "
                                + String.format("%.2f", movedDistance) + " blocks.");
                dist = client.player.distanceTo(currentTarget);
                if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                    return;
                }
            } else if (context.getAotvPostClickGraceUntil() > now) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Waiting for AOTV confirm: moved "
                                + String.format("%.2f", movedDistance)
                                + "/" + String.format("%.2f", AOTV_CONFIRM_DISTANCE) + " blocks.");
                return;
            } else {
                ClientUtils.sendDebugMessage("[PestDestroyer] AOTV confirm failed: moved "
                                + String.format("%.2f", movedDistance)
                                + "/" + String.format("%.2f", AOTV_CONFIRM_DISTANCE) + " blocks. Retrying.");
                context.setAotvPendingUseAt(0L);
                context.setAotvPostClickGraceUntil(0L);
            }
        } else if (context.getAotvPostClickGraceUntil() > now) {
            double movedDistanceSq = getAotvMovedDistanceSq(client, context);
            if (movedDistanceSq <= AOTV_CONFIRM_DISTANCE_SQ) {
                return;
            }
            context.setAotvPostClickGraceUntil(0L);
            dist = client.player.distanceTo(currentTarget);
            if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                return;
            }
        }

        long readyAt = context.getAotvNextUseAt();
        if (readyAt == 0L) {
            long anchor = context.getAotvLastUseAt() == 0L
                    ? context.getStateEnteredAt()
                    : context.getAotvLastUseAt();
            readyAt = anchor + dev.aether.config.ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_AOTV_DELAY_MIN.get(),
                    AetherConfig.PEST_AOTV_DELAY_MAX.get());
            context.setAotvNextUseAt(readyAt);
        }
        if (now >= readyAt) {
            if (!hasClearAotvHop(client, client.player.getViewVector(1.0F), aotvRange)) {
                pathAroundAotvObstacle(client, context, currentTarget);
                return;
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] Using AOTV (" + (context.getAotvUseCount() + 1) + "). Distance: "
                            + String.format("%.1f", dist));
            ClientUtils.performUseClick();
            FailsafeManager.addRotationGracePeriod(AOTV_POST_CLICK_GRACE_MS);
            context.setAotvPostClickGraceUntil(now + AOTV_POST_CLICK_GRACE_MS);
            context.setAotvLastUsePlayerX(client.player.getX());
            context.setAotvLastUsePlayerY(client.player.getY());
            context.setAotvLastUsePlayerZ(client.player.getZ());
            if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get()) {
                context.setAotvPendingUseAt(now);
                ClientUtils.sendDebugMessage("[PestDestroyer] Waiting for AOTV position confirm (>= "
                                + String.format("%.0f", AOTV_CONFIRM_DISTANCE) + " blocks).");
            } else {
                context.setAotvLastUseAt(now);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
            }
            context.setAotvNextUseAt(0L);
        }

        if (context.getAotvUseCount() > 10) {
            clearAotvBetweenPests(client, context);
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV usage exceeded maximum. Falling back to pathfinding.");
            context.startPathToPest(client, currentTarget);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
        }
    }

    private static boolean hasClearAotvHop(Minecraft client, Vec3 direction, double range) {
        Vec3 start = client.player.position();
        return FlightPathClearance.isClear(client, start, start.add(direction.normalize().scale(range)));
    }

    private static void pathAroundAotvObstacle(Minecraft client, Context context, Entity target) {
        clearAotvBetweenPests(client, context);
        ClientUtils.sendDebugMessage("[PestDestroyer] AOTV hop obstructed. Pathfinding around the obstacle.");
        context.startPathToPest(client, target);
        context.setState(PestDestroyer.State.FLY_TO_PEST);
    }

    private static boolean finishAotvIfClose(
            Minecraft client,
            Context context,
            Entity currentTarget,
            double dist,
            double stopDistance
    ) {
        if (dist > stopDistance) {
            return false;
        }

        boolean arrivedViaAotv = context.getAotvUseCount() > 0;
        clearAotvBetweenPests(client, context);
        context.setArrivedAtCurrentTargetViaAotv(arrivedViaAotv);
        ClientUtils.sendDebugMessage("[PestDestroyer] AOTV closed gap. Distance now " + String.format("%.1f", dist)
                        + ". Switching to pathfinding.");
        if (dist <= PestHuntingController.handoffRange(client, currentTarget, context.getVacuumRange())) {
            context.beginTerminalState(client);
        } else {
            context.startPathToPest(client, currentTarget);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
        }
        return true;
    }

    static boolean isOutsideForwardCone(Minecraft client, Entity target, double coneDegrees) {
        if (client == null || client.player == null || target == null || coneDegrees <= 0.0) {
            return false;
        }
        Vec3 toTarget = getEntityEyePosition(target).subtract(client.player.getEyePosition());
        if (toTarget.lengthSqr() == 0.0) {
            return false;
        }
        double dot = client.player.getViewVector(1.0F).normalize().dot(toTarget.normalize());
        double threshold = Math.cos(Math.toRadians(coneDegrees));
        return dot < threshold;
    }

    private static Vec3 getEntityEyePosition(Entity entity) {
        return entity.position().add(0, entity.getEyeHeight(entity.getPose()), 0);
    }

    private static void handleAirbornePestRecovery(
            Minecraft client,
            Context context,
            Entity currentTarget,
            double dist,
            double verticalGap) {
        PestDestroyerRuntime runtime = context.runtime();
        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop(false);
        }
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, verticalGap > AIRBORNE_RECOVERY_EXIT_GAP);
        context.setTargetWithoutSkullTicks(0);

        if (context.getVacuumSlot() == -1) {
            context.setVacuumSlot(context.findVacuumHotbarSlot(client));
        }
        if (context.getVacuumSlot() != -1
                && ((AccessorInventory) client.player.getInventory()).getSelected() != context.getVacuumSlot()) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()));
        }

        long now = System.currentTimeMillis();
        Vec3 rawAim = buildCombatAimTarget(client, currentTarget);
        if (runtime.airborneRecoveryAimPoint == null) {
            runtime.airborneRecoveryAimPoint = rawAim;
            runtime.airborneRecoveryAimUpdatedAt = now;
        } else if (now - runtime.airborneRecoveryAimUpdatedAt >= AIRBORNE_RECOVERY_AIM_REFRESH_MS) {
            runtime.airborneRecoveryAimPoint = runtime.airborneRecoveryAimPoint.lerp(
                    rawAim, AIRBORNE_RECOVERY_AIM_BLEND);
            runtime.airborneRecoveryAimUpdatedAt = now;
        }

        if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            RotationManager.trackRotation(
                    client,
                    runtime.airborneRecoveryAimPoint,
                    AIRBORNE_RECOVERY_AIM_SMOOTHING_MS,
                    AetherConfig.PEST_MAX_TURN_SPEED.get());
        }

        boolean inVacuumRange = dist <= context.getVacuumRange();
        boolean retryingUse = context.shouldTemporarilyReleaseKillVacuum(client, true, inVacuumRange);
        ClientUtils.setKeyMappingState(client.options.keyUse, inVacuumRange && !retryingUse);
    }

    private static void clearAotvBetweenPests(Minecraft client, Context context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, false);
        ClientUtils.setKeyMappingState(client.options.keyShift, false);
        client.player.setShiftKeyDown(false);
        RotationManager.cancelRotation();
        context.setAotvSlot(-1);
        context.setAotvUseCount(0);
        context.setAotvLastUseAt(0L);
        context.setAotvNextUseAt(0L);
        context.setAotvPostClickGraceUntil(0L);
        context.setAotvPendingUseAt(0L);
        context.setAotvAimStartedAt(0L);
        clearPestEtherwarpAttempt(client, context.runtime(), true);
    }

    private record PestEtherwarpCandidate(Vec3 aimPoint, Vec3 landingFeet, BlockPos landingBlock) {
    }

    private static boolean handleEtherwarpNearPest(
            Minecraft client,
            Context context,
            Entity currentTarget,
            double stopDistance,
            long now
    ) {
        PestDestroyerRuntime runtime = context.runtime();
        if (!AetherConfig.PEST_ETHERWARP_TO_PEST.get() && !runtime.pestEtherwarpActive) {
            return false;
        }
        if (runtime.pestEtherwarpRetryAfter > now && !runtime.pestEtherwarpActive) {
            return false;
        }
        if (runtime.pestEtherwarpActive
                && runtime.pestEtherwarpTargetEntityId != currentTarget.getId()) {
            clearPestEtherwarpAttempt(client, runtime, false);
        }

        if (!runtime.pestEtherwarpActive) {
            double pestDistance = client.player.distanceTo(currentTarget);
            if (pestDistance < AetherConfig.PEST_ETHERWARP_MIN_DISTANCE.get()) {
                return false;
            }

            int etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client);
            Entity nextRoutePest = findNextPlannedPest(client, runtime, currentTarget);
            PestEtherwarpCandidate candidate = findEtherwarpCandidateNearPest(
                    client, runtime, currentTarget, nextRoutePest, now);
            if (etherwarpSlot < 0 || candidate == null) {
                return false;
            }

            runtime.pestEtherwarpActive = true;
            runtime.pestEtherwarpTargetEntityId = currentTarget.getId();
            runtime.pestEtherwarpAimPoint = candidate.aimPoint();
            runtime.pestEtherwarpLandingBlock = candidate.landingBlock();
            runtime.pestEtherwarpClickAt = 0L;
            context.setAotvSlot(etherwarpSlot);
            context.setAotvAimStartedAt(0L);
        }

        int etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client);
        if (etherwarpSlot < 0 || runtime.pestEtherwarpAimPoint == null) {
            clearPestEtherwarpAttempt(client, runtime, true);
            return false;
        }
        context.setAotvSlot(etherwarpSlot);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, true);
        ClientUtils.setKeyMappingState(client.options.keyShift, true);
        client.player.setShiftKeyDown(true);

        if (runtime.pestEtherwarpClickAt != 0L) {
            double movedDistance = getAotvMovedDistance(client, context);
            if (movedDistance >= AOTV_CONFIRM_DISTANCE) {
                context.setAotvLastUseAt(runtime.pestEtherwarpClickAt);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
                runtime.pestEtherwarpMaintainHeight = true;
                runtime.pestEtherwarpJumpHeld = true;
                clearPestEtherwarpAttempt(client, runtime, false);
                double distance = client.player.distanceTo(currentTarget);
                finishAotvIfClose(client, context, currentTarget, distance, stopDistance);
                return true;
            }
            if (now - runtime.pestEtherwarpClickAt <= ETHERWARP_CONFIRM_TIMEOUT_MS) {
                return true;
            }
            if (runtime.pestEtherwarpLandingBlock != null) {
                runtime.pestEtherwarpFailedBlocksUntil.put(
                        runtime.pestEtherwarpLandingBlock.asLong(),
                        now + ETHERWARP_FAILED_BLOCK_MEMORY_MS);
            }
            runtime.pestEtherwarpRetryAfter = now + ETHERWARP_RETRY_COOLDOWN_MS;
            clearPestEtherwarpAttempt(client, runtime, false);
            return false;
        }

        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            clearPestEtherwarpAttempt(client, runtime, true);
            return false;
        }

        boolean etherwarpSelected = ((AccessorInventory) client.player.getInventory()).getSelected() == etherwarpSlot;
        if (!etherwarpSelected) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, etherwarpSlot));
        }

        Vec3 aimPoint = runtime.pestEtherwarpAimPoint;
        if (context.getAotvAimStartedAt() == 0L) {
            RotationManager.cancelRotation();
            RotationManager.initiateRotation(
                    client, aimPoint, ETHERWARP_AIM_MIN_DURATION_MS, 0.0f,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
            context.setAotvAimStartedAt(now);
            return true;
        }
        if (RotationManager.isRotating()) {
            return true;
        }
        if (!context.isLookingAt(client, aimPoint, ETHERWARP_AIM_TOLERANCE_DEGREES)) {
            RotationManager.cancelRotation();
            RotationManager.initiateRotation(
                    client, aimPoint, ETHERWARP_AIM_MIN_DURATION_MS, 0.0f,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
            context.setAotvAimStartedAt(now);
            return true;
        }
        if (!etherwarpSelected) {
            return true;
        }

        long readyAt = context.getAotvNextUseAt();
        if (readyAt == 0L) {
            long anchor = context.getAotvLastUseAt() == 0L
                    ? context.getStateEnteredAt() : context.getAotvLastUseAt();
            readyAt = anchor + dev.aether.config.ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_AOTV_DELAY_MIN.get(), AetherConfig.PEST_AOTV_DELAY_MAX.get());
            context.setAotvNextUseAt(readyAt);
        }
        if (now < readyAt) {
            return true;
        }

        context.setAotvLastUsePlayerX(client.player.getX());
        context.setAotvLastUsePlayerY(client.player.getY());
        context.setAotvLastUsePlayerZ(client.player.getZ());
        ClientUtils.performUseClick();
        FailsafeManager.addRotationGracePeriod(AOTV_POST_CLICK_GRACE_MS);
        runtime.pestEtherwarpClickAt = now;
        context.setAotvPostClickGraceUntil(now + AOTV_POST_CLICK_GRACE_MS);
        context.setAotvAimStartedAt(0L);
        context.setAotvNextUseAt(0L);
        return true;
    }

    private static Entity findNextPlannedPest(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity currentTarget
    ) {
        java.util.List<Entity> route = PestTargetController.buildPlannedRoute(client, runtime);
        boolean foundCurrent = false;
        for (Entity candidate : route) {
            if (candidate == null || candidate.isRemoved()) {
                continue;
            }
            if (currentTarget != null && candidate.getId() == currentTarget.getId()) {
                foundCurrent = true;
                continue;
            }
            if (foundCurrent || currentTarget == null) {
                return candidate;
            }
        }
        for (Entity candidate : route) {
            if (candidate != null
                    && !candidate.isRemoved()
                    && (currentTarget == null || candidate.getId() != currentTarget.getId())) {
                return candidate;
            }
        }
        return null;
    }

    private static PestEtherwarpCandidate findEtherwarpCandidateNearPest(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity pest,
            Entity nextPest,
            long now
    ) {
        WalkabilityChecker checker = new WalkabilityChecker(client.level);
        Vec3 sneakingEye = EtherwarpHelper.getEyePosition(client, client.player.position());
        int pestBlockX = (int) Math.floor(pest.getX());
        int pestBlockZ = (int) Math.floor(pest.getZ());
        int startY = (int) Math.floor(pest.getY()) - 1;
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };
        runtime.pestEtherwarpFailedBlocksUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        java.util.List<PestEtherwarpCandidate> candidates = new java.util.ArrayList<>();
        for (int[] offset : offsets) {
            int x = pestBlockX + offset[0];
            int z = pestBlockZ + offset[1];
            for (int blockY = startY; blockY >= startY - ETHERWARP_BLOCK_SCAN_DEPTH; blockY--) {
                PathPosition landingFeet = EtherwarpHelper.resolveTargetFeet(checker, x, blockY, z);
                if (landingFeet == null) {
                    continue;
                }
                Vec3 centeredFeet = EtherwarpHelper.getCenteredFeet(landingFeet);
                if (client.player.position().distanceTo(centeredFeet) < ETHERWARP_MIN_TRAVEL_DISTANCE) {
                    break;
                }
                BlockPos landingBlock = EtherwarpHelper.getTargetBlock(landingFeet);
                Long failedUntil = runtime.pestEtherwarpFailedBlocksUntil.get(landingBlock.asLong());
                if (failedUntil != null && failedUntil > now) {
                    continue;
                }
                Vec3 aimPoint = EtherwarpHelper.findVisibleTargetPoint(
                        client, checker, sneakingEye, landingFeet);
                if (aimPoint != null) {
                    candidates.add(new PestEtherwarpCandidate(aimPoint, centeredFeet, landingBlock));
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Vec3 pestPosition = pest.position();
        Vec3 preferredLanding = getPreferredEtherwarpLanding(client, pest);
        if (preferredLanding != null) {
            double bestFrontDistance = candidates.stream()
                    .mapToDouble(candidate -> horizontalDistance(candidate.landingFeet(), preferredLanding))
                    .min()
                    .orElse(Double.MAX_VALUE);
            candidates = candidates.stream()
                    .filter(candidate -> horizontalDistance(candidate.landingFeet(), preferredLanding)
                            <= bestFrontDistance + ETHERWARP_FRONT_MATCH_TOLERANCE)
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        }

        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        if (nextPest == null || nextPest.isRemoved()) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        Vec3 nextPosition = nextPest.position();
        candidates.sort(java.util.Comparator.comparingDouble(candidate ->
                candidate.landingFeet().distanceTo(nextPosition)
                        + candidate.landingFeet().distanceTo(pestPosition) * 0.20));
        double bestScore = candidates.getFirst().landingFeet().distanceTo(nextPosition)
                + candidates.getFirst().landingFeet().distanceTo(pestPosition) * 0.20;
        java.util.List<PestEtherwarpCandidate> nearBest = new java.util.ArrayList<>();
        for (PestEtherwarpCandidate candidate : candidates) {
            double score = candidate.landingFeet().distanceTo(nextPosition)
                    + candidate.landingFeet().distanceTo(pestPosition) * 0.20;
            if (score <= bestScore + 1.25) {
                nearBest.add(candidate);
            }
        }
        return nearBest.get(random.nextInt(nearBest.size()));
    }

    private static Vec3 getPreferredEtherwarpLanding(Minecraft client, Entity pest) {
        double towardPlayerX = client.player.getX() - pest.getX();
        double towardPlayerZ = client.player.getZ() - pest.getZ();
        double horizontalDistance = Math.hypot(towardPlayerX, towardPlayerZ);
        if (horizontalDistance < 1.0e-6) {
            return null;
        }

        return new Vec3(
                pest.getX() + towardPlayerX / horizontalDistance * ETHERWARP_FRONT_OFFSET,
                pest.getY(),
                pest.getZ() + towardPlayerZ / horizontalDistance * ETHERWARP_FRONT_OFFSET);
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static void clearPestEtherwarpAttempt(
            Minecraft client,
            PestDestroyerRuntime runtime,
            boolean clearRetryCooldown
    ) {
        if (client != null && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
            if (!runtime.pestEtherwarpMaintainHeight) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
                runtime.pestEtherwarpJumpHeld = false;
            }
        }
        if (client != null && client.player != null) {
            client.player.setShiftKeyDown(false);
        }
        RotationManager.cancelRotation();
        runtime.pestEtherwarpActive = false;
        runtime.pestEtherwarpTargetEntityId = -1;
        runtime.pestEtherwarpAimPoint = null;
        runtime.pestEtherwarpLandingBlock = null;
        runtime.pestEtherwarpClickAt = 0L;
        runtime.aotvAimStartedAt = 0L;
        runtime.aotvPostClickGraceUntil = 0L;
        if (clearRetryCooldown) {
            runtime.pestEtherwarpRetryAfter = 0L;
        }
    }

    static void updateEtherwarpAltitudeHold(Minecraft client, PestDestroyerRuntime runtime) {
        if (client == null || client.level == null || client.options == null
                || client.player == null || runtime == null) {
            return;
        }
        if (runtime.pestEtherwarpActive) {
            ClientUtils.setKeyMappingState(client.options.keyJump, true);
            runtime.pestEtherwarpJumpHeld = true;
            return;
        }

        boolean canMaintain = runtime.active
                && runtime.pestEtherwarpMaintainHeight
                && AetherConfig.PEST_ETHERWARP_TO_PEST.get()
                && client.player.getAbilities().flying
                && runtime.state != PestDestroyer.State.TELEPORT_TO_PLOT
                && runtime.state != PestDestroyer.State.AOTV_TO_ROOF
                && runtime.state != PestDestroyer.State.AOTV_TO_ROOF_RETURN
                && runtime.state != PestDestroyer.State.AOTV_POST_LOOKDOWN
                && runtime.state != PestDestroyer.State.HUNT_PEST
                && runtime.state != PestDestroyer.State.CHECK_NEXT
                && runtime.state != PestDestroyer.State.BALLSACK_SHREDDER
                && runtime.state != PestDestroyer.State.FINISH
                && runtime.state != PestDestroyer.State.IDLE;
        if (!canMaintain) {
            if (runtime.pestEtherwarpJumpHeld) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
                runtime.pestEtherwarpJumpHeld = false;
            }
            return;
        }

        double clearance = getGroundClearanceBelowPlayer(client);
        if (!Double.isFinite(clearance)) {
            if (runtime.pestEtherwarpJumpHeld) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
                runtime.pestEtherwarpJumpHeld = false;
            }
            return;
        }
        boolean shouldJump = runtime.pestEtherwarpJumpHeld
                ? clearance < ETHERWARP_POST_HOVER_RELEASE_CLEARANCE
                : clearance < ETHERWARP_POST_HOVER_MIN_CLEARANCE;
        ClientUtils.setKeyMappingState(client.options.keyJump, shouldJump);
        runtime.pestEtherwarpJumpHeld = shouldJump;
    }

    private static double getGroundClearanceBelowPlayer(Minecraft client) {
        double feetY = client.player.getY();
        int blockX = (int) Math.floor(client.player.getX());
        int blockZ = (int) Math.floor(client.player.getZ());
        int startY = (int) Math.floor(feetY - 1.0e-4);
        int endY = startY - ETHERWARP_POST_HOVER_GROUND_SCAN_DEPTH;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = startY; y >= endY; y--) {
            pos.set(blockX, y, blockZ);
            var shape = client.level.getBlockState(pos).getCollisionShape(client.level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            double surfaceY = y + shape.bounds().maxY;
            if (surfaceY <= feetY + 1.0e-4) {
                return Math.max(0.0, feetY - surfaceY);
            }
        }
        return Double.NaN;
    }

    private static double getAotvMovedDistance(Minecraft client, Context context) {
        return Math.sqrt(getAotvMovedDistanceSq(client, context));
    }

    private static double getAotvMovedDistanceSq(Minecraft client, Context context) {
        double dx = client.player.getX() - context.getAotvLastUsePlayerX();
        double dy = client.player.getY() - context.getAotvLastUsePlayerY();
        double dz = client.player.getZ() - context.getAotvLastUsePlayerZ();
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    static Vec3 buildCombatAimTarget(Minecraft client, Entity target) {
        if (PestDestroyer.isCatchInProgress()) {
            return target.position().add(0, target.getEyeHeight(target.getPose()), 0);
        }
        if (PestHuntingController.shouldLassoTarget(client, target)) {
            return target.position().add(0, target.getEyeHeight(target.getPose()), 0);
        }
        return PestAimTracker.trackingAim(client, target);
    }

    // high aim point that lets the vacuum beam connect from above
    static Vec3 buildVacuumAimTarget(Minecraft client, Entity target) {
        return buildVacuumAimTarget(
                client, target, getEntityEyePosition(target));
    }

    static Vec3 buildVacuumAimTarget(Minecraft client, Entity target, Vec3 targetEye) {
        Vec3 eyePos = client.player.getEyePosition();
        if (eyePos.y > targetEye.y) {
            double horizontalDistance = Math.sqrt(
                    (targetEye.x - eyePos.x) * (targetEye.x - eyePos.x)
                            + (targetEye.z - eyePos.z) * (targetEye.z - eyePos.z));
            float desiredPitch = getAbovePestPitch(target);
            double targetY = eyePos.y + Math.tan(Math.toRadians(-desiredPitch)) * horizontalDistance;
            return new Vec3(targetEye.x, targetY, targetEye.z);
        }
        return targetEye;
    }

    private static float getAbovePestPitch(Entity target) {
        return PestPitchRange.configured().bucketFor(target.getId());
    }
}
