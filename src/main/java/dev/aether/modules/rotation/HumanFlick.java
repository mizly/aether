package dev.aether.modules.rotation;

import dev.aether.modules.failsafe.FailsafeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

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
    // the skewed minimum jerk curve peaks at 1.81x its average speed, the rest is margin
    private static final double PEAK_SPEED_FACTOR = 1.85;
    // one smooth sweep longer than this reads as a camera pan, a hand lifts and re-grips the mouse instead
    private static final long MAX_SWEEP_MS = 560L;
    private static final double OVERSHOOT_SIDE_FRACTION = 0.035;
    // the server reads the camera once a tick, so a swing past the target has to stay there at least that long
    private static final double OVERSHOOT_DWELL_MIN_MS = 70.0;
    private static final double OVERSHOOT_DWELL_MAX_MS = 170.0;
    private static final double CORRECTION_BASE_MS = 45.0;
    private static final double CORRECTION_LOG_MS = 30.0;
    private static final long MIN_CORRECTION_MS = 55L;
    private static final long MAX_CORRECTION_MS = 260L;
    // a very low speed cap would hold the camera for seconds, past this the cap gives way
    static final long MAX_CAPPED_MAIN_MS = 1_500L;
    static final long MAX_CAPPED_CORRECTION_MS = 450L;
    private static final float LET_GO_DEGREES = 3.0f;

    public record Style(double overshootFromDegrees, double overshootChance, double overshootMinFraction,
                        double overshootMaxFraction, double turnSpeedCap, double targetWidthDegrees,
                        double finalSpreadDegrees, boolean stagedCorrections) {
        public static final Style PRECISE = new Style(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0,
                TARGET_WIDTH_DEGREES, 0.0, false);

        public static Style humanized(double overshootFromDegrees, double overshootChance, double minFraction,
                                      double maxFraction, double turnSpeedCap, double targetWidthDegrees,
                                      double finalSpreadDegrees) {
            return new Style(Math.max(0.0, overshootFromDegrees), Math.clamp(overshootChance, 0.0, 1.0),
                    Math.max(0.0, Math.min(minFraction, maxFraction)),
                    Math.max(0.0, Math.max(minFraction, maxFraction)), Math.max(0.0, turnSpeedCap),
                    Math.clamp(targetWidthDegrees, 1.0, 8.0), Math.max(0.0, finalSpreadDegrees), true);
        }
    }

    record Segment(float fromYaw, float fromPitch, float toYaw, float toPitch, long startMs, long durationMs,
                   float bulge) {
        Segment {
            durationMs = Math.max(1L, durationMs);
        }

        long endMs() {
            return startMs + durationMs;
        }
    }

    // a throw of one or two strokes, then the corrections
    record Plan(List<Segment> segments, int strokes) {
        Plan {
            segments = List.copyOf(segments);
            if (strokes < 1 || strokes > segments.size()) {
                throw new IllegalArgumentException("a plan needs a throw of at least one stroke");
            }
        }

        Segment main() {
            return segments.getFirst();
        }

        Segment correction() {
            return segments.size() > strokes ? segments.get(strokes) : null;
        }

        long endMs() {
            return segments.getLast().endMs();
        }
    }

    // lays segments end to end, each starting where and when the one before it stopped
    private static final class Pen {
        private final List<Segment> segments = new ArrayList<>(5);
        private float yaw;
        private float pitch;
        private long at;

        Pen(float yaw, float pitch, long at) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.at = at;
        }

        void move(float toYaw, float toPitch, long durationMs, float bulge) {
            Segment segment = new Segment(yaw, pitch, toYaw, toPitch, at, durationMs, bulge);
            segments.add(segment);
            yaw = toYaw;
            pitch = toPitch;
            at = segment.endMs();
        }

        void pause(long ms) {
            at += ms;
        }
    }

    private record Running(long id, Plan plan) {
    }

    private static volatile Running active;
    private static long lastId;
    private static float lastYaw;
    private static float lastPitch;

    private HumanFlick() {
    }

    public static boolean isActive() {
        return active != null;
    }

    public static boolean isActive(long id) {
        Running running = active;
        return running != null && running.id() == id;
    }

    public static void cancel() {
        active = null;
    }

    public static void cancel(long id) {
        if (isActive(id)) {
            active = null;
        }
    }

    public static long start(Minecraft mc, float yaw, float pitch) {
        return start(mc, yaw, pitch, Style.PRECISE);
    }

    // 0 when nothing started: no player, off the client thread, or the pest rotation failsafe is holding the camera
    public static long start(Minecraft mc, float yaw, float pitch, Style style) {
        if (mc.player == null || !mc.isSameThread() || FailsafeManager.shouldSuppressPestCleanerRotation(mc)) {
            return 0L;
        }
        RotationManager.cancelRotation();
        float startYaw = mc.player.getYRot();
        float startPitch = mc.player.getXRot();
        Plan plan = plan(startYaw, startPitch, yaw, pitch, Util.getMillis(), style, ThreadLocalRandom.current());
        lastYaw = startYaw;
        lastPitch = startPitch;
        long id = ++lastId;
        active = new Running(id, plan);
        return id;
    }

    static Plan plan(float startYaw, float startPitch, float targetYaw, float targetPitch, long now,
                     RandomGenerator random) {
        return plan(startYaw, startPitch, targetYaw, targetPitch, now, Style.PRECISE, random);
    }

    static Plan plan(float startYaw, float startPitch, float targetYaw, float targetPitch, long now, Style style,
                     RandomGenerator random) {
        double peak = peakBudget(style.turnSpeedCap(), random);
        float[] goal = finalPoint(targetYaw, targetPitch, style.finalSpreadDegrees(), random);
        float dYaw = Mth.wrapDegrees(goal[0] - startYaw);
        float dPitch = goal[1] - startPitch;
        float endYaw = startYaw + dYaw;
        float endPitch = goal[1];
        double distance = Math.hypot(dYaw, dPitch);

        // a precise flick has no peak budget or spread to draw above,
        // so from here it consumes the random stream exactly as it always has
        double pace = random.nextDouble(0.85, 1.15);
        float bulge = bulge(distance, random);
        Pen pen = new Pen(startYaw, startPitch, now);
        if (distance < CORRECTION_MIN_DEGREES) {
            pen.move(endYaw, endPitch, strokeMs(distance, style, pace, peak, MAX_CAPPED_MAIN_MS), bulge);
            return new Plan(pen.segments, 1);
        }

        double chance = overshootChance(style, distance);
        boolean overshoot = chance > 0.0 && random.nextDouble() < chance;
        double along;
        double side;
        if (overshoot) {
            along = overshootFraction(style, peak, random);
            side = random.nextDouble(-OVERSHOOT_SIDE_FRACTION, OVERSHOOT_SIDE_FRACTION);
        } else {
            // hands land a touch short far more often than long, and a little to one side
            along = random.nextInt(4) == 0 ? random.nextDouble(0.01, 0.04) : -random.nextDouble(0.02, 0.07);
            side = random.nextDouble(-0.02, 0.02);
        }
        double ux = dYaw / distance;
        double uy = dPitch / distance;
        float missYaw = (float) (endYaw + ux * distance * along - uy * distance * side);
        float missPitch = Mth.clamp((float) (endPitch + uy * distance * along + ux * distance * side),
                -90.0f, 90.0f);

        // a precise throw is timed on the turn as it always was, a staged one on the path the hand really travels
        double travelled = style.stagedCorrections()
                ? Math.hypot(missYaw - startYaw, missPitch - startPitch)
                : distance;
        long throwMs = strokeMs(travelled, style, pace, peak, MAX_CAPPED_MAIN_MS);
        if (throwMs > MAX_SWEEP_MS) {
            throwInTwoStrokes(pen, missYaw, missPitch, style, pace, peak, random);
        } else {
            pen.move(missYaw, missPitch, throwMs, bulge);
        }
        int strokes = pen.segments.size();

        pen.pause(overshoot
                ? Math.round(skewed(random, OVERSHOOT_DWELL_MIN_MS, OVERSHOOT_DWELL_MAX_MS))
                : random.nextLong(15L, 55L));
        if (style.stagedCorrections()) {
            correct(pen, endYaw, endPitch, style, peak, random);
        } else {
            pen.move(endYaw, endPitch, random.nextLong(55L, 115L), 0.0f);
        }
        return new Plan(pen.segments, strokes);
    }

    // each flick peaks somewhere in 70-100% of the cap, most often near 85%, so big turns differ in speed
    private static double peakBudget(double turnSpeedCap, RandomGenerator random) {
        return turnSpeedCap > 0.0 ? turnSpeedCap * (0.70 + 0.15 * (random.nextDouble() + random.nextDouble())) : 0.0;
    }

    // a hand comes to rest anywhere on the target rather than on its exact centre
    private static float[] finalPoint(float yaw, float pitch, double spreadDegrees, RandomGenerator random) {
        if (spreadDegrees <= 0.0) {
            return new float[] {yaw, pitch};
        }
        double radius = spreadDegrees * Math.sqrt(random.nextDouble());
        double angle = random.nextDouble(2.0 * Math.PI);
        return new float[] {(float) (yaw + radius * Math.cos(angle)),
                Mth.clamp((float) (pitch + radius * Math.sin(angle)), -90.0f, 90.0f)};
    }

    // none at half the overshoot turn, rising smoothly to the full chance at it, so no turn size is a visible step
    static double overshootChance(Style style, double distanceDegrees) {
        double half = style.overshootFromDegrees() / 2.0;
        if (style.overshootChance() <= 0.0 || Double.isInfinite(half)) {
            return 0.0;
        }
        double x = half > 0.0 ? Math.clamp((distanceDegrees - half) / half, 0.0, 1.0) : 1.0;
        return style.overshootChance() * x * x * (3.0 - 2.0 * x);
    }

    // the amounts are for a fast hand, a turn held to a slower peak swings past by less
    private static double overshootFraction(Style style, double peak, RandomGenerator random) {
        double scale = peak > 0.0 ? Math.clamp(peak / 600.0, 0.5, 1.0) : 1.0;
        return skewed(random, style.overshootMinFraction(), style.overshootMaxFraction()) * scale;
    }

    private static float bulge(double distanceDegrees, RandomGenerator random) {
        return (float) (distanceDegrees * random.nextDouble(0.03, 0.08) * (random.nextBoolean() ? 1 : -1));
    }

    // a sweep this long is made in two strokes, each with its own curve, and a pause to re-grip between them
    private static void throwInTwoStrokes(Pen pen, float toYaw, float toPitch, Style style, double pace,
                                          double peak, RandomGenerator random) {
        float dYaw = toYaw - pen.yaw;
        float dPitch = toPitch - pen.pitch;
        double split = random.nextDouble(0.50, 0.65);
        double aside = random.nextDouble(-0.02, 0.02);
        float gripYaw = (float) (pen.yaw + dYaw * split - dPitch * aside);
        float gripPitch = Mth.clamp((float) (pen.pitch + dPitch * split + dYaw * aside), -90.0f, 90.0f);
        stroke(pen, gripYaw, gripPitch, style, pace, peak, random);
        pen.pause(Math.round(skewed(random, 60.0, 160.0)));
        stroke(pen, toYaw, toPitch, style, pace, peak, random);
    }

    // the strokes split the throw's ceiling, so a very slow cap still lets go as early as one sweep would
    private static void stroke(Pen pen, float toYaw, float toPitch, Style style, double pace, double peak,
                               RandomGenerator random) {
        double length = Math.hypot(toYaw - pen.yaw, toPitch - pen.pitch);
        pen.move(toYaw, toPitch, strokeMs(length, style, pace, peak, MAX_CAPPED_MAIN_MS / 2),
                bulge(length, random));
    }

    // a miss past 3 degrees is more and more often closed in two goes,
    // the first one stopping a little short and to one side
    private static void correct(Pen pen, float toYaw, float toPitch, Style style, double peak,
                                RandomGenerator random) {
        float dYaw = toYaw - pen.yaw;
        float dPitch = toPitch - pen.pitch;
        double twoGoChance = Math.clamp((Math.hypot(dYaw, dPitch) - 3.0) / 9.0, 0.0, 0.85);
        if (twoGoChance > 0.0 && random.nextDouble() < twoGoChance) {
            double rest = random.nextDouble(0.08, 0.25);
            double restSide = random.nextDouble(-0.05, 0.05);
            correctTo(pen, (float) (toYaw - dYaw * rest - dPitch * restSide),
                    Mth.clamp((float) (toPitch - dPitch * rest + dYaw * restSide), -90.0f, 90.0f),
                    style, peak, random);
            pen.pause(Math.round(skewed(random, 40.0, 110.0)));
        }
        correctTo(pen, toYaw, toPitch, style, peak, random);
    }

    private static void correctTo(Pen pen, float toYaw, float toPitch, Style style, double peak,
                                  RandomGenerator random) {
        double length = Math.hypot(toYaw - pen.yaw, toPitch - pen.pitch);
        double ms = CORRECTION_BASE_MS + CORRECTION_LOG_MS * difficulty(length, style.targetWidthDegrees());
        long fitts = Math.clamp(Math.round(ms * random.nextDouble(0.85, 1.15)), MIN_CORRECTION_MS,
                MAX_CORRECTION_MS);
        pen.move(toYaw, toPitch, capped(fitts, length, peak, MAX_CAPPED_CORRECTION_MS), 0.0f);
    }

    static long mainDurationMs(double distanceDegrees, RandomGenerator random) {
        return fittsMs(distanceDegrees, TARGET_WIDTH_DEGREES, random.nextDouble(0.85, 1.15));
    }

    private static long strokeMs(double distanceDegrees, Style style, double pace, double peak, long ceilingMs) {
        return capped(fittsMs(distanceDegrees, style.targetWidthDegrees(), pace), distanceDegrees, peak, ceilingMs);
    }

    private static long fittsMs(double distanceDegrees, double targetWidthDegrees, double pace) {
        double ms = BASE_MS + LOG_MS * difficulty(distanceDegrees, targetWidthDegrees);
        return Math.clamp(Math.round(ms * pace), MIN_MAIN_MS, MAX_MAIN_MS);
    }

    private static double difficulty(double distanceDegrees, double targetWidthDegrees) {
        return Math.log(1.0 + distanceDegrees / targetWidthDegrees) / Math.log(2.0);
    }

    private static long capped(long ms, double distanceDegrees, double peakDegreesPerSecond, long ceilingMs) {
        if (peakDegreesPerSecond <= 0.0) {
            return ms;
        }
        long slowest = (long) Math.ceil(1000.0 * PEAK_SPEED_FACTOR * distanceDegrees / peakDegreesPerSecond);
        return Math.max(ms, Math.min(slowest, ceilingMs));
    }

    // most draws land in the lower part of the range and none pile up at its edges, as human timings do
    public static double skewed(RandomGenerator random, double min, double max) {
        double low = Math.min(min, max);
        double high = Math.max(min, max);
        double mean = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3.0;
        return low + (high - low) * Math.pow(mean, 1.4);
    }

    // yaw and pitch the plan wants at this moment, holding the last reached point through a pause
    static float[] sample(Plan plan, long now) {
        Segment segment = plan.main();
        for (Segment candidate : plan.segments()) {
            if (now >= candidate.startMs()) {
                segment = candidate;
            }
        }
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
        Running running = active;
        if (running == null || mc.player == null) {
            return;
        }
        // another rotation driver or the server moved the camera, or the pest failsafe holds it, so let go
        if (Math.abs(Mth.wrapDegrees(mc.player.getYRot() - lastYaw)) > LET_GO_DEGREES
                || Math.abs(mc.player.getXRot() - lastPitch) > LET_GO_DEGREES
                || FailsafeManager.shouldSuppressPestCleanerRotation(mc)) {
            active = null;
            return;
        }

        long now = Util.getMillis();
        Plan plan = running.plan();
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

        if (now >= plan.endMs() && active == running) {
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
