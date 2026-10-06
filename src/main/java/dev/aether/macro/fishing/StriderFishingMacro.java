package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.profit.helpers.ActivityRateTracker;
import dev.aether.modules.routes.BlockCentering;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.EntityUtils;
import dev.aether.util.SkyblockItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
import java.util.random.RandomGenerator;

// lava fishing for stridersurfers: cast, wait for the marker to flip from ? to !!, reel, kill, walk home
// every delay here is wall-clock and every decision runs on the client tick, so the macro behaves the same at 10 or 240 fps
public final class StriderFishingMacro extends AbstractFishingMacro {

    public enum State { CENTER, AIM_LAVA, CAST, WAIT_BITE, REEL, FIGHT, CLEAR, RETURN }

    enum CastMode { LOOK_UP, CLASSIC }

    // what a throw was meant to do, so a float that comes down off the lava can rule it out
    record CastRecord(CastMode mode, float yaw, float pitch, BlockPos aimBlock, BlockPos landing,
                      int predictedTicks) {
    }

    record SolveKey(BlockPos origin, boolean crouched) {
    }

    // one spot's look-up throws around the circle, and what real floats have since shown about them
    private static final class YawSolve {
        final boolean[] lands = new boolean[YAW_SAMPLES];
        final int[] landTicks = new int[YAW_SAMPLES];
        final List<Float> rejectedYaws = new ArrayList<>();
        int solved;
        float confirmedYaw = Float.NaN;
    }

    // a route ending here fishes the lava pit beside sawyer on galatea, where a caught strider cannot walk out
    static final BlockPos FIXED_SPOT = new BlockPos(-694, 120, 78);

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
    // four throws in a row that never reach the lava means the spot itself is wrong, not the aim
    private static final int MAX_FAILED_CASTS = 4;
    // a second snag in a row means the pool is in the way, and the whip is what clears it
    private static final int SNAG_CLEAR_STREAK = 2;
    private static final long CARRIER_GRACE_MS = 3_000L;
    private static final int CARRIER_LAVA_DEPTH = 2;
    private static final long CLEAR_REEL_RETRY_MS = 1_000L;
    private static final float WHIP_YAW_JITTER = 3.0f;
    private static final float WHIP_PITCH_MIN = 84.0f;
    private static final float WHIP_PITCH_MAX = 89.5f;
    private static final double WHIP_REACH = 5.0;
    // a turn that ended off the stair gets a moment for the camera to settle before it is tried again
    private static final long WHIP_AIM_SETTLE_MS = 1_000L;
    private static final int MAX_WHIP_RETURNS = 3;
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
    // centring only counts its own timeout on the ground, so a player still bobbing is cut off here
    private static final long CENTER_LIMIT_MS = 3_000L;
    // any pitch steeper than about -78.7 throws the same lob, so at the sawyer spot only the yaw is solved
    private static final int YAW_SAMPLES = 180;
    private static final float YAW_STEP = 2.0f;
    private static final float SOLVE_PITCH = -86.0f;
    // the lob takes up to about 7.3 s to come down onto lava 10 blocks under the feet
    private static final int LOOK_UP_TICKS = 170;
    private static final int YAWS_PER_TICK = 30;
    private static final int YAW_MARGIN_SAMPLES = 1;
    private static final float REJECT_WINDOW_DEGREES = 4.0f;
    private static final float LOOK_UP_YAW_JITTER = 1.5f;
    private static final float LOOK_UP_PITCH_MIN = -89.0f;
    private static final float LOOK_UP_PITCH_MAX = -84.0f;
    private static final float LOOK_UP_READY_PITCH = -79.0f;
    private static final float LOOK_UP_YAW_TOLERANCE = 2.0f;

