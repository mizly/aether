package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroState;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.ManualPestManager;
import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.execution.EtherwarpExecutor;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Hotkey "Etherwarp Next": crouches, aims at the block beneath the nearest pest
 * (the head of the optimal route) and etherwarps onto it in one tap. When no
 * AOTV is in the hotbar it falls back to the shared walker to close on the
 * pest. With Auto Stun on it then turns to the pest, taps the vacuum to stun
 * and swaps to the lasso; with Auto Lasso on it also throws the lasso.
 */
public final class EtherwarpNextController {
    // Ignore a pest we are essentially standing on so repeated taps move onward.
    private static final double SKIP_PEST_WITHIN = 2.0;
    private static final int GROUND_SCAN_DEPTH = 24;
    private static final double STUN_RANGE = 3.5;
    private static final float STUN_AIM_TOLERANCE_DEGREES = 4.0f;
    private static final long STUN_TIMEOUT_MS = 3000L;
    private static final long LASSO_THROW_DELAY_MS = 150L;
    // Walk fallback tuning: give the walker up to this long to close in before
    // giving up, and re-target the pest at most this often as it moves.
    private static final long WALK_TIMEOUT_MS = 15000L;
    private static final long WALK_RETARGET_COOLDOWN_MS = 400L;

    private enum Phase { IDLE, WARP, WALK, STUN_AIM, SWAP_LASSO, THROW }

    private static final EtherwarpExecutor executor = new EtherwarpExecutor();
    private static Phase phase = Phase.IDLE;
    private static long phaseSince = 0L;
    private static int targetPestId = -1;
    private static long lastWalkRetargetAt = 0L;

    private EtherwarpNextController() {
    }

    public static void trigger(Minecraft client) {
        if (phase != Phase.IDLE
                || client == null || client.player == null || client.level == null
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || !AetherConfig.MANUAL_PEST_MODE.get()
                || !ManualPestManager.isActive()) {
            return;
        }
        Entity pest = nearestPest(client, false);
        if (pest == null) {
            ClientUtils.sendMessage("§cEtherwarp Next: no pest in range.", false);
            return;
        }
        // Lock onto this exact pest so the stun targets the one we warp to, not
        // whatever happens to be closest after we land.
        targetPestId = pest.getId();

        // Without an AOTV in the hotbar, fall through to a walking approach that
        // uses the shared pathfinder to close on the pest and then stuns as usual.
        if (PestLoadoutHelper.findAotvHotbarSlot(client) < 0) {
            startWalkFollow(client, pest);
            return;
        }

        WalkabilityChecker checker = new WalkabilityChecker(client.level);
        PathPosition landing = resolveLanding(checker, pest);
        if (landing == null) {
            ClientUtils.sendMessage("§cEtherwarp Next: no landing under that pest.", false);
            targetPestId = -1;
            return;
        }
        Vec3 eye = EtherwarpHelper.getEyePosition(client, client.player.position());
        if (EtherwarpHelper.findVisibleTargetPoint(client, checker, eye, landing) == null) {
            ClientUtils.sendMessage("§cEtherwarp Next: no line of sight to that pest.", false);
            targetPestId = -1;
            return;
        }

        PathPosition start = new PathPosition(client.player.getX(), client.player.getY(), client.player.getZ());
        List<Node> path = List.of(new Node(start), new Node(landing));
        setPhase(Phase.WARP);
        // A single warp only; never re-fire the AOTV over and over.
        executor.start(path, rotationDurationMs(), 1, () -> {
        }, reason -> {
            ClientUtils.sendMessage("§cEtherwarp Next failed: " + describe(reason), false);
            return true;
        });
    }

    private static void startWalkFollow(Minecraft client, Entity pest) {
        setPhase(Phase.WALK);
        lastWalkRetargetAt = 0L;
        requestWalkTo(client, pest);
    }

    private static void requestWalkTo(Minecraft client, Entity pest) {
        long now = System.currentTimeMillis();
        if (now - lastWalkRetargetAt < WALK_RETARGET_COOLDOWN_MS) {
            return;
        }
        lastWalkRetargetAt = now;
        PathfindingManager.startConfiguredWalk(
                client,
                pest.position(),
                () -> {},
                () -> {},
                true,   // allow replan
                0.75,   // precise enough to end near the pest without pixel-hunting
                false,  // strictGoalCompletion
                false); // requireFullPath
    }

