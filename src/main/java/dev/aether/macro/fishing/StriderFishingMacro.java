package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

// lava fishing for stridersurfers: cast, wait for the marker to flip from ? to !!, reel, kill, walk home
// every delay here is wall-clock and every decision runs on the client tick, so the macro behaves the same at 10 or 240 fps
public final class StriderFishingMacro extends AbstractMacro {

    public enum State { AIM_LAVA, CAST, WAIT_BITE, REEL, FIGHT, RETURN }

    private static final double LAVA_SCAN_RADIUS = 6.0;
    private static final double LAVA_SCAN_DEPTH = 4.0;
    private static final double RAY_DISTANCE = 12.0;
    private static final double MARKER_SEARCH_SIZE = 6.0;
    private static final double TARGET_SEARCH_RADIUS = 16.0;

    private static final long BITE_TIMEOUT_MS = 90_000L;
    private static final long ACQUIRE_TIMEOUT_MS = 6_000L;
    private static final long FIGHT_TIMEOUT_MS = 45_000L;
    // 3-6 cps, redrawn every swing so the cadence is not a metronome
    private static final long ATTACK_MIN_DELAY_MS = 167L;
    private static final long ATTACK_MAX_DELAY_MS = 333L;
    private static final long RETURN_DELAY_MIN_MS = 75L;
    private static final long RETURN_DELAY_MAX_MS = 175L;
    // nothing was caught, so the next cast waits out a human pause instead of snapping straight back
    private static final long EMPTY_CATCH_DELAY_MIN_MS = 1_500L;
    private static final long EMPTY_CATCH_DELAY_MAX_MS = 3_500L;
    private static final double LOOK_AHEAD_DISTANCE = 6.0;
    private static final double LOOK_DOWN_DEGREES = 5.0;
    private static final double MIN_TRAVEL_SPEED_SQR = 1.0e-4;
    private static final double ETHERWARP_MIN_DISTANCE = 4.0;
    // wading out of lava is slow and expensive, so the warp takes over as soon as there is anywhere to go
    private static final double ETHERWARP_LIQUID_MIN_DISTANCE = 1.0;
    private static final long BOBBER_SETTLE_MS = 1_500L;
    private static final long REEL_SETTLE_MS = 350L;

    private static final long IDLE_MIN_DELAY_MS = 2_500L;
    private static final long IDLE_MAX_DELAY_MS = 7_000L;
    private static final long IDLE_TURN_MIN_MS = 500L;
    private static final long IDLE_TURN_MAX_MS = 1_100L;
    private static final long IDLE_TAP_MIN_MS = 90L;
    private static final long IDLE_TAP_MAX_MS = 200L;
    private static final float IDLE_YAW_DEGREES = 2.5f;
    private static final float IDLE_PITCH_DEGREES = 1.5f;
    private static final int IDLE_TAP_ONE_IN = 4;

    private static final float AIM_SMOOTHING_MS = 110.0f;
    private static final float AIM_MAX_TURN_SPEED = 520.0f;
    private static final double ATTACK_RANGE_SLACK = 0.85;
    private static final double FOLLOW_BAND = 0.35;
    private static final int MAX_AIM_ATTEMPTS = 6;
    private static final int MAX_RETURN_ATTEMPTS = 3;

    private State state = State.AIM_LAVA;
    private BlockPos origin;
    private long stateEnteredAt;
    private long nextActionAt;
    private long nextAttackAt;
    private long returnAt;
    private int followMove;
    private boolean emptyCatch;
    private int aimAttempts;
    private int returnAttempts;
    private Entity target;
    private boolean returnPathStarted;
    private boolean returnByWalk;
    private volatile boolean returnFinished;

    // everything loaded when the line was reeled, so a catch is told apart from whatever was already swimming
    private final Set<Integer> preReelEntityIds = new HashSet<>();

    private float idleAnchorYaw;
    private float idleAnchorPitch;
    private boolean idleAnchored;
    private long idleNextAt;
    private long idleTapUntil;
    private KeyMapping idleTapKey;

    @Override
    public void onEnable(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        origin = mc.player.blockPosition();
        target = null;
        followMove = 0;
        aimAttempts = 0;
        returnAttempts = 0;
        returnPathStarted = false;
        returnFinished = false;
        nextAttackAt = 0L;
        returnAt = 0L;
        emptyCatch = false;
        preReelEntityIds.clear();
        clearIdle();
        changeState(State.AIM_LAVA);
        ClientUtils.sendDebugMessage("[StriderFishing] started at "
                + origin.getX() + ", " + origin.getY() + ", " + origin.getZ());
    }

