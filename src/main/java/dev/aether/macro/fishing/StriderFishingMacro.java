package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.profit.helpers.ActivityRateTracker;
import dev.aether.modules.routes.EtherwarpLeg;
import dev.aether.modules.routes.Route;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;

// lava fishing for stridersurfers: cast, wait for the marker to flip from ? to !!, reel, kill, walk home
// every delay here is wall-clock and every decision runs on the client tick, so the macro behaves the same at 10 or 240 fps
public final class StriderFishingMacro extends AbstractFishingMacro {

    public enum State { AIM_LAVA, CAST, WAIT_BITE, REEL, FIGHT, CLEAR, RETURN }

    private static final double LAVA_SCAN_RADIUS = 6.0;
    private static final double LAVA_SCAN_DEPTH = 4.0;
    private static final double MARKER_SEARCH_SIZE = 6.0;
    private static final double TARGET_SEARCH_RADIUS = 16.0;
    // with no route the macro fishes the lava pit beside sawyer on galatea, where a caught strider cannot walk out
    static final BlockPos FIXED_SPOT = new BlockPos(-694, 120, 78);
    // the galatea warp lands about 178 blocks out, so from inside this the walk alone is the shorter way there
    static final double FIXED_SPOT_WALK_RANGE = 96.0;

    private static final long BITE_TIMEOUT_MS = 90_000L;
    // a catch surfaces within a tick or two, so anything slower than this means the reel brought up loot
    private static final long ACQUIRE_TIMEOUT_MS = 800L;
    private static final long FIGHT_TIMEOUT_MS = 45_000L;
    // 3-6 cps, redrawn every swing so the cadence is not a metronome
    private static final long ATTACK_MIN_DELAY_MS = 167L;
    private static final long ATTACK_MAX_DELAY_MS = 333L;
    private static final long RETURN_DELAY_MIN_MS = 25L;
    private static final long RETURN_DELAY_MAX_MS = 80L;
    // loot instead of a mob leaves nothing to fight, so the line goes straight back out
    private static final long EMPTY_CATCH_DELAY_MIN_MS = 150L;
    private static final long EMPTY_CATCH_DELAY_MAX_MS = 400L;
    // the block being walked to sits just under eye level, so watching it reads as ahead and slightly down
    private static final double LOOK_TARGET_HEIGHT = 1.2;
    private static final long LIQUID_JUMP_MIN_DELAY_MS = 100L;
    private static final long LIQUID_JUMP_MAX_DELAY_MS = 300L;
    private static final double ETHERWARP_MIN_DISTANCE = 4.0;
    // wading out of lava is slow and expensive, so the warp takes over as soon as there is anywhere to go
    private static final double ETHERWARP_LIQUID_MIN_DISTANCE = 1.0;
    private static final long BOBBER_SETTLE_MS = 1_500L;
    // the float needs time to finish its arc before where it landed means anything
    private static final long BOBBER_LANDED_MS = 1_200L;
    private static final long REEL_SETTLE_MS = 350L;

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

    private static final float AIM_SMOOTHING_MS = 110.0f;
    private static final float AIM_MAX_TURN_SPEED = 520.0f;
    private static final double ATTACK_RANGE_SLACK = 0.85;
    private static final double FOLLOW_BAND = 0.35;
    private static final double MAX_LAVA_SCAN_RADIUS = 14.0;
    private static final long AIM_RETRY_MIN_MS = 400L;
    private static final long AIM_RETRY_MAX_MS = 900L;
    private static final int MAX_RETURN_ATTEMPTS = 6;
    // a failed plan usually means we are still sinking in lava, so the jump needs time before retrying
    private static final long RETURN_RETRY_MIN_MS = 500L;
    private static final long RETURN_RETRY_MAX_MS = 900L;
    // the block footprint plus a sliver; an exact block match alone routes us to where we already stand
    private static final double ORIGIN_RADIUS = 0.55;
    private static final double ORIGIN_BELOW = 0.5;
    private static final double ORIGIN_ABOVE = 1.0;
    // the nearest lava is usually straight down at our feet, which is no way to cast
    private static final double MIN_CAST_HORIZONTAL = 1.0;
    // vanilla bobber flight: 0.3 ahead of the eye, 0.6/len + ~0.5 speed per axis, -0.03 gravity then 0.92 drag each tick
    private static final double CAST_START_OFFSET = 0.3;
    private static final double CAST_SPEED_BASE = 0.6;
    private static final double CAST_SPEED_BONUS = 0.5;
    private static final double CAST_GRAVITY = 0.03;
    private static final double CAST_DRAG = 0.92;
    private static final int CAST_MAX_TICKS = 60;
    private static final int CAST_SUBSTEPS = 4;
    private static final double CAST_HOOK_HALF_WIDTH = 0.125;
    private static final double CAST_HOOK_HEIGHT = 0.25;
    // an upward lob is allowed, since a rim above the lava can leave no downward throw that clears it
    private static final float CAST_PITCH_MIN = -30.0f;
    private static final float CAST_PITCH_MAX = 89.0f;
    private static final float CAST_PITCH_STEP = 0.5f;
    // pitches either side that still land in lava, so a little aim error or throw scatter does not hit the rim
    private static final int CAST_MARGIN_CAP = 6;
    // aim somewhere inside the block rather than its exact centre, so casts do not stack on one pixel
    private static final double CAST_TARGET_JITTER = 0.2;
    private static final double AIM_BOX_RADIUS = 0.18;

