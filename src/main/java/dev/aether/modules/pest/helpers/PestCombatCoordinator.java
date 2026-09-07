package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
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
import net.minecraft.world.phys.shapes.VoxelShape;

final class PestCombatCoordinator {
    // A completed/aborted fly path used to leave the cleaner motionless for
    // two full seconds before retrying. A short debounce releases keys without
    // making the route visibly pause.
    private static final long STUCK_PATH_RETRY_DELAY_MS = 300L;
    private static final long AOTV_POST_CLICK_GRACE_MS = 250L;
    private static final double AOTV_CONFIRM_DISTANCE = 2.0;
    private static final double AOTV_CONFIRM_DISTANCE_SQ = AOTV_CONFIRM_DISTANCE * AOTV_CONFIRM_DISTANCE;
    // AOTV movement uses a one-shot eased turn instead of live target tracking.
    // This is intentionally a little forgiving because a pest can move while the
    // camera is turning; re-tracking every tick caused visible jitter and stalls.
    private static final float AOTV_AIM_TOLERANCE_DEGREES = 8.0f;
    private static final long AOTV_AIM_MIN_DURATION_MS = 150L;
    private static final float ETHERWARP_AIM_TOLERANCE_DEGREES = 2.0f;
    private static final long ETHERWARP_CONFIRM_TIMEOUT_MS = 900L;
    private static final long ETHERWARP_RETRY_COOLDOWN_MS = 1500L;
    private static final long ETHERWARP_FAILED_BLOCK_MEMORY_MS = 15_000L;
    private static final int ETHERWARP_BLOCK_SCAN_DEPTH = 20;
    private static final double ETHERWARP_MIN_TRAVEL_DISTANCE = 3.0;
    private static final double ETHERWARP_POST_HOVER_MIN_CLEARANCE = 3.0;
    private static final double ETHERWARP_POST_HOVER_RELEASE_CLEARANCE = 3.35;
    private static final int ETHERWARP_POST_HOVER_GROUND_SCAN_DEPTH = 32;
    // Slower than the hunt's: nothing here closes in a two-tick window, so the
    // cleaner can take a human beat to swing between targets.
    private static final float COMBAT_AIM_SMOOTHING_MS = 190.0f;
    // Normal vacuum tracking deliberately samples/blends the target instead of
    // feeding the exact entity position into the rotation manager every tick.
    // A small angular deadzone also prevents constant micro-corrections while
    // the pest is already comfortably inside the vacuum aim cone.
    private static final long COMBAT_AIM_REFRESH_MS = 80L;
    private static final double COMBAT_AIM_BLEND = 0.55;
    private static final float COMBAT_AIM_CORRECTION_TRIGGER_DEGREES = 2.75f;
    private static final float COMBAT_AIM_SETTLE_DEGREES = 1.25f;
    private static final double VACUUM_REAPPROACH_BUFFER = 6.0;
    private static final double TARGET_REACQUIRE_CONE_DEGREES = 120.0;
    private static final double S_BRAKE_ENTER_DISTANCE = 2.0;
    private static final double S_BRAKE_EXIT_DISTANCE = 4.0;
    private static final double S_BRAKE_MIN_SPEED = 0.20;
    private static final double KILL_FORWARD_HOLD_DISTANCE = 5.0;
    // When a pest jumps almost straight above the player, horizontal
    // pathfinding is counterproductive. Stay underneath it and climb smoothly
    // until vacuum range is restored instead of repathing to an airborne Y.
    private static final double VERTICAL_ESCAPE_TRIGGER_GAP = 2.5;
    private static final double VERTICAL_ESCAPE_EXIT_GAP = 1.25;
    private static final double VERTICAL_ESCAPE_HORIZONTAL_RANGE_BUFFER = 2.0;
    private static final double VERTICAL_ESCAPE_EXIT_HORIZONTAL_BUFFER = 4.0;
    private static final long VERTICAL_ESCAPE_AIM_REFRESH_MS = 100L;
    private static final double VERTICAL_ESCAPE_AIM_BLEND = 0.45;
    private static final float VERTICAL_ESCAPE_AIM_SMOOTHING_MS = 240.0f;
    private static final long ONE_TAP_ASSUME_KILL_MS = 500L;
    private static final double ONE_TAP_POPUP_HORIZONTAL_RADIUS = 3.0;
    private static final double ONE_TAP_POPUP_MIN_Y_OFFSET = -1.0;
    private static final double ONE_TAP_POPUP_MAX_Y_OFFSET = 5.0;
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
            if (context.runtime().lockedTargetEntityId == currentTarget.getId()) {
                context.runtime().lockedTargetEntityId = -1;
            }
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
        double approachHorizontalDist = Math.hypot(
                currentTarget.getX() - client.player.getX(),
                currentTarget.getZ() - client.player.getZ());
        double approachVerticalGap = getEntityEyePosition(currentTarget).y - client.player.getEyePosition().y;
        if (!PestHuntingController.shouldLassoTarget(client, currentTarget)
                && client.player.getAbilities().flying
                && approachHorizontalDist <= terminalRange
                && approachVerticalGap > VERTICAL_ESCAPE_TRIGGER_GAP) {
            PathfindingManager.stop(false);
            context.beginTerminalState(client);
            return;
        }
        if (dist <= terminalRange
                && !PestHuntingController.shouldLassoTarget(client, currentTarget)
                && !FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            maintainStableCombatAim(client, context, currentTarget);
        }

