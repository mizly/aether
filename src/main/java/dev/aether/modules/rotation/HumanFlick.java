package dev.aether.modules.rotation;

import dev.aether.modules.failsafe.FailsafeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

// a flick the way a hand does it: a quick slightly curved throw that stops a little off, then a small correction
// updated every frame, so the motion is smooth at any fps
public final class HumanFlick {
    // fitts style timing, a few degrees take ~100ms and a half turn under 300ms
    private static final double BASE_MS = 55.0;
    private static final double LOG_MS = 34.0;
    private static final double TARGET_WIDTH_DEGREES = 2.0;
    private static final long MIN_MAIN_MS = 70L;
    private static final long MAX_MAIN_MS = 320L;
    // below this the throw itself is precise enough that a correction would read as jitter
    private static final float CORRECTION_MIN_DEGREES = 1.5f;

    record Segment(float fromYaw, float fromPitch, float toYaw, float toPitch, long startMs, long durationMs,
                   float bulge) {
        long endMs() {
            return startMs + durationMs;
        }
    }

    record Plan(Segment main, Segment correction) {
        long endMs() {
            return correction == null ? main.endMs() : correction.endMs();
        }
    }

    private static volatile Plan active;
    private static float lastYaw;
    private static float lastPitch;

    private HumanFlick() {
    }

    public static boolean isActive() {
        return active != null;
    }

    public static void cancel() {
        active = null;
    }

    public static void start(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) {
            return;
        }
        RotationManager.cancelRotation();
        float startYaw = mc.player.getYRot();
        float startPitch = mc.player.getXRot();
        active = plan(startYaw, startPitch, yaw, pitch, System.currentTimeMillis(), ThreadLocalRandom.current());
        lastYaw = startYaw;
        lastPitch = startPitch;
    }

    static Plan plan(float startYaw, float startPitch, float targetYaw, float targetPitch, long now,
                     ThreadLocalRandom random) {
        float dYaw = Mth.wrapDegrees(targetYaw - startYaw);
        float dPitch = targetPitch - startPitch;
        float endYaw = startYaw + dYaw;
        double distance = Math.hypot(dYaw, dPitch);

        long mainMs = mainDurationMs(distance, random);
        float bulge = (float) (distance * random.nextDouble(0.03, 0.08) * (random.nextBoolean() ? 1 : -1));
        if (distance < CORRECTION_MIN_DEGREES) {
            return new Plan(new Segment(startYaw, startPitch, endYaw, targetPitch, now, mainMs, bulge), null);
        }

        // hands land a touch short far more often than long, and a little to one side
        double along = random.nextInt(4) == 0 ? random.nextDouble(0.01, 0.04) : -random.nextDouble(0.02, 0.07);
        double side = random.nextDouble(-0.02, 0.02);
        double ux = dYaw / distance;
        double uy = dPitch / distance;
        float missYaw = (float) (endYaw + ux * distance * along - uy * distance * side);
        float missPitch = Mth.clamp((float) (targetPitch + uy * distance * along + ux * distance * side),
                -90.0f, 90.0f);

        Segment main = new Segment(startYaw, startPitch, missYaw, missPitch, now, mainMs, bulge);
        long dwell = random.nextLong(15L, 55L);
        long correctMs = random.nextLong(55L, 115L);
        Segment correction = new Segment(missYaw, missPitch, endYaw, targetPitch, main.endMs() + dwell, correctMs,
                0.0f);
        return new Plan(main, correction);
    }

    static long mainDurationMs(double distanceDegrees, ThreadLocalRandom random) {
        double ms = BASE_MS + LOG_MS * (Math.log(1.0 + distanceDegrees / TARGET_WIDTH_DEGREES) / Math.log(2.0));
        ms *= random.nextDouble(0.85, 1.15);
        return Math.clamp(Math.round(ms), MIN_MAIN_MS, MAX_MAIN_MS);
    }

    // yaw and pitch the plan wants at this moment
    static float[] sample(Plan plan, long now) {
        Segment segment = plan.correction() != null && now >= plan.correction().startMs()
                ? plan.correction()
                : plan.main();
        double t = Math.clamp((double) (now - segment.startMs()) / segment.durationMs(), 0.0, 1.0);
        double s = minimumJerk(Math.pow(t, 0.85));
        float yaw = (float) (segment.fromYaw() + (segment.toYaw() - segment.fromYaw()) * s);
        float pitch = (float) (segment.fromPitch() + (segment.toPitch() - segment.fromPitch()) * s);
        if (segment.bulge() != 0.0f) {
            double dy = segment.toYaw() - segment.fromYaw();
            double dp = segment.toPitch() - segment.fromPitch();
            double length = Math.hypot(dy, dp);
            if (length > 1.0e-4) {
                double arc = Math.sin(Math.PI * s) * segment.bulge();
                yaw += (float) (-dp / length * arc);
                pitch += (float) (dy / length * arc);
            }
        }
        return new float[] {yaw, Mth.clamp(pitch, -90.0f, 90.0f)};
    }

    // a reach peaks in speed early and settles gently, which is what the skewed minimum jerk curve gives
    static double minimumJerk(double t) {
        return t * t * t * (10.0 - 15.0 * t + 6.0 * t * t);
    }

    public static void update(Minecraft mc) {
        Plan plan = active;
        if (plan == null || mc.player == null) {
            return;
        }
        // the player grabbed the mouse, so the flick gives the camera back
        if (Math.abs(Mth.wrapDegrees(mc.player.getYRot() - lastYaw)) > 3.0f
                || Math.abs(mc.player.getXRot() - lastPitch) > 3.0f) {
            active = null;
            return;
        }

        long now = System.currentTimeMillis();
        float[] wanted = sample(plan, now);
        float yaw = quantize(mc, wanted[0], mc.player.getYRot());
        float pitch = Mth.clamp(quantize(mc, wanted[1], mc.player.getXRot()), -90.0f, 90.0f);

        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        lastYaw = yaw;
        lastPitch = pitch;
        FailsafeManager.expectRotation(yaw, pitch);

        if (now >= plan.endMs()) {
            active = null;
        }
    }

    // real mouse input only ever moves in whole sensitivity steps
    private static float quantize(Minecraft mc, float wanted, float current) {
        double multiplier = mc.options.sensitivity().get() * 0.6 + 0.2;
        double step = multiplier * multiplier * multiplier * 1.2;
        double delta = Mth.wrapDegrees(wanted - current);
        return (float) (current + Math.round(delta / step) * step);
    }
}
