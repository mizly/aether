package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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
    private static final double FOLLOW_UP_MIN_DEGREES = 10.0;
    private static final int MAX_FOLLOW_UPS = 2;
    private static final double MAX_FLICK_PITCH = 75.0;
    private static final double MIN_FLICK_HORIZONTAL = 1.2;
    private static final double REACT_MIN_MS = 20.0;
    private static final double REACT_MAX_MS = 60.0;
    private static final double SEARCH_MIN_MS = 60.0;
    private static final double SEARCH_MAX_MS = 160.0;
    private static final double MIN_TEMPO = 0.90;
    private static final double MAX_TEMPO = 1.15;
    private static final long MEMORY_WINDOW_MS = 8_000L;
    private static final double MEMORY_DOUBLES_AFTER_MS = 8_000.0;
    private static final double UNSEEN_ERROR_SCALE = 1.5;
    private static final double UNSEEN_MIN_ERROR_DEGREES = 5.0;
    private static final double PITCH_ERROR_SCALE = 0.3;
    private static final double MAX_PITCH_ERROR_DEGREES = 6.0;
    private static final double MAX_ERROR_SIGMAS = 2.5;
    private static final double MAX_REMEMBERED_PITCH = 85.0;

    private Phase phase = Phase.IDLE;
    private int targetId = -1;
    private AimKind kind = AimKind.VACUUM;
    private long beganAt;
    private long phaseUntil;
    private long flickId;
    private boolean usedMemory;
    private int followUps;
    private long completedAt;
    private double tempo = 1.0;

    // each run gets its own pace, so separate runs do not share one timing distribution
    void newRun(RandomGenerator random) {
        tempo = random.nextDouble(MIN_TEMPO, MAX_TEMPO);
    }

    // true holds the camera through a reaction even when no flick follows, false leaves the turn to the caller
    boolean begin(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind, long now) {
        return start(client, runtime, target, kind, now, true);
    }

    // for a pest already being chased or just landed next to, where waiting to notice it would freeze the camera
    boolean beginNow(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind, long now) {
        return start(client, runtime, target, kind, now, false);
    }

    private boolean start(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind, long now,
                          boolean noticeFirst) {
        if (isGone(target) || target != runtime.currentTarget || !AetherConfig.PEST_HUMAN_TARGET_SWITCH.get()
                || FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            return false;
        }
        reset();
        RotationManager.cancelRotation();
        if (!noticeFirst) {
            perceive(target.getId(), kind, now, 0L);
            decide(client, runtime, target, FLICK_MIN_DEGREES, now);
            return true;
        }
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
            case LANDED -> hold(Phase.REACT, now, reactionAfterFlick());
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
        usedMemory = false;
        followUps = 0;
        completedAt = 0L;
    }

    private void decide(Minecraft client, PestDestroyerRuntime runtime, Entity target, double minDegrees, long now) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 liveEye = eyeOf(target);
        // out of sight the turn goes where the player believes the pest is, the look after it finds the real one
        usedMemory = AetherConfig.PEST_MEMORY_ROTATION.get() && !PestView.canSee(client, liveEye);
        PestSightings.Sighting sighting = usedMemory
                ? recall(runtime.sightings, target.getId(), followUps > 0, now)
                : null;
        Vec3 believedEye = sighting != null ? sighting.eye() : liveEye;
        Vec3 aim = aimFrom(client, runtime, target, kind, believedEye);
        RotationUtils.Rotation look = RotationUtils.calculateLookAt(eye, aim);
        Vec3 lookPoint = aim;
        if (usedMemory) {
            look = misjudge(look, memoryErrorDegrees(sighting, AetherConfig.PEST_MEMORY_ERROR.get()),
                    ThreadLocalRandom.current());
            lookPoint = eye.add(Vec3.directionFromRotation(look.pitch, look.yaw));
        }
        if (!wantsFlick(PestView.angleFromCrosshair(client, lookPoint), minDegrees, look.pitch,
                Math.hypot(aim.x - eye.x, aim.z - eye.z))) {
            finish(now);
            return;
        }
        HumanFlick.Style style = style(AetherConfig.PEST_OVERSHOOT_MIN_ANGLE.get(),
                mostlyVertical(client.player.getYRot(), client.player.getXRot(), look)
                        ? 0 : AetherConfig.PEST_OVERSHOOT_CHANCE.get(),
                AetherConfig.PEST_OVERSHOOT_AMOUNT_MIN.get(),
                AetherConfig.PEST_OVERSHOOT_AMOUNT_MAX.get(), AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get(),
                targetWidthDegrees(target.getBbWidth(), eye.distanceTo(believedEye)));
        flickId = HumanFlick.start(client, look.yaw, look.pitch, usedMemory ? withoutSpread(style) : style);
        if (flickId == 0L) {
            finish(now);
            return;
        }
        phase = Phase.FLICK;
    }

    private long reactionAfterFlick() {
        if (!usedMemory) {
            return reactMs(ThreadLocalRandom.current(), tempo);
        }
        return searchMs(ThreadLocalRandom.current(), tempo);
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

    // after a swing toward where the pest should be it still has to be spotted before the correction
    static long searchMs(RandomGenerator random, double tempo) {
        return Math.round(tempo * HumanFlick.skewed(random, SEARCH_MIN_MS, SEARCH_MAX_MS));
    }

    // a pest still out of sight on a second look is placed by its rough bearing, not where it was remembered
    static PestSightings.Sighting recall(PestSightings sightings, int targetId, boolean followUp, long now) {
        return followUp ? null : sightings.lastSeen(targetId, now, MEMORY_WINDOW_MS);
    }

    // a fresh memory is off by about the configured error, growing to twice it over 8 s,
    // and a pest never seen is only a rough bearing, as if heard rather than seen
    static double memoryErrorDegrees(PestSightings.Sighting sighting, double errorDegrees) {
        if (sighting == null) {
            return Math.max(UNSEEN_MIN_ERROR_DEGREES, UNSEEN_ERROR_SCALE * errorDegrees);
        }
        return errorDegrees * (1.0 + Math.min(sighting.ageMs(), MEMORY_WINDOW_MS) / MEMORY_DOUBLES_AFTER_MS);
    }

    // pests keep to a band of height but can be anywhere around, so memory errs mostly in bearing,
    // and the cuts stop a rare draw from swinging somewhere absurd like the sky
    static RotationUtils.Rotation misjudge(RotationUtils.Rotation look, double errorDegrees, RandomGenerator random) {
        double yawError = Math.clamp(random.nextGaussian(), -MAX_ERROR_SIGMAS, MAX_ERROR_SIGMAS) * errorDegrees;
        double pitchError = Math.clamp(random.nextGaussian() * PITCH_ERROR_SCALE * errorDegrees,
                -MAX_PITCH_ERROR_DEGREES, MAX_PITCH_ERROR_DEGREES);
        return new RotationUtils.Rotation((float) (look.yaw + yawError),
                (float) Math.clamp(look.pitch + pitchError, -MAX_REMEMBERED_PITCH, MAX_REMEMBERED_PITCH));
    }

    // close to the crosshair the trackers finish the job, and near the poles yaw is so ill defined a flick spins
    static boolean wantsFlick(double offAxisDegrees, double minDegrees, float pitch, double horizontalDistance) {
        return offAxisDegrees > minDegrees
                && Math.abs(pitch) <= MAX_FLICK_PITCH
                && horizontalDistance >= MIN_FLICK_HORIZONTAL;
    }

    // a mouse overshoots along its sideways sweep, a mostly up or down turn overshooting flings the view at the sky
    static boolean mostlyVertical(float yaw, float pitch, RotationUtils.Rotation look) {
        return Math.abs(look.pitch - pitch) > Math.abs(Mth.wrapDegrees(look.yaw - yaw));
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

    // a remembered swing lands exactly where the player thinks the pest is, its error is in the memory
    static HumanFlick.Style withoutSpread(HumanFlick.Style style) {
        return new HumanFlick.Style(style.overshootFromDegrees(), style.overshootChance(),
                style.overshootMinFraction(), style.overshootMaxFraction(), style.turnSpeedCap(),
                style.targetWidthDegrees(), 0.0, style.stagedCorrections());
    }

    private static Vec3 liveAim(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind) {
        return aimFrom(client, runtime, target, kind, eyeOf(target));
    }

    // aims at the body spot the tracker holds afterwards, so the hand-over does not pull the view to the centre
    private static Vec3 aimFrom(Minecraft client, PestDestroyerRuntime runtime, Entity target, AimKind kind,
                                Vec3 eye) {
        return switch (kind) {
            case VACUUM -> PestCombatCoordinator.buildVacuumAimTarget(client, target,
                    PestAimTracker.bodySpot(target, eye));
            case EYE -> PestAimTracker.bodySpot(target, eye);
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
