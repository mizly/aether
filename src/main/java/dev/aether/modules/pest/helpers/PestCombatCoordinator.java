package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

final class PestCombatCoordinator {
    // A completed/aborted fly path used to leave the cleaner motionless for
    // two full seconds before retrying. A short debounce releases keys without
    // making the route visibly pause.
    private static final long STUCK_PATH_RETRY_DELAY_MS = 300L;
    private static final long AOTV_POST_CLICK_GRACE_MS = 250L;
    private static final double AOTV_CONFIRM_DISTANCE = 2.0;
    private static final double AOTV_CONFIRM_DISTANCE_SQ = AOTV_CONFIRM_DISTANCE * AOTV_CONFIRM_DISTANCE;
    private static final float AOTV_AIM_TOLERANCE_DEGREES = 2.0f;
    // A pest that never settles inside the tight tolerance must not stall the hop chain.
    private static final long AOTV_AIM_SETTLE_TIMEOUT_MS = 1_000L;
    private static final float AOTV_AIM_FALLBACK_TOLERANCE_DEGREES = 6.0f;
    private static final float AOTV_AIM_SMOOTHING_MS = 110.0f;
    // Slower than the hunt's: nothing here closes in a two-tick window, so the
    // cleaner can take a human beat to swing between targets.
    private static final float COMBAT_AIM_SMOOTHING_MS = 150.0f;
    private static final double POST_AOTV_LOOK_DOWN_HORIZONTAL_DISTANCE = 3.0;
    private static final double VACUUM_REAPPROACH_BUFFER = 6.0;
    private static final double TARGET_REACQUIRE_CONE_DEGREES = 120.0;
    private static final double S_BRAKE_ENTER_DISTANCE = 2.0;
    private static final double S_BRAKE_EXIT_DISTANCE = 4.0;
    private static final double S_BRAKE_MIN_SPEED = 0.20;
    private static final double KILL_FORWARD_HOLD_DISTANCE = 5.0;
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
        default long getFlyRetryAfterUnflyAt() { return runtime().flyRetryAfterUnflyAt; }
        default void setFlyRetryAfterUnflyAt(long value) { runtime().flyRetryAfterUnflyAt = value; }
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

        if (dist <= targetReachDistance) {
            PathfindingManager.stop();
            context.setState(PestDestroyer.State.APPROACH_PEST);
            return;
        }

        if (!PathfindingManager.isNavigating()) {
            long now = System.currentTimeMillis();
            if (context.getFlyRetryAfterUnflyAt() > now) {
                return;
            }
            // A failed or partial fly route used to sit idle for twenty ticks
            // before it was even scheduled again. Retry immediately with only
            // a short debounce to avoid hammering an unloaded world.
            ClientUtils.sendDebugMessage("[PestDestroyer] Fly route ended before reaching pest. Repathing now.");
            context.setStuckTicks(0);
            context.setFlyRetryAfterUnflyAt(now + STUCK_PATH_RETRY_DELAY_MS);
            context.startPathToPest(client, currentTarget);
            return;
        } else {
            context.setStuckTicks(0);
        }

        if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Fly-to-pest timed out. Checking for next pest.");
            PathfindingManager.stop();
            context.deferTarget(currentTarget);
            context.setState(PestDestroyer.State.CHECK_NEXT);
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

        double terminalRange = PestHuntingController.handoffRange(
                client, currentTarget, targetReachDistance);
        if (dist <= terminalRange
                && !PestHuntingController.shouldLassoTarget(client, currentTarget)
                && !FailsafeManager.shouldSuppressPestCleanerRotation(client)
                && shouldRotateForCombatAim(context, client, currentTarget)) {
            Vec3 targetEye = buildCombatAimTarget(client, currentTarget);
            RotationManager.trackRotation(
                    client,
                    targetEye,
                    COMBAT_AIM_SMOOTHING_MS,
                    AetherConfig.PEST_MAX_TURN_SPEED.get());
        }

        if (dist <= terminalRange) {
            context.beginTerminalState(client);
            return;
        }

