package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

// wither impact aimed at the block underfoot: the teleport has nowhere to go, the implosion still goes off
final class HyperionClearer {

    // the implosion reaches 6 blocks, so this leaves room for the catch to drift while the click is in flight
    static final double REACH = 5.5;
    private static final long FAR_DROP_MS = 6_000L;
    private static final int GIVE_UP_CLICKS = 12;
    private static final long GIVE_UP_MS = 8_000L;
    private static final float LOOK_YAW_JITTER = 2.0f;
    private static final float LOOK_PITCH_MIN = 86.0f;
    private static final float LOOK_PITCH_MAX = 89.5f;
    // far enough to reach the floor from a standing eye at the shallowest look, short of the block beyond it
    private static final double FLOOR_RAY = 2.5;

    private int slot = -1;
    private boolean lookIssued;
    private boolean drawn;
    private long nextClickAt;
    private int clicksSinceKill;
    private long clockFrom;
    private long lastClickAt;
    private final Map<Integer, Long> farSince = new HashMap<>();

    void begin(int slot, long now) {
        this.slot = slot;
        lookIssued = false;
        drawn = false;
        nextClickAt = 0L;
        clicksSinceKill = 0;
        clockFrom = now;
        farSince.clear();
    }

    void onKill(long now) {
        clicksSinceKill = 0;
        clockFrom = now;
    }

    long lastClickAt() {
        return lastClickAt;
    }

    boolean clickedSinceKill() {
        return clicksSinceKill > 0;
    }

    boolean givenUp(long now) {
        return hyperionGivenUp(clicksSinceKill, now - clockFrom);
    }

    // returns the catches that stayed out of the implosion too long, for the caller to leave alone
    List<Integer> tick(Minecraft mc, long now, Collection<Integer> targets, RandomGenerator random) {
        List<Integer> dropped = new ArrayList<>();
        boolean inReach = false;
        farSince.keySet().retainAll(targets);
        for (int id : targets) {
            Entity entity = mc.level.getEntity(id);
            if (!CatchWatch.isAlive(entity)) {
                continue;
            }
            if (mc.player.distanceTo(entity) <= REACH) {
                farSince.remove(id);
                inReach = true;
            } else if (now - farSince.computeIfAbsent(id, key -> now) > FAR_DROP_MS) {
                dropped.add(id);
            }
        }
        dropped.forEach(farSince::remove);
        // the give up clock only runs while there is something the blade could be killing
        if (!inReach) {
            clockFrom = now;
        }

        if (!lookIssued) {
            lookDown(mc, random);
            return dropped;
        }
        if (RotationManager.isRotating()) {
            return dropped;
        }
        boolean floor = floorHit(mc);
        if (!shouldClickHyperion(true, mc.options.keyUse.isDown(), floor)) {
            if (!floor) {
                lookDown(mc, random);
            }
            return dropped;
        }
        if (FailsafeManager.getCurrentSelectedSlot(mc) != slot) {
            drawn = false;
        }
        if (!drawn) {
            FailsafeManager.selectHotbarSlot(mc, slot);
            drawn = true;
            nextClickAt = now + AbilitySwapClicker.nextDrawDelayMs(random, AbilitySwapClicker.HYPERION);
            return dropped;
        }
        if (!inReach || now < nextClickAt) {
            return dropped;
        }
        ClientUtils.performUseClickInstant();
        clicksSinceKill++;
        lastClickAt = now;
        nextClickAt = now + Math.max(AbilitySwapClicker.nextIntervalMs(random, AbilitySwapClicker.HYPERION),
                AbilitySwapClicker.HYPERION.minClickGapMs());
        return dropped;
    }

    // forced, since a look still running from the reel or a melee track would otherwise swallow it
    private void lookDown(Minecraft mc, RandomGenerator random) {
        RotationManager.cancelRotation();
        float yaw = mc.player.getYRot() + (float) random.nextDouble(-LOOK_YAW_JITTER, LOOK_YAW_JITTER);
        float pitch = (float) random.nextDouble(LOOK_PITCH_MIN, LOOK_PITCH_MAX);
        RotationManager.rotateToYawPitch(mc, yaw, pitch, AetherConfig.ROTATION_TIME.get(), true);
        lookIssued = true;
    }

    // knocked to the lip of the block, a steep look can pass by it into the water below and teleport the player
    static boolean floorHit(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(FLOOR_RAY));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(mc.player.getOnPos());
    }

    // a use key still held from the reel would fire the blade the moment it is in hand, wherever the camera is
    static boolean shouldClickHyperion(boolean rotationDone, boolean useKeyDown, boolean floorHit) {
        return rotationDone && !useKeyDown && floorHit;
    }

    static boolean hyperionGivenUp(int clicksSinceKill, long msSinceKill) {
        return clicksSinceKill >= GIVE_UP_CLICKS || msSinceKill >= GIVE_UP_MS;
    }
}
