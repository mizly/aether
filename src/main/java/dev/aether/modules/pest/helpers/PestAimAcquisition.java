package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

// turns onto each new pest the way a player does: notice it, flick, look again, and correct if still off
// while it owns the camera the pest trackers and clicks wait, then they take over from wherever it landed
final class PestAimAcquisition {
    enum Phase {
        IDLE,
        PERCEIVE,
        FLICK,
        REACT
    }

    enum AimKind {
        VACUUM,
        EYE,
        HUNT
    }

    enum Step {
        HOLD,
        DECIDE,
        LANDED,
        REASSESS,
        EXPIRED
    }

    // a low turn speed cap must not keep every other pest system waiting on one turn
    private static final long MAX_OWN_MS = 3_000L;
    private static final double ANTICIPATED_DEGREES = 25.0;
    private static final double ANTICIPATED_REACTION_SCALE = 0.75;
    private static final double FLICK_MIN_DEGREES = 10.0;
    private static final double FOLLOW_UP_MIN_DEGREES = 4.0;
    private static final int MAX_FOLLOW_UPS = 2;
    private static final double MAX_FLICK_PITCH = 75.0;
    private static final double MIN_FLICK_HORIZONTAL = 1.2;
    private static final double REACT_MIN_MS = 30.0;
    private static final double REACT_MAX_MS = 90.0;
    private static final double MIN_TEMPO = 0.90;
    private static final double MAX_TEMPO = 1.15;

    private Phase phase = Phase.IDLE;
    private int targetId = -1;
    private AimKind kind = AimKind.VACUUM;
    private long beganAt;
    private long phaseUntil;
    private long flickId;
    private int followUps;
    private long completedAt;
    private double tempo = 1.0;

    // each run gets its own pace, so separate runs do not share one timing distribution
    void newRun(RandomGenerator random) {
        tempo = random.nextDouble(MIN_TEMPO, MAX_TEMPO);
    }

    // true holds the camera through a reaction even when no flick follows, false leaves the turn to the caller
    boolean begin(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind, long now) {
        if (isGone(target) || target != runtime.currentTarget || !AetherConfig.PEST_HUMAN_TARGET_SWITCH.get()
                || FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            return false;
        }
        reset();
        RotationManager.cancelRotation();
        double offAxis = PestView.angleFromCrosshair(client, liveAim(client, runtime, target, kind));
        perceive(target.getId(), kind, now, perceiveMs(ThreadLocalRandom.current(), tempo,
                AetherConfig.PEST_REACTION_MIN_MS.get(), AetherConfig.PEST_REACTION_MAX_MS.get(), offAxis));
        return true;
    }

    void perceive(int targetId, AimKind kind, long now, long reactionMs) {
        this.targetId = targetId;
        this.kind = kind;
        beganAt = now;
        hold(Phase.PERCEIVE, now, reactionMs);
    }

