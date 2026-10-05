package dev.aether.macro.fishing;

import dev.aether.macro.MacroInput;
import dev.aether.modules.rotation.RotationManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

// small slow camera drift and the occasional short step, so a long wait is not a statue staring at the float
final class IdleMotion {

    private static final long IDLE_MIN_DELAY_MS = 2_500L;
    private static final long IDLE_MAX_DELAY_MS = 7_000L;
    private static final long IDLE_FIRST_MIN_DELAY_MS = 400L;
    private static final long IDLE_FIRST_MAX_DELAY_MS = 900L;
    // a flick, not a glide; a half second spent easing across two degrees is what reads as a machine
    // the rotation manager floors any duration at 100ms, so nothing shorter is worth asking for
    private static final long IDLE_TURN_MIN_MS = 100L;
    private static final long IDLE_TURN_MAX_MS = 220L;
    private static final long IDLE_TAP_MIN_MS = 90L;
    private static final long IDLE_TAP_MAX_MS = 200L;
    private static final float IDLE_YAW_DEGREES = 2.5f;
    private static final float IDLE_PITCH_DEGREES = 1.5f;
    private static final int IDLE_TAP_ONE_IN = 4;
    private static final float GLANCE_YAW_DEGREES = 5.0f;
    private static final float GLANCE_PITCH_DEGREES = 3.0f;
    private static final double AIM_BOX_RADIUS = 0.18;

    // somewhere other than the float worth a look now and then, or null to stay on the float
    @FunctionalInterface
    interface Glance {
        Vec3 pick(Minecraft mc, RandomGenerator random);
    }

    private final BooleanSupplier look;
    private final BooleanSupplier shuffle;
    private final Glance glance;
    private boolean anchored;
    private long nextAt;
    private long tapUntil;
    private KeyMapping tapKey;

    IdleMotion(BooleanSupplier look, BooleanSupplier shuffle, Glance glance) {
        this.look = look;
        this.shuffle = shuffle;
        this.glance = glance;
    }

    void tick(Minecraft mc, long now, boolean sneak, boolean sneakAllowed, boolean onOrigin,
              RandomGenerator random) {
        var options = mc.options;
        boolean tapping = now < tapUntil && tapKey != null;
        if (!tapping && tapKey != null) {
            MacroInput.set(tapKey, false);
            tapKey = null;
        }

        MacroInput.set(options.keyUp, false);
        MacroInput.set(options.keyDown, false);
        MacroInput.set(options.keyLeft, false);
        MacroInput.set(options.keyRight, false);
        MacroInput.set(options.keySprint, false);
        MacroInput.set(options.keyJump, false);
        MacroInput.set(options.keyShift, sneak || tapping);
        if (tapping) {
            MacroInput.set(tapKey, true);
        }

        FishingHook hook = mc.player.fishing;
        if (!anchored || hook == null || now < nextAt || RotationManager.isRotating()) {
            return;
        }

        nextAt = now + nextIdleDelayMs(random);
        if (look.getAsBoolean()) {
            lookAround(mc, hook, random);
        }

        // a step only happens crouched, so the shuffle cannot carry the player off the start block
        if (shuffle.getAsBoolean() && sneakAllowed && onOrigin && random.nextInt(IDLE_TAP_ONE_IN) == 0) {
            tapKey = switch (random.nextInt(4)) {
                case 0 -> options.keyUp;
                case 1 -> options.keyDown;
                case 2 -> options.keyLeft;
                default -> options.keyRight;
            };
            tapUntil = now + random.nextLong(IDLE_TAP_MIN_MS, IDLE_TAP_MAX_MS + 1);
        }
    }

    // drift around the float itself, offset inside a small box so the cursor is never dead centre on it
    private void lookAround(Minecraft mc, FishingHook hook, RandomGenerator random) {
        Vec3 glanceAt = glance == null ? null : glance.pick(mc, random);
        Vec3 aimAt = glanceAt != null ? glanceAt : hook.position();
        aimAt = aimAt.add(aimBoxOffset(random));
        float yawRange = glanceAt != null ? GLANCE_YAW_DEGREES : IDLE_YAW_DEGREES;
        float pitchRange = glanceAt != null ? GLANCE_PITCH_DEGREES : IDLE_PITCH_DEGREES;

        Vec3 eye = mc.player.getEyePosition();
        double dx = aimAt.x - eye.x;
        double dy = aimAt.y - eye.y;
        double dz = aimAt.z - eye.z;
        RotationManager.rotateToYawPitch(mc,
                CastSim.yawTo(dx, dz) + driftDegrees(random, yawRange),
                CastSim.pitchTo(dx, dy, dz) + driftDegrees(random, pitchRange),
                nextIdleTurnMs(random));
    }

    void anchor(long now, RandomGenerator random) {
        anchored = true;
        // settle onto the float shortly after it lands, then drift on the slower cadence
        nextAt = now + nextFirstIdleDelayMs(random);
        tapUntil = 0L;
        tapKey = null;
    }

    void clear() {
        anchored = false;
        nextAt = 0L;
        tapUntil = 0L;
        tapKey = null;
    }

    void dropTap() {
        tapKey = null;
    }

    static long nextIdleDelayMs(RandomGenerator random) {
        return random.nextLong(IDLE_MIN_DELAY_MS, IDLE_MAX_DELAY_MS + 1);
    }

    static boolean idleDelayInRange(long delay) {
        return delay >= IDLE_MIN_DELAY_MS && delay <= IDLE_MAX_DELAY_MS;
    }

    static Vec3 aimBoxOffset(RandomGenerator random) {
        return new Vec3(
                random.nextDouble(-AIM_BOX_RADIUS, AIM_BOX_RADIUS),
                random.nextDouble(-AIM_BOX_RADIUS, AIM_BOX_RADIUS),
                random.nextDouble(-AIM_BOX_RADIUS, AIM_BOX_RADIUS));
    }

    static boolean aimBoxOffsetInRange(Vec3 offset) {
        return Math.abs(offset.x) <= AIM_BOX_RADIUS
                && Math.abs(offset.y) <= AIM_BOX_RADIUS
                && Math.abs(offset.z) <= AIM_BOX_RADIUS;
    }

    static long nextIdleTurnMs(RandomGenerator random) {
        return random.nextLong(IDLE_TURN_MIN_MS, IDLE_TURN_MAX_MS + 1);
    }

    static boolean idleTurnInRange(long turnMs) {
        return turnMs >= IDLE_TURN_MIN_MS && turnMs <= IDLE_TURN_MAX_MS;
    }

    static long nextFirstIdleDelayMs(RandomGenerator random) {
        return random.nextLong(IDLE_FIRST_MIN_DELAY_MS, IDLE_FIRST_MAX_DELAY_MS + 1);
    }

    static boolean firstIdleDelayInRange(long delay) {
        return delay >= IDLE_FIRST_MIN_DELAY_MS && delay <= IDLE_FIRST_MAX_DELAY_MS;
    }

    static float driftDegrees(RandomGenerator random, float range) {
        return (float) random.nextDouble(-range, range);
    }
}
