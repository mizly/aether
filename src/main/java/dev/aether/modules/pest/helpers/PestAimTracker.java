package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

// a first-order tracker always trails a moving target by its own time constant, so the point is led by the pest's measured velocity
// the crosshair rests on some spot of the pest's body and wanders between spots, because one pinned on its centre is the tell
final class PestAimTracker {
    private static final long SAMPLE_MIN_MS = 40L;
    private static final long SAMPLE_STALE_MS = 400L;
    private static final double VELOCITY_BLEND = 0.4;
    private static final double MAX_LEAD_BLOCKS = 2.5;
    private static final long SPOT_HOLD_MIN_MS = 400L;
    private static final long SPOT_HOLD_MAX_MS = 1_400L;
    private static final double SPOT_SMOOTHING_MS = 350.0;
    private static final long MAX_SPOT_STEP_MS = 200L;
    private static final double REACH_PER_DRIFT = 0.8;
    // kept inside the hitbox so a wandering aim never slides off the pest
    private static final double MAX_REACH = 0.95;

    private static int trackedId = -1;
    private static Vec3 lastPosition = null;
    private static long lastSampleAt = 0L;
    private static Vec3 velocity = Vec3.ZERO;

    private static Vec3 spot = Vec3.ZERO;
    private static Vec3 spotGoal = Vec3.ZERO;
    private static long spotGoalUntil = 0L;
    private static long lastSpotAt = 0L;

    private PestAimTracker() {
    }

    static void reset() {
        trackedId = -1;
        lastPosition = null;
        lastSampleAt = 0L;
        velocity = Vec3.ZERO;
        spot = Vec3.ZERO;
        spotGoal = Vec3.ZERO;
        spotGoalUntil = 0L;
        lastSpotAt = 0L;
    }

    static Vec3 trackingAim(Minecraft client, Entity target) {
        long now = System.currentTimeMillis();
        follow(target, now);
        sampleVelocity(target, now);
        advanceSpot(target, now);

        Vec3 predictedEye = target.position()
                .add(lead())
                .add(0, target.getEyeHeight(target.getPose()), 0);
        return PestCombatCoordinator.buildVacuumAimTarget(client, target, predictedEye.add(spot));
    }

    // where on the body a turn onto this pest should land, the same spot the tracker holds afterwards
    static Vec3 bodySpot(Entity target, Vec3 eye) {
        follow(target, System.currentTimeMillis());
        return eye.add(spot);
    }

    private static void follow(Entity target, long now) {
        if (trackedId == target.getId()) {
            return;
        }
        reset();
        trackedId = target.getId();
        spot = spotOn(ThreadLocalRandom.current(), target);
        spotGoal = spot;
        spotGoalUntil = now + ThreadLocalRandom.current().nextLong(SPOT_HOLD_MIN_MS, SPOT_HOLD_MAX_MS + 1);
        lastSpotAt = now;
    }

    private static void advanceSpot(Entity target, long now) {
        if (now >= spotGoalUntil) {
            spotGoalUntil = now + ThreadLocalRandom.current().nextLong(SPOT_HOLD_MIN_MS, SPOT_HOLD_MAX_MS + 1);
            spotGoal = spotOn(ThreadLocalRandom.current(), target);
        }
        long elapsed = Math.min(now - lastSpotAt, MAX_SPOT_STEP_MS);
        lastSpotAt = now;
        double closed = 1.0 - Math.exp(-Math.max(0L, elapsed) / SPOT_SMOOTHING_MS);
        spot = spot.add(spotGoal.subtract(spot).scale(closed));
    }

    private static Vec3 spotOn(RandomGenerator random, Entity target) {
        return spotOffset(random, target.getBbWidth(), target.getBbHeight(), target.getEyeHeight(target.getPose()),
                reach(AetherConfig.PEST_AIM_DRIFT.get()));
    }

    static double reach(double driftStrength) {
        return Math.clamp(driftStrength * REACH_PER_DRIFT, 0.0, MAX_REACH);
    }

    // a point inside the hitbox, relative to the eye, kept within reach of the centre on every axis
    static Vec3 spotOffset(RandomGenerator random, double width, double height, double eyeHeight, double reach) {
        if (reach <= 0.0) {
            return Vec3.ZERO;
        }
        double radius = width / 2.0 * reach * Math.sqrt(random.nextDouble());
        double angle = random.nextDouble(0.0, Math.PI * 2.0);
        double centre = height / 2.0 - eyeHeight;
        double y = centre + (random.nextDouble() * 2.0 - 1.0) * height / 2.0 * reach;
        return new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
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
}
