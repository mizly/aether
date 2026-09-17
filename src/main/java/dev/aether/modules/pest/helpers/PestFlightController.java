package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.execution.FlightMotion;
import dev.aether.modules.pathfinding.execution.FlightPathClearance;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

final class PestFlightController {
    private int targetId = -1;
    private int lastTick;
    private Vec3 lastPosition;
    private Vec3 targetVelocity = Vec3.ZERO;
    private double approachRange = 12.0;

    void reset() {
        targetId = -1;
        lastPosition = null;
        targetVelocity = Vec3.ZERO;
        approachRange = 12.0;
    }

    Vec3 sampleVelocity(int id, Vec3 position, int tick) {
        int elapsed = tick - lastTick;
        if (id != targetId || lastPosition == null || elapsed < 0 || elapsed > 5
                || position.distanceToSqr(lastPosition) > 9.0) {
            targetVelocity = Vec3.ZERO;
        } else if (elapsed == 0) {
            return targetVelocity;
        } else {
            Vec3 sample = position.subtract(lastPosition).scale(1.0 / elapsed);
            targetVelocity = targetVelocity.scale(0.65).add(sample.scale(0.35));
        }
        targetId = id;
        lastPosition = position;
        lastTick = tick;
        return targetVelocity;
    }

    double handoffDistance(Vec3 velocity, double vacuumRange) {
        // Retain the capture range as we slow down, or braking immediately hands control back to the route.
        approachRange = Math.max(approachRange, AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE.get()
                + velocity.horizontalDistance() * FlightMotion.coastTicks(
                        AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get()) + 2.0);
        return Math.max(vacuumRange, approachRange);
    }

    boolean canApproachDirectly(Minecraft client, Entity target, double vacuumRange) {
        if (client.level == null || client.player == null
                || client.player.distanceTo(target) > handoffDistance(client.player.getDeltaMovement(), vacuumRange)) {
            return false;
        }
        Vec3 eye = target.position().add(0, target.getEyeHeight(target.getPose()), 0);
        if (!ClientUtils.hasLineOfSight(client.player, eye)) {
            return false;
        }
        Vec3 destination = target.position().add(0, client.player.getAbilities().flying ? 3.0 : 0.0, 0);
        return FlightPathClearance.isClear(client, client.player.position(), destination);
    }

    void update(Minecraft client, Entity target, double vacuumRange, boolean canTranslate) {
        Vec3 velocity = client.player.getDeltaMovement();
        Vec3 pestVelocity = sampleVelocity(target.getId(), target.position(), client.player.tickCount);
        Vec3 offset = target.position().subtract(client.player.position());
        double followHeight = client.player.getAbilities().flying ? 3.0 : offset.y;
        double follow = followDistance(AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE.get(), vacuumRange, followHeight);
        Vec3 desired = canTranslate
                ? FlightMotion.approachVelocity(offset, pestVelocity, follow,
                        AetherConfig.PEST_APPROACH_SPEED.get(), AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get())
                : Vec3.ZERO;
        // A turn must not accelerate us past the pest while the camera catches up.
        if (!facesTarget(offset, client.player.getYRot())) {
            desired = Vec3.ZERO;
        }
        FlightMotion.apply(client, FlightMotion.horizontalInput(desired, velocity, client.player.getYRot()));
        int vertical = canTranslate && client.player.getAbilities().flying
                ? FlightMotion.verticalInput(offset.y + 3.0, velocity.y, 0.5) : 0;
        ClientUtils.setKeyMappingState(client.options.keyJump, vertical > 0);
        ClientUtils.setKeyMappingState(client.options.keyShift, vertical < 0);
        RotationManager.trackRotation(client, PestAimTracker.trackingAim(client, target),
                AetherConfig.PEST_TRACKING_SMOOTHING_MS.get(), AetherConfig.PEST_MAX_TURN_SPEED.get());
    }

    static double followDistance(double configured, double range, double heightDifference) {
        double usableRange = Math.max(0.0, range - 1.0);
        return Math.min(configured, Math.sqrt(Math.max(0.0,
                usableRange * usableRange - heightDifference * heightDifference)));
    }

    static boolean facesTarget(Vec3 offset, float yaw) {
        double horizontal = offset.horizontalDistance();
        if (horizontal < 0.25) {
            return false;
        }
        double angle = Math.toRadians(yaw);
        return (-offset.x * Math.sin(angle) + offset.z * Math.cos(angle)) / horizontal >= 0.5;
    }
}