    @Override
    public void onDisable(Minecraft mc) {
        PathfindingManager.stop(false);
        RotationManager.cancelRotation();
        releaseAll(mc);
        target = null;
        followMove = 0;
        returnPathStarted = false;
        returnFinished = false;
        preReelEntityIds.clear();
        clearIdle();
    }

    @Override
    public void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mc.screen != null) {
            releaseAll(mc);
            return;
        }

        // only the lava turn has to land before its state can carry on; waiting for a bite still has to
        // poll the marker every tick, or an idle drift would hide the short !! window
        if (state == State.AIM_LAVA && RotationManager.isRotating()) {
            holdStill(mc);
            return;
        }

        switch (state) {
            case AIM_LAVA -> tickAimLava(mc);
            case CAST -> tickCast(mc);
            case WAIT_BITE -> tickWaitBite(mc);
            case REEL -> tickReel(mc);
            case FIGHT -> tickFight(mc);
            case RETURN -> tickReturn(mc);
        }
    }

    private void tickAimLava(Minecraft mc) {
        holdStill(mc);

        if (!isOnOrigin(mc)) {
            beginReturn(mc);
            return;
        }

        if (isLookingAtLava(mc)) {
            FailsafeManager.selectHotbarSlot(mc, rodSlot());
            changeState(State.CAST);
            nextActionAt = System.currentTimeMillis() + castDelayForCycle();
            return;
        }

        if (++aimAttempts > MAX_AIM_ATTEMPTS) {
            fail("Strider fishing stopped: could not line up any lava to cast into.");
            return;
        }

        Vec3 lava = findLavaSurface(mc);
        if (lava == null) {
            fail("Strider fishing stopped: no lava within reach of the start block.");
            return;
        }
        RotationManager.initiateRotation(mc, lava, AetherConfig.ROTATION_TIME.get());
    }

    private void tickCast(Minecraft mc) {
        holdStill(mc);
        long now = System.currentTimeMillis();
        if (now < nextActionAt) {
            return;
        }

        if (!isOnOrigin(mc)) {
            beginReturn(mc);
            return;
        }

        // the camera can still be settling from the walk back, so the lava is confirmed again at the last moment
        if (!isLookingAtLava(mc)) {
            if (++aimAttempts > MAX_AIM_ATTEMPTS) {
                fail("Strider fishing stopped: could not keep the rod pointed at lava.");
                return;
            }
            changeState(State.AIM_LAVA);
            return;
        }

        FailsafeManager.selectHotbarSlot(mc, rodSlot());
        ClientUtils.performUseClick();
        // a bobber still out means that click reeled the stuck line in, so cast on the next pass
        if (hasLiveHook(mc)) {
            nextActionAt = now + castDelayMs();
            return;
        }
        aimAttempts = 0;
        emptyCatch = false;
        anchorIdle(mc, now);
        changeState(State.WAIT_BITE);
    }

    private void tickWaitBite(Minecraft mc) {
        long now = System.currentTimeMillis();

        if (!hasLiveHook(mc)) {
            holdStill(mc);
            // the cast never left the rod, or the line came back on its own
            if (now - stateEnteredAt > BOBBER_SETTLE_MS) {
                recast(now);
            }
            return;
        }

        if (hasCatchMarker(mc, mc.player.fishing)) {
            clearIdle();
            snapshotLoadedEntities(mc);
            changeState(State.REEL);
            return;
        }

        if (now - stateEnteredAt > BITE_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[StriderFishing] no bite in time, recasting");
            clearIdle();
            recast(now);
            return;
        }

        tickIdleMotion(mc, now);
    }

    private void tickReel(Minecraft mc) {
        holdStill(mc);
        ClientUtils.performUseClick();
        target = null;
        followMove = 0;
        returnAt = 0L;
        changeState(State.FIGHT);
        nextActionAt = System.currentTimeMillis() + REEL_SETTLE_MS;
    }

    private void tickFight(Minecraft mc) {
        long now = System.currentTimeMillis();

        if (target != null && !isAlive(target)) {
            target = null;
            // the catch is down, so head back now instead of sitting out the acquire window
            returnAt = now + nextReturnDelayMs(ThreadLocalRandom.current());
        }
        if (target == null) {
            target = findTarget(mc);
            if (target != null) {
                returnAt = 0L;
                emptyCatch = false;
            }
        }

        if (target == null) {
            holdStill(mc);
            if (returnAt != 0L) {
                if (now >= returnAt) {
                    beginReturn(mc);
                }
                return;
            }
            // item drops and empty catches never spawn a mob, so go back and cast again
            if (now - stateEnteredAt > ACQUIRE_TIMEOUT_MS) {
                emptyCatch = true;
                beginReturn(mc);
            }
            return;
        }

        if (now - stateEnteredAt > FIGHT_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[StriderFishing] fight timed out, returning");
            beginReturn(mc);
            return;
        }

        FailsafeManager.selectHotbarSlot(mc, weaponSlot());

        Vec3 aim = aimPoint(target);
        RotationManager.trackRotation(mc, aim, AIM_SMOOTHING_MS, AIM_MAX_TURN_SPEED);

        double follow = AetherConfig.STRIDER_FISHING_KILL_DISTANCE.get();
        double horizontal = horizontalDistanceTo(mc, target);
        followMove = followDirection(horizontal, follow, followMove);

        var options = mc.options;
        MacroInput.set(options.keyUp, followMove > 0);
        MacroInput.set(options.keyDown, followMove < 0);
        MacroInput.set(options.keyLeft, false);
        MacroInput.set(options.keyRight, false);
        MacroInput.set(options.keySprint, false);
        MacroInput.set(options.keyJump, false);
        // crouching through the kill is what keeps the player off the ledge it was pulled from
        MacroInput.set(options.keyShift, sneakAllowedHere(mc));

        if (now < nextActionAt) {
            return;
        }
        // the tracker is already on the catch, so swing on cadence instead of waiting for a perfect angle
        if (horizontal <= follow + ATTACK_RANGE_SLACK && now >= nextAttackAt) {
            ClientUtils.performAttackClick();
            nextAttackAt = now + nextAttackDelayMs(ThreadLocalRandom.current());
        }
    }

    private void tickReturn(Minecraft mc) {
        if (origin == null) {
            changeState(State.AIM_LAVA);
            return;
        }

        if (isOnOrigin(mc)) {
            PathfindingManager.stop(false);
            arriveHome(mc);
            return;
        }

        if (returnFinished) {
            // the route ended somewhere else, so line the block up again rather than casting from it
            returnFinished = false;
            returnPathStarted = false;
            if (++returnAttempts > MAX_RETURN_ATTEMPTS) {
                fail("Strider fishing stopped: could not get back onto the start block.");
            }
            return;
        }

        if (!returnPathStarted) {
            returnPathStarted = true;
            Vec3 home = Vec3.atBottomCenterOf(origin);
            if (shouldEtherwarp(mc.player.position().distanceTo(home),
                    mc.player.isInLiquid(),
                    AetherConfig.STRIDER_FISHING_ETHERWARP_RETURN.get())) {
                returnByWalk = false;
                PathfindingManager.startConfiguredPureEtherwarp(mc,
                        origin.getX(), origin.getY(), origin.getZ(),
                        () -> returnFinished = true,
                        () -> mc.execute(() -> startWalkHome(mc, home)));
                return;
            }
            startWalkHome(mc, home);
            return;
        }

        // the warp has to keep its own aim, so only the walk gets the relaxed camera
        if (returnByWalk && PathfindingManager.isNavigating()) {
            PathfindingManager.setWalkLookTarget(travelLookTarget(mc));
        }
    }

    private void startWalkHome(Minecraft mc, Vec3 home) {
        returnByWalk = true;
        PathfindingManager.startConfiguredWalk(mc, home,
                () -> returnFinished = true,
                () -> returnFinished = true,
                true, 0.35, true, false);
    }

    // small slow camera drift and the occasional short step, so a long wait is not a statue staring at lava
    private void tickIdleMotion(Minecraft mc, long now) {
        var options = mc.options;
        boolean tapping = now < idleTapUntil && idleTapKey != null;
        if (!tapping && idleTapKey != null) {
            MacroInput.set(idleTapKey, false);
            idleTapKey = null;
        }

        MacroInput.set(options.keyUp, false);
        MacroInput.set(options.keyDown, false);
        MacroInput.set(options.keyLeft, false);
        MacroInput.set(options.keyRight, false);
        MacroInput.set(options.keySprint, false);
        MacroInput.set(options.keyJump, false);
        boolean sneak = shouldSneak(mc) || tapping;
        MacroInput.set(options.keyShift, sneak);
        if (tapping) {
            MacroInput.set(idleTapKey, true);
        }

        if (!idleAnchored || now < idleNextAt || RotationManager.isRotating()) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        RotationManager.rotateToYawPitch(mc,
                idleAnchorYaw + driftDegrees(random, IDLE_YAW_DEGREES),
                idleAnchorPitch + driftDegrees(random, IDLE_PITCH_DEGREES),
                random.nextLong(IDLE_TURN_MIN_MS, IDLE_TURN_MAX_MS + 1));
        idleNextAt = now + nextIdleDelayMs(random);

        // a step only happens crouched, so the shuffle cannot carry the player off the start block
        if (sneakAllowedHere(mc) && isOnOrigin(mc) && random.nextInt(IDLE_TAP_ONE_IN) == 0) {
            idleTapKey = switch (random.nextInt(4)) {
                case 0 -> options.keyUp;
                case 1 -> options.keyDown;
                case 2 -> options.keyLeft;
                default -> options.keyRight;
            };
            idleTapUntil = now + random.nextLong(IDLE_TAP_MIN_MS, IDLE_TAP_MAX_MS + 1);
        }
    }

    private void anchorIdle(Minecraft mc, long now) {
        idleAnchorYaw = mc.player.getYRot();
        idleAnchorPitch = mc.player.getXRot();
        idleAnchored = true;
        idleNextAt = now + nextIdleDelayMs(ThreadLocalRandom.current());
        idleTapUntil = 0L;
        idleTapKey = null;
    }

    private void clearIdle() {
        idleAnchored = false;
        idleNextAt = 0L;
        idleTapUntil = 0L;
        idleTapKey = null;
    }

    static long nextIdleDelayMs(ThreadLocalRandom random) {
        return random.nextLong(IDLE_MIN_DELAY_MS, IDLE_MAX_DELAY_MS + 1);
    }

    static float driftDegrees(ThreadLocalRandom random, float range) {
        return (float) random.nextDouble(-range, range);
    }

    static boolean idleDelayInRange(long delay) {
        return delay >= IDLE_MIN_DELAY_MS && delay <= IDLE_MAX_DELAY_MS;
    }

    private void changeState(State next) {
        if (state == State.FIGHT && next != State.FIGHT) {
            RotationManager.cancelRotation();
        }
        state = next;
        stateEnteredAt = System.currentTimeMillis();
    }

    private void recast(long now) {
        // the line came back with nothing on it, so the next cast is not instant
        emptyCatch = true;
        changeState(State.CAST);
        nextActionAt = now + castDelayForCycle();
    }

    private void beginReturn(Minecraft mc) {
        target = null;
        followMove = 0;
        returnPathStarted = false;
        returnByWalk = false;
        returnFinished = false;
        clearIdle();
        releaseAll(mc);
        changeState(State.RETURN);
    }

    private void arriveHome(Minecraft mc) {
        returnPathStarted = false;
        returnByWalk = false;
        returnFinished = false;
        returnAttempts = 0;
        releaseAll(mc);
        aimAttempts = 0;
        // the route leaves the camera wherever it was steering, so the lava aim starts from a clean slate
        RotationManager.cancelRotation();
        changeState(State.AIM_LAVA);
    }

    private void fail(String message) {
        ClientUtils.sendMessage("§c" + message, false);
        MacroStateManager.stopMacro(Minecraft.getInstance(), message, false);
    }

    private boolean isOnOrigin(Minecraft mc) {
        return origin != null && origin.equals(mc.player.blockPosition());
    }

    private void holdStill(Minecraft mc) {
        var options = mc.options;
        MacroInput.set(options.keyUp, false);
        MacroInput.set(options.keyDown, false);
        MacroInput.set(options.keyLeft, false);
        MacroInput.set(options.keyRight, false);
        MacroInput.set(options.keySprint, false);
        MacroInput.set(options.keyJump, false);
        MacroInput.set(options.keyShift, shouldSneak(mc));
    }

    public void releaseAll(Minecraft mc) {
        if (mc == null || mc.options == null) {
            return;
        }
        idleTapKey = null;
        MacroInput.setAttack(mc.options.keyAttack, false);
        MacroInput.releaseMovement(mc);
    }

    // the walk home is the one leg that stays un-sneaked, so it is not a crawl
    private boolean shouldSneak(Minecraft mc) {
        if (state == State.RETURN || !AetherConfig.STRIDER_FISHING_ALWAYS_SNEAK.get()) {
            return false;
        }
        return sneakAllowedHere(mc);
    }

    private static boolean sneakAllowedHere(Minecraft mc) {
        boolean inLiquid = mc.player != null && mc.player.isInLiquid();
        return sneakAllowedInLiquid(inLiquid, AetherConfig.STRIDER_FISHING_SNEAK_IN_LIQUID.get());
    }

    // crouching does nothing while swimming, so dropping it there keeps the sneak on solid ground
    static boolean sneakAllowedInLiquid(boolean inLiquid, boolean continueInLiquid) {
        return continueInLiquid || !inLiquid;
    }

    static long nextAttackDelayMs(ThreadLocalRandom random) {
        return random.nextLong(ATTACK_MIN_DELAY_MS, ATTACK_MAX_DELAY_MS + 1);
    }

    static boolean attackDelayInRange(long delay) {
        return delay >= ATTACK_MIN_DELAY_MS && delay <= ATTACK_MAX_DELAY_MS;
    }

    static long nextReturnDelayMs(ThreadLocalRandom random) {
        return random.nextLong(RETURN_DELAY_MIN_MS, RETURN_DELAY_MAX_MS + 1);
    }

    static boolean returnDelayInRange(long delay) {
        return delay >= RETURN_DELAY_MIN_MS && delay <= RETURN_DELAY_MAX_MS;
    }

    // walking a few blocks beats lining up a warp, but lava is worth leaving at once
    static boolean shouldEtherwarp(double distance, boolean inLiquid, boolean etherwarpEnabled) {
        if (!etherwarpEnabled) {
            return false;
        }
        return distance >= (inLiquid ? ETHERWARP_LIQUID_MIN_DISTANCE : ETHERWARP_MIN_DISTANCE);
    }

    private static int rodSlot() {
        return Mth.clamp(AetherConfig.STRIDER_FISHING_ROD_SLOT.get() - 1, 0, 8);
    }

    private static int weaponSlot() {
        return Mth.clamp(AetherConfig.STRIDER_FISHING_WEAPON_SLOT.get() - 1, 0, 8);
    }

    // aiming down the path snaps the camera around every corner, so it just looks where it is walking
    private static Vec3 travelLookTarget(Minecraft mc) {
        Vec3 motion = mc.player.getDeltaMovement();
        double yawRad = Math.toRadians(travelYawDegrees(motion.x, motion.z, mc.player.getYRot()));
        Vec3 ahead = new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad)).scale(LOOK_AHEAD_DISTANCE);
        return mc.player.getEyePosition().add(ahead).subtract(0.0, lookDrop(), 0.0);
    }

    static float travelYawDegrees(double motionX, double motionZ, float fallbackYaw) {
        if (motionX * motionX + motionZ * motionZ < MIN_TRAVEL_SPEED_SQR) {
            return fallbackYaw;
        }
        return (float) Math.toDegrees(Math.atan2(-motionX, motionZ));
    }

    static double lookDrop() {
        return LOOK_AHEAD_DISTANCE * Math.tan(Math.toRadians(LOOK_DOWN_DEGREES));
    }

    private long castDelayForCycle() {
        return emptyCatch
                ? nextEmptyCatchDelayMs(ThreadLocalRandom.current())
                : castDelayMs();
    }

    static long nextEmptyCatchDelayMs(ThreadLocalRandom random) {
        return random.nextLong(EMPTY_CATCH_DELAY_MIN_MS, EMPTY_CATCH_DELAY_MAX_MS + 1);
    }

    static boolean emptyCatchDelayInRange(long delay) {
        return delay >= EMPTY_CATCH_DELAY_MIN_MS && delay <= EMPTY_CATCH_DELAY_MAX_MS;
    }

    private static long castDelayMs() {
        return ConfigHelpers.getRandomizedDelay(
                AetherConfig.STRIDER_FISHING_CAST_DELAY_MIN.get(),
                AetherConfig.STRIDER_FISHING_CAST_DELAY_MAX.get());
    }

    private static boolean hasLiveHook(Minecraft mc) {
        FishingHook hook = mc.player.fishing;
        return hook != null && !hook.isRemoved();
    }

    private static boolean isLookingAtLava(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(RAY_DISTANCE));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK
                && isLava(mc.level.getBlockState(hit.getBlockPos()));
    }

    private static boolean isLava(BlockState state) {
        if (state.getBlock() == Blocks.LAVA) {
            return true;
        }
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.getType().isSame(Fluids.LAVA);
    }

    private static Vec3 findLavaSurface(Minecraft mc) {
        BlockPos base = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;

        int radius = (int) LAVA_SCAN_RADIUS;
        int depth = (int) LAVA_SCAN_DEPTH;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -depth; dy <= 1; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!isLava(mc.level.getBlockState(pos))) {
                        continue;
                    }
                    BlockPos above = pos.above();
                    if (!mc.level.getBlockState(above).getCollisionShape(mc.level, above).isEmpty()) {
                        continue;
                    }
                    Vec3 surface = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    double distance = eye.distanceToSqr(surface);
                    if (distance < bestDistance && ClientUtils.hasLineOfSight(mc.player, surface)) {
                        bestDistance = distance;
                        best = surface;
                    }
                }
            }
        }
        return best;
    }

    private static boolean hasCatchMarker(Minecraft mc, FishingHook hook) {
        AABB box = AABB.ofSize(hook.position(),
                MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE);
        for (ArmorStand marker : mc.level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (marker.isRemoved() || marker.getCustomName() == null) {
                continue;
            }
            if (isCatchMarker(stripFormatting(marker.getCustomName().getString()))) {
                return true;
            }
        }
        return false;
    }

    // the bite marker shows a single ? and flips to !! once the catch is on the line
    static boolean isCatchMarker(String plainName) {
        return plainName != null && plainName.contains("!!");
    }

    static boolean isBiteMarker(String plainName) {
        return plainName != null && plainName.contains("?");
    }

    private void snapshotLoadedEntities(Minecraft mc) {
        preReelEntityIds.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity && !(entity instanceof ArmorStand)) {
                preReelEntityIds.add(entity.getId());
            }
        }
    }

    // a catch is only whatever the reel pulled up, so drops and mobs that were already swimming are left alone
    static boolean shouldAcceptTarget(int entityId, Set<Integer> preReelEntityIds) {
        return !preReelEntityIds.contains(entityId);
    }

    private Entity findTarget(Minecraft mc) {
        String wanted = AetherConfig.STRIDER_FISHING_TARGET_NAME.get();
        String needle = wanted == null ? "" : stripFormatting(wanted).toLowerCase(Locale.ROOT).trim();

        AABB box = AABB.ofSize(mc.player.position(),
                TARGET_SEARCH_RADIUS * 2, TARGET_SEARCH_RADIUS, TARGET_SEARCH_RADIUS * 2);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(mc.player, box)) {
            if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand || !isAlive(entity)) {
                continue;
            }
            if (!shouldAcceptTarget(entity.getId(), preReelEntityIds)) {
                continue;
            }
            if (!needle.isEmpty() && !matchesName(mc, entity, needle)) {
                continue;
            }
            double distance = entity.distanceToSqr(mc.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    // sea creatures carry their name on a separate plate, so a miss on the mob still has to check above it
    private static boolean matchesName(Minecraft mc, Entity entity, String needle) {
        if (stripFormatting(entity.getDisplayName().getString()).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        AABB box = AABB.ofSize(entity.position().add(0.0, 1.0, 0.0), 3.0, 4.0, 3.0);
        for (ArmorStand marker : mc.level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (marker.getCustomName() != null
                    && stripFormatting(marker.getCustomName().getString())
                            .toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlive(Entity entity) {
        return entity != null
                && !entity.isRemoved()
                && !(entity instanceof LivingEntity living && living.isDeadOrDying());
    }

    private static Vec3 aimPoint(Entity target) {
        return target.position().add(0.0, target.getBbHeight() * 0.6, 0.0);
    }

    private static double horizontalDistanceTo(Minecraft mc, Entity target) {
        double dx = mc.player.getX() - target.getX();
        double dz = mc.player.getZ() - target.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // 1 closes, -1 backs off, 0 holds, latched on previous
    // an unlatched dead band answers every overshoot with the opposite key and pumps forward and back
    static int followDirection(double horizontal, double follow, int previous) {
        if (previous > 0) {
            return horizontal > follow ? 1 : 0;
        }
        if (previous < 0) {
            return horizontal < follow ? -1 : 0;
        }
        if (horizontal > follow + FOLLOW_BAND) {
            return 1;
        }
        return horizontal < follow - FOLLOW_BAND ? -1 : 0;
    }

    static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-or]", "").trim();
    }

    public State getState() {
        return state;
    }
}
