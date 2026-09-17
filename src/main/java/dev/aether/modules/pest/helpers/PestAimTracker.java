package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

// a first-order tracker always trails a moving target by its own time constant, so the point is led by the pest's measured velocity
// a slow offset wanders inside the silhouette, because a crosshair pinned motionless on a moving mob is the tell
final class PestAimTracker {
    private static final long SAMPLE_MIN_MS = 40L;
    private static final long SAMPLE_STALE_MS = 400L;
    private static final double VELOCITY_BLEND = 0.4;
    private static final double MAX_LEAD_BLOCKS = 2.5;
    private static final long DRIFT_HOLD_MIN_MS = 240L;
    private static final long DRIFT_HOLD_MAX_MS = 720L;
    private static final double DRIFT_SMOOTHING_MS = 280.0;
    private static final long MAX_DRIFT_STEP_MS = 200L;
    private static final double DRIFT_SILHOUETTE_FRACTION = 0.45;
    private static final double MIN_DRIFT_DEGREES = 0.12;
    private static final double MAX_DRIFT_DEGREES = 2.5;
    private static final double DRIFT_SETTLED_DEGREES = 1.0e-3;

    private static int trackedId = -1;
    private static Vec3 lastPosition = null;
    private static long lastSampleAt = 0L;
    private static Vec3 velocity = Vec3.ZERO;

    private static double driftYaw = 0.0;
    private static double driftPitch = 0.0;
    private static double driftGoalYaw = 0.0;
    private static double driftGoalPitch = 0.0;
    private static long driftGoalUntil = 0L;
    private static long lastDriftAt = 0L;

    private PestAimTracker() {
    }

    static void reset() {
        trackedId = -1;
        lastPosition = null;
        lastSampleAt = 0L;
        velocity = Vec3.ZERO;
        driftYaw = 0.0;
        driftPitch = 0.0;
        driftGoalYaw = 0.0;
        driftGoalPitch = 0.0;
        driftGoalUntil = 0L;
        lastDriftAt = 0L;
    }

    static Vec3 trackingAim(Minecraft client, Entity target) {
        long now = System.currentTimeMillis();
        if (trackedId != target.getId()) {
            reset();
            trackedId = target.getId();
        }
        sampleVelocity(target, now);

        Vec3 predictedEye = target.position()
                .add(lead())
                .add(0, target.getEyeHeight(target.getPose()), 0);
        Vec3 aim = PestCombatCoordinator.buildVacuumAimTarget(client, target, predictedEye);
        return applyDrift(client, target, aim, now);
    }

    private static void sampleVelocity(Entity target, long now) {
        Vec3 position = target.position();
        if (lastPosition == null || now - lastSampleAt > SAMPLE_STALE_MS) {
            lastPosition = position;
            lastSampleAt = now;
            velocity = Vec3.ZERO;
            return;
        }

        long elapsed = now - lastSampleAt;
        // Both the render and the tick pass ask for an aim point; resampling on
        // the render pass would read a delta of zero and flatten the estimate.
        if (elapsed < SAMPLE_MIN_MS) {
            return;
        }

        Vec3 sample = position.subtract(lastPosition).scale(1000.0 / elapsed);
        velocity = velocity.add(sample.subtract(velocity).scale(VELOCITY_BLEND));
        lastPosition = position;
        lastSampleAt = now;
    }

    private static Vec3 lead() {
        // Leading by the lag the tracker itself introduces is what cancels the
        // trail, so the lead time follows the configured tracking smoothing.
        Vec3 lead = velocity.scale(AetherConfig.PEST_TRACKING_SMOOTHING_MS.get() / 1000.0);
        double length = lead.length();
        return length > MAX_LEAD_BLOCKS ? lead.scale(MAX_LEAD_BLOCKS / length) : lead;
    }

    private static Vec3 applyDrift(Minecraft client, Entity target, Vec3 aim, long now) {
        advanceDrift(now, driftAmplitudeDegrees(client, target, aim));
        if (Math.abs(driftYaw) < DRIFT_SETTLED_DEGREES && Math.abs(driftPitch) < DRIFT_SETTLED_DEGREES) {
            return aim;
        }

        Vec3 eye = client.player.getEyePosition();
        double distance = aim.distanceTo(eye);
        if (distance < 1.0e-4) {
            return aim;
        }
        RotationUtils.Rotation look = RotationUtils.calculateLookAt(eye, aim);
        return eye.add(direction(look.yaw + driftYaw, look.pitch + driftPitch).scale(distance));
    }

    private static double driftAmplitudeDegrees(Minecraft client, Entity target, Vec3 aim) {
        float strength = AetherConfig.PEST_AIM_DRIFT.get();
        if (strength <= 0.0f) {
            return 0.0;
        }
        double distance = Math.max(0.5, aim.distanceTo(client.player.getEyePosition()));
        // Wandering inside the pest's own silhouette keeps a loose drift from
        // ever costing a hit, however far away the target is.
        double silhouette = Math.toDegrees(Math.atan2(target.getBbWidth() * 0.5, distance));
        double amplitude = silhouette * DRIFT_SILHOUETTE_FRACTION * strength;
        return Math.min(MAX_DRIFT_DEGREES, Math.max(MIN_DRIFT_DEGREES, amplitude));
    }

    private static void advanceDrift(long now, double amplitude) {
        if (lastDriftAt == 0L) {
            lastDriftAt = now;
        }
        if (now >= driftGoalUntil) {
            driftGoalUntil = now + ThreadLocalRandom.current()
                    .nextLong(DRIFT_HOLD_MIN_MS, DRIFT_HOLD_MAX_MS + 1);
            driftGoalYaw = ThreadLocalRandom.current().nextDouble(-amplitude, amplitude + 1.0e-9);
            driftGoalPitch = ThreadLocalRandom.current().nextDouble(-amplitude, amplitude + 1.0e-9);
        }

        long elapsed = Math.min(now - lastDriftAt, MAX_DRIFT_STEP_MS);
        lastDriftAt = now;
        double closed = 1.0 - Math.exp(-elapsed / DRIFT_SMOOTHING_MS);
        driftYaw += (driftGoalYaw - driftYaw) * closed;
        driftPitch += (driftGoalPitch - driftPitch) * closed;
    }

    private static Vec3 direction(double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch);
    }
}
