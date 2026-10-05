package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.profit.helpers.ActivityRateTracker;
import dev.aether.modules.routes.Route;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;

// lava fishing for stridersurfers: cast, wait for the marker to flip from ? to !!, reel, kill, walk home
// every delay here is wall-clock and every decision runs on the client tick, so the macro behaves the same at 10 or 240 fps
public final class StriderFishingMacro extends AbstractFishingMacro {

    public enum State { AIM_LAVA, CAST, WAIT_BITE, REEL, FIGHT, CLEAR, RETURN }

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
    private static final long BOBBER_SETTLE_MS = 1_500L;
    // the float needs time to finish its arc before where it landed means anything
    private static final long BOBBER_LANDED_MS = 1_200L;
    private static final long REEL_SETTLE_MS = 350L;

    private static final float AIM_SMOOTHING_MS = 110.0f;
    private static final float AIM_MAX_TURN_SPEED = 520.0f;
    private static final double ATTACK_RANGE_SLACK = 0.85;
    private static final double FOLLOW_BAND = 0.35;
    private static final long AIM_RETRY_MIN_MS = 400L;
    private static final long AIM_RETRY_MAX_MS = 900L;

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
    private static final float WHIP_AIM_TOLERANCE_DEGREES = 6.0f;
    // the whip's swing lands above the crosshair, so aiming at the legs puts it through the body
    private static final double WHIP_AIM_HEIGHT = 0.15;
    // hypixel refuses a new sea creature while a player already has this many alive
    private static final int SEA_CREATURE_CAP = 10;
    // a double hook brings its second catch up a moment after the first
    private static final long DOUBLE_HOOK_WINDOW_MS = 400L;
    private static final String CAP_LINE = "there is not enough space for another sea creature!";

    private State state = State.AIM_LAVA;
    private long stateEnteredAt;
    private long nextActionAt;
    private long nextAttackAt;
    private long returnAt;
    private int followMove;
    private boolean emptyCatch;
    private final Set<BlockPos> rejectedLava = new HashSet<>();
    private BlockPos aimTargetBlock;
    // what the last throw was meant to do, so a float that comes down off the lava can rule both out
    private BlockPos castLanding;
    private BlockPos castAimBlock;
    private CastAimSearch aimSearch;
    private long aimRetryAt;
    private int aimSweep;
    private Entity target;
    private final HomeKeeper homeKeeper = new HomeKeeper("[StriderFishing]",
            () -> AetherConfig.STRIDER_FISHING_ETHERWARP_RETURN.get(), Entity::isInLiquid);

    private final Set<Integer> pooledCatchIds = new LinkedHashSet<>();
    // outlives the macro instance, so a stop and start in the same lobby picks the pool back up
    private static final Set<Integer> rememberedCatchIds = new LinkedHashSet<>();
    private static WeakReference<Level> rememberedLevel = new WeakReference<>(null);
    // catches a timed out clear left alive, which still count against the sea creature cap
    private final Set<Integer> strayIds = new LinkedHashSet<>();
    private static final Set<Integer> rememberedStrayIds = new LinkedHashSet<>();
    private long firstCatchAt;
    private boolean capReached;
    private final AbilitySwapClicker whipClicker = new AbilitySwapClicker(AbilitySwapClicker.SOUL_WHIP,
            slot -> FailsafeManager.selectHotbarSlot(Minecraft.getInstance(), slot),
            ClientUtils::performUseClickInstant);
    private int ticks;
    private final Map<Integer, Vec3> catchLastSeen = new HashMap<>();
    private final Set<Integer> manualKillIds = new HashSet<>();
    private int whipsAtTarget;
    private long whipTargetSince;
    private int whipGiveUpStreak;
    private boolean whipAbandoned;

    // everything loaded when the line was reeled, so a catch is told apart from whatever was already swimming
    private final Set<Integer> preReelEntityIds = new HashSet<>();