        if (!PathfindingManager.isNavigating()) {
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
            if (currentTarget != null && (currentTarget.isRemoved() || (currentTarget instanceof LivingEntity le2 && le2.isDeadOrDying()))) {
                if (context.recordTrackedPestKill(client, currentTarget)) {
                    return;
                }
            }
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        if (client.player == null) {
            return;
        }

        // If we pass the pest, it can remain inside the vacuum re-approach
        // buffer while sitting behind us. This is an orientation problem, not
        // a navigation problem: a path to a nearby target can complete without
        // moving and bounce KILL_PEST <-> APPROACH_PEST forever.
        if (isOutsideForwardCone(client, currentTarget, TARGET_REACQUIRE_CONE_DEGREES)) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            PathfindingManager.stop();
            context.setTargetWithoutSkullTicks(0);
            if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
                RotationManager.trackRotation(
                        client,
                        buildCombatAimTarget(client, currentTarget),
                        COMBAT_AIM_SMOOTHING_MS,
                        AetherConfig.PEST_MAX_TURN_SPEED.get());
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] Target moved behind forward cone. Turning to reacquire.");
            return;
        }

        double dist = client.player.distanceTo(currentTarget);
        if (context.getVacuumSlot() == -1) {
            context.setVacuumSlot(context.findVacuumHotbarSlot(client));
        }
        if (context.getVacuumSlot() != -1
                && ((AccessorInventory) client.player.getInventory()).getSelected() != context.getVacuumSlot()) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()));
            return;
        }

        if (dist <= context.getVacuumRange()) {
            boolean retryingUse =
                    context.shouldTemporarilyReleaseKillVacuum(client, true, true);
            ClientUtils.setKeyMappingState(client.options.keyUse, !retryingUse);
            ClientUtils.setKeyMappingState(client.options.keyUp, dist > KILL_FORWARD_HOLD_DISTANCE);

            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop();
            }

            if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)
                    && shouldRotateForCombatAim(context, client, currentTarget)) {
                Vec3 targetEye = buildCombatAimTarget(client, currentTarget);
                RotationManager.trackRotation(
                        client,
                        targetEye,
                        COMBAT_AIM_SMOOTHING_MS,
                        AetherConfig.PEST_MAX_TURN_SPEED.get());
            }

            double speed = Math.abs(client.player.getDeltaMovement().x)
                    + Math.abs(client.player.getDeltaMovement().z);
            boolean braking = client.options.keyDown.isDown();
            boolean shouldBrake = (braking ? dist < S_BRAKE_EXIT_DISTANCE : dist < S_BRAKE_ENTER_DISTANCE)
                    && speed > S_BRAKE_MIN_SPEED;
            ClientUtils.setKeyMappingState(client.options.keyDown, shouldBrake);

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
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            context.setTargetWithoutSkullTicks(0);
            ClientUtils.setKeyMappingState(client.options.keyUp, dist > KILL_FORWARD_HOLD_DISTANCE);
            if (dist > context.getVacuumRange() + VACUUM_REAPPROACH_BUFFER) {
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

        double stopDistance = aotvRange * aotvGapMultiplier;
        double dist = client.player.distanceTo(currentTarget);
        if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
            return;
        }

        Vec3 aimPos = getEntityEyePosition(currentTarget);

        // If we're below the current target and don't have line-of-sight to it, try
        // to gain vision first (avoid firing AOTV blindly).
        Vec3 currentTargetPos = currentTarget.position().add(0, currentTarget.getEyeHeight(currentTarget.getPose()), 0);
        if (client.player.getY() < currentTargetPos.y && !ClientUtils.hasLineOfSight(client.player, currentTargetPos)) {
            ClientUtils.sendDebugMessage("[PestDestroyer] No LOS and below pest (" + currentTarget.getDisplayName().getString() + "), flying up for vision...");
            ClientUtils.setKeyMappingState(client.options.keyJump, true);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.setKeyMappingState(client.options.keySprint, false);
            return;
        } else {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
        }

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
                    client, aimPos, AOTV_AIM_SMOOTHING_MS, AetherConfig.PEST_MAX_TURN_SPEED.get());

            float tolerance = now - context.getAotvAimStartedAt() > AOTV_AIM_SETTLE_TIMEOUT_MS
                    ? AOTV_AIM_FALLBACK_TOLERANCE_DEGREES
                    : AOTV_AIM_TOLERANCE_DEGREES;
            if (!context.isLookingAt(client, aimPos, tolerance)) {
                return;
            }
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

    private static void clearAotvBetweenPests(Minecraft client, Context context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        RotationManager.cancelRotation();
        context.setAotvSlot(-1);
        context.setAotvUseCount(0);
        context.setAotvLastUseAt(0L);
        context.setAotvNextUseAt(0L);
        context.setAotvPostClickGraceUntil(0L);
        context.setAotvPendingUseAt(0L);
        context.setAotvAimStartedAt(0L);
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

    private static boolean shouldRotateForCombatAim(Context context, Minecraft client, Entity target) {
        if (!context.didArriveAtCurrentTargetViaAotv()) {
            return true;
        }

        double dx = client.player.getX() - target.getX();
        double dz = client.player.getZ() - target.getZ();
        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));
        return horizontalDistance <= POST_AOTV_LOOK_DOWN_HORIZONTAL_DISTANCE;
    }

    static Vec3 buildCombatAimTarget(Minecraft client, Entity target) {
        if (PestDestroyer.isCatchInProgress()) {
            return target.position().add(0, target.getEyeHeight(target.getPose()), 0);
        }
        if (PestHuntingController.shouldLassoTarget(client, target)) {
            return target.position().add(0, target.getEyeHeight(target.getPose()), 0);
        }
        return buildVacuumAimTarget(client, target);
    }

    /** Builds the high aim point that lets the vacuum beam connect from above. */
    static Vec3 buildVacuumAimTarget(Minecraft client, Entity target) {
        Vec3 eyePos = client.player.getEyePosition();
        Vec3 targetEye = target.position().add(0, target.getEyeHeight(target.getPose()), 0);
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
