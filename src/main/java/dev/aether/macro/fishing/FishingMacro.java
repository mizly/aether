package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.profit.helpers.ActivityRateTracker;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.SkyblockItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

// casts into the lava or water next to the start block, reels on the bite and fights whatever came up
// every delay here is wall-clock and every decision runs on the client tick, so the macro behaves the same at any fps
public final class FishingMacro extends AbstractFishingMacro {

    public enum State { MOVE, AIM, HEAL, CAST, WAIT_BITE, REEL, FIGHT }

    public enum AimAt {
        LAVA, WATER, HOTSPOT;

        public static AimAt fromConfig(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                return WATER;
            }
        }
    }

    private static final long BITE_TIMEOUT_MS = 90_000L;
    // a catch surfaces within a tick or two of the reel, so anything new near the float after this is not ours
    private static final long ACQUIRE_WINDOW_MS = 800L;
    private static final long FIGHT_TIMEOUT_MS = 45_000L;
    // 3-6 cps, redrawn every swing so the cadence is not a metronome
    private static final long ATTACK_MIN_DELAY_MS = 167L;
    private static final long ATTACK_MAX_DELAY_MS = 333L;
    // a catch that never comes under the crosshair this long is out of reach from the block
    private static final long UNREACHED_DROP_MS = 6_000L;
    private static final long SNAG_MS = 1_000L;
    // a plate stand spawns a moment after its mob, so an unnamed catch gets this long before it counts as wanted
    private static final long PLATE_WAIT_MS = 2_000L;
    // hypixel lets a player keep ten sea creatures alive, and a full cap stops anything new from spawning
    private static final int UNFOUGHT_CAP = 8;
    private static final String CAP_FULL_LINE = "there is not enough space for another sea creature";
    private static final String CAP_FULL_STOP =
            "Fishing Macro stopped: the sea creature cap is full. Kill the catches left alive or change the mob lists.";
    private static final String TOO_MANY_LEFT_STOP =
            "Fishing Macro stopped: too many catches were left alive. Kill them or change the mob lists.";
    private static final long BOBBER_SETTLE_MS = 1_500L;
    // the server drops the float a moment after the reel, and the wand must not go out while it is still there
    private static final long HEAL_REEL_WAIT_MS = 2_000L;
    private static final long REEL_SETTLE_MS = 350L;
    private static final long EMPTY_CATCH_DELAY_MIN_MS = 150L;
    private static final long EMPTY_CATCH_DELAY_MAX_MS = 400L;
    private static final long AIM_RETRY_MIN_MS = 400L;
    private static final long AIM_RETRY_MAX_MS = 900L;
    private static final int MAX_EMPTY_SWEEPS = 3;
    // the hotspot fallback picks whichever liquid has a surface this close, the same reach the cast search has
    private static final int LIQUID_PICK_RADIUS = 6;
    // the rotation lands on the gcd grid, so the pitch the cast comes from can sit a hair outside the band
    private static final float PITCH_SLACK = 1.0f;
    // well inside the two degrees of margin every picked throw has either side
    private static final float REUSE_YAW_DRIFT = 0.75f;
    private static final float REUSE_PITCH_DRIFT = 0.5f;
    // two blade fights in a row without a kill means it cannot hurt what this spot catches
    private static final int HYPERION_GIVE_UP_STREAK = 2;
    private static final float AIM_SMOOTHING_MS = 110.0f;
    private static final float AIM_MAX_TURN_SPEED = 520.0f;
    private static final double AIM_HEIGHT = 0.6;

    private State state = State.AIM;
    private long stateEnteredAt;
    private long nextActionAt;
    private long nextAttackAt;
    private boolean emptyCatch;

    private final HomeKeeper homeKeeper = new HomeKeeper("[FishingMacro]", () -> false, Entity::isInLava);
    private final IdleMotion idle = new IdleMotion(() -> AetherConfig.FISHING_MACRO_RANDOM_LOOK.get(),
            () -> AetherConfig.FISHING_MACRO_BLOCK_SHUFFLE.get(), null);

    private Predicate<BlockState> liquid;
    private final Set<BlockPos> rejected = new HashSet<>();
    private CastAimSearch aimSearch;
    private BlockPos aimTargetBlock;
    private CastSim.CastAim confirmedAim;
    private long aimRetryAt;
    private int emptySweeps;

    // what the last throw was meant to do, so a float that comes down off the liquid can rule both out
    private BlockPos castLanding;
    private CastSim.CastAim castAim;
    private int castFlightTicks;
    private long hookSeenAt;
    private boolean settleHandled;
    private int markerId = -1;
    private long snagSince;

    // everything loaded when the line was reeled, so a catch is told apart from whatever was already swimming
    private final Set<Integer> preReelIds = new HashSet<>();
    private Vec3 hookAtReel;
    private final Map<Integer, Long> unsortedIds = new LinkedHashMap<>();
    private final Set<Integer> fightIds = new LinkedHashSet<>();
    // catches left alive on purpose or out of reach; they still count against the sea creature cap
    private final Set<Integer> unfoughtIds = new LinkedHashSet<>();
    private boolean capFullSeen;
    private boolean caughtAny;
    private Entity target;
    private long targetReachedAt;
    private long fightStartedAt;

    private final HyperionClearer hyperion = new HyperionClearer();
    private boolean hyperionThisFight;
    private boolean hyperionOff;
    private int hyperionGiveUps;
    private boolean hyperionMissingWarned;

    private final WandHealer healer = new WandHealer();
    private State healResume = State.AIM;
    private long healReelAt;
    private int ticks;

    @Override
    public void onEnable(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        homeKeeper.start(home(mc));
        resetOrigin();
        nextAttackAt = 0L;
        emptyCatch = false;
        clearFight();
        unfoughtIds.clear();
        capFullSeen = false;
        healReelAt = 0L;
        idle.clear();
        changeState(State.AIM);
        BlockPos origin = homeKeeper.origin();
        ClientUtils.sendDebugMessage("[FishingMacro] started at "
                + origin.getX() + ", " + origin.getY() + ", " + origin.getZ());
    }

    @Override
    public void onDisable(Minecraft mc) {
        healer.abort(mc);
        homeKeeper.cancel(mc);
        RotationManager.cancelRotation();
        releaseAll(mc);
        clearFight();
        unfoughtIds.clear();
        idle.clear();
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
        if (guardCap(mc)) {
            return;
        }
        ticks++;
        healer.confirm(mc, System.currentTimeMillis());

        // a turn has to land before the look it was for can be checked; the bite still has to be polled every tick
        if ((state == State.AIM || state == State.CAST) && RotationManager.isRotating()) {
            holdStill(mc);
        } else {
            switch (state) {
                case MOVE -> tickMove(mc);
                case AIM -> tickAim(mc);
                case HEAL -> tickHeal(mc);
                case CAST -> tickCast(mc);
                case WAIT_BITE -> tickWaitBite(mc);
                case REEL -> tickReel(mc);
                case FIGHT -> tickFight(mc);
            }
        }

        // last word on the jump key, since every state above clears it
        homeKeeper.tickLiquidEscape(mc, System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    private void tickAim(Minecraft mc) {
        holdStill(mc);

        if (!homeKeeper.isOnOrigin(mc)) {
            beginMove(mc);
            return;
        }

        long now = System.currentTimeMillis();
        if (healReelAt != 0L) {
            if (mc.player.fishing != null && now - healReelAt < HEAL_REEL_WAIT_MS) {
                return;
            }
            healReelAt = 0L;
        }
        // before the rod comes back out, since the wand is only clicked with the line in
        if (healer.wants(mc, now, mc.player.fishing != null, hyperion.lastClickAt())) {
            enterHeal(State.AIM);
            return;
        }

        // a crouch still lifting moves the eye, and with it where the throw lands
        if (now < aimRetryAt || mc.player.isCrouching() || mc.player.getPose() != Pose.STANDING) {
            return;
        }

        if (liquid == null) {
            liquid = pickLiquid(mc);
        }

        if (aimTargetBlock != null) {
            if (lookLanding(mc) != null) {
                FailsafeManager.selectHotbarSlot(mc, rodSlot());
                changeState(State.CAST);
                nextActionAt = now + castDelayForCycle();
                return;
            }
            // the turn landed somewhere a float cannot go after all, so that spot is out
            rejected.add(aimTargetBlock);
            aimTargetBlock = null;
        }

        // the look that last put a float in the liquid is tried again, a little off so no two throws are identical
        if (confirmedAim != null && !rejected.contains(confirmedAim.block())) {
            RandomGenerator random = ThreadLocalRandom.current();
            turnTo(mc, new CastSim.CastAim(confirmedAim.block(),
                    confirmedAim.yaw() + IdleMotion.driftDegrees(random, REUSE_YAW_DRIFT),
                    confirmedAim.pitch() + IdleMotion.driftDegrees(random, REUSE_PITCH_DRIFT)));
            return;
        }

        if (aimSearch == null) {
            aimSearch = new CastAimSearch(mc.level, mc.player.blockPosition(), mc.player.getEyePosition(),
                    mc.player.getYRot(), CastAimSearch.Spec.GENERAL, rejected, liquid, ThreadLocalRandom.current());
        }
        CastAimSearch.Step step = aimSearch.step();
        if (step.status() == CastAimSearch.Status.WORKING) {
            return;
        }
        if (step.status() == CastAimSearch.Status.EXHAUSTED) {
            aimSearch = null;
            if (sweepsExhausted(++emptySweeps)) {
                fail("Fishing Macro stopped: no water or lava within reach.");
                return;
            }
            aimRetryAt = now + nextAimRetryDelayMs(ThreadLocalRandom.current());
            ClientUtils.sendDebugMessage("[FishingMacro] no throw lined up, searching again");
            return;
        }
        emptySweeps = 0;
        turnTo(mc, step.aim());
    }

    private void turnTo(Minecraft mc, CastSim.CastAim aim) {
        aimTargetBlock = aim.block();
        // forced, since a dropped turn would read as a miss and rule out a good spot
        RotationManager.rotateToYawPitch(mc, aim.yaw(), aim.pitch(), AetherConfig.ROTATION_TIME.get(), true);
    }

    private void tickCast(Minecraft mc) {
        holdStill(mc);
        long now = System.currentTimeMillis();
        if (now < nextActionAt) {
            return;
        }

        if (!homeKeeper.isOnOrigin(mc)) {
            beginMove(mc);
            return;
        }

        // the look is confirmed again at the last moment; aiming handles the retry and rules the spot out
        BlockPos landing = lookLanding(mc);
        if (landing == null) {
            changeState(State.AIM);
            return;
        }

        FailsafeManager.selectHotbarSlot(mc, rodSlot());
        ClientUtils.performUseClick();
        // a bobber still out means that click reeled the stuck line in, so cast on the next pass
        if (CatchWatch.hasLiveHook(mc)) {
            nextActionAt = now + castDelayMs();
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        castLanding = landing;
        castAim = new CastSim.CastAim(aimTargetBlock != null ? aimTargetBlock : landing, yaw, pitch);
        castFlightTicks = flightTicks(CastSim.castPath(eye, yaw, pitch, CastSim.DEFAULT_TICKS), eye,
                Vec3.atCenterOf(landing));
        aimTargetBlock = null;
        hookSeenAt = 0L;
        settleHandled = false;
        markerId = -1;
        snagSince = 0L;
        emptyCatch = false;
        idle.anchor(now, ThreadLocalRandom.current());
        changeState(State.WAIT_BITE);
    }

    private void tickWaitBite(Minecraft mc) {
        long now = System.currentTimeMillis();

        if (!CatchWatch.hasLiveHook(mc)) {
            holdStill(mc);
            // the cast never left the rod, or the line came back on its own
            if (now - stateEnteredAt > BOBBER_SETTLE_MS) {
                recast();
            }
            return;
        }

        FishingHook hook = mc.player.fishing;
        if (hookSeenAt == 0L) {
            hookSeenAt = now;
        }

        // a float stuck in a mob never gets a bite
        Entity hookedIn = hook.getHookedIn();
        snagSince = isSnagged(hookedIn) ? (snagSince == 0L ? now : snagSince) : 0L;
        if (snagHeld(snagSince, now)) {
            ClientUtils.sendDebugMessage("[FishingMacro] float hooked a mob, recasting");
            reelBack();
            return;
        }

        if (!settleHandled && CatchWatch.settled(mc.level, hook, liquid, now, hookSeenAt,
                CatchWatch.settleDeadlineMs(castFlightTicks))) {
            settleHandled = true;
            boolean inLiquid = CatchWatch.floatInLiquid(mc.level, hook, liquid);
            // a float parked on an entity says nothing about where it came down
            if (!inLiquid && hookedIn == null) {
                ClientUtils.sendDebugMessage("[FishingMacro] float landed out of the liquid, recasting");
                rejectCast();
                reelBack();
                return;
            }
            if (inLiquid) {
                // a float down in the liquid proves the spot, so the misses before it are forgiven
                rejected.clear();
                aimSearch = null;
                emptySweeps = 0;
                confirmedAim = castAim;
            }
        }

        // the timer over the float only means anything once the float has stopped
        if (settleHandled) {
            if (!markerStillThere(mc)) {
                markerId = CatchWatch.lockMarker(mc.level, hook);
            }
            if (CatchWatch.isBite(mc.level, markerId)) {
                idle.clear();
                CatchWatch.snapshot(mc.level, preReelIds);
                hookAtReel = hook.position();
                changeState(State.REEL);
                return;
            }
        }

        // the wand needs the line in, so low health gives up this cast
        if (healer.wants(mc, now, false, hyperion.lastClickAt())) {
            ClientUtils.sendDebugMessage("[FishingMacro] health is low, reeling in to heal");
            healReelAt = now;
            reelBack();
            return;
        }

        if (now - stateEnteredAt > BITE_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[FishingMacro] no bite in time, recasting");
            recast();
            return;
        }

        idle.tick(mc, now, false, !mc.player.isInLiquid(), homeKeeper.isOnOrigin(mc), ThreadLocalRandom.current());
    }

    private boolean markerStillThere(Minecraft mc) {
        Entity marker = markerId < 0 ? null : mc.level.getEntity(markerId);
        return marker != null && !marker.isRemoved();
    }

    private static boolean isSnagged(Entity hookedIn) {
        return hookedIn instanceof LivingEntity && !(hookedIn instanceof ArmorStand) && CatchWatch.isAlive(hookedIn);
    }

    static boolean snagHeld(long snagSince, long now) {
        return snagSince != 0L && now - snagSince > SNAG_MS;
    }

    private void reelBack() {
        idle.clear();
        ClientUtils.performUseClick();
        changeState(State.AIM);
        aimRetryAt = System.currentTimeMillis() + nextEmptyCatchDelayMs(ThreadLocalRandom.current());
    }

    private void tickReel(Minecraft mc) {
        holdStill(mc);
        ClientUtils.performUseClick();
        target = null;
        fightIds.clear();
        long now = System.currentTimeMillis();
        hyperionThisFight = AetherConfig.FISHING_MACRO_USE_HYPERION.get() && !hyperionOff && readyHyperion(mc, now);
        changeState(State.FIGHT);
        fightStartedAt = now;
        nextActionAt = now + REEL_SETTLE_MS;
    }

    private boolean readyHyperion(Minecraft mc, long now) {
        int slot = SkyblockItems.findHotbarSlot(mc, SkyblockItems::isHyperion);
        if (slot < 0) {
            if (!hyperionMissingWarned) {
                hyperionMissingWarned = true;
                ClientUtils.sendMessage("§e" + AetherLang.localize(
                        "Fishing Macro: no Hyperion in the hotbar, fighting with the weapon instead."), false);
            }
            return false;
        }
        hyperion.begin(slot, now);
        return true;
    }

    private void tickFight(Minecraft mc) {
        long now = System.currentTimeMillis();
        boolean acquiring = now - fightStartedAt <= ACQUIRE_WINDOW_MS;
        if (acquiring && hookAtReel != null) {
            acquire(mc, now);
        }
        sortCatches(mc, now);
        pruneFight(mc, now);

        if (fightIds.isEmpty()) {
            holdStill(mc);
            if (acquiring || !unsortedIds.isEmpty()) {
                return;
            }
            finishFight(mc);
            return;
        }

        if (now - fightStartedAt > FIGHT_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[FishingMacro] fight timed out, leaving the rest");
            unfoughtIds.addAll(fightIds);
            unfoughtIds.addAll(unsortedIds.keySet());
            fightIds.clear();
            unsortedIds.clear();
            finishFight(mc);
            return;
        }

        if (hyperionThisFight) {
            if (!hyperion.givenUp(now)) {
                fightWithHyperion(mc, now);
                return;
            }
            giveUpHyperion();
        }
        melee(mc, now);
    }

    private void fightWithHyperion(Minecraft mc, long now) {
        holdFightKeys(mc);
        for (int id : hyperion.tick(mc, now, fightIds, ThreadLocalRandom.current())) {
            ClientUtils.sendDebugMessage("[FishingMacro] a catch stayed out of the blade's reach, leaving it");
            fightIds.remove(id);
            unfoughtIds.add(id);
        }
    }

    private void giveUpHyperion() {
        hyperionThisFight = false;
        if (++hyperionGiveUps >= HYPERION_GIVE_UP_STREAK) {
            hyperionOff = true;
            ClientUtils.sendMessage("§e" + AetherLang.localize(
                    "Fishing Macro: the Hyperion is not killing anything here, using the weapon for the rest of the session."),
                    false);
        } else {
            ClientUtils.sendDebugMessage("[FishingMacro] the hyperion is not killing it, finishing the fight by hand");
        }
    }

    // every new mob at the float counts, since a double hook brings up two
    private void acquire(Minecraft mc, long now) {
        for (Entity entity : CatchWatch.newCatches(mc.level, hookAtReel, CatchWatch.CATCH_RADIUS, preReelIds)) {
            int id = entity.getId();
            if (!fightIds.contains(id) && !unfoughtIds.contains(id) && !unsortedIds.containsKey(id)) {
                unsortedIds.put(id, now);
                caughtAny = true;
            }
        }
    }

    // the lists are read off the catch's plate, which can show up a moment after the catch itself
    private void sortCatches(Minecraft mc, long now) {
        List<String> whitelist = AetherConfig.FISHING_MACRO_MOB_WHITELIST.get();
        List<String> blacklist = AetherConfig.FISHING_MACRO_MOB_BLACKLIST.get();
        Iterator<Map.Entry<Integer, Long>> pending = unsortedIds.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Integer, Long> entry = pending.next();
            Entity entity = mc.level.getEntity(entry.getKey());
            if (!CatchWatch.isAlive(entity)) {
                pending.remove();
                continue;
            }
            String plate = CatchWatch.plateName(mc.level, entity);
            MobFilter.Verdict verdict = settleVerdict(MobFilter.classify(plate, whitelist, blacklist),
                    entry.getValue(), now);
            if (verdict == MobFilter.Verdict.UNKNOWN) {
                continue;
            }
            pending.remove();
            if (verdict == MobFilter.Verdict.ACCEPT) {
                fightIds.add(entity.getId());
                continue;
            }
            unfoughtIds.add(entity.getId());
            if (blacklisted(plate, blacklist)) {
                ClientUtils.sendMessage("§e" + AetherLang.localize("Fishing Macro left a blacklisted catch alone:")
                        + " " + plate, false);
            } else {
                ClientUtils.sendDebugMessage("[FishingMacro] " + plate + " is not on the whitelist, leaving it");
            }
        }
    }

    static MobFilter.Verdict settleVerdict(MobFilter.Verdict verdict, long acquiredAt, long now) {
        return verdict == MobFilter.Verdict.UNKNOWN && now - acquiredAt >= PLATE_WAIT_MS
                ? MobFilter.Verdict.ACCEPT
                : verdict;
    }

    static boolean blacklisted(String plate, List<String> blacklist) {
        return MobFilter.classify(plate, List.of(), blacklist) == MobFilter.Verdict.IGNORE;
    }

    // stops while there is still room for a catch the lists want, instead of fishing up nothing for good
    private boolean guardCap(Minecraft mc) {
        unfoughtIds.removeIf(id -> !CatchWatch.isAlive(mc.level.getEntity(id)));
        if (!capFullSeen && !capGuardTripped(unfoughtIds.size())) {
            return false;
        }
        String reason = capFullSeen ? CAP_FULL_STOP : TOO_MANY_LEFT_STOP;
        List<String> names = unfoughtNames(mc);
        fail(reason, names.isEmpty() ? "" : " (" + String.join(", ", names) + ")");
        return true;
    }

    private List<String> unfoughtNames(Minecraft mc) {
        List<String> names = new ArrayList<>();
        for (int id : unfoughtIds) {
            Entity entity = mc.level.getEntity(id);
            if (entity != null) {
                String plate = CatchWatch.plateName(mc.level, entity);
                names.add(plate != null ? plate : entity.getName().getString());
            }
        }
        return names;
    }

    static boolean capGuardTripped(int unfoughtAlive) {
        return unfoughtAlive >= UNFOUGHT_CAP;
    }

    @Override
    void onChat(String plain) {
        if (isCapFullLine(plain)) {
            capFullSeen = true;
        }
    }

    static boolean isCapFullLine(String plain) {
        return plain != null && plain.toLowerCase(Locale.ROOT).contains(CAP_FULL_LINE);
    }

    private void pruneFight(Minecraft mc, long now) {
        fightIds.removeIf(id -> {
            if (CatchWatch.isAlive(mc.level.getEntity(id))) {
                return false;
            }
            ActivityRateTracker.onMobKilled();
            onKill(now);
            return true;
        });
        if (target != null && !fightIds.contains(target.getId())) {
            target = null;
        }
    }

    private void onKill(long now) {
        if (!hyperionThisFight) {
            return;
        }
        if (hyperion.clickedSinceKill()) {
            hyperionGiveUps = 0;
        }
        hyperion.onKill(now);
    }

    private void melee(Minecraft mc, long now) {
        if (now >= nextAttackAt && healer.wants(mc, now, mc.player.fishing != null, hyperion.lastClickAt())) {
            enterHeal(State.FIGHT);
            return;
        }
        FailsafeManager.selectHotbarSlot(mc, weaponSlot());
        if (target == null) {
            target = nearestFightCatch(mc);
            targetReachedAt = now;
        }

        RotationManager.trackRotation(mc, target.position().add(0.0, target.getBbHeight() * AIM_HEIGHT, 0.0),
                AIM_SMOOTHING_MS, AIM_MAX_TURN_SPEED);
        holdFightKeys(mc);

        // vanilla only picks an entity inside the interaction range, so a hit under the crosshair is a hit in reach
        Entity under = mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
        boolean onCatch = under != null && fightIds.contains(under.getId()) && CatchWatch.isAlive(under);
        if (onCatch && under == target) {
            targetReachedAt = now;
        }
        if (onCatch && now >= nextActionAt && now >= nextAttackAt) {
            ClientUtils.performAttackClick();
            nextAttackAt = now + nextAttackDelayMs(ThreadLocalRandom.current());
        }

        if (unreachedTooLong(targetReachedAt, now)) {
            ClientUtils.sendDebugMessage("[FishingMacro] a catch stayed out of reach, leaving it");
            fightIds.remove(target.getId());
            unfoughtIds.add(target.getId());
            target = null;
        }
    }

    private static void holdFightKeys(Minecraft mc) {
        var options = mc.options;
        MacroInput.set(options.keyUp, false);
        MacroInput.set(options.keyDown, false);
        MacroInput.set(options.keyLeft, false);
        MacroInput.set(options.keyRight, false);
        MacroInput.set(options.keySprint, false);
        MacroInput.set(options.keyJump, false);
        // crouching through the kill is what keeps the player off the edge it was pulled toward
        MacroInput.set(options.keyShift, true);
    }

    static boolean unreachedTooLong(long reachedAt, long now) {
        return now - reachedAt > UNREACHED_DROP_MS;
    }

    private Entity nearestFightCatch(Minecraft mc) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int id : fightIds) {
            Entity entity = mc.level.getEntity(id);
            if (!CatchWatch.isAlive(entity)) {
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

    private void finishFight(Minecraft mc) {
        // loot never spawns a mob, so the line goes straight back out
        emptyCatch = !caughtAny;
        clearFight();
        releaseAll(mc);
        changeState(State.AIM);
    }

    private void clearFight() {
        unsortedIds.clear();
        fightIds.clear();
        preReelIds.clear();
        hookAtReel = null;
        caughtAny = false;
        target = null;
        targetReachedAt = 0L;
        hyperionThisFight = false;
    }

    private void enterHeal(State resume) {
        healResume = resume;
        changeState(State.HEAL);
    }

    private void tickHeal(Minecraft mc) {
        if (healResume == State.FIGHT) {
            holdFightKeys(mc);
        } else {
            holdStill(mc);
        }
        if (healer.tick(mc, System.currentTimeMillis(), ticks, ThreadLocalRandom.current())) {
            changeState(healResume);
        }
    }

    private void tickMove(Minecraft mc) {
        if (homeKeeper.origin() == null) {
            changeState(State.AIM);
            return;
        }
        HomeKeeper.Result result = homeKeeper.tick(mc, System.currentTimeMillis(), ThreadLocalRandom.current());
        if (result == HomeKeeper.Result.ARRIVED) {
            releaseAll(mc);
            // the walk leaves the camera wherever it was steering, so aiming starts from a clean slate
            RotationManager.cancelRotation();
            changeState(State.AIM);
        } else if (result == HomeKeeper.Result.FAILED) {
            fail("Fishing Macro stopped: could not get back onto the start block.");
        }
    }

    private void beginMove(Minecraft mc) {
        target = null;
        aimTargetBlock = null;
        homeKeeper.beginTrip(mc);
        idle.clear();
        releaseAll(mc);
        changeState(State.MOVE);
    }

    private void changeState(State next) {
        // a heal between swings goes back to the same fight, so the weapon and the camera stay as they are
        if (state == State.FIGHT && next != State.FIGHT && next != State.HEAL) {
            RotationManager.cancelRotation();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                FailsafeManager.selectHotbarSlot(mc, rodSlot());
            }
        }
        state = next;
        stateEnteredAt = System.currentTimeMillis();
    }

    // the camera has been drifting around the float, so the throw is lined up again before the rod goes out;
    // with the line still out, the cast click reels it in first
    private void recast() {
        emptyCatch = true;
        idle.clear();
        changeState(State.AIM);
    }

    private void fail(String message) {
        fail(message, "");
    }

    private void fail(String message, String detail) {
        // localised before the colour code goes on, which would otherwise end up in the lookup key
        ClientUtils.sendMessage("§c" + AetherLang.localize(message) + detail, false);
        MacroStateManager.stopMacro(Minecraft.getInstance(), message + detail, false);
    }

    // a new origin means new water, so nothing learned about the old one carries over
    private void resetOrigin() {
        liquid = null;
        rejected.clear();
        aimSearch = null;
        aimTargetBlock = null;
        confirmedAim = null;
        aimRetryAt = 0L;
        emptySweeps = 0;
        castLanding = null;
        castAim = null;
    }

    // the sim promised this throw the liquid, so neither the cell nor the block it aimed at is trusted again
    private void rejectCast() {
        if (castLanding != null) {
            rejected.add(castLanding);
        }
        if (castAim != null) {
            rejected.add(castAim.block());
        }
        if (confirmedAim != null && castAim != null && confirmedAim.block().equals(castAim.block())) {
            confirmedAim = null;
        }
        castLanding = null;
        castAim = null;
    }

    // where a cast at the current look comes down, or null when that is not a throw the search would pick
    private BlockPos lookLanding(Minecraft mc) {
        CastAimSearch.Spec spec = CastAimSearch.Spec.GENERAL;
        if (liquid == null || !withinPitchBand(mc.player.getXRot(), spec.pitchMin(), spec.pitchMax())) {
            return null;
        }
        Vec3 landing = CastSim.predictCastLanding(mc.level, mc.player.getEyePosition(), mc.player.getYRot(),
                mc.player.getXRot(), liquid, CastSim.DEFAULT_TICKS, spec.maxLandingHorizontal());
        BlockPos block = landing == null ? null : BlockPos.containing(landing);
        return CastSim.acceptsLanding(block, rejected) ? block : null;
    }

    static boolean withinPitchBand(float pitch, float min, float max) {
        return pitch >= Math.min(min, max) - PITCH_SLACK && pitch <= Math.max(min, max) + PITCH_SLACK;
    }

    private static Predicate<BlockState> pickLiquid(Minecraft mc) {
        return switch (AimAt.fromConfig(AetherConfig.FISHING_MACRO_AIM_AT.get())) {
            case LAVA -> CastSim::isLava;
            case WATER -> CastSim::isWater;
            case HOTSPOT -> nearestLiquid(mc);
        };
    }

    private static Predicate<BlockState> nearestLiquid(Minecraft mc) {
        BlockPos base = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        double lava = nearestSurface(CastAimSearch.surfaceCells(mc.level, base, LIQUID_PICK_RADIUS, CastSim::isLava),
                eye);
        double water = nearestSurface(CastAimSearch.surfaceCells(mc.level, base, LIQUID_PICK_RADIUS,
                CastSim::isWater), eye);
        return nearerLiquid(lava, water) == AimAt.LAVA ? CastSim::isLava : CastSim::isWater;
    }

    private static double nearestSurface(List<BlockPos> cells, Vec3 eye) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos cell : cells) {
            best = Math.min(best, Math.hypot(cell.getX() + 0.5 - eye.x, cell.getZ() + 0.5 - eye.z));
        }
        return best;
    }

    // water wins a tie, and with neither in reach the search simply comes up empty on water
    static AimAt nearerLiquid(double lavaDistance, double waterDistance) {
        return lavaDistance < waterDistance ? AimAt.LAVA : AimAt.WATER;
    }

    // ticks until the float is as far out as where it lands, since it only ever moves outward
    static int flightTicks(Vec3[] path, Vec3 eye, Vec3 landing) {
        double reach = Math.hypot(landing.x - eye.x, landing.z - eye.z);
        for (int tick = 1; tick < path.length; tick++) {
            if (Math.hypot(path[tick].x - eye.x, path[tick].z - eye.z) >= reach) {
                return tick;
            }
        }
        return path.length - 1;
    }

    static boolean sweepsExhausted(int sweeps) {
        return sweeps >= MAX_EMPTY_SWEEPS;
    }

    private void holdStill(Minecraft mc) {
        var options = mc.options;
        MacroInput.set(options.keyUp, false);
        MacroInput.set(options.keyDown, false);
        MacroInput.set(options.keyLeft, false);
        MacroInput.set(options.keyRight, false);
        MacroInput.set(options.keySprint, false);
        MacroInput.set(options.keyJump, false);
        // casts are planned from the standing eye, so the macro never crouches outside a fight
        MacroInput.set(options.keyShift, false);
    }

    @Override
    public void releaseAll(Minecraft mc) {
        if (mc == null || mc.options == null) {
            return;
        }
        idle.dropTap();
        MacroInput.setAttack(mc.options.keyAttack, false);
        MacroInput.releaseMovement(mc);
    }

    static long nextAttackDelayMs(RandomGenerator random) {
        return random.nextLong(ATTACK_MIN_DELAY_MS, ATTACK_MAX_DELAY_MS + 1);
    }

    static boolean attackDelayInRange(long delay) {
        return delay >= ATTACK_MIN_DELAY_MS && delay <= ATTACK_MAX_DELAY_MS;
    }

    static long nextAimRetryDelayMs(RandomGenerator random) {
        return random.nextLong(AIM_RETRY_MIN_MS, AIM_RETRY_MAX_MS + 1);
    }

    static long nextEmptyCatchDelayMs(RandomGenerator random) {
        return random.nextLong(EMPTY_CATCH_DELAY_MIN_MS, EMPTY_CATCH_DELAY_MAX_MS + 1);
    }

    private long castDelayForCycle() {
        return emptyCatch ? nextEmptyCatchDelayMs(ThreadLocalRandom.current()) : castDelayMs();
    }

    private static long castDelayMs() {
        return ConfigHelpers.getRandomizedDelay(
                AetherConfig.FISHING_MACRO_CAST_DELAY_MIN.get(),
                AetherConfig.FISHING_MACRO_CAST_DELAY_MAX.get());
    }

    private static int rodSlot() {
        return Mth.clamp(AetherConfig.FISHING_MACRO_ROD_SLOT.get() - 1, 0, 8);
    }

    private static int weaponSlot() {
        return Mth.clamp(AetherConfig.FISHING_MACRO_WEAPON_SLOT.get() - 1, 0, 8);
    }

    public State getState() {
        return state;
    }
}
