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
    private static final long STUCK_PATH_RETRY_DELAY_MS = 2000L;
    private static final long AOTV_POST_CLICK_GRACE_MS = 250L;
    private static final double AOTV_CONFIRM_DISTANCE = 2.0;
    private static final double AOTV_CONFIRM_DISTANCE_SQ = AOTV_CONFIRM_DISTANCE * AOTV_CONFIRM_DISTANCE;
    private static final float AOTV_AIM_TOLERANCE_DEGREES = 2.0f;
    private static final double POST_AOTV_LOOK_DOWN_HORIZONTAL_DISTANCE = 3.0;
    private static final double VACUUM_REAPPROACH_BUFFER = 6.0;
    private static final double S_BRAKE_ENTER_DISTANCE = 2.0;
    private static final double S_BRAKE_EXIT_DISTANCE = 4.0;
    private static final double S_BRAKE_MIN_SPEED = 0.20;
    private static final double KILL_FORWARD_HOLD_DISTANCE = 5.0;
    interface Context {
        Entity getCurrentTarget();
        int getVacuumSlot();
        void setVacuumSlot(int slot);
        double getVacuumRange();
        int getAotvSlot();
        void setAotvSlot(int slot);
        int getAotvUseCount();
        void setAotvUseCount(int useCount);
        long getAotvLastUseAt();
        void setAotvLastUseAt(long lastUseAt);
        long getAotvNextUseAt();
        void setAotvNextUseAt(long nextUseAt);
        long getAotvPostClickGraceUntil();
        void setAotvPostClickGraceUntil(long graceUntil);
        long getAotvPendingUseAt();
        void setAotvPendingUseAt(long pendingUseAt);
        double getAotvLastUsePlayerX();
        void setAotvLastUsePlayerX(double x);
        double getAotvLastUsePlayerY();
        void setAotvLastUsePlayerY(double y);
        double getAotvLastUsePlayerZ();
        void setAotvLastUsePlayerZ(double z);
        boolean didArriveAtCurrentTargetViaAotv();
        void setArrivedAtCurrentTargetViaAotv(boolean arrived);
        long getStateEnteredAt();
        void setStateEnteredAt(long enteredAt);
        int getStuckTicks();
        void setStuckTicks(int stuckTicks);
        long getFlyRetryAfterUnflyAt();
        void setFlyRetryAfterUnflyAt(long retryAt);
        int getApproachTicks();
        void setApproachTicks(int approachTicks);
        int getTargetWithoutSkullTicks();
        void setTargetWithoutSkullTicks(int targetWithoutSkullTicks);
        boolean isLookingAt(Minecraft client, Vec3 targetPos, float tolerance);
        void setState(PestDestroyer.State state);
        void startPathToPest(Minecraft client, Entity pest);
        boolean switchToNextQueuedTarget(Minecraft client);
        Entity peekNextQueuedPest(Minecraft client);
        void maybePreMoveToNextTarget(Minecraft client, Entity nextTarget, double currentDist);
        boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target);
        void markKilled(Entity entity);
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

        double dist = client.player.distanceTo(currentTarget);
        if (dist <= targetReachDistance * 1.5
                && !FailsafeManager.shouldSuppressPestCleanerRotation(client)
                && shouldRotateForCombatAim(context, client, currentTarget)) {
            Vec3 targetEye = buildCombatAimTarget(client, currentTarget);
            if (!context.isLookingAt(client, targetEye, AetherConfig.PEST_FOV_RANGE.get())) {
                RotationManager.initiateRotation(client, targetEye, 80, AetherConfig.PEST_FOV_RANGE.get());
            }
        }

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

            if (context.getFlyRetryAfterUnflyAt() != 0L) {
                context.setFlyRetryAfterUnflyAt(0L);
                context.startPathToPest(client, currentTarget);
                return;
            }

            context.setStuckTicks(context.getStuckTicks() + 1);
            if (context.getStuckTicks() > pathfinderStuckRetryTicks) {
                ClientUtils.sendDebugMessage(client,
                        "[PestDestroyer] Pathfinder stuck. Retrying path to pest.");
                context.setStuckTicks(0);
                context.setFlyRetryAfterUnflyAt(now + STUCK_PATH_RETRY_DELAY_MS);
            }
        } else {
            context.setStuckTicks(0);
            context.setFlyRetryAfterUnflyAt(0L);
        }

        if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) {
            ClientUtils.sendDebugMessage(client, "[PestDestroyer] Fly-to-pest timed out. Checking for next pest.");
            PathfindingManager.stop();
            context.markKilled(currentTarget);
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

        if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)
                && shouldRotateForCombatAim(context, client, currentTarget)) {
            Vec3 targetEye = buildCombatAimTarget(client, currentTarget);
            RotationManager.forceRotation(client, targetEye, 120);
        }

        if (dist <= targetReachDistance) {
            context.setState(PestDestroyer.State.KILL_PEST);
            return;
        }

        if (!PathfindingManager.isNavigating()) {
            context.startPathToPest(client, currentTarget);
        }

        if (context.getApproachTicks() > approachTimeoutTicks) {
            ClientUtils.sendDebugMessage(client, "[PestDestroyer] Approach timed out.");
            PathfindingManager.stop();
            context.markKilled(currentTarget);
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
                PestDestroyer.recordTrackedPestKill(client, currentTarget);
            }
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        if (client.player == null) {
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
            boolean retryingUse = PestDestroyer.shouldTemporarilyReleaseKillVacuum(client, true, true);
            ClientUtils.setKeyMappingState(client.options.keyUse, !retryingUse);
            ClientUtils.setKeyMappingState(client.options.keyUp, dist > KILL_FORWARD_HOLD_DISTANCE);

            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop();
            }

            if (!FailsafeManager.shouldSuppressPestCleanerRotation(client)
                    && shouldRotateForCombatAim(context, client, currentTarget)) {
                Vec3 targetEye = buildCombatAimTarget(client, currentTarget);
                RotationManager.forceRotation(client, targetEye, 120);
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
                    PestDestroyer.recordTrackedPestKill(client, currentTarget);
                    ClientUtils.sendDebugMessage(client,
                            "[PestDestroyer] Pest skull disappeared. Switching target immediately.");
                    if (!context.switchToNextQueuedTarget(client)) {
                        context.setState(PestDestroyer.State.CHECK_NEXT);
                    }
                    return;
                }
            } else {
                context.setTargetWithoutSkullTicks(0);
            }
        } else {
            PestDestroyer.shouldTemporarilyReleaseKillVacuum(client, true, false);
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
            ClientUtils.sendDebugMessage(client, "[PestDestroyer] Kill pest timed out. Moving on.");
            context.markKilled(currentTarget);
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
                ClientUtils.sendDebugMessage(client, "[PestDestroyer] No AOTV found. Falling back to pathfinding.");
                context.startPathToPest(client, currentTarget);
                context.setState(PestDestroyer.State.FLY_TO_PEST);
                return;
            }
            context.setStateEnteredAt(System.currentTimeMillis());
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
            ClientUtils.sendDebugMessage(client, "[PestDestroyer] No LOS and below pest (" + currentTarget.getDisplayName().getString() + "), flying up for vision...");
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

        boolean facingAim = context.isLookingAt(client, aimPos, AOTV_AIM_TOLERANCE_DEGREES);
        boolean suppressRotation = FailsafeManager.shouldSuppressPestCleanerRotation(client);
        if (!suppressRotation) {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.setKeyMappingState(client.options.keySprint, false);
        }
        long now = System.currentTimeMillis();
        long elapsed = now - context.getStateEnteredAt();
        if (!facingAim) {
            if (!suppressRotation && !RotationManager.isRotating()) {
                RotationManager.initiateRotation(client, aimPos, AetherConfig.ROTATION_TIME.get());
            }
            if (!suppressRotation) {
                return;
            }
        }
        if (!suppressRotation && RotationManager.isRotating()) {
            return;
        }

        if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get() && context.getAotvPendingUseAt() != 0L) {
            double movedDistance = getAotvMovedDistance(client, context);
            if (movedDistance >= AOTV_CONFIRM_DISTANCE) {
                context.setAotvLastUseAt(context.getAotvPendingUseAt());
                context.setAotvPendingUseAt(0L);
                context.setAotvPostClickGraceUntil(0L);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
                ClientUtils.sendDebugMessage(client,
                        "[PestDestroyer] AOTV confirmed by movement: "
                                + String.format("%.2f", movedDistance) + " blocks.");
                dist = client.player.distanceTo(currentTarget);
                if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                    return;
                }
            } else if (context.getAotvPostClickGraceUntil() > now) {
                ClientUtils.sendDebugMessage(client,
                        "[PestDestroyer] Waiting for AOTV confirm: moved "
                                + String.format("%.2f", movedDistance)
                                + "/" + String.format("%.2f", AOTV_CONFIRM_DISTANCE) + " blocks.");
                return;
            } else {
                ClientUtils.sendDebugMessage(client,
                        "[PestDestroyer] AOTV confirm failed: moved "
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
            ClientUtils.sendDebugMessage(client,
                    "[PestDestroyer] Using AOTV (" + (context.getAotvUseCount() + 1) + "). Distance: "
                            + String.format("%.1f", dist));
            ClientUtils.performUseClick(client);
            context.setAotvPostClickGraceUntil(now + AOTV_POST_CLICK_GRACE_MS);
            context.setAotvLastUsePlayerX(client.player.getX());
            context.setAotvLastUsePlayerY(client.player.getY());
            context.setAotvLastUsePlayerZ(client.player.getZ());
            if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get()) {
                context.setAotvPendingUseAt(now);
                ClientUtils.sendDebugMessage(client,
                        "[PestDestroyer] Waiting for AOTV position confirm (>= "
                                + String.format("%.0f", AOTV_CONFIRM_DISTANCE) + " blocks).");
            } else {
                context.setAotvLastUseAt(now);
                context.setAotvUseCount(context.getAotvUseCount() + 1);
            }
            context.setAotvNextUseAt(0L);
        }

        if (context.getAotvUseCount() > 10) {
            clearAotvBetweenPests(client, context);
            ClientUtils.sendDebugMessage(client,
                    "[PestDestroyer] AOTV usage exceeded maximum. Falling back to pathfinding.");
            context.startPathToPest(client, currentTarget);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
            return;
        }

        if (elapsed > stateTimeoutMs) {
            clearAotvBetweenPests(client, context);
            ClientUtils.sendDebugMessage(client, "[PestDestroyer] AOTV state timed out. Falling back to pathfinding.");
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
        ClientUtils.sendDebugMessage(client,
                "[PestDestroyer] AOTV closed gap. Distance now " + String.format("%.1f", dist)
                        + ". Switching to pathfinding.");
        if (dist <= context.getVacuumRange()) {
            context.setState(PestDestroyer.State.KILL_PEST);
        } else {
            context.startPathToPest(client, currentTarget);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
        }
        return true;
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
        float minPitch = AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.get();
        float maxPitch = AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.get();
        if (maxPitch < minPitch) {
            float swap = minPitch;
            minPitch = maxPitch;
            maxPitch = swap;
        }
        float range = maxPitch - minPitch;
        int bucket = Math.floorMod(target.getId(), (int) range + 1);
        return maxPitch - bucket;
    }
}