    // a pool that never empties means a catch slipped out of reach, so the rest is written off and fishing resumes
    private static final long CLEAR_TIMEOUT_MS = 90_000L;
    // a strider that has taken this many whips, or this long, is not dying to them, so the weapon finishes it
    private static final int WHIP_GIVE_UP_SWINGS = 6;
    private static final long WHIP_GIVE_UP_MS = 8_000L;
    // two strays in a row means the whip itself is out, usually mana, so the rest of the pool goes by hand
    private static final int WHIP_GIVE_UP_STREAK = 2;
    // the pool sits beside the start block; a catch this far out, or one that jumped this far in a tick, was moved
    private static final double CAGE_RADIUS = 7.0;
    private static final double CAGE_TELEPORT_JUMP = 3.0;
    // the hotbar key for the whip goes down a beat before the right click, never on the same frame
    private static final long WHIP_DRAW_MIN_MS = 45L;
    private static final long WHIP_DRAW_MAX_MS = 120L;
    private static final long WHIP_INTERVAL_MIN_MS = 350L;
    private static final long WHIP_INTERVAL_MAX_MS = 800L;
    // now and then the weapon key is fumbled a little late, the way a real hand misses the rhythm
    private static final int WHIP_HESITATE_ONE_IN = 12;
    private static final long WHIP_HESITATE_MIN_MS = 30L;
    private static final long WHIP_HESITATE_MAX_MS = 90L;
    private static final float WHIP_AIM_TOLERANCE_DEGREES = 6.0f;
    // the whip's swing lands above the crosshair, so aiming at the legs puts it through the body
    private static final double WHIP_AIM_HEIGHT = 0.15;
    private static final int GLANCE_AT_POOL_ONE_IN = 3;
    private static final float GLANCE_YAW_DEGREES = 5.0f;
    private static final float GLANCE_PITCH_DEGREES = 3.0f;

    private State state = State.AIM_LAVA;
    private BlockPos origin;
    private long stateEnteredAt;
    private long nextActionAt;
    private long nextAttackAt;
    private long returnAt;
    private long jumpHoldAt;
    private int followMove;
    private boolean emptyCatch;
    private final Set<BlockPos> rejectedLava = new HashSet<>();
    private BlockPos aimTargetBlock;
    private long aimRetryAt;
    private int aimSweep;
    private int returnAttempts;
    private Entity target;
    private boolean returnPathStarted;
    private boolean returnByWalk;
    private boolean etherwarpUsed;
    private EtherwarpLeg returnWarp;
    private long returnRetryAt;
    private volatile boolean returnFinished;

    private final Set<Integer> pooledCatchIds = new LinkedHashSet<>();
    // outlives the macro instance, so a stop and start in the same lobby picks the pool back up
    private static final Set<Integer> rememberedCatchIds = new LinkedHashSet<>();
    private static WeakReference<Level> rememberedLevel = new WeakReference<>(null);
    private long whipClickAt;
    private long whipSwapAt;
    private long whipNextAt;
    private int ticks;
    private int whipClickTick;
    private final Map<Integer, Vec3> catchLastSeen = new HashMap<>();
    private final Set<Integer> manualKillIds = new HashSet<>();
    private int whipsAtTarget;
    private long whipTargetSince;
    private int whipGiveUpStreak;
    private boolean whipAbandoned;

    // everything loaded when the line was reeled, so a catch is told apart from whatever was already swimming
    private final Set<Integer> preReelEntityIds = new HashSet<>();

    private boolean idleAnchored;
    private long idleNextAt;
    private long idleTapUntil;
    private KeyMapping idleTapKey;

    @Override
    public void onEnable(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        origin = home(mc);
        target = null;
        followMove = 0;
        clearAimSearch();
        returnAttempts = 0;
        returnPathStarted = false;
        returnFinished = false;
        nextAttackAt = 0L;
        returnAt = 0L;
        jumpHoldAt = 0L;
        emptyCatch = false;
        preReelEntityIds.clear();
        pooledCatchIds.clear();
        clearWhip();
        clearKillPlan();
        clearIdle();
        changeState(State.AIM_LAVA);
        ClientUtils.sendDebugMessage("[StriderFishing] started at "
                + origin.getX() + ", " + origin.getY() + ", " + origin.getZ());
        resumeRememberedPool(mc);
    }

    // the same striders still stuck at the full count go straight to the kill; fewer means fishing tops it up
    private void resumeRememberedPool(Minecraft mc) {
        boolean sameLevel = rememberedLevel.get() == mc.level;
        String needle = catchNeedle();
        pooledCatchIds.addAll(stillPooled(rememberedCatchIds, sameLevel, id -> {
            Entity entity = mc.level.getEntity(id);
            return isAlive(entity) && (needle.isEmpty() || matchesName(mc, entity, needle));
        }));
        forgetPool();
        if (!soulWhipFishing() || pooledCatchIds.isEmpty()) {
            pooledCatchIds.clear();
            return;
        }
        int goal = AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get();
        ClientUtils.sendDebugMessage("[StriderFishing] pool still holds " + pooledCatchIds.size() + "/" + goal);
        if (soulWhipGoalReached(pooledCatchIds.size(), goal)) {
            changeState(State.CLEAR);
        }
    }

    static Route fixedSpotRoute(String warp) {
        Route route = new Route("Sawyer spot", warp);
        route.add(new Route.Waypoint(FIXED_SPOT.getX(), FIXED_SPOT.getY(), FIXED_SPOT.getZ(), Route.LegType.WALK));
        return route;
    }