        if (dist <= terminalRange) {
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
            if (!context.runtime().pestEtherwarpMaintainHeight) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            }
            if (currentTarget != null && (currentTarget.isRemoved() || (currentTarget instanceof LivingEntity le2 && le2.isDeadOrDying()))) {
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

        // Detect a vertical escape before the normal forward-cone and death
        // heuristics. A jumping pest can move far above its marker for a few
        // ticks; treating that as a normal combat frame causes three systems
        // to fight at once (reacquire rotation, re-approach pathing, and
        // marker-missing target handoff).
        double dist = client.player.distanceTo(currentTarget);
        double horizontalDist = Math.hypot(
                currentTarget.getX() - client.player.getX(),
                currentTarget.getZ() - client.player.getZ());
        double verticalGap = getEntityEyePosition(currentTarget).y - client.player.getEyePosition().y;
        PestDestroyerRuntime runtime = context.runtime();

        boolean sameAirborneTarget = runtime.airborneRecoveryActive
                && runtime.airborneRecoveryTargetEntityId == currentTarget.getId();
        boolean shouldEnterAirborneRecovery = client.player.getAbilities().flying
                && verticalGap > VERTICAL_ESCAPE_TRIGGER_GAP
                && horizontalDist <= context.getVacuumRange() + VERTICAL_ESCAPE_HORIZONTAL_RANGE_BUFFER;

        if (!sameAirborneTarget && shouldEnterAirborneRecovery) {
            runtime.airborneRecoveryActive = true;
            runtime.airborneRecoveryTargetEntityId = currentTarget.getId();
            runtime.airborneRecoveryAimPoint = getEntityEyePosition(currentTarget);
            runtime.airborneRecoveryAimUpdatedAt = System.currentTimeMillis();
            context.setTargetWithoutSkullTicks(0);
            runtime.oneTapVacuumNearStartedAt = 0L;
            PathfindingManager.stop(false);
            RotationManager.cancelRotation();
            sameAirborneTarget = true;
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Pest jumped vertically. Locking airborne recovery on target "
                            + currentTarget.getId() + ".");
        }