    void tick(Minecraft client, PestDestroyerRuntime runtime, long now) {
        if (phase == Phase.IDLE) {
            return;
        }
        Entity target = runtime.currentTarget;
        if (isGone(target) || target.getId() != targetId) {
            reset();
            return;
        }
        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            finish(now);
            return;
        }
        switch (step(phase, now, beganAt, phaseUntil, HumanFlick.isActive(flickId))) {
            case HOLD -> {
            }
            case EXPIRED -> finish(now);
            case DECIDE -> decide(client, runtime, target, FLICK_MIN_DEGREES, now);
            case LANDED -> hold(Phase.REACT, now, reactMs(ThreadLocalRandom.current(), tempo));
            case REASSESS -> {
                double residual = PestView.angleFromCrosshair(client, liveAim(client, runtime, target, kind));
                if (needsFollowUp(residual, followUps)) {
                    followUps++;
                    decide(client, runtime, target, FOLLOW_UP_MIN_DEGREES, now);
                } else {
                    finish(now);
                }
            }
        }
    }

    boolean ownsCamera(Entity target) {
        return phase != Phase.IDLE && target != null && target.getId() == targetId;
    }

    boolean isHolding() {
        return phase == Phase.PERCEIVE || phase == Phase.REACT;
    }

    long completedAt() {
        return completedAt;
    }

    Phase phase() {
        return phase;
    }

    double tempo() {
        return tempo;
    }

    void reset() {
        cancelFlick();
        phase = Phase.IDLE;
        targetId = -1;
        kind = AimKind.VACUUM;
        beganAt = 0L;
        phaseUntil = 0L;
        followUps = 0;
        completedAt = 0L;
    }

    private void decide(Minecraft client, PestDestroyerRuntime runtime, Entity target, double minDegrees, long now) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 aim = liveAim(client, runtime, target, kind);
        RotationUtils.Rotation look = RotationUtils.calculateLookAt(eye, aim);
        if (!wantsFlick(PestView.angleFromCrosshair(client, aim), minDegrees, look.pitch,
                Math.hypot(aim.x - eye.x, aim.z - eye.z))) {
            finish(now);
            return;
        }
        double width = targetWidthDegrees(target.getBbWidth(), eye.distanceTo(eyeOf(target)));
        flickId = HumanFlick.start(client, look.yaw, look.pitch, style(
                AetherConfig.PEST_OVERSHOOT_MIN_ANGLE.get(), AetherConfig.PEST_OVERSHOOT_CHANCE.get(),
                AetherConfig.PEST_OVERSHOOT_AMOUNT_MIN.get(), AetherConfig.PEST_OVERSHOOT_AMOUNT_MAX.get(),
                AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get(), width));
        if (flickId == 0L) {
            finish(now);
            return;
        }
        phase = Phase.FLICK;
    }

    private void hold(Phase phase, long now, long ms) {
        this.phase = phase;
        phaseUntil = now + ms;
    }

    private void finish(long now) {
        cancelFlick();
        phase = Phase.IDLE;
        completedAt = now;
    }

    private void cancelFlick() {
        if (flickId != 0L) {
            HumanFlick.cancel(flickId);
            flickId = 0L;
        }
    }

    static Step step(Phase phase, long now, long beganAt, long phaseUntil, boolean flickActive) {
        if (phase != Phase.IDLE && now - beganAt > MAX_OWN_MS) {
            return Step.EXPIRED;
        }
        return switch (phase) {
            case IDLE -> Step.HOLD;
            case PERCEIVE -> now >= phaseUntil ? Step.DECIDE : Step.HOLD;
            case FLICK -> flickActive ? Step.HOLD : Step.LANDED;
            case REACT -> now >= phaseUntil ? Step.REASSESS : Step.HOLD;
        };
    }

    // a pest already near the crosshair needs no search, so it is picked up sooner
    static long perceiveMs(RandomGenerator random, double tempo, int reactionMinMs, int reactionMaxMs,
                           double offAxisDegrees) {
        double scale = offAxisDegrees <= ANTICIPATED_DEGREES ? ANTICIPATED_REACTION_SCALE : 1.0;
        return Math.round(tempo * HumanFlick.skewed(random, reactionMinMs, reactionMaxMs) * scale);
    }

    // the eyes need a moment to find the pest again after a flick before a correction can be judged
    static long reactMs(RandomGenerator random, double tempo) {
        return Math.round(tempo * HumanFlick.skewed(random, REACT_MIN_MS, REACT_MAX_MS));
    }

    // close to the crosshair the trackers finish the job, and near the poles yaw is so ill defined a flick spins
    static boolean wantsFlick(double offAxisDegrees, double minDegrees, float pitch, double horizontalDistance) {
        return offAxisDegrees > minDegrees
                && Math.abs(pitch) <= MAX_FLICK_PITCH
                && horizontalDistance >= MIN_FLICK_HORIZONTAL;
    }

    static boolean needsFollowUp(double residualDegrees, int followUps) {
        return residualDegrees > FOLLOW_UP_MIN_DEGREES && followUps < MAX_FOLLOW_UPS;
    }

    // a far pest is a small target, which takes a more careful hand to land on
    static double targetWidthDegrees(double boxWidth, double distance) {
        return Math.clamp(2.0 * Math.toDegrees(Math.atan2(boxWidth / 2.0, distance)), 1.0, 8.0);
    }

    // the hand comes to rest anywhere on the pest, not on its exact centre
    static HumanFlick.Style style(double overshootMinAngle, int chancePercent, int amountMinPercent,
                                  int amountMaxPercent, double turnSpeedCap, double targetWidthDegrees) {
        return HumanFlick.Style.humanized(overshootMinAngle, chancePercent / 100.0, amountMinPercent / 100.0,
                amountMaxPercent / 100.0, turnSpeedCap, targetWidthDegrees,
                Math.max(0.25, 0.3 * targetWidthDegrees / 2.0));
    }

    // read without PestAimTracker, whose lead and drift advance on every read
    private static Vec3 liveAim(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind) {
        Vec3 eye = eyeOf(target);
        return switch (kind) {
            case VACUUM -> PestCombatCoordinator.buildVacuumAimTarget(client, target, eye);
            case EYE -> eye;
            case HUNT -> PestHuntingController.acquisitionAimPoint(client, runtime, target, eye);
        };
    }

    private static Vec3 eyeOf(Entity target) {
        return target.position().add(0, target.getEyeHeight(target.getPose()), 0);
    }

    private static boolean isGone(Entity target) {
        return target == null
                || target.isRemoved()
                || target instanceof LivingEntity living && living.isDeadOrDying();
    }
}