    // a restart starts from the hub or a fresh lobby, so only a start already close by on galatea skips the warp
    static String fixedSpotWarp(boolean restart, boolean onGalatea, double horizontal) {
        return !restart && onGalatea && horizontal <= FIXED_SPOT_WALK_RANGE ? "" : "galatea";
    }

    static Set<Integer> stillPooled(Set<Integer> remembered, boolean sameLevel, IntPredicate stillThere) {
        Set<Integer> kept = new LinkedHashSet<>();
        if (!sameLevel) {
            return kept;
        }
        for (int id : remembered) {
            if (stillThere.test(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    private static void forgetPool() {
        rememberedCatchIds.clear();
        rememberedLevel = new WeakReference<>(null);
    }

    @Override
    public void onDisable(Minecraft mc) {
        PathfindingManager.stop(false);
        dropReturnWarp(mc);
        RotationManager.cancelRotation();
        releaseAll(mc);
        target = null;
        followMove = 0;
        returnPathStarted = false;
        returnFinished = false;
        preReelEntityIds.clear();
        forgetPool();
        if (!pooledCatchIds.isEmpty()) {
            rememberedCatchIds.addAll(pooledCatchIds);
            rememberedLevel = new WeakReference<>(mc.level);
        }
        pooledCatchIds.clear();
        clearWhip();
        clearKillPlan();
        clearIdle();
    }

    @Override
    public void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        ticks++;
        if (mc.screen != null) {
            releaseAll(mc);
            return;
        }

        // only the lava turn has to land before its state can carry on; waiting for a bite still has to
        // poll the marker every tick, or an idle drift would hide the short !! window
        if (state == State.AIM_LAVA && RotationManager.isRotating()) {
            holdStill(mc);
        } else {
            switch (state) {
                case AIM_LAVA -> tickAimLava(mc);
                case CAST -> tickCast(mc);
                case WAIT_BITE -> tickWaitBite(mc);
                case REEL -> tickReel(mc);
                case FIGHT -> tickFight(mc);
                case CLEAR -> tickClear(mc);
                case RETURN -> tickReturn(mc);
            }
        }

        watchCage(mc);

        // last word on the jump key, since every state above clears it
        tickLiquidEscape(mc);
    }

    private void tickAimLava(Minecraft mc) {
        holdStill(mc);

        if (!isOnOrigin(mc)) {
            beginReturn(mc);
            return;
        }

        long now = System.currentTimeMillis();
        if (now < aimRetryAt) {
            return;
        }

        // a crouch or stand still in progress moves the eye, and with it where the throw lands
        if (mc.player.isCrouching() != shouldSneak(mc)) {
            return;
        }

        if (castLandsInLava(mc)) {
            FailsafeManager.selectHotbarSlot(mc, rodSlot());
            changeState(State.CAST);
            nextActionAt = now + castDelayForCycle();
            return;
        }

        // the turn landed somewhere that is not lava after all, so never pick that spot again this sweep
        if (aimTargetBlock != null) {
            rejectedLava.add(aimTargetBlock);
            aimTargetBlock = null;
        }

        CastAim aim = findCastAim(mc, scanRadius(), ThreadLocalRandom.current());
        if (aim == null) {
            // out of candidates rather than out of luck: widen the search and come back to it
            rejectedLava.clear();
            aimSweep++;
            aimRetryAt = now + nextAimRetryDelayMs(ThreadLocalRandom.current());
            ClientUtils.sendDebugMessage("[StriderFishing] no lava lined up, widening the search");
            return;
        }
        aimTargetBlock = aim.block();
        RotationManager.rotateToYawPitch(mc, aim.yaw(), aim.pitch(), AetherConfig.ROTATION_TIME.get());
    }

    private int scanRadius() {
        return (int) Math.min(LAVA_SCAN_RADIUS + aimSweep * 2.0, MAX_LAVA_SCAN_RADIUS);
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
        // aiming handles the retry, and it drops this spot from the running once it sees the miss
        if (!castLandsInLava(mc)) {
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
        clearAimSearch();
        emptyCatch = false;
        anchorIdle(now);
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

        // a pool packed with striders can snag the float on one of them, which will never bite
        // hypixel parks the lava float on its own entity, so only one of our pooled catches counts as a snag
        Entity hookedIn = mc.player.fishing.getHookedIn();
        if (soulWhipFishing() && hookedIn != null && pooledCatchIds.contains(hookedIn.getId())) {
            ClientUtils.sendDebugMessage("[StriderFishing] float hooked a strider, recasting");
            clearIdle();
            ClientUtils.performUseClick();
            changeState(State.AIM_LAVA);
            return;
        }

        if (hasCatchMarker(mc, mc.player.fishing)) {
            clearIdle();
            snapshotLoadedEntities(mc);
            changeState(State.REEL);
            return;
        }

        // a float sitting on stone will never get a bite, so reel it in and aim somewhere else
        if (now - stateEnteredAt > BOBBER_LANDED_MS && !isInLava(mc, mc.player.fishing)) {
            ClientUtils.sendDebugMessage("[StriderFishing] float landed out of the lava, recasting");
            rejectCurrentAim();
            clearIdle();
            ClientUtils.performUseClick();
            changeState(State.AIM_LAVA);
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
            ActivityRateTracker.onMobKilled();
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

        if (target != null && soulWhipFishing()) {
            poolCatch(mc, now);
            return;
        }

        if (target == null) {
            holdStill(mc);
            if (returnAt != 0L) {
                if (now >= returnAt) {
                    beginReturn(mc);
                    // plan the route in this same tick instead of idling until the next one
                    tickReturn(mc);
                }
                return;
            }
            // loot never spawns a mob, and the rod never left the start block, so just cast again
            if (now - stateEnteredAt > ACQUIRE_TIMEOUT_MS) {
                if (isOnOrigin(mc)) {
                    recast(now);
                    return;
                }
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

        engage(mc, now);
    }

    private void engage(Minecraft mc, long now) {
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

    // the catch stays stuck in the pool, so it is only counted and the line goes straight back out
    private void poolCatch(Minecraft mc, long now) {
        pooledCatchIds.add(target.getId());
        target = null;
        pruneDeadCatches(mc);
        int goal = AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get();
        ClientUtils.sendDebugMessage("[StriderFishing] pool holds " + pooledCatchIds.size() + "/" + goal);
        if (soulWhipGoalReached(pooledCatchIds.size(), goal)) {
            clearWhip();
            changeState(State.CLEAR);
            return;
        }
        emptyCatch = false;
        if (isOnOrigin(mc)) {
            changeState(State.CAST);
            nextActionAt = now + castDelayMs();
            return;
        }
        beginReturn(mc);
    }

    private void tickClear(Minecraft mc) {
        long now = System.currentTimeMillis();
        for (int i = pruneDeadCatches(mc); i > 0; i--) {
            ActivityRateTracker.onMobKilled();
        }

        if (pooledCatchIds.isEmpty() || now - stateEnteredAt > CLEAR_TIMEOUT_MS) {
            if (!pooledCatchIds.isEmpty()) {
                ClientUtils.sendDebugMessage("[StriderFishing] pool clear timed out, fishing again");
            }
            finishClear(mc, now);
            return;
        }

        if (target == null || !isAlive(target) || !pooledCatchIds.contains(target.getId())) {
            // only a whip kill breaks the failing streak; one the weapon finished after a give up does not
            if (target != null && whipsAtTarget > 0 && !manualKillIds.contains(target.getId())) {
                whipGiveUpStreak = 0;
            }
            // everything the whip can reach from the block goes first, then the walk out to the strays
            target = nearestPooledCatch(mc, false);
            if (target == null) {
                target = nearestPooledCatch(mc, true);
            }
            if (target == null) {
                finishClear(mc, now);
                return;
            }
            whipsAtTarget = 0;
            whipTargetSince = now;
            clearWhip();
        }

        if (!whipsThisTarget()) {
            engage(mc, now);
            return;
        }
        // only between swings, so a give up never leaves the whip in hand mid swap
        if (whipClickAt == 0L && whipSwapAt == 0L
                && whipFailing(whipsAtTarget, now - whipTargetSince)) {
            giveUpWhip();
            engage(mc, now);
            return;
        }
        tickWhip(mc, now);
    }

    private boolean whipsThisTarget() {
        return AetherConfig.STRIDER_FISHING_SOUL_WHIP.get()
                && !whipAbandoned
                && !manualKillIds.contains(target.getId());
    }

    private void giveUpWhip() {
        manualKillIds.add(target.getId());
        if (++whipGiveUpStreak >= WHIP_GIVE_UP_STREAK) {
            whipAbandoned = true;
            ClientUtils.sendDebugMessage("[StriderFishing] soul whip keeps failing, clearing the pool by hand");
        } else {
            ClientUtils.sendDebugMessage("[StriderFishing] soul whip is not killing it, finishing it by hand");
        }
        clearWhip();
        followMove = 0;
    }

    static boolean whipFailing(int whips, long msOnTarget) {
        return whips >= WHIP_GIVE_UP_SWINGS || msOnTarget >= WHIP_GIVE_UP_MS;
    }

    // a catch moved out of its cage cannot be whipped from the block, so it is marked for a manual kill
    private void watchCage(Minecraft mc) {
        if (pooledCatchIds.isEmpty()) {
            catchLastSeen.clear();
            return;
        }
        catchLastSeen.keySet().retainAll(pooledCatchIds);
        Vec3 home = origin == null ? mc.player.position() : Vec3.atBottomCenterOf(origin);
        for (int id : pooledCatchIds) {
            Entity entity = mc.level.getEntity(id);
            if (!isAlive(entity)) {
                continue;
            }
            Vec3 now = entity.position();
            Vec3 last = catchLastSeen.put(id, now);
            if (!manualKillIds.contains(id) && escapedCage(last, now, home)) {
                manualKillIds.add(id);
                ClientUtils.sendDebugMessage("[StriderFishing] a strider left its cage, it will be killed by hand");
            }
        }
    }

    static boolean escapedCage(Vec3 last, Vec3 now, Vec3 home) {
        if (last != null && last.distanceTo(now) > CAGE_TELEPORT_JUMP) {
            return true;
        }
        return Math.hypot(now.x - home.x, now.z - home.z) > CAGE_RADIUS;
    }

    // whip from the block, then swap to the weapon before the hit resolves so the weapon's stats carry it
    private void tickWhip(Minecraft mc, long now) {
        holdStill(mc);
        Vec3 aim = whipAimPoint(target);
        RotationManager.trackRotation(mc, aim, AIM_SMOOTHING_MS, AIM_MAX_TURN_SPEED);

        if (whipSwapAt != 0L) {
            // the click is only sent on the tick after it was queued, and the swap must not beat it there
            if (now >= whipSwapAt && ticks > whipClickTick) {
                FailsafeManager.selectHotbarSlot(mc, weaponSlot());
                whipSwapAt = 0L;
                whipNextAt = now + nextWhipIntervalMs(ThreadLocalRandom.current());
            }
            return;
        }

        if (whipClickAt != 0L) {
            if (now >= whipClickAt) {
                ClientUtils.performUseClickInstant();
                whipsAtTarget++;
                whipClickAt = 0L;
                whipClickTick = ticks;
                whipSwapAt = now + nextWhipSwapDelayMs(ThreadLocalRandom.current(),
                        AetherConfig.STRIDER_FISHING_WHIP_SWAP_MIN.get(),
                        AetherConfig.STRIDER_FISHING_WHIP_SWAP_MAX.get());
            }
            return;
        }

        if (now < whipNextAt || !isAimedAt(mc, aim)) {
            return;
        }
        FailsafeManager.selectHotbarSlot(mc, soulWhipSlot());
        whipClickAt = now + nextWhipDrawDelayMs(ThreadLocalRandom.current());
    }

    private void finishClear(Minecraft mc, long now) {
        pooledCatchIds.clear();
        target = null;
        followMove = 0;
        clearWhip();
        clearKillPlan();
        releaseAll(mc);
        if (isOnOrigin(mc)) {
            changeState(State.AIM_LAVA);
            return;
        }
        beginReturn(mc);
    }

    private void clearKillPlan() {
        catchLastSeen.clear();
        manualKillIds.clear();
        whipsAtTarget = 0;
        whipTargetSince = 0L;
        whipGiveUpStreak = 0;
        whipAbandoned = false;
    }

    private void clearWhip() {
        whipClickAt = 0L;
        whipSwapAt = 0L;
        whipNextAt = 0L;
    }

    private int pruneDeadCatches(Minecraft mc) {
        int before = pooledCatchIds.size();
        pooledCatchIds.removeIf(id -> !isAlive(mc.level.getEntity(id)));
        return before - pooledCatchIds.size();
    }

    private Entity nearestPooledCatch(Minecraft mc, boolean manual) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int id : pooledCatchIds) {
            Entity entity = mc.level.getEntity(id);
            if (!isAlive(entity) || manualKillIds.contains(id) != manual) {
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

    private Entity randomPooledCatch(Minecraft mc, ThreadLocalRandom random) {
        List<Entity> alive = new ArrayList<>();
        for (int id : pooledCatchIds) {
            Entity entity = mc.level.getEntity(id);
            if (isAlive(entity)) {
                alive.add(entity);
            }
        }
        return alive.isEmpty() ? null : alive.get(random.nextInt(alive.size()));
    }

    private static boolean isAimedAt(Minecraft mc, Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        return aimWithin(mc.player.getYRot(), mc.player.getXRot(),
                yawTo(dx, dz), pitchTo(dx, dy, dz), WHIP_AIM_TOLERANCE_DEGREES);
    }

    static boolean aimWithin(float yaw, float pitch, float wantYaw, float wantPitch, float tolerance) {
        return Math.abs(Mth.wrapDegrees(wantYaw - yaw)) <= tolerance
                && Math.abs(wantPitch - pitch) <= tolerance;
    }

    static boolean soulWhipGoalReached(int pooled, int goal) {
        return pooled >= goal;
    }

    // two uniforms averaged make a triangle, so most swaps land mid range and the edges stay rare
    static long nextWhipSwapDelayMs(ThreadLocalRandom random, int min, int max) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        double t = (random.nextDouble() + random.nextDouble()) / 2.0;
        long delay = Math.round(lo + (hi - lo) * t);
        if (random.nextInt(WHIP_HESITATE_ONE_IN) == 0) {
            delay += random.nextLong(WHIP_HESITATE_MIN_MS, WHIP_HESITATE_MAX_MS + 1);
        }
        return delay;
    }

    static boolean whipSwapDelayInRange(long delay, int min, int max) {
        return delay >= Math.min(min, max) && delay <= Math.max(min, max) + WHIP_HESITATE_MAX_MS;
    }

    static long nextWhipDrawDelayMs(ThreadLocalRandom random) {
        return random.nextLong(WHIP_DRAW_MIN_MS, WHIP_DRAW_MAX_MS + 1);
    }

    static boolean whipDrawDelayInRange(long delay) {
        return delay >= WHIP_DRAW_MIN_MS && delay <= WHIP_DRAW_MAX_MS;
    }

    static long nextWhipIntervalMs(ThreadLocalRandom random) {
        return random.nextLong(WHIP_INTERVAL_MIN_MS, WHIP_INTERVAL_MAX_MS + 1);
    }

    static boolean whipIntervalInRange(long delay) {
        return delay >= WHIP_INTERVAL_MIN_MS && delay <= WHIP_INTERVAL_MAX_MS;
    }

    private static boolean soulWhipFishing() {
        return AetherConfig.STRIDER_FISHING_SOUL_WHIP_FISHING.get();
    }

    private void tickReturn(Minecraft mc) {
        if (origin == null) {
            changeState(State.AIM_LAVA);
            return;
        }

        if (isOnOrigin(mc)) {
            PathfindingManager.stop(false);
            dropReturnWarp(mc);
            arriveHome(mc);
            return;
        }

        // one crouched, lined up click; the old pathfinder warp re-clicked on a timer and could fire unsneaked,
        // which is a plain aotv teleport straight off the block
        if (returnWarp != null) {
            EtherwarpLeg.Result result = returnWarp.tick(mc);
            if (result == EtherwarpLeg.Result.RUNNING) {
                return;
            }
            if (result == EtherwarpLeg.Result.FAILED) {
                ClientUtils.sendDebugMessage("[StriderFishing] return warp failed: " + returnWarp.failure());
            }
            dropReturnWarp(mc);
            returnFinished = true;
            return;
        }

        long now = System.currentTimeMillis();
        if (returnFinished) {
            returnFinished = false;
            returnPathStarted = false;
            // a refused warp is a change of plan, not a failed attempt; only a dead walk route counts
            if (returnByWalk && ++returnAttempts > MAX_RETURN_ATTEMPTS) {
                fail("Strider fishing stopped: could not get back onto the start block.");
                return;
            }
            returnByWalk = false;
            returnRetryAt = now + nextReturnRetryDelayMs(ThreadLocalRandom.current());
            return;
        }

        if (now < returnRetryAt) {
            return;
        }

        Vec3 home = Vec3.atBottomCenterOf(origin);
        if (!returnPathStarted) {
            boolean inLiquid = mc.player.isInLiquid();
            // one warp per trip, landed or missed; anything after it walks and looks at the block
            if (!etherwarpUsed && shouldEtherwarp(mc.player.position().distanceTo(home), inLiquid,
                    AetherConfig.STRIDER_FISHING_ETHERWARP_RETURN.get())) {
                etherwarpUsed = true;
                returnPathStarted = true;
                returnByWalk = false;
                PathfindingManager.stop(false);
                returnWarp = new EtherwarpLeg(
                        new Route.Waypoint(origin.getX(), origin.getY(), origin.getZ(), Route.LegType.ETHERWARP), null);
                return;
            }
            // a walk route cannot be planned out of lava, so the jump has to lift us clear first
            if (inLiquid) {
                return;
            }
            returnPathStarted = true;
            startWalkHome(mc, home);
            return;
        }

        // the warp has to keep its own aim, so only the walk watches the block it is heading for
        if (returnByWalk && PathfindingManager.isNavigating()) {
            PathfindingManager.setWalkLookTarget(home.add(0.0, LOOK_TARGET_HEIGHT, 0.0));
        }
    }

    private void dropReturnWarp(Minecraft mc) {
        if (returnWarp != null) {
            returnWarp = null;
            EtherwarpLeg.release(mc);
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

        FishingHook hook = mc.player.fishing;
        if (!idleAnchored || hook == null || now < idleNextAt || RotationManager.isRotating()) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        idleNextAt = now + nextIdleDelayMs(random);
        if (AetherConfig.STRIDER_FISHING_RANDOM_LOOK.get()) {
            lookAround(mc, hook, random);
        }

        // a step only happens crouched, so the shuffle cannot carry the player off the start block
        if (AetherConfig.STRIDER_FISHING_BLOCK_SHUFFLE.get()
                && sneakAllowedHere(mc) && isOnOrigin(mc) && random.nextInt(IDLE_TAP_ONE_IN) == 0) {
            idleTapKey = switch (random.nextInt(4)) {
                case 0 -> options.keyUp;
                case 1 -> options.keyDown;
                case 2 -> options.keyLeft;
                default -> options.keyRight;
            };
            idleTapUntil = now + random.nextLong(IDLE_TAP_MIN_MS, IDLE_TAP_MAX_MS + 1);
        }
    }

    // drift around the float itself, offset inside a small box so the cursor is never dead centre on it
    // with a pool filling up, the odd glance goes to one of the striders already stuck in it
    private void lookAround(Minecraft mc, FishingHook hook, ThreadLocalRandom random) {
        Entity glance = soulWhipFishing() && random.nextInt(GLANCE_AT_POOL_ONE_IN) == 0
                ? randomPooledCatch(mc, random)
                : null;
        Vec3 aimAt = glance != null ? aimPoint(glance) : hook.position();
        aimAt = aimAt.add(aimBoxOffset(random));
        float yawRange = glance != null ? GLANCE_YAW_DEGREES : IDLE_YAW_DEGREES;
        float pitchRange = glance != null ? GLANCE_PITCH_DEGREES : IDLE_PITCH_DEGREES;

        Vec3 eye = mc.player.getEyePosition();
        double dx = aimAt.x - eye.x;
        double dy = aimAt.y - eye.y;
        double dz = aimAt.z - eye.z;
        RotationManager.rotateToYawPitch(mc,
                yawTo(dx, dz) + driftDegrees(random, yawRange),
                pitchTo(dx, dy, dz) + driftDegrees(random, pitchRange),
                nextIdleTurnMs(random));
    }

    private void anchorIdle(long now) {
        idleAnchored = true;
        // settle onto the float shortly after it lands, then drift on the slower cadence
        idleNextAt = now + nextFirstIdleDelayMs(ThreadLocalRandom.current());
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

    static Vec3 aimBoxOffset(ThreadLocalRandom random) {
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

    static long nextReturnRetryDelayMs(ThreadLocalRandom random) {
        return random.nextLong(RETURN_RETRY_MIN_MS, RETURN_RETRY_MAX_MS + 1);
    }

    static boolean returnRetryDelayInRange(long delay) {
        return delay >= RETURN_RETRY_MIN_MS && delay <= RETURN_RETRY_MAX_MS;
    }

    static long nextIdleTurnMs(ThreadLocalRandom random) {
        return random.nextLong(IDLE_TURN_MIN_MS, IDLE_TURN_MAX_MS + 1);
    }

    static boolean idleTurnInRange(long turnMs) {
        return turnMs >= IDLE_TURN_MIN_MS && turnMs <= IDLE_TURN_MAX_MS;
    }

    static long nextFirstIdleDelayMs(ThreadLocalRandom random) {
        return random.nextLong(IDLE_FIRST_MIN_DELAY_MS, IDLE_FIRST_MAX_DELAY_MS + 1);
    }

    static boolean firstIdleDelayInRange(long delay) {
        return delay >= IDLE_FIRST_MIN_DELAY_MS && delay <= IDLE_FIRST_MAX_DELAY_MS;
    }

    static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static float pitchTo(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    static float driftDegrees(ThreadLocalRandom random, float range) {
        return (float) random.nextDouble(-range, range);
    }

    static boolean idleDelayInRange(long delay) {
        return delay >= IDLE_MIN_DELAY_MS && delay <= IDLE_MAX_DELAY_MS;
    }

    private void changeState(State next) {
        if ((state == State.FIGHT || state == State.CLEAR) && next != state) {
            RotationManager.cancelRotation();
        }
        state = next;
        stateEnteredAt = System.currentTimeMillis();
    }

    private void recast(long now) {
        // nothing on the line and nothing to fight, so the rod goes back out almost at once
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
        etherwarpUsed = false;
        returnRetryAt = 0L;
        dropReturnWarp(mc);
        clearIdle();
        releaseAll(mc);
        changeState(State.RETURN);
    }

    private void arriveHome(Minecraft mc) {
        returnPathStarted = false;
        returnByWalk = false;
        returnFinished = false;
        etherwarpUsed = false;
        returnRetryAt = 0L;
        returnAttempts = 0;
        releaseAll(mc);
        clearAimSearch();
        // the route leaves the camera wherever it was steering, so the lava aim starts from a clean slate
        RotationManager.cancelRotation();
        changeState(State.AIM_LAVA);
    }

    private void fail(String message) {
        ClientUtils.sendMessage("§c" + message, false);
        MacroStateManager.stopMacro(Minecraft.getInstance(), message, false);
    }

    private void clearAimSearch() {
        rejectedLava.clear();
        aimTargetBlock = null;
        aimRetryAt = 0L;
        aimSweep = 0;
    }

    static long nextAimRetryDelayMs(ThreadLocalRandom random) {
        return random.nextLong(AIM_RETRY_MIN_MS, AIM_RETRY_MAX_MS + 1);
    }

    static boolean aimRetryDelayInRange(long delay) {
        return delay >= AIM_RETRY_MIN_MS && delay <= AIM_RETRY_MAX_MS;
    }

    private boolean isOnOrigin(Minecraft mc) {
        if (origin == null) {
            return false;
        }
        if (origin.equals(mc.player.blockPosition())) {
            return true;
        }
        Vec3 home = Vec3.atBottomCenterOf(origin);
        return withinOriginBlock(mc.player.getX() - home.x,
                mc.player.getY() - home.y,
                mc.player.getZ() - home.z);
    }

    // standing on the lip of the block, or a hair above it after the jump out, still counts as home
    static boolean withinOriginBlock(double dx, double dy, double dz) {
        return Math.abs(dx) <= ORIGIN_RADIUS
                && Math.abs(dz) <= ORIGIN_RADIUS
                && dy >= -ORIGIN_BELOW
                && dy <= ORIGIN_ABOVE;
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

    @Override
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

    private static int soulWhipSlot() {
        return Mth.clamp(AetherConfig.STRIDER_FISHING_SOUL_WHIP_SLOT.get() - 1, 0, 8);
    }

    private void tickLiquidEscape(Minecraft mc) {
        long now = System.currentTimeMillis();
        // a jump mid aim moves the eye off the line the warp was lined up on
        if (!mc.player.isInLiquid() || returnWarp != null) {
            jumpHoldAt = 0L;
            return;
        }
        if (jumpHoldAt == 0L) {
            // a beat of sinking first, so surfacing is not a frame-perfect reaction to touching lava
            jumpHoldAt = now + nextLiquidJumpDelayMs(ThreadLocalRandom.current());
        }
        if (shouldHoldLiquidJump(true, now, jumpHoldAt)) {
            MacroInput.set(mc.options.keyJump, true);
        }
    }

    static long nextLiquidJumpDelayMs(ThreadLocalRandom random) {
        return random.nextLong(LIQUID_JUMP_MIN_DELAY_MS, LIQUID_JUMP_MAX_DELAY_MS + 1);
    }

    static boolean liquidJumpDelayInRange(long delay) {
        return delay >= LIQUID_JUMP_MIN_DELAY_MS && delay <= LIQUID_JUMP_MAX_DELAY_MS;
    }

    static boolean shouldHoldLiquidJump(boolean inLiquid, long now, long holdFrom) {
        return inLiquid && holdFrom != 0L && now >= holdFrom;
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

    private static boolean isLava(BlockState state) {
        if (state.getBlock() == Blocks.LAVA) {
            return true;
        }
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.getType().isSame(Fluids.LAVA);
    }

    record CastAim(BlockPos block, float yaw, float pitch) {
    }

    private static boolean castLandsInLava(Minecraft mc) {
        return predictCastLanding(mc, mc.player.getYRot(), mc.player.getXRot()) != null;
    }

    // walks the bobber's flight through the world; null when it clips a block or never reaches lava
    // at leg-height lava the rim sits above the surface and the float drops under the crosshair, so a look is not enough
    private static Vec3 predictCastLanding(Minecraft mc, float yaw, float pitch) {
        Vec3[] path = castPath(mc.player.getEyePosition(), yaw, pitch);
        for (int i = 1; i < path.length; i++) {
            for (int step = 1; step <= CAST_SUBSTEPS; step++) {
                Vec3 at = path[i - 1].lerp(path[i], step / (double) CAST_SUBSTEPS);
                BlockPos pos = BlockPos.containing(at);
                BlockState state = mc.level.getBlockState(pos);
                if (isLava(state) && at.y <= pos.getY() + state.getFluidState().getHeight(mc.level, pos)) {
                    return at;
                }
                AABB hook = new AABB(at.x - CAST_HOOK_HALF_WIDTH, at.y, at.z - CAST_HOOK_HALF_WIDTH,
                        at.x + CAST_HOOK_HALF_WIDTH, at.y + CAST_HOOK_HEIGHT, at.z + CAST_HOOK_HALF_WIDTH);
                if (!mc.level.noCollision(hook)) {
                    return null;
                }
            }
        }
        return null;
    }

    static Vec3[] castPath(Vec3 eye, float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        double rise = Mth.clamp(-Math.tan(Math.toRadians(pitch)), -5.0, 5.0);
        double speed = CAST_SPEED_BASE / Math.sqrt(1.0 + rise * rise) + CAST_SPEED_BONUS;

        double vx = dirX * speed;
        double vy = rise * speed;
        double vz = dirZ * speed;
        Vec3[] path = new Vec3[CAST_MAX_TICKS + 1];
        path[0] = new Vec3(eye.x + dirX * CAST_START_OFFSET, eye.y, eye.z + dirZ * CAST_START_OFFSET);
        for (int tick = 1; tick <= CAST_MAX_TICKS; tick++) {
            vy -= CAST_GRAVITY;
            Vec3 last = path[tick - 1];
            path[tick] = new Vec3(last.x + vx, last.y + vy, last.z + vz);
            vx *= CAST_DRAG;
            vy *= CAST_DRAG;
            vz *= CAST_DRAG;
        }
        return path;
    }

    // how many pitch steps either side of the pick still land, capped so a wide pool does not beat a close one
    static int castMargin(boolean[] lands, int index) {
        int left = 0;
        while (left < CAST_MARGIN_CAP && index - left - 1 >= 0 && lands[index - left - 1]) {
            left++;
        }
        int right = 0;
        while (right < CAST_MARGIN_CAP && index + right + 1 < lands.length && lands[index + right + 1]) {
            right++;
        }
        return Math.min(left, right);
    }

    // the throw with the most room for error wins, then the one that comes down nearest the block centre
    private CastAim findCastAim(Minecraft mc, int radius, ThreadLocalRandom random) {
        BlockPos base = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        int steps = Math.round((CAST_PITCH_MAX - CAST_PITCH_MIN) / CAST_PITCH_STEP) + 1;
        boolean[] lands = new boolean[steps];
        Vec3[] landings = new Vec3[steps];
        CastAim best = null;
        int bestMargin = 0;
        double bestError = Double.MAX_VALUE;

        int depth = (int) LAVA_SCAN_DEPTH;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -depth; dy <= 1; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (rejectedLava.contains(pos) || !isLava(mc.level.getBlockState(pos))) {
                        continue;
                    }
                    BlockPos above = pos.above();
                    if (!mc.level.getBlockState(above).getCollisionShape(mc.level, above).isEmpty()) {
                        continue;
                    }
                    double tx = pos.getX() + 0.5 + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
                    double tz = pos.getZ() + 0.5 + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
                    if (Math.hypot(tx - eye.x, tz - eye.z) < MIN_CAST_HORIZONTAL) {
                        continue;
                    }
                    float yaw = yawTo(tx - eye.x, tz - eye.z);
                    for (int i = 0; i < steps; i++) {
                        landings[i] = predictCastLanding(mc, yaw, CAST_PITCH_MIN + i * CAST_PITCH_STEP);
                        lands[i] = landings[i] != null;
                    }
                    for (int i = 0; i < steps; i++) {
                        if (!lands[i]) {
                            continue;
                        }
                        int margin = castMargin(lands, i);
                        double error = Math.hypot(landings[i].x - tx, landings[i].z - tz);
                        if (margin < 1 || margin < bestMargin || (margin == bestMargin && error >= bestError)) {
                            continue;
                        }
                        best = new CastAim(pos, yaw, CAST_PITCH_MIN + i * CAST_PITCH_STEP);
                        bestMargin = margin;
                        bestError = error;
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

    private static boolean isInLava(Minecraft mc, FishingHook hook) {
        BlockPos at = BlockPos.containing(hook.position());
        return isLava(mc.level.getBlockState(at)) || isLava(mc.level.getBlockState(at.below()));
    }

    private void rejectCurrentAim() {
        if (aimTargetBlock != null) {
            rejectedLava.add(aimTargetBlock);
            aimTargetBlock = null;
        }
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

    private static String catchNeedle() {
        String wanted = AetherConfig.STRIDER_FISHING_TARGET_NAME.get();
        return wanted == null ? "" : stripFormatting(wanted).toLowerCase(Locale.ROOT).trim();
    }

    private Entity findTarget(Minecraft mc) {
        String needle = catchNeedle();

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

    private static Vec3 whipAimPoint(Entity target) {
        return target.position().add(0.0, target.getBbHeight() * WHIP_AIM_HEIGHT, 0.0);
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