        if (sameAirborneTarget) {
            boolean keepRecovering = client.player.getAbilities().flying
                    && verticalGap > VERTICAL_ESCAPE_EXIT_GAP
                    && horizontalDist <= context.getVacuumRange() + VERTICAL_ESCAPE_EXIT_HORIZONTAL_BUFFER;
            if (keepRecovering) {
                handleAirbornePestRecovery(client, context, currentTarget, dist, verticalGap);
                return;
            }

            runtime.resetAirborneRecovery();
            context.setTargetWithoutSkullTicks(0);
            if (!runtime.pestEtherwarpMaintainHeight) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            }
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Airborne pest stabilized. Resuming normal combat.");
        }

        // If we pass the pest, it can remain inside the vacuum re-approach
        // buffer while sitting behind us. This is an orientation problem, not
        // a navigation problem: a path to a nearby target can complete without
        // moving and bounce KILL_PEST <-> APPROACH_PEST forever.
        if (isOutsideForwardCone(client, currentTarget, TARGET_REACQUIRE_CONE_DEGREES)) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            PathfindingManager.stop(false);
            context.setTargetWithoutSkullTicks(0);
            if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
                maintainStableCombatAim(client, context, currentTarget);
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] Target moved behind forward cone. Turning to reacquire.");
            return;
        }

        boolean verticalEscape = false;

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
            ClientUtils.setKeyMappingState(client.options.keyUse, !retryingUse);
            ClientUtils.setKeyMappingState(
                    client.options.keyUp,
                    !verticalEscape && dist > KILL_FORWARD_HOLD_DISTANCE);

            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop();
            }

            if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
                maintainStableCombatAim(client, context, currentTarget);
            }

            boolean hasPestMarker = context.hasPestSkullMarkerForTarget(client, currentTarget);
            if (AetherConfig.PEST_ONE_TAP_PESTS.get()) {
                if (runtime.oneTapTargetEntityId != currentTarget.getId()) {
                    prepareOneTapTarget(client, runtime, currentTarget);
                }

                long now = System.currentTimeMillis();
                float oneTapAimTolerance = Math.max(12.0f, AetherConfig.PEST_FOV_RANGE.get());
                boolean vacuumHeldNearTarget = !retryingUse
                        && context.isLookingAt(client, buildCombatAimTarget(client, currentTarget), oneTapAimTolerance);
                if (vacuumHeldNearTarget) {
                    if (runtime.oneTapVacuumNearStartedAt == 0L) {
                        runtime.oneTapVacuumNearStartedAt = now;
                    }

                    long heldMs = now - runtime.oneTapVacuumNearStartedAt;
                    boolean damagePopup = hasNewNamedDamagePopupNearTarget(client, runtime, currentTarget);
                    int confidence = 0;
                    if (damagePopup) {
                        confidence += 100;
                    }
                    if (!hasPestMarker) {
                        confidence += 65;
                    }
                    if (heldMs >= ONE_TAP_ASSUME_KILL_MS) {
                        // Preserve the requested half-second One Tap fallback.
                        confidence += 100;
                    } else if (heldMs >= 350L) {
                        confidence += 40;
                    } else if (heldMs >= 200L) {
                        confidence += 20;
                    }

                    if (confidence >= 100) {
                        String reason;
                        if (damagePopup) {
                            reason = "damage popup confirmed the hit";
                        } else if (!hasPestMarker && heldMs < ONE_TAP_ASSUME_KILL_MS) {
                            reason = "vacuum hold + disappearing pest marker reached kill confidence";
                        } else {
                            reason = "vacuum held near target for 0.5s";
                        }
                        if (completeAssumedOneTapKill(client, context, currentTarget, reason)) {
                            return;
                        }
                    }
                } else {
                    runtime.oneTapVacuumNearStartedAt = 0L;
                }
            }

            double speed = Math.abs(client.player.getDeltaMovement().x)
                    + Math.abs(client.player.getDeltaMovement().z);
            boolean braking = client.options.keyDown.isDown();
            boolean shouldBrake = !verticalEscape
                    && (braking ? dist < S_BRAKE_EXIT_DISTANCE : dist < S_BRAKE_ENTER_DISTANCE)
                    && speed > S_BRAKE_MIN_SPEED;
            ClientUtils.setKeyMappingState(client.options.keyDown, shouldBrake);

            if (!hasPestMarker) {
                context.setTargetWithoutSkullTicks(context.getTargetWithoutSkullTicks() + 1);
                if (context.getTargetWithoutSkullTicks() >= skullMissingConfirmTicks) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    ClientUtils.setKeyMappingState(client.options.keyDown, false);
                    if (!context.runtime().pestEtherwarpMaintainHeight) {
                        ClientUtils.setKeyMappingState(client.options.keyJump, false);
                    }
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
            context.runtime().oneTapVacuumNearStartedAt = 0L;
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            context.setTargetWithoutSkullTicks(0);
            ClientUtils.setKeyMappingState(
                    client.options.keyUp,
                    !verticalEscape && dist > KILL_FORWARD_HOLD_DISTANCE);
            if (!verticalEscape && dist > context.getVacuumRange() + VACUUM_REAPPROACH_BUFFER) {
                context.setState(PestDestroyer.State.APPROACH_PEST);
                return;
            }
        }

        if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            if (!context.runtime().pestEtherwarpMaintainHeight) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            }
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

        double stopDistance = AetherConfig.PEST_SMART_AOTV_ROUTING.get()
                ? PestTargetController.getAotvStopDistance(
                        client, currentTarget, context.getVacuumRange())
                : aotvRange * aotvGapMultiplier;
        double dist = client.player.distanceTo(currentTarget);
        if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
            return;
        }

        // Prefer one precise Etherwarp onto a random safe block 1-2 blocks
        // around the pest. If no valid/visible landing exists, fall through to
        // the existing balanced AOTV route unchanged.
        if ((AetherConfig.PEST_ETHERWARP_TO_PEST.get() || context.runtime().pestEtherwarpActive)
                && handleEtherwarpNearPest(client, context, currentTarget, stopDistance, now)) {
            return;
        }

        Vec3 currentTargetPos = getEntityEyePosition(currentTarget);
        if (client.player.getY() < currentTargetPos.y && !ClientUtils.hasLineOfSight(client.player, currentTargetPos)) {
            ClientUtils.sendDebugMessage("[PestDestroyer] No LOS and below pest (" + currentTarget.getDisplayName().getString() + "), flying up for vision...");
            ClientUtils.setKeyMappingState(client.options.keyJump, true);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.setKeyMappingState(client.options.keySprint, false);
            return;
        } else {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
        }

        boolean aotvSelected = context.getAotvSlot() != -1
                && ((AccessorInventory) client.player.getInventory()).getSelected() == context.getAotvSlot();
        if (!aotvSelected) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getAotvSlot()));
        }

        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);

        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            RotationManager.cancelRotation();
            context.setAotvAimStartedAt(0L);
            return;
        }

        // Confirm the previous click before beginning the next camera turn. Doing
        // this after aiming made a successful teleport look like a failed aim and
        // could leave the cleaner staring at the pest without using the AOTV.
        if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get() && context.getAotvPendingUseAt() != 0L) {
            double movedDistance = getAotvMovedDistance(client, context);
            if (movedDistance >= AOTV_CONFIRM_DISTANCE) {
                context.setAotvLastUseAt(context.getAotvPendingUseAt());
                context.setAotvPendingUseAt(0L);
                context.setAotvPostClickGraceUntil(0L);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
                context.setAotvAimStartedAt(0L);
                RotationManager.cancelRotation();
                ClientUtils.sendDebugMessage("[PestDestroyer] AOTV confirmed by movement: "
                                + String.format("%.2f", movedDistance) + " blocks.");
                dist = client.player.distanceTo(currentTarget);
                if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                    return;
                }
            } else if (context.getAotvPostClickGraceUntil() > now) {
                return;
            } else {
                ClientUtils.sendDebugMessage("[PestDestroyer] AOTV confirm failed: moved "
                                + String.format("%.2f", movedDistance)
                                + "/" + String.format("%.2f", AOTV_CONFIRM_DISTANCE) + " blocks. Retrying.");
                context.setAotvPendingUseAt(0L);
                context.setAotvPostClickGraceUntil(0L);
                context.setAotvAimStartedAt(0L);
            }
        } else if (context.getAotvPostClickGraceUntil() > now) {
            double movedDistanceSq = getAotvMovedDistanceSq(client, context);
            if (movedDistanceSq <= AOTV_CONFIRM_DISTANCE_SQ) {
                return;
            }
            context.setAotvPostClickGraceUntil(0L);
            context.setAotvAimStartedAt(0L);
            RotationManager.cancelRotation();
            dist = client.player.distanceTo(currentTarget);
            if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                return;
            }
        }

        Vec3 aimPos = getEntityEyePosition(currentTarget);

        // One snapshot, one eased turn. Do NOT chase the moving pest every tick:
        // live tracking adds tiny corrections/noise that look jittery and forces
        // the click gate to keep re-checking alignment. The configured Pest Turn
        // Speed Limit keeps this turn readable without sacrificing route speed.
        if (context.getAotvAimStartedAt() == 0L) {
            RotationManager.cancelRotation();
            RotationManager.initiateRotation(
                    client,
                    aimPos,
                    AOTV_AIM_MIN_DURATION_MS,
                    0.0f,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
            context.setAotvAimStartedAt(now);
            return;
        }

        if (RotationManager.isRotating()) {
            return;
        }

        // Only correct again if the pest moved far enough that the completed
        // snapshot turn is no longer reasonably pointed toward it. This is a
        // one-shot correction, not continuous tracking.
        aimPos = getEntityEyePosition(currentTarget);
        if (!context.isLookingAt(client, aimPos, AOTV_AIM_TOLERANCE_DEGREES)) {
            RotationManager.cancelRotation();
            RotationManager.initiateRotation(
                    client,
                    aimPos,
                    AOTV_AIM_MIN_DURATION_MS,
                    0.0f,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
            context.setAotvAimStartedAt(now);
            return;
        }

        // The hotbar swap can land a tick after the turn finishes. Never click
        // until the AOTV is actually selected, but don't add another delay.
        if (!aotvSelected) {
            return;
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
            } else {
                context.setAotvLastUseAt(now);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
            }
            context.setAotvNextUseAt(0L);
            context.setAotvAimStartedAt(0L);
        }

        if (context.getAotvUseCount() > 10) {
            clearAotvBetweenPests(client, context);
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV usage exceeded maximum. Falling back to pathfinding.");
            context.startPathToPest(client, currentTarget);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
        }
    }

    private record PestEtherwarpCandidate(Vec3 aimPoint, Vec3 landingFeet, BlockPos landingBlock) {
    }

    /**
     * Tries a direct Etherwarp to a random safe block 1-2 blocks around the pest.
     * The chosen point is locked for the duration of the attempt so a moving pest
     * cannot make the camera jitter between neighboring landing blocks.
     */
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
            double minEtherwarpDistance = AetherConfig.PEST_ETHERWARP_MIN_DISTANCE.get();
            if (pestDistance < minEtherwarpDistance) {
                return false;
            }

            int etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client);
            if (etherwarpSlot < 0) {
                return false;
            }

            Entity nextRoutePest = findNextPlannedPest(client, runtime, currentTarget);
            PestEtherwarpCandidate candidate = findEtherwarpCandidateNearPest(
                    client, runtime, currentTarget, nextRoutePest, now);
            if (candidate == null) {
                return false;
            }

            runtime.pestEtherwarpActive = true;
            runtime.pestEtherwarpTargetEntityId = currentTarget.getId();
            runtime.pestEtherwarpAimPoint = candidate.aimPoint();
            runtime.pestEtherwarpLandingBlock = candidate.landingBlock();
            runtime.pestEtherwarpClickAt = 0L;
            context.setAotvSlot(etherwarpSlot);
            context.setAotvAimStartedAt(0L);
            RotationManager.cancelRotation();
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Direct Etherwarp available near pest at "
                            + String.format("%.1f, %.1f, %.1f",
                            candidate.landingFeet().x,
                            candidate.landingFeet().y,
                            candidate.landingFeet().z)
                            + ". Pest is " + String.format("%.1f", pestDistance)
                            + " blocks away (minimum " + String.format("%.1f", minEtherwarpDistance)
                            + "). Preferring Etherwarp over AOTV hops"
                            + (nextRoutePest != null ? " with a next-pest-aware landing." : "."));
        }

        int etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client);
        if (etherwarpSlot < 0 || runtime.pestEtherwarpAimPoint == null) {
            clearPestEtherwarpAttempt(client, runtime, true);
            return false;
        }
        context.setAotvSlot(etherwarpSlot);

        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        // Etherwarp requires crouching. While flying, hold jump at the same
        // time so crouch does not pull the player downward during the aim/swap
        // window. This follows the same KeyMapping state path used elsewhere
        // by Aether's pest movement logic.
        ClientUtils.setKeyMappingState(client.options.keyJump, true);
        ClientUtils.setKeyMappingState(client.options.keyShift, true);
        client.player.setShiftKeyDown(true);

        // Once clicked, hold sneak until movement confirms the Etherwarp. This
        // mirrors the existing Etherwarp executor and avoids releasing sneak
        // before the server has processed the use action.
        if (runtime.pestEtherwarpClickAt != 0L) {
            double movedDistance = getAotvMovedDistance(client, context);
            if (movedDistance >= AOTV_CONFIRM_DISTANCE) {
                long clickedAt = runtime.pestEtherwarpClickAt;
                context.setAotvLastUseAt(clickedAt);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
                context.setAotvNextUseAt(0L);
                runtime.pestEtherwarpRetryAfter = 0L;
                // Keep flying upward after the landing until we regain a
                // reusable Etherwarp-ready hover height. The per-tick altitude
                // controller keeps this active through the pest kill / handoff.
                runtime.pestEtherwarpMaintainHeight = true;
                runtime.pestEtherwarpJumpHeld = true;
                clearPestEtherwarpAttempt(client, runtime, false);

                double dist = client.player.distanceTo(currentTarget);
                ClientUtils.sendDebugMessage(
                        "[PestDestroyer] Etherwarp near pest confirmed (moved "
                                + String.format("%.1f", movedDistance) + " blocks).");
                finishAotvIfClose(client, context, currentTarget, dist, stopDistance);
                return true;
            }

            if (now - runtime.pestEtherwarpClickAt <= ETHERWARP_CONFIRM_TIMEOUT_MS) {
                return true;
            }

            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Etherwarp near pest did not move the player. Falling back to AOTV temporarily.");
            if (runtime.pestEtherwarpLandingBlock != null) {
                runtime.pestEtherwarpFailedBlocksUntil.put(
                        runtime.pestEtherwarpLandingBlock.asLong(),
                        now + ETHERWARP_FAILED_BLOCK_MEMORY_MS);
                ClientUtils.sendDebugMessage(
                        "[PestDestroyer] Remembering failed Etherwarp block "
                                + runtime.pestEtherwarpLandingBlock + " for 15s.");
            }
            runtime.pestEtherwarpRetryAfter = now + ETHERWARP_RETRY_COOLDOWN_MS;
            clearPestEtherwarpAttempt(client, runtime, false);
            return true;
        }

        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            clearPestEtherwarpAttempt(client, runtime, true);
            return true;
        }

        boolean etherwarpSelected = ((AccessorInventory) client.player.getInventory()).getSelected() == etherwarpSlot;
        if (!etherwarpSelected) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, etherwarpSlot));
        }

        Vec3 aimPoint = runtime.pestEtherwarpAimPoint;
        if (context.getAotvAimStartedAt() == 0L) {
            RotationManager.cancelRotation();
            RotationManager.initiateRotation(
                    client,
                    aimPoint,
                    AOTV_AIM_MIN_DURATION_MS,
                    0.0f,
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
                    client,
                    aimPoint,
                    AOTV_AIM_MIN_DURATION_MS,
                    0.0f,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
            context.setAotvAimStartedAt(now);
            return true;
        }

        if (!etherwarpSelected) {
            return true;
        }

        long readyAt = context.getAotvNextUseAt();
        if (readyAt == 0L) {
            long anchorTime = context.getAotvLastUseAt() == 0L
                    ? context.getStateEnteredAt()
                    : context.getAotvLastUseAt();
            readyAt = anchorTime + dev.aether.config.ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_AOTV_DELAY_MIN.get(),
                    AetherConfig.PEST_AOTV_DELAY_MAX.get());
            context.setAotvNextUseAt(readyAt);
        }

        if (now < readyAt) {
            return true;
        }

        ClientUtils.sendDebugMessage("[PestDestroyer] Etherwarping to a nearby block around current pest.");
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
        // The route normally begins with the committed/current target. If an
        // entity refresh caused it not to, use the first different pest as the
        // best available estimate for the next handoff.
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
        if (client == null || client.player == null || client.level == null || pest == null) {
            return null;
        }

        WalkabilityChecker checker = new WalkabilityChecker(client.level);
        Vec3 sneakingEye = EtherwarpHelper.getEyePosition(client, client.player.position());
        int pestBlockX = (int) Math.floor(pest.getX());
        int pestBlockZ = (int) Math.floor(pest.getZ());
        int startY = (int) Math.floor(pest.getY()) - 1;

        // Candidate columns form a horizontal ring 1-2 blocks around the pest.
        // Collect every safe/visible candidate first so the current landing can
        // also prepare the player for the likely next pest.
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

            // Keep only the highest usable landing in each nearby column.
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

        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        if (nextPest == null || nextPest.isRemoved()) {
            // No future target to optimize around: retain a little variation.
            return candidates.get(random.nextInt(candidates.size()));
        }

        Vec3 nextPosition = nextPest.position();
        Vec3 pestPosition = pest.position();
        candidates.sort(java.util.Comparator.comparingDouble(candidate -> {
            // The next-pest term dominates. A small current-pest term avoids an
            // awkward outer-ring landing for a negligible future gain.
            double nextCost = candidate.landingFeet().distanceTo(nextPosition);
            double currentCost = candidate.landingFeet().distanceTo(pestPosition);
            return nextCost + currentCost * 0.20;
        }));

        PestEtherwarpCandidate best = candidates.getFirst();
        double bestScore = best.landingFeet().distanceTo(nextPosition)
                + best.landingFeet().distanceTo(pestPosition) * 0.20;
        java.util.List<PestEtherwarpCandidate> nearBest = new java.util.ArrayList<>();
        for (PestEtherwarpCandidate candidate : candidates) {
            double score = candidate.landingFeet().distanceTo(nextPosition)
                    + candidate.landingFeet().distanceTo(pestPosition) * 0.20;
            // Preserve slight natural variation, but never choose a landing that
            // meaningfully worsens the next leg.
            if (score <= bestScore + 1.25) {
                nearBest.add(candidate);
            }
        }

        PestEtherwarpCandidate selected = nearBest.get(random.nextInt(nearBest.size()));
        ClientUtils.sendDebugMessage(
                "[PestDestroyer] Etherwarp landing prepared toward next pest "
                        + nextPest.getId() + " ("
                        + String.format("%.1f", selected.landingFeet().distanceTo(nextPosition))
                        + " blocks from next target after landing).");
        return selected;
    }

    private static void clearPestEtherwarpAttempt(
            Minecraft client,
            PestDestroyerRuntime runtime,
            boolean clearRetryCooldown
    ) {
        if (client != null && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
            // A successful Etherwarp intentionally leaves jump held so the
            // altitude controller can climb back to its hover floor. Failed or
            // cancelled attempts release it immediately unless a prior warp is
            // already maintaining altitude.
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

    /**
     * Keeps the player Etherwarp-ready after a successful direct pest warp.
     * During an active Etherwarp attempt jump must remain held alongside sneak;
     * afterwards jump is only held while the player's feet are below the
     * configured 3-block clearance over the collision surface underneath them.
     * A small release hysteresis prevents rapid key flicker around the boundary.
     */
    static void updateEtherwarpAltitudeHold(
            Minecraft client,
            PestDestroyerRuntime runtime
    ) {
        if (client == null || client.options == null || client.player == null || runtime == null) {
            return;
        }

        // The Etherwarp setup itself always wins: crouch + jump together keeps
        // flight altitude neutral while the camera lines up with the block.
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
                // CHECK_NEXT may be the stationary final verification scan.
                // Do not let the post-Etherwarp hover helper re-press jump
                // after the scan deliberately released all movement keys.
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
            // Do not climb indefinitely over an unloaded/very deep void column.
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
            VoxelShape shape = client.level.getBlockState(pos).getCollisionShape(client.level, pos);
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

    private static boolean finishAotvIfClose(
            Minecraft client,
            Context context,
            Entity currentTarget,
            double dist,
            double stopDistance
    ) {
        boolean closeEnough = dist <= stopDistance;
        boolean anotherHopWorthwhile = PestTargetController.shouldUseAotvBetweenPests(
                client, currentTarget, context.getVacuumRange());
        if (!closeEnough && anotherHopWorthwhile) {
            return false;
        }

        boolean arrivedViaAotv = context.getAotvUseCount() > 0;
        clearAotvBetweenPests(client, context);
        context.setArrivedAtCurrentTargetViaAotv(arrivedViaAotv);
        ClientUtils.sendDebugMessage("[PestDestroyer] AOTV route complete at "
                        + String.format("%.1f", dist) + " blocks ("
                        + (closeEnough ? "inside stop distance" : "another hop would not save enough travel")
                        + "). Switching to normal movement.");
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
        ClientUtils.setKeyMappingState(client.options.keyShift, false);
        if (client.player != null) {
            client.player.setShiftKeyDown(false);
        }
        context.runtime().pestEtherwarpActive = false;
        context.runtime().pestEtherwarpTargetEntityId = -1;
        context.runtime().pestEtherwarpAimPoint = null;
        context.runtime().pestEtherwarpClickAt = 0L;
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

    static void prepareOneTapTarget(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity target) {
        runtime.resetOneTapTracking();
        if (!AetherConfig.PEST_ONE_TAP_PESTS.get()
                || client == null
                || client.level == null
                || target == null) {
            return;
        }

        runtime.oneTapTargetEntityId = target.getId();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (hasNamedMarker(entity)) {
                runtime.oneTapKnownNamedEntityIds.add(entity.getId());
            }
        }
    }

    private static boolean hasNewNamedDamagePopupNearTarget(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity target) {
        if (client == null || client.level == null || target == null) {
            return false;
        }

        boolean found = false;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == target || entity == client.player || entity.isRemoved() || !hasNamedMarker(entity)) {
                continue;
            }

            int entityId = entity.getId();
            if (runtime.oneTapKnownNamedEntityIds.contains(entityId)) {
                continue;
            }

            double dx = entity.getX() - target.getX();
            double dz = entity.getZ() - target.getZ();
            double dy = entity.getY() - target.getY();
            boolean nearTarget = dx * dx + dz * dz
                    <= ONE_TAP_POPUP_HORIZONTAL_RADIUS * ONE_TAP_POPUP_HORIZONTAL_RADIUS
                    && dy >= ONE_TAP_POPUP_MIN_Y_OFFSET
                    && dy <= ONE_TAP_POPUP_MAX_Y_OFFSET;

            // Remember every newly observed name-tag entity. That way an
            // unrelated marker that spawned elsewhere cannot wander into the
            // pest later and masquerade as this pest's damage popup.
            runtime.oneTapKnownNamedEntityIds.add(entityId);
            if (nearTarget) {
                found = true;
            }
        }
        return found;
    }

    private static boolean hasNamedMarker(Entity entity) {
        if (entity == null || entity.getCustomName() == null) {
            return false;
        }
        return !entity.getCustomName().getString().trim().isEmpty();
    }

    private static boolean completeAssumedOneTapKill(
            Minecraft client,
            Context context,
            Entity currentTarget,
            String reason) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        if (!context.runtime().pestEtherwarpMaintainHeight) {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
        }
        PathfindingManager.stop();
        context.setTargetWithoutSkullTicks(0);
        context.runtime().resetOneTapTracking();
        context.runtime().oneTapAssumedKilledAt.put(
                currentTarget.getId(), System.currentTimeMillis());
        context.markKilled(currentTarget);

        ClientUtils.sendDebugMessage("[PestDestroyer] One Tap Pests: " + reason + ". Moving on.");
        if (context.recordTrackedPestKill(client, currentTarget)) {
            return true;
        }
        if (!context.switchToNextQueuedTarget(client)) {
            context.setState(PestDestroyer.State.CHECK_NEXT);
        }
        return true;
    }

    private static void handleAirbornePestRecovery(
            Minecraft client,
            Context context,
            Entity currentTarget,
            double dist,
            double verticalGap) {
        PestDestroyerRuntime runtime = context.runtime();

        // One owner for movement: stay underneath the same pest and climb.
        // No pathfinder, braking, pre-move, target switching, or skull-missing
        // confirmation is allowed while this recovery is active.
        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop(false);
        }
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, verticalGap > VERTICAL_ESCAPE_EXIT_GAP);
        context.setTargetWithoutSkullTicks(0);
        runtime.oneTapVacuumNearStartedAt = 0L;

        if (context.getVacuumSlot() == -1) {
            context.setVacuumSlot(context.findVacuumHotbarSlot(client));
        }
        if (context.getVacuumSlot() != -1
                && ((AccessorInventory) client.player.getInventory()).getSelected() != context.getVacuumSlot()) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()));
        }

        // Do not feed the raw jumping Y into the rotation target every tick.
        // Refresh a blended aim point at a modest cadence, then let the normal
        // tracking smoother close on that stable point. This removes the
        // rapid up/down pitch twitch while still following the airborne pest.
        long now = System.currentTimeMillis();
        Vec3 rawAim = buildCombatAimTarget(client, currentTarget);
        if (runtime.airborneRecoveryAimPoint == null) {
            runtime.airborneRecoveryAimPoint = rawAim;
            runtime.airborneRecoveryAimUpdatedAt = now;
        } else if (now - runtime.airborneRecoveryAimUpdatedAt >= VERTICAL_ESCAPE_AIM_REFRESH_MS) {
            runtime.airborneRecoveryAimPoint = runtime.airborneRecoveryAimPoint.lerp(
                    rawAim, VERTICAL_ESCAPE_AIM_BLEND);
            runtime.airborneRecoveryAimUpdatedAt = now;
        }

        if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            RotationManager.trackRotation(
                    client,
                    runtime.airborneRecoveryAimPoint,
                    VERTICAL_ESCAPE_AIM_SMOOTHING_MS,
                    AetherConfig.PEST_MAX_TURN_SPEED.get());
        }

        // Keep vacuum pressure if the pest is still physically in range. If it
        // jumped beyond range, climb without triggering a re-approach state.
        boolean inVacuumRange = dist <= context.getVacuumRange();
        boolean retryingUse = context.shouldTemporarilyReleaseKillVacuum(
                client, true, inVacuumRange);
        ClientUtils.setKeyMappingState(
                client.options.keyUse, inVacuumRange && !retryingUse);
    }

    private static void maintainStableCombatAim(
            Minecraft client, Context context, Entity target) {
        if (client.player == null || target == null
                || FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            return;
        }

        PestDestroyerRuntime runtime = context.runtime();
        long now = System.currentTimeMillis();
        Vec3 rawAim = buildCombatAimTarget(client, target);

        if (runtime.combatAimTargetEntityId != target.getId()
                || runtime.combatAimPoint == null) {
            runtime.combatAimTargetEntityId = target.getId();
            runtime.combatAimPoint = rawAim;
            runtime.combatAimUpdatedAt = now;
            runtime.combatAimCorrecting = true;
        } else if (now - runtime.combatAimUpdatedAt >= COMBAT_AIM_REFRESH_MS) {
            runtime.combatAimPoint = runtime.combatAimPoint.lerp(rawAim, COMBAT_AIM_BLEND);
            runtime.combatAimUpdatedAt = now;
        }

        if (runtime.combatAimCorrecting) {
            if (context.isLookingAt(client, runtime.combatAimPoint, COMBAT_AIM_SETTLE_DEGREES)) {
                runtime.combatAimCorrecting = false;
                return;
            }
        } else {
            if (context.isLookingAt(
                    client, runtime.combatAimPoint, COMBAT_AIM_CORRECTION_TRIGGER_DEGREES)) {
                return;
            }
            runtime.combatAimCorrecting = true;
        }

        RotationManager.trackRotation(
                client,
                runtime.combatAimPoint,
                COMBAT_AIM_SMOOTHING_MS,
                AetherConfig.PEST_MAX_TURN_SPEED.get());
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

    /**
     * Aim the vacuum at the pest's actual eye position.
     *
     * The old implementation replaced the target Y coordinate with a synthetic
     * 25-40 degree downward pitch whenever the player was above the pest. That
     * could put the crosshair several blocks away from the entity, especially
     * while approaching from the roof or from long horizontal distances.
     */
    static Vec3 buildVacuumAimTarget(Minecraft client, Entity target) {
        return target.position().add(0, target.getEyeHeight(target.getPose()), 0);
    }
}