    private State state = State.AIM_LAVA;
    private State pendingState = State.AIM_LAVA;
    private State afterReturn = State.AIM_LAVA;
    private BlockCentering centering;
    private long stateEnteredAt;
    private long nextActionAt;
    private long nextAttackAt;
    private long returnAt;
    private int followMove;
    private boolean emptyCatch;
    private final Set<BlockPos> rejectedLava = new HashSet<>();
    private BlockPos aimTargetBlock;
    private CastRecord lastCast;
    private YawSolve castSolve;
    private boolean castJudged;
    private long hookSeenAt;
    private CastMode castMode = CastMode.CLASSIC;
    private float wantedYaw;
    private int predictedTicks;
    private boolean lookUpIssued;
    private float snagYaw = Float.NaN;
    private int failedCasts;
    private boolean failed;
    private int snagStreak;
    private int markerId = -1;
    private BlockPos whipFloor;
    private int whipTurns;
    private long whipTurnEndedAt;
    private long clearReelAt;
    // the classic search is the sawyer spot's fallback; once it runs dry the look-up throw goes out anyway
    private boolean classicExhausted;
    private CastAimSearch aimSearch;
    private long aimRetryAt;
    private int aimSweep;
    private Entity target;
    private final HomeKeeper homeKeeper = new HomeKeeper("[StriderFishing]",
            () -> AetherConfig.STRIDER_FISHING_ETHERWARP_RETURN.get(), Entity::isInLiquid, false);