    @Override
    public void onEnable(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        homeKeeper.start(home(mc));
        target = null;
        followMove = 0;
        clearAimSearch();
        nextAttackAt = 0L;
        returnAt = 0L;
        emptyCatch = false;
        preReelEntityIds.clear();
        pooledCatchIds.clear();
        strayIds.clear();
        firstCatchAt = 0L;
        capReached = false;
        clearWhip();
        clearKillPlan();
        changeState(State.AIM_LAVA);
        BlockPos origin = homeKeeper.origin();
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
            return CatchWatch.isAlive(entity)
                    && (needle.isEmpty() || CatchWatch.matchesName(mc.level, entity, needle));
        }));
        strayIds.addAll(stillPooled(rememberedStrayIds, sameLevel,
                id -> CatchWatch.isAlive(mc.level.getEntity(id))));
        forgetPool();
        if (!pooling()) {
            pooledCatchIds.clear();
            strayIds.clear();
            return;
        }
        if (pooledCatchIds.isEmpty()) {
            return;
        }
        int goal = poolGoal();
        ClientUtils.sendDebugMessage("[StriderFishing] pool still holds " + pooledCatchIds.size() + "/" + goal);
        if (soulWhipGoalReached(pooledCatchIds.size(), goal)) {
            startClear();
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
        rememberedStrayIds.clear();
        rememberedLevel = new WeakReference<>(null);
    }

    @Override
    public void onDisable(Minecraft mc) {
        homeKeeper.cancel(mc);
        RotationManager.cancelRotation();
        releaseAll(mc);
        target = null;
        followMove = 0;
        preReelEntityIds.clear();
        forgetPool();
        if (!pooledCatchIds.isEmpty() || !strayIds.isEmpty()) {
            rememberedCatchIds.addAll(pooledCatchIds);
            rememberedStrayIds.addAll(strayIds);
            rememberedLevel = new WeakReference<>(mc.level);
        }
        pooledCatchIds.clear();
        strayIds.clear();
        clearWhip();
        clearKillPlan();
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

        if (pooling() && !strayIds.isEmpty()) {
            for (int i = pruneDeadStrays(mc); i > 0; i--) {
                ActivityRateTracker.onMobKilled();
            }
            if (poolGoal() < 1) {
                fail("Strider fishing stopped: " + strayIds.size()
                        + " striders escaped the pool, kill them by hand.");
                return;
            }
        }

        // only the lava turn has to land before its state can carry on; waiting for a bite still has to
        // poll the marker every tick, or the short !! window could slip by
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

        // the cap line answers a reel, so it is acted on once the macro is back between casts
        if (capReached && (state == State.AIM_LAVA || state == State.CAST || state == State.FIGHT)) {
            capReached = false;
            if (pooling() && !pooledCatchIds.isEmpty()) {
                ClientUtils.sendDebugMessage("[StriderFishing] sea creature cap reached, clearing the pool");
                startClear();
            }
        }

        watchCage(mc);

        // last word on the jump key, since every state above clears it
        homeKeeper.tickLiquidEscape(mc, System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    private void tickAimLava(Minecraft mc) {
        holdStill(mc);

        if (!homeKeeper.isOnOrigin(mc)) {
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

        if (lookLanding(mc) != null) {
            FailsafeManager.selectHotbarSlot(mc, rodSlot());
            changeState(State.CAST);
            nextActionAt = now + castDelayForCycle();
            return;
        }

        // the turn landed somewhere a float cannot go after all, so never pick that spot again this sweep
        if (aimTargetBlock != null) {
            rejectedLava.add(aimTargetBlock);
            aimTargetBlock = null;
        }

        if (aimSearch == null) {
            aimSearch = new CastAimSearch(mc.level, mc.player.blockPosition(), mc.player.getEyePosition(),
                    mc.player.getYRot(), CastAimSearch.Spec.STRIDER_CLASSIC.widened(aimSweep), rejectedLava,
                    CastSim::isLava, ThreadLocalRandom.current());
        }
        CastAimSearch.Step step = aimSearch.step();
        if (step.status() == CastAimSearch.Status.WORKING) {
            return;
        }
        if (step.status() == CastAimSearch.Status.EXHAUSTED) {
            // out of candidates rather than out of luck: widen the search and come back to it
            aimSearch = null;
            rejectedLava.clear();
            aimSweep++;
            aimRetryAt = now + nextAimRetryDelayMs(ThreadLocalRandom.current());
            ClientUtils.sendDebugMessage("[StriderFishing] no lava lined up, widening the search");
            return;
        }
        CastSim.CastAim aim = step.aim();
        aimTargetBlock = aim.block();
        RotationManager.rotateToYawPitch(mc, aim.yaw(), aim.pitch(), AetherConfig.ROTATION_TIME.get());
    }

    private void tickCast(Minecraft mc) {
        holdStill(mc);
        long now = System.currentTimeMillis();
        if (now < nextActionAt) {
            return;
        }

        if (!homeKeeper.isOnOrigin(mc)) {
            beginReturn(mc);
            return;
        }

        // the camera can still be settling from the walk back, so the lava is confirmed again at the last moment
        // aiming handles the retry, and it drops this spot from the running once it sees the miss
        BlockPos landing = lookLanding(mc);
        if (landing == null) {
            changeState(State.AIM_LAVA);
            return;
        }

        FailsafeManager.selectHotbarSlot(mc, rodSlot());
        ClientUtils.performUseClick();
        // a bobber still out means that click reeled the stuck line in, so cast on the next pass
        if (CatchWatch.hasLiveHook(mc)) {
            nextActionAt = now + castDelayMs();
            return;
        }
        castLanding = landing;
        castAimBlock = aimTargetBlock;
        aimTargetBlock = null;
        emptyCatch = false;
        changeState(State.WAIT_BITE);
    }

    private void tickWaitBite(Minecraft mc) {
        holdStill(mc);
        long now = System.currentTimeMillis();

        if (!CatchWatch.hasLiveHook(mc)) {
            // the cast never left the rod, or the line came back on its own
            if (now - stateEnteredAt > BOBBER_SETTLE_MS) {
                recast(now);
            }
            return;
        }

        // a pool packed with striders can snag the float on one of them, which will never bite
        // hypixel parks the lava float on its own entity, so only one of our pooled catches counts as a snag
        Entity hookedIn = mc.player.fishing.getHookedIn();
        if (pooling() && hookedIn != null && pooledCatchIds.contains(hookedIn.getId())) {
            ClientUtils.sendDebugMessage("[StriderFishing] float hooked a strider, recasting");
            ClientUtils.performUseClick();
            changeState(State.AIM_LAVA);
            return;
        }

        if (CatchWatch.hasCatchMarker(mc.level, mc.player.fishing)) {
            clearAimSearch();
            CatchWatch.snapshot(mc.level, preReelEntityIds);
            changeState(State.REEL);
            return;
        }

        if (now - stateEnteredAt > BOBBER_LANDED_MS) {
            // a float sitting on stone will never get a bite, so reel it in and aim somewhere else
            if (!CatchWatch.floatInLiquid(mc.level, mc.player.fishing, CastSim::isLava)) {
                ClientUtils.sendDebugMessage("[StriderFishing] float landed out of the lava, recasting");
                rejectCast();
                ClientUtils.performUseClick();
                changeState(State.AIM_LAVA);
                return;
            }
            // a float down in the lava proves the spot, so the misses before it are forgiven
            if (castLanding != null) {
                clearAimSearch();
            }
        }

        if (now - stateEnteredAt > BITE_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[StriderFishing] no bite in time, recasting");
            recast(now);
            return;
        }
    }

    private void tickReel(Minecraft mc) {
        holdStill(mc);
        ClientUtils.performUseClick();
        target = null;
        followMove = 0;
        returnAt = 0L;
        firstCatchAt = 0L;
        changeState(State.FIGHT);
        nextActionAt = System.currentTimeMillis() + REEL_SETTLE_MS;
    }

    private void tickFight(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (pooling()) {
            tickPoolFight(mc, now);
            return;
        }

        if (target != null && !CatchWatch.isAlive(target)) {
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
                if (homeKeeper.isOnOrigin(mc)) {
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

    // a double hook brings two catches up, so the pool takes every new match until the window closes
    private void tickPoolFight(Minecraft mc, long now) {
        holdStill(mc);
        for (Entity caught = findTarget(mc); caught != null; caught = findTarget(mc)) {
            pooledCatchIds.add(caught.getId());
            preReelEntityIds.add(caught.getId());
            if (firstCatchAt == 0L) {
                firstCatchAt = now;
            }
        }
        if (firstCatchAt != 0L) {
            if (now - firstCatchAt >= DOUBLE_HOOK_WINDOW_MS) {
                poolCatch(mc);
            }
            return;
        }
        // loot never spawns a mob, and the rod never left the start block, so just cast again
        if (now - stateEnteredAt > ACQUIRE_TIMEOUT_MS) {
            if (homeKeeper.isOnOrigin(mc)) {
                recast(now);
                return;
            }
            emptyCatch = true;
            beginReturn(mc);
        }
    }

    // the catch stays stuck in the pool, so it is only counted and the line goes back out
    private void poolCatch(Minecraft mc) {
        firstCatchAt = 0L;
        target = null;
        pruneDeadCatches(mc);
        int goal = poolGoal();
        ClientUtils.sendDebugMessage("[StriderFishing] pool holds " + pooledCatchIds.size() + "/" + goal);
        if (soulWhipGoalReached(pooledCatchIds.size(), goal)) {
            startClear();
            return;
        }
        emptyCatch = false;
        if (homeKeeper.isOnOrigin(mc)) {
            changeState(State.AIM_LAVA);
            return;
        }
        beginReturn(mc);
    }

    private void startClear() {
        capReached = false;
        clearWhip();
        changeState(State.CLEAR);
    }

    private void tickClear(Minecraft mc) {
        long now = System.currentTimeMillis();
        for (int i = pruneDeadCatches(mc); i > 0; i--) {
            ActivityRateTracker.onMobKilled();
        }

        if (pooledCatchIds.isEmpty() || now - stateEnteredAt > CLEAR_TIMEOUT_MS) {
            if (!pooledCatchIds.isEmpty()) {
                ClientUtils.sendDebugMessage("[StriderFishing] pool clear timed out, "
                        + pooledCatchIds.size() + " striders left as strays");
                strayIds.addAll(pooledCatchIds);
            }
            finishClear(mc, now);
            return;
        }

        if (target == null || !CatchWatch.isAlive(target) || !pooledCatchIds.contains(target.getId())) {
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
        if (!whipClicker.midUse() && whipFailing(whipsAtTarget, now - whipTargetSince)) {
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
        BlockPos origin = homeKeeper.origin();
        Vec3 home = origin == null ? mc.player.position() : Vec3.atBottomCenterOf(origin);
        for (int id : pooledCatchIds) {
            Entity entity = mc.level.getEntity(id);
            if (!CatchWatch.isAlive(entity)) {
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

        if (whipClicker.tick(now, ticks, soulWhipSlot(), weaponSlot(),
                AetherConfig.STRIDER_FISHING_WHIP_SWAP_MIN.get(), AetherConfig.STRIDER_FISHING_WHIP_SWAP_MAX.get(),
                () -> isAimedAt(mc, aim), ThreadLocalRandom.current())) {
            whipsAtTarget++;
        }
    }

    private void finishClear(Minecraft mc, long now) {
        pooledCatchIds.clear();
        target = null;
        followMove = 0;
        clearWhip();
        clearKillPlan();
        releaseAll(mc);
        if (homeKeeper.isOnOrigin(mc)) {
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
        whipClicker.reset();
    }

    private int pruneDeadCatches(Minecraft mc) {
        int before = pooledCatchIds.size();
        pooledCatchIds.removeIf(id -> !CatchWatch.isAlive(mc.level.getEntity(id)));
        return before - pooledCatchIds.size();
    }

    private int pruneDeadStrays(Minecraft mc) {
        int before = strayIds.size();
        strayIds.removeIf(id -> !CatchWatch.isAlive(mc.level.getEntity(id)));
        return before - strayIds.size();
    }

    private Entity nearestPooledCatch(Minecraft mc, boolean manual) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int id : pooledCatchIds) {
            Entity entity = mc.level.getEntity(id);
            if (!CatchWatch.isAlive(entity) || manualKillIds.contains(id) != manual) {
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

    private static boolean isAimedAt(Minecraft mc, Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        return aimWithin(mc.player.getYRot(), mc.player.getXRot(),
                CastSim.yawTo(dx, dz), CastSim.pitchTo(dx, dy, dz), WHIP_AIM_TOLERANCE_DEGREES);
    }

    static boolean aimWithin(float yaw, float pitch, float wantYaw, float wantPitch, float tolerance) {
        return Math.abs(Mth.wrapDegrees(wantYaw - yaw)) <= tolerance
                && Math.abs(wantPitch - pitch) <= tolerance;
    }

    static boolean soulWhipGoalReached(int pooled, int goal) {
        return pooled >= goal;
    }

    // every live stray takes a place under the cap, so a goal past what is left would never be reached
    static int effectiveGoal(int goal, int liveStrays) {
        return Math.min(goal, SEA_CREATURE_CAP - liveStrays);
    }

    static boolean isCapLine(String plain) {
        return plain != null && plain.toLowerCase(Locale.ROOT).contains(CAP_LINE);
    }

    private boolean pooling() {
        return AetherConfig.STRIDER_FISHING_SOUL_WHIP_FISHING.get();
    }

    private int poolGoal() {
        return effectiveGoal(AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get(), strayIds.size());
    }

    @Override
    void onChat(String plain) {
        if (isCapLine(plain)) {
            capReached = true;
        }
    }

    private void tickReturn(Minecraft mc) {
        if (homeKeeper.origin() == null) {
            changeState(State.AIM_LAVA);
            return;
        }
        HomeKeeper.Result result = homeKeeper.tick(mc, System.currentTimeMillis(), ThreadLocalRandom.current());
        if (result == HomeKeeper.Result.ARRIVED) {
            arriveHome(mc);
        } else if (result == HomeKeeper.Result.FAILED) {
            fail("Strider fishing stopped: could not get back onto the start block.");
        }
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
        homeKeeper.beginTrip(mc);
        releaseAll(mc);
        changeState(State.RETURN);
    }

    private void arriveHome(Minecraft mc) {
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
        aimSearch = null;
        aimTargetBlock = null;
        aimRetryAt = 0L;
        aimSweep = 0;
        castLanding = null;
        castAimBlock = null;
    }

    static long nextAimRetryDelayMs(ThreadLocalRandom random) {
        return random.nextLong(AIM_RETRY_MIN_MS, AIM_RETRY_MAX_MS + 1);
    }

    static boolean aimRetryDelayInRange(long delay) {
        return delay >= AIM_RETRY_MIN_MS && delay <= AIM_RETRY_MAX_MS;
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

    private static int rodSlot() {
        return Mth.clamp(AetherConfig.STRIDER_FISHING_ROD_SLOT.get() - 1, 0, 8);
    }

    private static int weaponSlot() {
        return Mth.clamp(AetherConfig.STRIDER_FISHING_WEAPON_SLOT.get() - 1, 0, 8);
    }

    private static int soulWhipSlot() {
        return Mth.clamp(AetherConfig.STRIDER_FISHING_SOUL_WHIP_SLOT.get() - 1, 0, 8);
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

    // where a cast at the current look comes down, or null when that is off the lava or where a float already missed
    private BlockPos lookLanding(Minecraft mc) {
        Vec3 landing = CastSim.predictCastLanding(mc.level, mc.player.getEyePosition(), mc.player.getYRot(),
                mc.player.getXRot(), CastSim::isLava, CastSim.DEFAULT_TICKS);
        BlockPos block = landing == null ? null : BlockPos.containing(landing);
        return CastSim.acceptsLanding(block, rejectedLava) ? block : null;
    }

    // the sim promised this throw the lava, so neither the cell nor the block it aimed at is trusted again
    private void rejectCast() {
        if (castLanding != null) {
            rejectedLava.add(castLanding);
        }
        if (castAimBlock != null) {
            rejectedLava.add(castAimBlock);
        }
        castLanding = null;
        castAimBlock = null;
    }

    private static String catchNeedle() {
        String wanted = AetherConfig.STRIDER_FISHING_TARGET_NAME.get();
        return wanted == null ? "" : CatchWatch.stripFormatting(wanted).toLowerCase(Locale.ROOT).trim();
    }

    private Entity findTarget(Minecraft mc) {
        String needle = catchNeedle();
        return CatchWatch.findTarget(mc, preReelEntityIds,
                entity -> needle.isEmpty() || CatchWatch.matchesName(mc.level, entity, needle));
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

    public State getState() {
        return state;
    }
}