    public static void tick(Minecraft client) {
        if (phase == Phase.IDLE) {
            return;
        }
        if (client == null || client.player == null || client.level == null) {
            stop(client);
            return;
        }
        // Only the warp holds sneak (which sinks a flying player), so only then do
        // we counter it with space. The stun phases must not move the player.
        if (client.options != null) {
            boolean holdJump = phase == Phase.WARP && client.player.getAbilities().flying;
            MacroInput.set(client.options.keyJump, holdJump);
        }
        switch (phase) {
            case WARP -> tickWarp(client);
            case WALK -> tickWalk(client);
            case STUN_AIM -> tickStunAim(client);
            case SWAP_LASSO -> tickSwapLasso(client);
            case THROW -> tickThrow(client);
            default -> {
            }
        }
    }

    private static void tickWalk(Minecraft client) {
        Entity pest = resolveTargetPest(client);
        if (pest == null) {
            PathfindingManager.stop(false);
            finish(client);
            return;
        }
        if (client.player.distanceTo(pest) <= STUN_RANGE) {
            PathfindingManager.stop(false);
            if (AetherConfig.MANUAL_HUNT_AUTO_STUN.get()
                    && !PestHuntingPolicy.isVacuumTarget(client, pest)) {
                setPhase(Phase.STUN_AIM);
            } else {
                swapToVacuum(client);
                finish(client);
            }
            return;
        }
        if (System.currentTimeMillis() - phaseSince > WALK_TIMEOUT_MS) {
            ClientUtils.sendMessage("§cEtherwarp Next: could not reach pest in time.", false);
            PathfindingManager.stop(false);
            finish(client);
            return;
        }
        if (!PathfindingManager.isNavigating()) {
            requestWalkTo(client, pest);
        }
    }

    private static void tickWarp(Minecraft client) {
        executor.tick(client);
        EtherwarpExecutor.State state = executor.getState();
        if (state == EtherwarpExecutor.State.FINISHED) {
            Entity pest = resolveTargetPest(client);
            // Only stun the locked lasso target we actually landed next to. Vacuum
            // -blacklist pests are meant to be vacuumed, and an out-of-range pest
            // must not be chased -- in both cases stay on vacuum for the player.
            boolean stunnable = pest != null
                    && client.player.distanceTo(pest) <= STUN_RANGE
                    && !PestHuntingPolicy.isVacuumTarget(client, pest);
            if (AetherConfig.MANUAL_HUNT_AUTO_STUN.get() && stunnable) {
                setPhase(Phase.STUN_AIM);
            } else {
                swapToVacuum(client);
                finish(client);
            }
        } else if (state == EtherwarpExecutor.State.FAILED) {
            finish(client);
        }
    }

    private static void tickStunAim(Minecraft client) {
        long now = System.currentTimeMillis();
        Entity pest = resolveTargetPest(client);
        // Never chase: if the pest leaves stun range (or we run out of time to line
        // up), stop and leave the player on the vacuum.
        if (pest == null
                || client.player.distanceTo(pest) > STUN_RANGE
                || now - phaseSince > STUN_TIMEOUT_MS) {
            swapToVacuum(client);
            finish(client);
            return;
        }
        // Stun with the lowest-tier vacuum (Skymart) so it never kills the pest.
        int vacuumSlot = PestLoadoutHelper.findLowestVacuumHotbarSlot(client);
        if (vacuumSlot < 0) {
            finish(client);
            return;
        }
        if (FailsafeManager.getCurrentSelectedSlot(client) != vacuumSlot) {
            FailsafeManager.selectHotbarSlot(client, vacuumSlot);
            return;
        }
        Vec3 aimPoint = tracePest(client, pest);
        if (RotationUtils.isLookingAt(client.player.getYRot(), client.player.getXRot(),
                client.player.getEyePosition(), aimPoint, STUN_AIM_TOLERANCE_DEGREES)) {
            // A single tap, not a hold: a sustained use overlaps and swallows the
            // lasso throw, and holds the vacuum far longer than the stun needs.
            ClientUtils.performUseClickInstant();
            setPhase(Phase.SWAP_LASSO);
        }
    }