    private final Set<Integer> pooledCatchIds = new LinkedHashSet<>();
    // outlives the macro instance, so a stop and start in the same lobby picks the pool back up
    private static final Set<Integer> rememberedCatchIds = new LinkedHashSet<>();
    private static WeakReference<Level> rememberedLevel = new WeakReference<>(null);
    // outlives the macro like the pool, so a stop and start at the same spot neither solves nor misses again
    private static final Map<SolveKey, YawSolve> yawSolves = new HashMap<>();
    private static WeakReference<Level> yawSolveLevel = new WeakReference<>(null);
    // catches a timed out clear left alive, which still count against the sea creature cap
    private final Set<Integer> strayIds = new LinkedHashSet<>();
    private static final Set<Integer> rememberedStrayIds = new LinkedHashSet<>();
    private long firstCatchAt;
    private boolean capReached;
    private boolean hotbarCheckPending;
    private boolean axeWarned;
    private int whipSlot = -1;
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
        lastCast = null;
        castSolve = null;
        castMode = CastMode.CLASSIC;
        snagYaw = Float.NaN;
        failedCasts = 0;
        snagStreak = 0;
        nextAttackAt = 0L;
        returnAt = 0L;
        emptyCatch = false;
        preReelEntityIds.clear();
        pooledCatchIds.clear();
        strayIds.clear();
        firstCatchAt = 0L;
        capReached = false;
        hotbarCheckPending = true;
        axeWarned = false;
        whipSlot = -1;
        clearWhip();
        clearKillPlan();
        centering = null;
        afterReturn = State.AIM_LAVA;
        changeState(State.AIM_LAVA);
        BlockPos origin = homeKeeper.origin();
        ClientUtils.sendDebugMessage("[StriderFishing] started at "
                + origin.getX() + ", " + origin.getY() + ", " + origin.getZ());
        resumeRememberedPool(mc);
        if (state == State.AIM_LAVA) {
            centreThen(mc, State.AIM_LAVA);
        }
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
            startClear(mc);
        }
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

        if (hotbarCheckPending) {
            hotbarCheckPending = false;
            if (!checkHotbar(mc)) {
                return;
            }
        }

        if (pooling() && !strayIds.isEmpty()) {
            for (int i = pruneDeadStrays(mc); i > 0; i--) {
                ActivityRateTracker.onMobKilled();
            }
            if (poolGoal() < 1) {
                fail("Strider fishing stopped: striders escaped the pool, kill them by hand.",
                        " (" + strayIds.size() + ")");
                return;
            }
        }

        // only the lava turn has to land before its state can carry on; waiting for a bite still has to
        // poll the marker every tick, or the short !! window could slip by
        if (state == State.AIM_LAVA && (RotationManager.isRotating() || HumanFlick.isActive())) {
            holdStill(mc);
        } else {
            switch (state) {
                case CENTER -> tickCenter(mc);
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
                startClear(mc);
            }
        }

        if (!sawyer()) {
            watchCage(mc);
        }

        // last word on the jump key, since every state above clears it; a failure this tick already let go of it
        if (!failed) homeKeeper.tickLiquidEscape(mc, System.currentTimeMillis(), ThreadLocalRandom.current());
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

        if (sawyer()) {
            tickLookUpAim(mc, now);
            return;
        }
        tickClassicAim(mc, now);
    }

    private void tickLookUpAim(Minecraft mc, long now) {
        if (lookUpIssued) {
            lookUpIssued = false;
            if (lookUpReady(mc)) {
                beginCast(mc, now);
                return;
            }
            // the turn was cut short, so it is picked and issued again
        }
        YawSolve solve = yawSolve(mc);
        if (solve.solved < YAW_SAMPLES) {
            solveYaws(mc, solve);
            return;
        }

        float current = Mth.wrapDegrees(mc.player.getYRot());
        float yaw;
        int landTicks;
        if (!Float.isNaN(snagYaw)) {
            yaw = snagYaw;
            snagYaw = Float.NaN;
            landTicks = ticksNear(solve, yaw);
        } else if (!Float.isNaN(solve.confirmedYaw) && !isRejected(solve, solve.confirmedYaw)) {
            yaw = solve.confirmedYaw;
            landTicks = ticksNear(solve, yaw);
        } else {
            int index = bestYawIndex(usableYaws(solve), YAW_MARGIN_SAMPLES);
            if (index >= 0) {
                yaw = sampleYaw(index, YAW_STEP);
                landTicks = solve.landTicks[index];
            } else if (index == -2) {
                yaw = current;
                landTicks = ticksNear(solve, yaw);
            } else if (!classicExhausted) {
                tickClassicAim(mc, now);
                return;
            } else {
                yaw = current;
                landTicks = 0;
            }
        }

        RandomGenerator random = ThreadLocalRandom.current();
        castMode = CastMode.LOOK_UP;
        wantedYaw = yaw + jitter(random, LOOK_UP_YAW_JITTER);
        predictedTicks = landTicks;
        lookUpIssued = true;
        RotationManager.rotateToYawPitch(mc, wantedYaw, lookUpPitch(random), AetherConfig.ROTATION_TIME.get(), true);
    }

    private boolean lookUpReady(Minecraft mc) {
        return mc.player.getXRot() <= LOOK_UP_READY_PITCH
                && Math.abs(Mth.wrapDegrees(mc.player.getYRot() - wantedYaw)) <= LOOK_UP_YAW_TOLERANCE;
    }

    private void beginCast(Minecraft mc, long now) {
        FailsafeManager.selectHotbarSlot(mc, rodSlot());
        changeState(State.CAST);
        nextActionAt = now + castDelayForCycle();
    }

    private YawSolve yawSolve(Minecraft mc) {
        if (yawSolveLevel.get() != mc.level) {
            yawSolves.clear();
            yawSolveLevel = new WeakReference<>(mc.level);
        }
        return yawSolves.computeIfAbsent(new SolveKey(homeKeeper.origin(), mc.player.isCrouching()),
                key -> new YawSolve());
    }

    // solved from where the eye sits once centred, so a stance a little off the middle does not skew it
    private void solveYaws(Minecraft mc, YawSolve solve) {
        Pose pose = mc.player.isCrouching() ? Pose.CROUCHING : Pose.STANDING;
        Vec3 eye = Vec3.atBottomCenterOf(homeKeeper.origin()).add(0.0, mc.player.getEyeHeight(pose), 0.0);
        int end = Math.min(YAW_SAMPLES, solve.solved + YAWS_PER_TICK);
        for (int i = solve.solved; i < end; i++) {
            float yaw = sampleYaw(i, YAW_STEP);
            Vec3 landing = CastSim.predictCastLanding(mc.level, eye, yaw, SOLVE_PITCH, CastSim::isLava,
                    LOOK_UP_TICKS);
            solve.lands[i] = landing != null;
            solve.landTicks[i] = landing == null ? 0
                    : landingTick(CastSim.castPath(eye, yaw, SOLVE_PITCH, LOOK_UP_TICKS), eye, landing);
        }
        solve.solved = end;
    }

    private static boolean[] usableYaws(YawSolve solve) {
        boolean[] usable = solve.lands.clone();
        for (float rejected : solve.rejectedYaws) {
            rejectWindow(usable, rejected, REJECT_WINDOW_DEGREES, YAW_STEP);
        }
        return usable;
    }

    private static boolean isRejected(YawSolve solve, float yaw) {
        for (float rejected : solve.rejectedYaws) {
            if (Math.abs(Mth.wrapDegrees(yaw - rejected)) <= REJECT_WINDOW_DEGREES) {
                return true;
            }
        }
        return false;
    }

    private static int ticksNear(YawSolve solve, float yaw) {
        int index = nearestSample(yaw, YAW_SAMPLES, YAW_STEP);
        return solve.lands[index] ? solve.landTicks[index] : 0;
    }

    // the middle of the longest run of landing yaws around the circle; -2 when every yaw lands, -1 when no run
    // keeps the margin either side
    static int bestYawIndex(boolean[] lands, int marginSamples) {
        int n = lands.length;
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (!lands[i]) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return n == 0 ? -1 : -2;
        }
        int bestStart = -1;
        int bestLength = 0;
        int runStart = -1;
        int run = 0;
        for (int k = 1; k <= n; k++) {
            int i = (start + k) % n;
            if (!lands[i]) {
                run = 0;
                continue;
            }
            if (run == 0) {
                runStart = i;
            }
            run++;
            if (run > bestLength) {
                bestLength = run;
                bestStart = runStart;
            }
        }
        if (bestLength == 0 || bestLength < 2 * Math.max(0, marginSamples) + 1) {
            return -1;
        }
        return (bestStart + bestLength / 2) % n;
    }

    static void rejectWindow(boolean[] lands, float rejectedYaw, float windowDeg, float stepDeg) {
        for (int i = 0; i < lands.length; i++) {
            if (Math.abs(Mth.wrapDegrees(sampleYaw(i, stepDeg) - rejectedYaw)) <= windowDeg) {
                lands[i] = false;
            }
        }
    }

    static float sampleYaw(int index, float stepDeg) {
        return -180.0f + index * stepDeg;
    }

    static int nearestSample(float yaw, int samples, float stepDeg) {
        return Math.floorMod(Math.round((Mth.wrapDegrees(yaw) + 180.0f) / stepDeg), samples);
    }

    // the float only ever moves further out, so it lands on the first tick that reaches the landing's distance
    static int landingTick(Vec3[] path, Vec3 eye, Vec3 landing) {
        double target = horizontalSq(landing, eye);
        for (int i = 1; i < path.length; i++) {
            if (horizontalSq(path[i], eye) >= target) {
                return i;
            }
        }
        return path.length - 1;
    }

    private static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    static float jitter(RandomGenerator random, float half) {
        return half <= 0.0f ? 0.0f : random.nextFloat(-half, half);
    }

    static float lookUpPitch(RandomGenerator random) {
        return random.nextFloat(LOOK_UP_PITCH_MIN, LOOK_UP_PITCH_MAX);
    }

    private void tickClassicAim(Minecraft mc, long now) {
        if (lookLanding(mc) != null) {
            castMode = CastMode.CLASSIC;
            beginCast(mc, now);
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
        if (step.status() == CastAimSearch.Status.EXHAUSTED && sawyer()) {
            aimSearch = null;
            classicExhausted = true;
            ClientUtils.sendDebugMessage("[StriderFishing] no lava lined up, throwing up at the current yaw");
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
        Vec3 landing = null;
        if (castMode == CastMode.LOOK_UP) {
            // the solve already promised this yaw the lava, and any pitch this steep throws the same lob
            if (!lookUpReady(mc)) {
                changeState(State.AIM_LAVA);
                return;
            }
        } else {
            landing = lookLandingAt(mc);
            if (landing == null) {
                changeState(State.AIM_LAVA);
                return;
            }
        }

        FailsafeManager.selectHotbarSlot(mc, rodSlot());
        ClientUtils.performUseClick();
        // a bobber still out means that click reeled the stuck line in, so cast on the next pass
        if (CatchWatch.hasLiveHook(mc)) {
            nextActionAt = now + castDelayMs();
            return;
        }
        recordCast(mc, landing);
        aimTargetBlock = null;
        emptyCatch = false;
        changeState(State.WAIT_BITE);
    }

    private void recordCast(Minecraft mc, Vec3 landing) {
        float yaw = Mth.wrapDegrees(mc.player.getYRot());
        float pitch = mc.player.getXRot();
        if (castMode == CastMode.LOOK_UP) {
            lastCast = new CastRecord(CastMode.LOOK_UP, yaw, pitch, null, null, predictedTicks);
            castSolve = yawSolve(mc);
        } else {
            Vec3 eye = mc.player.getEyePosition();
            int ticks = landingTick(CastSim.castPath(eye, mc.player.getYRot(), pitch, CastSim.DEFAULT_TICKS),
                    eye, landing);
            lastCast = new CastRecord(CastMode.CLASSIC, yaw, pitch, aimTargetBlock, BlockPos.containing(landing),
                    ticks);
            castSolve = null;
        }
        castJudged = false;
        hookSeenAt = 0L;
        markerId = -1;
    }

    private void tickWaitBite(Minecraft mc) {
        holdStill(mc);
        long now = System.currentTimeMillis();

        if (!CatchWatch.hasLiveHook(mc)) {
            // the cast never left the rod, or the line came back on its own
            if (hookSeenAt != 0L || now - stateEnteredAt > BOBBER_SETTLE_MS) {
                ClientUtils.sendDebugMessage("[StriderFishing] no float out, recasting");
                if (!castFailed()) {
                    recast(now);
                }
            }
            return;
        }

        FishingHook hook = mc.player.fishing;
        if (hookSeenAt == 0L) {
            hookSeenAt = now;
        }
        // a pool packed with striders can snag the float on one of them, which will never bite
        Entity hooked = hook.getHookedIn();
        if (hooked != null && pooledCatchIds.contains(hooked.getId())) {
            snag(mc);
            return;
        }
        if (hooked != null && pooling() && isUnpooledCatch(mc, hooked)) {
            strayIds.remove(hooked.getId());
            pooledCatchIds.add(hooked.getId());
            snag(mc);
            return;
        }
        // hypixel parks the lava float on its own carrier, which has to sit right over the lava
        boolean carried = hooked != null;
        boolean inLava = carried ? overLava(mc.level, hooked.position())
                : CatchWatch.floatsOn(mc.level, hook, CastSim::isLava);
        if (carried && !inLava && now - hookSeenAt >= CARRIER_GRACE_MS) {
            ClientUtils.sendDebugMessage("[StriderFishing] float caught on something away from the lava, recasting");
            ClientUtils.performUseClick();
            if (castFailed()) {
                return;
            }
            // the same throw would only land on it again, so it moves off the obstruction
            if (lastCast != null && lastCast.mode() == CastMode.CLASSIC) {
                if (lastCast.landing() != null) {
                    rejectedLava.add(lastCast.landing());
                }
            } else {
                moveThrowOffSnag();
            }
            changeState(State.AIM_LAVA);
            return;
        }

        long deadline = CatchWatch.settleDeadlineMs(lastCast == null ? 0 : lastCast.predictedTicks());
        if (!CatchWatch.settled(carried, hook.onGround(), inLava, now, hookSeenAt, deadline)) {
            return;
        }

        // only read once the float is down, so a neighbour's marker cannot pass for ours while it flies; picked
        // again every tick, since a carried float settles before its own stand spawns and a neighbour's may be first
        int nearest = CatchWatch.lockMarker(mc.level, hook);
        if (nearest >= 0) {
            markerId = nearest;
        }
        if (CatchWatch.isBite(mc.level, markerId)) {
            castLanded();
            CatchWatch.snapshot(mc.level, preReelEntityIds);
            changeState(State.REEL);
            return;
        }

        if (!carried && !inLava) {
            // a float sitting on stone will never get a bite, so reel it in and aim somewhere else
            ClientUtils.sendDebugMessage("[StriderFishing] float landed out of the lava, recasting");
            rejectCast();
            ClientUtils.performUseClick();
            if (!castFailed()) {
                changeState(State.AIM_LAVA);
            }
            return;
        }
        if (inLava && !castJudged) {
            castLanded();
        }

        if (now - stateEnteredAt > BITE_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[StriderFishing] no bite in time, recasting");
            recast(now);
        }
    }

    // a float down in the lava proves the throw, so the misses before it are forgiven
    private void castLanded() {
        castJudged = true;
        failedCasts = 0;
        snagStreak = 0;
        if (lastCast != null && lastCast.mode() == CastMode.LOOK_UP && castSolve != null) {
            castSolve.confirmedYaw = lastCast.yaw();
        }
        clearAimSearch();
    }

    // true when the macro was stopped
    private boolean castFailed() {
        if (++failedCasts < MAX_FAILED_CASTS) {
            return false;
        }
        fail("Strider fishing stopped: the float keeps missing the lava from this spot.");
        return true;
    }

    // reeling a snag pulls the strider toward us, and the same throw would only hook it again
    private void snag(Minecraft mc) {
        ClientUtils.sendDebugMessage("[StriderFishing] float hooked a strider, recasting");
        ClientUtils.performUseClick();
        snagStreak++;
        if (snagStreak >= SNAG_CLEAR_STREAK && !pooledCatchIds.isEmpty()) {
            ClientUtils.sendDebugMessage("[StriderFishing] the pool keeps snagging the float, clearing it");
            startClear(mc);
            return;
        }
        moveThrowOffSnag();
        changeState(State.AIM_LAVA);
    }

    private void moveThrowOffSnag() {
        if (lastCast != null && lastCast.mode() == CastMode.LOOK_UP && castSolve != null) {
            int moved = farthestInRun(usableYaws(castSolve),
                    nearestSample(lastCast.yaw(), YAW_SAMPLES, YAW_STEP), YAW_MARGIN_SAMPLES);
            if (moved >= 0) {
                snagYaw = sampleYaw(moved, YAW_STEP);
            }
        }
    }

    private boolean isUnpooledCatch(Minecraft mc, Entity entity) {
        if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand || !CatchWatch.isAlive(entity)
                || EntityUtils.isRealPlayer(mc, entity)) {
            return false;
        }
        // with no name to go on, hypixel's own float carrier could pass for a catch
        String needle = catchNeedle();
        return !needle.isEmpty() && CatchWatch.matchesName(mc.level, entity, needle);
    }

    private static boolean overLava(Level level, Vec3 pos) {
        BlockPos at = BlockPos.containing(pos);
        for (int down = 0; down <= CARRIER_LAVA_DEPTH; down++) {
            BlockPos cell = at.below(down);
            BlockState blockState = level.getBlockState(cell);
            if (CastSim.isLava(blockState)) {
                return pos.y - (cell.getY() + blockState.getFluidState().getHeight(level, cell)) <= CARRIER_LAVA_DEPTH;
            }
        }
        return false;
    }

    // the far end of the yaw run the snag sat in, keeping the margin; -1 when the run has nowhere else to go
    static int farthestInRun(boolean[] lands, int from, int marginSamples) {
        int n = lands.length;
        if (from < 0 || from >= n || !lands[from]) {
            return -1;
        }
        int left = 0;
        while (left < n - 1 && lands[Math.floorMod(from - left - 1, n)]) {
            left++;
        }
        if (left == n - 1) {
            return (from + n / 2) % n;
        }
        int right = 0;
        while (lands[(from + right + 1) % n]) {
            right++;
        }
        int margin = Math.max(0, marginSamples);
        int leftReach = Math.max(0, left - margin);
        int rightReach = Math.max(0, right - margin);
        if (leftReach == 0 && rightReach == 0) {
            return -1;
        }
        return leftReach >= rightReach ? Math.floorMod(from - leftReach, n) : (from + rightReach) % n;
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
            // a held attack key only mines, so with the mouse grabbed only a queued click hits an entity
            ClientUtils.performAttackClickDirect();
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
            startClear(mc);
            return;
        }
        emptyCatch = false;
        if (homeKeeper.isOnOrigin(mc)) {
            changeState(State.AIM_LAVA);
            return;
        }
        beginReturn(mc);
    }

    private void startClear(Minecraft mc) {
        capReached = false;
        snagStreak = 0;
        hotbarCheckPending = true;
        clearWhip();
        centreThen(mc, State.CLEAR);
    }

    // at the sawyer spot the throw and the whip both move with the feet, so the player is centred first;
    // anywhere else the aim is worked out from wherever the player stands
    private void centreThen(Minecraft mc, State next) {
        if (!sawyer() || BlockCentering.isCentred(mc.player.position(), homeKeeper.origin())) {
            changeState(next);
            return;
        }
        if (!homeKeeper.isOnOrigin(mc)) {
            beginReturn(mc, next);
            return;
        }
        pendingState = next;
        centering = new BlockCentering(System.currentTimeMillis(), homeKeeper.origin().below());
        changeState(State.CENTER);
    }

    private void tickCenter(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (centering != null && !centering.tick(mc, now) && now - stateEnteredAt <= CENTER_LIMIT_MS) {
            return;
        }
        centering = null;
        MacroInput.set(mc.options.keyShift, false);
        changeState(pendingState);
    }

    private boolean sawyer() {
        return FIXED_SPOT.equals(homeKeeper.origin());
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

        // the whip is a fishing rod too, so with a line still out its first click would only reel that in
        if (CatchWatch.hasLiveHook(mc)) {
            holdStill(mc);
            if (now >= clearReelAt) {
                FailsafeManager.selectHotbarSlot(mc, rodSlot());
                ClientUtils.performUseClick();
                clearReelAt = now + CLEAR_REEL_RETRY_MS;
            }
            return;
        }

        if (sawyer()) {
            tickStairWhip(mc, now);
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

    // the pool is stuck in the pit under the spot, so the whip goes down onto the stair and never chases one
    private void tickStairWhip(Minecraft mc, long now) {
        holdStill(mc);
        if (!homeKeeper.isOnOrigin(mc)) {
            beginReturn(mc, State.CLEAR);
            return;
        }
        if (whipFloor == null) {
            BlockPos origin = homeKeeper.origin();
            boolean inStair = !mc.level.getBlockState(origin).getCollisionShape(mc.level, origin).isEmpty();
            whipFloor = whipFloor(origin, inStair);
            turnToStair(mc);
        }

        boolean turning = RotationManager.isRotating();
        if (turning) {
            whipTurnEndedAt = 0L;
        } else if (whipTurnEndedAt == 0L) {
            whipTurnEndedAt = now;
        }
        boolean aimed = !turning && aimsAt(mc, whipFloor);
        if (!aimed && !turning && !whipClicker.midUse() && now - whipTurnEndedAt > WHIP_AIM_SETTLE_MS) {
            if (whipTurns >= MAX_WHIP_RETURNS) {
                fail("Strider fishing stopped: could not aim the Soul Whip at the stair under the spot.");
                return;
            }
            whipTurns++;
            turnToStair(mc);
            return;
        }
        whipClicker.tick(now, ticks, whipSlot >= 0 ? whipSlot : soulWhipSlot(), weaponSlot(),
                AetherConfig.STRIDER_FISHING_WHIP_SWAP_MIN.get(), AetherConfig.STRIDER_FISHING_WHIP_SWAP_MAX.get(),
                () -> aimed, ThreadLocalRandom.current());
    }

    // looking down, the lash lands along the yaw, so it faces the way the throws go out over the pit
    private void turnToStair(Minecraft mc) {
        RandomGenerator random = ThreadLocalRandom.current();
        float yaw = stairYaw(mc) + jitter(random, WHIP_YAW_JITTER);
        RotationManager.rotateToYawPitch(mc, yaw, whipPitch(random), AetherConfig.ROTATION_TIME.get(), true);
        whipTurnEndedAt = 0L;
    }

    private float stairYaw(Minecraft mc) {
        YawSolve solve = yawSolve(mc);
        if (!Float.isNaN(solve.confirmedYaw)) {
            return solve.confirmedYaw;
        }
        if (solve.solved >= YAW_SAMPLES) {
            int index = bestYawIndex(usableYaws(solve), YAW_MARGIN_SAMPLES);
            if (index >= 0) {
                return sampleYaw(index, YAW_STEP);
            }
        }
        return Mth.wrapDegrees(mc.player.getYRot());
    }

    private static boolean aimsAt(Minecraft mc, BlockPos block) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot()).scale(WHIP_REACH));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block);
    }

    // standing on the stair's lower step puts the feet inside its own block, so that block is the floor
    static BlockPos whipFloor(BlockPos origin, boolean originHasCollision) {
        return originHasCollision ? origin : origin.below();
    }

    static float whipPitch(RandomGenerator random) {
        return random.nextFloat(WHIP_PITCH_MIN, WHIP_PITCH_MAX);
    }

    // where a look from an eye off the block's centre meets the top of that block, within its footprint or not
    static boolean floorAimHits(double eyeDx, double eyeDz, double eyeAboveTop, float yaw, float pitch) {
        Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
        if (direction.y >= 0.0) {
            return false;
        }
        double reach = eyeAboveTop / -direction.y;
        double x = eyeDx + direction.x * reach;
        double z = eyeDz + direction.z * reach;
        return Math.abs(x) <= 0.5 && Math.abs(z) <= 0.5;
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

        if (whipClicker.tick(now, ticks, whipSlot >= 0 ? whipSlot : soulWhipSlot(), weaponSlot(),
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

    // the whip and the rod are the same vanilla item, so a mixed up slot flays where it should cast or the reverse
    private boolean checkHotbar(Minecraft mc) {
        var inventory = mc.player.getInventory();
        if (SkyblockItems.isSoulWhip(inventory.getItem(rodSlot()))) {
            fail("Strider fishing stopped: the fishing rod slot holds a Soul Whip.");
            return false;
        }
        if (usesWhip()) {
            int slot = soulWhipSlot();
            if (!SkyblockItems.isSoulWhip(inventory.getItem(slot))) {
                slot = SkyblockItems.findHotbarSlot(mc, SkyblockItems::isSoulWhip);
                if (slot < 0) {
                    fail("Strider fishing needs a Soul Whip in the hotbar.");
                    return false;
                }
                ClientUtils.sendDebugMessage("[StriderFishing] soul whip found in slot " + (slot + 1));
            }
            whipSlot = slot;
        }
        // galatea sea creatures only take damage from axes
        if (!axeWarned && !inventory.getItem(weaponSlot()).is(ItemTags.AXES)) {
            axeWarned = true;
            ClientUtils.sendMessage("§e" + AetherLang.localize(
                    "Strider fishing: the weapon slot holds no axe, so Galatea sea creatures will not take its hits."),
                    false);
        }
        return true;
    }

    // a pool cleared by hand never swings the whip, so away from the sawyer spot only the toggle asks for one
    private boolean usesWhip() {
        return AetherConfig.STRIDER_FISHING_SOUL_WHIP.get() || sawyer();
    }

    // a strider caught at the sawyer spot cannot leave the pit, so it is always pooled there
    private boolean pooling() {
        return AetherConfig.STRIDER_FISHING_SOUL_WHIP_FISHING.get() || sawyer();
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
        // a queued whip click must not outlive the aim it was armed for, say across a walk home
        if (state == State.CLEAR && next != State.CLEAR) {
            clearWhip();
        }
        // a clear picked back up after a walk home aims at the stair from scratch
        if (next == State.CLEAR && state != State.CLEAR) {
            whipFloor = null;
            whipTurns = 0;
            whipTurnEndedAt = 0L;
            clearReelAt = 0L;
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
        beginReturn(mc, State.AIM_LAVA);
    }

    private void beginReturn(Minecraft mc, State then) {
        afterReturn = then;
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
        State next = afterReturn;
        afterReturn = State.AIM_LAVA;
        centreThen(mc, next);
    }

    private void fail(String message) {
        fail(message, "");
    }

    // the detail goes on after the lookup, so a count in it never lands in the translation key
    private void fail(String message, String detail) {
        failed = true;
        ClientUtils.sendMessage("§c" + AetherLang.localize(message) + detail, false);
        MacroStateManager.stopMacro(Minecraft.getInstance(), message + detail, false);
    }

    private void clearAimSearch() {
        rejectedLava.clear();
        aimSearch = null;
        aimTargetBlock = null;
        aimRetryAt = 0L;
        aimSweep = 0;
        classicExhausted = false;
        lookUpIssued = false;
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
        Vec3 landing = lookLandingAt(mc);
        return landing == null ? null : BlockPos.containing(landing);
    }

    private Vec3 lookLandingAt(Minecraft mc) {
        Vec3 landing = CastSim.predictCastLanding(mc.level, mc.player.getEyePosition(), mc.player.getYRot(),
                mc.player.getXRot(), CastSim::isLava, CastSim.DEFAULT_TICKS);
        BlockPos block = landing == null ? null : BlockPos.containing(landing);
        return CastSim.acceptsLanding(block, rejectedLava) ? landing : null;
    }

    // the sim promised this throw the lava, so the yaws around it, or the cell and the block it aimed at, are out
    private void rejectCast() {
        if (lastCast == null) {
            return;
        }
        if (lastCast.mode() == CastMode.LOOK_UP) {
            if (castSolve != null) {
                castSolve.rejectedYaws.add(lastCast.yaw());
                if (!Float.isNaN(castSolve.confirmedYaw) && isRejected(castSolve, castSolve.confirmedYaw)) {
                    castSolve.confirmedYaw = Float.NaN;
                }
            }
        } else {
            if (lastCast.landing() != null) {
                rejectedLava.add(lastCast.landing());
            }
            if (lastCast.aimBlock() != null) {
                rejectedLava.add(lastCast.aimBlock());
            }
        }
        lastCast = null;
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