    private static void tickSwapLasso(Minecraft client) {
        Entity pest = resolveTargetPest(client);
        if (pest != null) {
            tracePest(client, pest);
        }
        int lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client);
        if (lassoSlot < 0) {
            ClientUtils.sendMessage("§cAuto Stun: no lasso in the hotbar.", false);
            finish(client);
            return;
        }
        if (FailsafeManager.getCurrentSelectedSlot(client) != lassoSlot) {
            FailsafeManager.selectHotbarSlot(client, lassoSlot);
            return;
        }
        if (AetherConfig.MANUAL_HUNT_AUTO_LASSO.get()) {
            setPhase(Phase.THROW);
        } else {
            finish(client);
        }
    }

    private static void tickThrow(Minecraft client) {
        Entity pest = resolveTargetPest(client);
        if (pest != null) {
            tracePest(client, pest);
        }
        if (System.currentTimeMillis() - phaseSince < LASSO_THROW_DELAY_MS) {
            return;
        }
        // Thrown the same way the auto hunter throws its lasso.
        ClientUtils.performUseClickNow();
        finish(client);
    }

    // Smoothly follow the pest with the camera at the configured stun strength,
    // without moving the player. Returns the aim point.
    private static Vec3 tracePest(Minecraft client, Entity pest) {
        Vec3 aimPoint = pest.position().add(0, pest.getBbHeight() * 0.5, 0);
        RotationManager.trackRotation(client, aimPoint, stunSmoothingMs(), 0.0f);
        return aimPoint;
    }

    private static float stunSmoothingMs() {
        int strength = Mth.clamp(AetherConfig.MANUAL_HUNT_STUN_STRENGTH.get(), 1, 10);
        return 350.0f - strength * 28.0f;
    }

    public static void stop(Minecraft client) {
        if (phase == Phase.WARP) {
            executor.stop(client);
        }
        if (phase == Phase.WALK) {
            PathfindingManager.stop(false);
        }
        endSequence(client);
    }

    private static void finish(Minecraft client) {
        endSequence(client);
    }

    private static void endSequence(Minecraft client) {
        ClientUtils.endUseHold();
        releaseJump(client);
        if (phase.ordinal() >= Phase.STUN_AIM.ordinal()) {
            RotationManager.cancelRotation();
        }
        phase = Phase.IDLE;
        targetPestId = -1;
        lastWalkRetargetAt = 0L;
    }

    private static Entity resolveTargetPest(Minecraft client) {
        if (targetPestId == -1) {
            return null;
        }
        Entity entity = client.level.getEntity(targetPestId);
        if (entity == null || entity.isRemoved()
                || (entity instanceof LivingEntity living && living.isDeadOrDying())) {
            return null;
        }
        return entity;
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseSince = System.currentTimeMillis();
    }

    private static void swapToVacuum(Minecraft client) {
        int vacuumSlot = PestLoadoutHelper.findVacuumHotbarSlot(client);
        if (vacuumSlot >= 0 && vacuumSlot < 9) {
            FailsafeManager.selectHotbarSlot(client, vacuumSlot);
        }
    }

    private static void releaseJump(Minecraft client) {
        if (client != null && client.options != null) {
            MacroInput.set(client.options.keyJump, false);
        }
    }

    // Eased etherwarp turn on its own 1-10 speed setting; higher turns faster.
    private static long rotationDurationMs() {
        int speed = Mth.clamp(AetherConfig.MANUAL_HUNT_ETHERWARP_ROTATION.get(), 1, 10);
        long ms = Math.round((350.0 - speed * 28.0) * 2.0);
        return Mth.clamp(ms, 150L, 900L);
    }

    private static String describe(EtherwarpExecutor.FailureReason reason) {
        return switch (reason) {
            case MISSING_ETHERWARP_ITEM -> "no AOTV with Ether Transmission in the hotbar.";
            case LOST_LINE_OF_SIGHT -> "lost line of sight.";
            case WARP_TIMEOUT -> "warp timed out.";
            case PLAYER_CONTEXT_MISSING -> "player context missing.";
        };
    }

    private static Entity nearestPest(Minecraft client, boolean includeClose) {
        Vec3 playerPos = client.player.position();
        Entity closest = null;
        double closestSq = Double.MAX_VALUE;
        for (Entity entity : PestTargetTracker.getLoadedPestMobs(client)) {
            if (entity == null || entity.isRemoved()
                    || (entity instanceof LivingEntity living && living.isDeadOrDying())) {
                continue;
            }
            double dx = entity.getX() - playerPos.x;
            double dz = entity.getZ() - playerPos.z;
            if (!includeClose && dx * dx + dz * dz < SKIP_PEST_WITHIN * SKIP_PEST_WITHIN) {
                continue;
            }
            double distSq = entity.position().distanceToSqr(playerPos);
            if (distSq < closestSq) {
                closestSq = distSq;
                closest = entity;
            }
        }
        return closest;
    }

    private static PathPosition resolveLanding(WalkabilityChecker checker, Entity pest) {
        int x = (int) Math.floor(pest.getX());
        int z = (int) Math.floor(pest.getZ());
        int startY = (int) Math.floor(pest.getY());
        for (int y = startY; y > startY - GROUND_SCAN_DEPTH; y--) {
            PathPosition feet = new PathPosition(x, y, z);
            if (EtherwarpHelper.isValidLandingFeet(checker, feet)) {
                return feet;
            }
        }
        return null;
    }
}
