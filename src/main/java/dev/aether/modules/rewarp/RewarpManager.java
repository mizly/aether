package dev.aether.modules.rewarp;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.config.RewarpPointPair;
import dev.aether.config.RewarpPointPairs;
import dev.aether.config.RewarpMode;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class RewarpManager {
    private static final long REWARP_COOLDOWN_MS = 5000;
    private static final long CLIENT_THREAD_TIMEOUT_MS = 1000L;
    private static final long REWARP_FLY_STOP_MS = 0L;
    private static final long REWARP_POSITION_ADJUST_TIMEOUT_MS = 5000L;
    private static final long REWARP_POSITION_TAP_MS = 50L;
    private static final double REWARP_AOTV_ALIGN_XZ_TOLERANCE = 0.5;

    // Fly-back recovery: how many times we recompute the path before giving up
    // and forcing position with a command teleport.
    private static final int REWARP_FLY_MAX_ATTEMPTS = 3;
    // Hard wall-clock cap per fly attempt so a hung navigation can never freeze
    // the worker thread (the original wait loop had no timeout).
    private static final long REWARP_NAV_TIMEOUT_MS = 18000L;
    private static final long REWARP_NAV_STARTUP_GRACE_MS = 2000L;
    private static final long REWARP_UNSTUCK_MOVE_MS = 450L;
    private static final long REWARP_UNSTUCK_CLIMB_STEP_MS = 350L;
    private static final long REWARP_FALLBACK_SETTLE_MS = 600L;
    // Small take-off hop so the fly path starts above the crops. Kept low and
    // stall-aware so roofed farms don't get pinned against the ceiling.
    private static final double REWARP_CLIMB_GAIN_BLOCKS = 3.0;
    private static final long REWARP_CLIMB_TIMEOUT_MS = 3000L;
    private static final long REWARP_CLIMB_STALL_MS = 400L;
    private static final double REWARP_NEAR_START_ACCEPT_DIST = 3.0;
    // Wider ring when AOTV align is on: the etherwarp covers the last stretch.
    private static final double REWARP_NEAR_START_WARP_DIST = 8.0;
    private static final int REWARP_MAX_SLOPPY_LANDINGS = 3;
    private static final long REWARP_TAP_SETTLE_MAX_MS = 400L;
    private static final long REWARP_SNEAK_PRIME_MS = 150L;
    private static final long REWARP_WARP_SETTLE_TIMEOUT_MS = 900L;
    private static final double REWARP_WARP_LAND_TOLERANCE = 1.5;
    // Vertical tolerance against the exact captured start Y. A wall block next
    // to the start is +1.0, so this must stay below that.
    private static final double REWARP_START_Y_TOLERANCE = 0.75;

    private static long lastRewarpTime = 0;
    // Rewarps in a row that ended away from the start point.
    private static int consecutiveSloppyLandings = 0;

    private RewarpManager() {
    }

    private enum FlyNavResult {
        ABORTED, REACHED, FAILED
    }

    public static void handle(Minecraft client) {
        if (!AetherConfig.ENABLE_REWARP.get()
                || MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRewarpTime < REWARP_COOLDOWN_MS) {
            return;
        }

        RewarpPointPair pair = findReachedEndPair(client);
        if (pair == null) {
            return;
        }

        if (pair.rewarpMode.usesCommand() && isZeroRewarpDelay()
                && !AetherConfig.SQUEAKY_MOUSEMAT.get()) {
            handleInstantCommandRewarp(client, now, pair);
            return;
        }

        lastRewarpTime = now;
        ClientUtils.sendMessage("\u00A76Rewarp End Position reached!", true);
        MacroWorkerThread.getInstance().submit("PlotTpRewarp", () -> performRewarp(client, pair));
    }

    private static boolean isZeroRewarpDelay() {
        return AetherConfig.REWARP_DELAY_MIN.get() <= 0 && AetherConfig.REWARP_DELAY_MAX.get() <= 0;
    }

    private static void handleInstantCommandRewarp(Minecraft client, long now, RewarpPointPair pair) {
        if (client == null || client.player == null) {
            return;
        }

        lastRewarpTime = now;
        client.execute(() -> {
            ConfigHelpers.executeRewarpCommand(pair.rewarpMode, pair.plotTpNumber);
            PestManager.markRewarpCompleted();
            AbstractMacro active = FarmingMacroManager.getActiveMacro();
            if (active != null) {
                active.suppressDropDetection(3000);
            }
        });
    }

    private static boolean withinRadius(Minecraft client, double x, double y, double z) {
        double dx = client.player.getX() - x;
        double dy = client.player.getY() - y;
        double dz = client.player.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= 1.5 * 1.5;
    }

    private static RewarpPointPair findReachedEndPair(Minecraft client) {
        if (client == null || client.player == null) {
            return null;
        }
        for (RewarpPointPair pair : RewarpPointPairs.get()) {
            if (pair.hasEnd() && withinRadius(client, pair.endX, pair.endY, pair.endZ)) {
                return pair;
            }
        }
        return null;
    }

    private static void performRewarp(Minecraft client, RewarpPointPair pair) {
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
            return;
        }

        // Save the current view angles so we can restore them after any server-side
        // teleport that may have changed the player's rotation (avoids snapping).
        RotationSnapshot savedRotation = getPlayerRotation(client);

        MacroStateManager.setCurrentState(MacroState.State.REWARPING);
        client.execute(() -> FarmingMacroManager.disable(client));
        MacroWorkerThread.sleepRandom(255, 90);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return;
        }

        boolean rewarpCompleted = false;
        if (pair.rewarpMode.usesCommand()) {
            performCommandRewarp(client, pair);
            rewarpCompleted = MacroStateManager.getCurrentState() == MacroState.State.REWARPING;
        } else if (pair.rewarpMode == RewarpMode.FLY && pair.hasStart()) {
            rewarpCompleted = performCoordinateRewarp(client, pair);
        }

        if (rewarpCompleted && MacroStateManager.getCurrentState() == MacroState.State.REWARPING) {
            restorePreRewarpRotation(client, savedRotation.yaw(), savedRotation.pitch());
            if (MacroStateManager.getCurrentState() != MacroState.State.REWARPING) {
                return;
            }

            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            SqueakyMousematManager.armReapplyAttempt();
            client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
            PestManager.markRewarpCompleted();
        }
    }

    private static void restorePreRewarpRotation(Minecraft client, float restoreYaw, float restorePitch) {
        if (client == null || getPlayerPosition(client) == null) {
            return;
        }

        client.execute(() -> RotationManager.rotateToYawPitch(
                client,
                restoreYaw,
                restorePitch,
                AetherConfig.ROTATION_TIME.get()));

        while (RotationManager.isRotating()) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                RotationManager.cancelRotation();
                return;
            }
            MacroWorkerThread.sleep(25);
        }

        MacroWorkerThread.sleepRandom(50, 20);
    }

    private static void performCommandRewarp(Minecraft client, RewarpPointPair pair) {
        client.execute(() -> {
            ConfigHelpers.executeRewarpCommand(pair.rewarpMode, pair.plotTpNumber);
            AbstractMacro active = FarmingMacroManager.getActiveMacro();
            if (active != null) {
                active.suppressDropDetection(3000);
            }
        });
        MacroWorkerThread.sleepRandom(
                AetherConfig.REWARP_DELAY_MIN.get(),
                Math.max(0, AetherConfig.REWARP_DELAY_MAX.get() - AetherConfig.REWARP_DELAY_MIN.get()));
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return;
        }

        if (!pair.holdWUntilWall) {
            return;
        }

        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUp, true));
        MacroWorkerThread.sleepRandom(170, 60);
        long wallTimeout = System.currentTimeMillis() + 5000;
        Vec3 lastPos = getPlayerPosition(client);
        if (lastPos == null) {
            client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUp, false));
            return;
        }

        double lastX = lastPos.x;
        double lastZ = lastPos.z;
        while (System.currentTimeMillis() < wallTimeout) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUp, false));
                return;
            }

            MacroWorkerThread.sleep(100);
            Vec3 currPos = getPlayerPosition(client);
            if (currPos == null) {
                client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUp, false));
                return;
            }

            double currX = currPos.x;
            double currZ = currPos.z;
            double moved = Math.sqrt((currX - lastX) * (currX - lastX) + (currZ - lastZ) * (currZ - lastZ));
            if (moved < 0.03) {
                break;
            }
            lastX = currX;
            lastZ = currZ;
        }

        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUp, false));
        MacroWorkerThread.sleepRandom(85, 30);
    }

    private static boolean performCoordinateRewarp(Minecraft client, RewarpPointPair pair) {
        ClientUtils.sendDebugMessage(String.format(
                "[Rewarp] Fly-back begin: pair '%s', start (%.0f, %.0f, %.0f), player %s",
                pair.displayName(), pair.startX, pair.startY, pair.startZ,
                formatPos(getPlayerPosition(client))));
        ensureFlight(client);
        ClientUtils.sendDebugMessage("[Rewarp] After ensureFlight: flying=" + isPlayerFlying(client)
                + ", player " + formatPos(getPlayerPosition(client)));
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        rotateToRewarpStart(client, pair);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        climbForTakeoff(client);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        FlyNavResult flight = attemptFlyToStart(client, pair);
        if (flight == FlyNavResult.ABORTED) {
            return false;
        }
        if (flight == FlyNavResult.FAILED) {
            // Every fly attempt failed: force position with a command teleport,
            // then give the fly one more bounded round from the fresh position.
            ClientUtils.sendMessage("§cRewarp fly recovery failed, using teleport fallback.", false);
            if (!performHardTpFallback(client, pair)) {
                return false;
            }
            flight = attemptFlyToStart(client, pair);
            if (flight == FlyNavResult.ABORTED) {
                return false;
            }
            if (flight == FlyNavResult.FAILED) {
                return stopMacroAfterRewarpFailure(client);
            }
        }

        performAotvAlign(client, pair);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        try {
            PestReturnManager.performUnfly(client);
        } catch (InterruptedException ignored) {
        }

        MacroWorkerThread.sleepRandom(425, 150);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        return verifyRewarpLanding(client, pair);
    }

    private static void performAotvAlign(Minecraft client, RewarpPointPair pair) {
        if (!pair.aotvAlign || getPlayerPosition(client) == null) {
            return;
        }

        double distance = distanceToStartXZ(client, pair);
        if (!Double.isNaN(distance) && distance <= REWARP_WARP_LAND_TOLERANCE) {
            // Close in XZ isn't enough: hovering over the edge of a wall block
            // means the drop lands on the wall, a block above the start. Only
            // skip the warp when the column below actually ends at start level.
            double dropY = resolveDropLandingY(client);
            if (!Double.isNaN(dropY) && Math.abs(dropY - pair.startY) <= REWARP_START_Y_TOLERANCE) {
                ClientUtils.sendDebugMessage(String.format(
                        "[Rewarp] Already %.1f blocks from start with clean drop, skipping warp.", distance));
                return;
            }
            ClientUtils.sendDebugMessage(String.format(
                    "[Rewarp] Drop here would land at y=%.1f (start y %.1f), warping instead.",
                    dropY, pair.startY));
        }

        // Preferred: aim at the start block and etherwarp straight onto it, the
        // same way the etherwarp pathfinder does. Falls back to the hover align
        // when there's no Ether Transmission AOTV or no line of sight.
        if (aimAndEtherwarpToStart(client, pair)) {
            return;
        }
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return;
        }

        // Fallback: shuffle until we're directly above the start, then let the
        // unfly drop us onto it. No AOTV click here - it warps us off the spot.
        if (!stabilizeFlyPositionForAotv(client, pair)) {
            ClientUtils.sendDebugMessage("Rewarp align skipped: could not stabilize above target");
        }
    }

    private static boolean aimAndEtherwarpToStart(Minecraft client, RewarpPointPair pair) {
        Integer slot = queryClientThread(client,
                () -> GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client), -1);
        if (slot == null || slot < 0) {
            ClientUtils.sendDebugMessage("[Rewarp] No Ether Transmission AOTV in hotbar; using hover align.");
            return false;
        }

        PathPosition startCell = new PathPosition(
                Math.floor(pair.startX), Math.floor(pair.startY), Math.floor(pair.startZ));

        for (int attempt = 1; attempt <= 2; attempt++) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                return false;
            }

            Vec3 aimPoint = queryClientThread(client, () -> {
                if (client.player == null || client.level == null) {
                    return null;
                }
                Vec3 eye = EtherwarpHelper.getEyePosition(client, client.player.position());
                return EtherwarpHelper.findVisibleTargetPoint(
                        client, new WalkabilityChecker(client.level), eye, startCell);
            }, null);
            if (aimPoint == null) {
                ClientUtils.sendDebugMessage("[Rewarp] No line of sight to start block; using hover align.");
                return false;
            }

            final int warpSlot = slot;
            client.execute(() -> {
                FailsafeManager.selectHotbarSlot(client, warpSlot);
                ClientUtils.setKeyMappingState(client.options.keyShift, true);
                if (client.player != null) {
                    client.player.setShiftKeyDown(true);
                }
            });
            // Let the sneak register server-side before the click, or the warp
            // silently becomes an Instant Transmission.
            MacroWorkerThread.sleep(REWARP_SNEAK_PRIME_MS);

            Vec3 eyeNow = queryClientThread(client,
                    () -> client.player == null
                            ? null
                            : EtherwarpHelper.getEyePosition(client, client.player.position()),
                    null);
            if (eyeNow == null) {
                releaseSneakKeys(client);
                return false;
            }
            RotationUtils.Rotation look = RotationUtils.calculateLookAt(eyeNow, aimPoint);
            client.execute(() -> RotationManager.rotateToYawPitch(
                    client, look.yaw, look.pitch, AetherConfig.ROTATION_TIME.get(), true));
            while (RotationManager.isRotating()) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    releaseSneakKeys(client);
                    return false;
                }
                MacroWorkerThread.sleep(25);
            }

            // Re-check the aim right before firing: any drift between the aim
            // computation and now would send the warp somewhere else entirely.
            final Vec3 verifyPoint = aimPoint;
            boolean aimed = Boolean.TRUE.equals(queryClientThread(client, () -> {
                if (client.player == null) {
                    return false;
                }
                Vec3 eye = EtherwarpHelper.getEyePosition(client, client.player.position());
                return RotationUtils.isLookingAt(
                        client.player.getYRot(), client.player.getXRot(), eye, verifyPoint, 2.0f);
            }, false));
            if (!aimed) {
                releaseSneakKeys(client);
                ClientUtils.sendDebugMessage("[Rewarp] Warp aim drifted, retrying.");
                continue;
            }

            Vec3 prePos = getPlayerPosition(client);
            client.execute(ClientUtils::performUseClick);
            boolean moved = waitForWarpSettle(client, prePos);
            releaseSneakKeys(client);

            double distance = distanceToStartXZ(client, pair);
            Vec3 postPos = getPlayerPosition(client);
            double dy = postPos == null ? Double.NaN : postPos.y - pair.startY;
            ClientUtils.sendDebugMessage(String.format(
                    "[Rewarp] Etherwarp align attempt %d: moved=%s, %.1f blocks from start, dy=%.1f",
                    attempt, moved, distance, dy));
            if (!Double.isNaN(distance) && distance <= REWARP_WARP_LAND_TOLERANCE
                    && !Double.isNaN(dy) && Math.abs(dy) <= REWARP_START_Y_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** Feet Y where a straight-down drop from the current position would land. */
    private static double resolveDropLandingY(Minecraft client) {
        Double landingY = queryClientThread(client, () -> {
            if (client.player == null || client.level == null) {
                return null;
            }
            Vec3 from = client.player.position();
            BlockHitResult hit = client.level.clip(new ClipContext(
                    from,
                    from.add(0, -32, 0),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    client.player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return null;
            }
            return hit.getLocation().y;
        }, null);
        return landingY == null ? Double.NaN : landingY;
    }

    private static boolean waitForWarpSettle(Minecraft client, Vec3 prePos) {
        if (prePos == null) {
            MacroWorkerThread.sleep(REWARP_WARP_SETTLE_TIMEOUT_MS);
            return false;
        }
        long deadline = System.currentTimeMillis() + REWARP_WARP_SETTLE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Vec3 pos = getPlayerPosition(client);
            if (pos != null && pos.distanceTo(prePos) > 1.0) {
                MacroWorkerThread.sleepRandom(120, 60);
                return true;
            }
            MacroWorkerThread.sleep(50);
        }
        return false;
    }

    private static void releaseSneakKeys(Minecraft client) {
        client.execute(() -> {
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
            if (client.player != null) {
                client.player.setShiftKeyDown(false);
            }
        });
    }

    private static boolean stabilizeFlyPositionForAotv(Minecraft client, RewarpPointPair pair) {
        releaseHorizontalMovementKeys(client);
        MacroWorkerThread.sleep(REWARP_FLY_STOP_MS);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        rotateToRewarpStartBlock(client, pair);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + REWARP_POSITION_ADJUST_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                return false;
            }

            Vec3 playerPos = getPlayerPosition(client);
            if (playerPos == null) {
                return false;
            }

            Vec3 targetPos = getRewarpWalkGoalPosition(pair);
            double deltaX = targetPos.x - playerPos.x;
            double deltaZ = targetPos.z - playerPos.z;
            if (Math.abs(deltaX) <= REWARP_AOTV_ALIGN_XZ_TOLERANCE
                    && Math.abs(deltaZ) <= REWARP_AOTV_ALIGN_XZ_TOLERANCE) {
                releaseHorizontalMovementKeys(client);
                ClientUtils.sendDebugMessage(String.format(
                        "[Rewarp] AOTV stabilize ok in %d ms (dx=%.2f dz=%.2f)",
                        System.currentTimeMillis() - startedAt, deltaX, deltaZ));
                return true;
            }

            float yaw = getPlayerYaw(client);
            double yawRad = Math.toRadians(yaw);
            double forwardX = -Math.sin(yawRad);
            double forwardZ = Math.cos(yawRad);
            double rightX = Math.cos(yawRad);
            double rightZ = Math.sin(yawRad);

            double forwardError = deltaX * forwardX + deltaZ * forwardZ;
            double strafeError = deltaX * rightX + deltaZ * rightZ;

            KeyMapping keyToTap = Math.abs(forwardError) >= Math.abs(strafeError)
                    ? (forwardError >= 0 ? client.options.keyUp : client.options.keyDown)
                    : (strafeError >= 0 ? client.options.keyRight : client.options.keyLeft);
            tapMovementKey(client, keyToTap);
        }

        releaseHorizontalMovementKeys(client);
        Vec3 endPos = getPlayerPosition(client);
        if (endPos != null) {
            Vec3 targetPos = getRewarpWalkGoalPosition(pair);
            ClientUtils.sendDebugMessage(String.format(
                    "[Rewarp] AOTV stabilize timed out, offset dx=%.2f dz=%.2f",
                    targetPos.x - endPos.x, targetPos.z - endPos.z));
        }
        return false;
    }

    private static FlyNavResult attemptFlyToStart(Minecraft client, RewarpPointPair pair) {
        for (int attempt = 1; attempt <= REWARP_FLY_MAX_ATTEMPTS; attempt++) {
            ClientUtils.sendDebugMessage(String.format(
                    "[Rewarp] Fly attempt %d/%d to (%d, %d, %d) from %s",
                    attempt, REWARP_FLY_MAX_ATTEMPTS,
                    (int) Math.floor(pair.startX), (int) Math.floor(pair.startY) + 1,
                    (int) Math.floor(pair.startZ), formatPos(getPlayerPosition(client))));
            // Target one block above the real start point: the 3D pathfinder still
            // routes under roofs and around walls, but hovering keeps us off the
            // floor - touching down kicks us out of flight mid-approach.
            client.execute(() -> PathfindingManager.startFlyPathfind(
                    client,
                    (int) Math.floor(pair.startX),
                    (int) Math.floor(pair.startY) + 1,
                    (int) Math.floor(pair.startZ)));

            FlyNavResult result = awaitFlyNavigation(client);
            ClientUtils.sendDebugMessage(String.format(
                    "[Rewarp] Fly attempt %d result: %s, player %s, %.1f blocks from start",
                    attempt, result, formatPos(getPlayerPosition(client)),
                    distanceToStartXZ(client, pair)));
            if (result == FlyNavResult.ABORTED || result == FlyNavResult.REACHED) {
                return result;
            }

            // A failed fly that still got us next to the start is good enough;
            // the AOTV align / unfly handles the last couple of blocks.
            if (isNearRewarpStartXZ(client, pair)) {
                ClientUtils.sendMessage("§eRewarp fly stopped close to the start point, continuing.", false);
                return FlyNavResult.REACHED;
            }

            if (attempt < REWARP_FLY_MAX_ATTEMPTS) {
                ClientUtils.sendMessage("§eRewarp fly stuck (try " + attempt + "/"
                        + REWARP_FLY_MAX_ATTEMPTS + "), unsticking and recomputing.", false);
                performUnstuckNudge(client, attempt);
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    return FlyNavResult.ABORTED;
                }
            }
        }
        return FlyNavResult.FAILED;
    }

    private static FlyNavResult awaitFlyNavigation(Minecraft client) {
        // The pathfind start is queued on the client thread; wait for it to land
        // so a slow frame can't make us misread "not navigating" as a finished run.
        long startupDeadline = System.currentTimeMillis() + REWARP_NAV_STARTUP_GRACE_MS;
        while (!PathfindingManager.isNavigating() && System.currentTimeMillis() < startupDeadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                return FlyNavResult.ABORTED;
            }
            MacroWorkerThread.sleep(50);
        }
        if (!PathfindingManager.isNavigating()) {
            ClientUtils.sendDebugMessage("[Rewarp] Fly navigation never started (or ended instantly).");
        }

        long deadline = System.currentTimeMillis() + REWARP_NAV_TIMEOUT_MS;
        while (PathfindingManager.isNavigating()) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                PathfindingManager.stop(false);
                return FlyNavResult.ABORTED;
            }
            if (System.currentTimeMillis() > deadline) {
                // Hard cap: treat a hung navigation as a failed attempt to recover from.
                PathfindingManager.stop(false);
                ClientUtils.sendMessage("§cRewarp fly attempt timed out.", false);
                return FlyNavResult.FAILED;
            }
            MacroWorkerThread.sleep(100);
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.REWARPING) {
            return FlyNavResult.ABORTED;
        }
        return PathfindingManager.lastFlyReachedGoal() ? FlyNavResult.REACHED : FlyNavResult.FAILED;
    }

    private static void climbForTakeoff(Minecraft client) {
        Vec3 startPos = getPlayerPosition(client);
        if (startPos == null) {
            return;
        }

        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, true));
        long deadline = System.currentTimeMillis() + REWARP_CLIMB_TIMEOUT_MS;
        double lastY = startPos.y;
        long lastGainAt = System.currentTimeMillis();
        String exitReason = "timeout";
        while (System.currentTimeMillis() < deadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                exitReason = "aborted";
                break;
            }
            Vec3 pos = getPlayerPosition(client);
            if (pos == null || pos.y - startPos.y >= REWARP_CLIMB_GAIN_BLOCKS) {
                exitReason = "gained height";
                break;
            }
            if (pos.y > lastY + 0.05) {
                lastY = pos.y;
                lastGainAt = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastGainAt > REWARP_CLIMB_STALL_MS) {
                // Ceiling overhead: stop pushing into it and let the pathfinder
                // work from here.
                exitReason = "ceiling";
                break;
            }
            MacroWorkerThread.sleep(50);
        }
        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, false));
        ClientUtils.sendDebugMessage(String.format("[Rewarp] Takeoff climb done (%s): y %.1f -> %s",
                exitReason, startPos.y, formatPos(getPlayerPosition(client))));
    }

    private static void performUnstuckNudge(Minecraft client, int attempt) {
        PathfindingManager.stop(false);
        releaseHorizontalMovementKeys(client);
        // Back out sideways to leave the pocket we got wedged in. Alternate the
        // strafe side and the vertical direction each retry so repeats don't
        // loop: up can pin against a roof, down can pin on crops, so try both.
        KeyMapping strafeKey = attempt % 2 == 0 ? client.options.keyLeft : client.options.keyRight;
        KeyMapping verticalKey = attempt % 2 == 0 ? client.options.keyShift : client.options.keyJump;
        ClientUtils.sendDebugMessage("[Rewarp] Unstuck nudge " + attempt + ": back+"
                + (attempt % 2 == 0 ? "left+down" : "right+up"));
        client.execute(() -> {
            ClientUtils.setKeyMappingState(client.options.keyDown, true);
            ClientUtils.setKeyMappingState(strafeKey, true);
            ClientUtils.setKeyMappingState(verticalKey, true);
        });
        MacroWorkerThread.sleep(REWARP_UNSTUCK_MOVE_MS);
        client.execute(() -> {
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(strafeKey, false);
        });
        MacroWorkerThread.sleep(REWARP_UNSTUCK_CLIMB_STEP_MS * attempt);
        client.execute(() -> ClientUtils.setKeyMappingState(verticalKey, false));
        MacroWorkerThread.sleepRandom(150, 50);
    }

    private static boolean performHardTpFallback(Minecraft client, RewarpPointPair pair) {
        if (client == null || client.player == null) {
            return false;
        }

        PathfindingManager.stop(false);
        releaseHorizontalMovementKeys(client);

        // FLY pairs don't carry a command, so pick a teleport that will resolve:
        // the configured plot if one is set, otherwise /warp garden.
        RewarpMode fallbackMode = hasUsablePlotNumber(pair) ? RewarpMode.PLOT_TP : RewarpMode.WARP_GARDEN;
        ClientUtils.sendDebugMessage("[Rewarp] TP fallback via " + fallbackMode.displayName());
        client.execute(() -> {
            ConfigHelpers.executeRewarpCommand(fallbackMode, pair.plotTpNumber);
            AbstractMacro active = FarmingMacroManager.getActiveMacro();
            if (active != null) {
                active.suppressDropDetection(3000);
            }
        });
        MacroWorkerThread.sleep(REWARP_FALLBACK_SETTLE_MS);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        // The teleport may have dropped us out of flight; get airborne again for
        // the follow-up fly attempt.
        ensureFlight(client);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }
        climbForTakeoff(client);
        return !MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING);
    }

    private static boolean isNearRewarpStartXZ(Minecraft client, RewarpPointPair pair) {
        double distance = distanceToStartXZ(client, pair);
        // With AOTV align on, the etherwarp can cover the last few blocks, so a
        // slightly overshot fly still counts as arrived.
        double acceptDist = pair.aotvAlign ? REWARP_NEAR_START_WARP_DIST : REWARP_NEAR_START_ACCEPT_DIST;
        return !Double.isNaN(distance) && distance <= acceptDist;
    }

    private static double distanceToStartXZ(Minecraft client, RewarpPointPair pair) {
        Vec3 pos = getPlayerPosition(client);
        if (pos == null) {
            return Double.NaN;
        }
        double dx = pos.x - (Math.floor(pair.startX) + 0.5);
        double dz = pos.z - (Math.floor(pair.startZ) + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String formatPos(Vec3 pos) {
        return pos == null
                ? "(unknown)"
                : String.format("(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }

    private static boolean verifyRewarpLanding(Minecraft client, RewarpPointPair pair) {
        if (!isLandedOnStart(client, pair) && pair.aotvAlign) {
            // Typical miss: dropped onto the wall block beside the start. Re-aim
            // at the block centre and etherwarp down onto it.
            ClientUtils.sendDebugMessage("[Rewarp] Landed off the start point, correcting with etherwarp.");
            aimAndEtherwarpToStart(client, pair);
        }

        Vec3 landed = getPlayerPosition(client);
        if (landed == null) {
            return true;
        }
        double distance = distanceToStartXZ(client, pair);
        double dy = landed.y - pair.startY;
        if (isLandedOnStart(client, pair)) {
            consecutiveSloppyLandings = 0;
            ClientUtils.sendDebugMessage(String.format(
                    "[Rewarp] Landed %.1f blocks from start (dy=%.1f).", distance, dy));
            return true;
        }

        // Landing repeatedly away from the start means every rewarp drifts and
        // re-triggers; stop instead of ping-ponging across the farm all night.
        consecutiveSloppyLandings++;
        ClientUtils.sendMessage(String.format(
                "§eRewarp landed %.1f blocks from the start point, dy=%.1f (%d/%d tolerated).",
                distance, dy, consecutiveSloppyLandings, REWARP_MAX_SLOPPY_LANDINGS), false);
        if (consecutiveSloppyLandings >= REWARP_MAX_SLOPPY_LANDINGS) {
            consecutiveSloppyLandings = 0;
            return stopMacroAfterRewarpFailure(client);
        }
        return true;
    }

    private static boolean isLandedOnStart(Minecraft client, RewarpPointPair pair) {
        Vec3 pos = getPlayerPosition(client);
        if (pos == null) {
            return false;
        }
        double distance = distanceToStartXZ(client, pair);
        double dy = pos.y - pair.startY;
        return !Double.isNaN(distance)
                && distance <= REWARP_NEAR_START_ACCEPT_DIST
                && Math.abs(dy) <= REWARP_START_Y_TOLERANCE;
    }

    private static boolean stopMacroAfterRewarpFailure(Minecraft client) {
        PathfindingManager.stop(false);
        releaseHorizontalMovementKeys(client);
        ClientUtils.sendMessage("§cRewarp could not reach the start point. Stopping the macro for safety.", false);
        MacroStateManager.setCurrentState(MacroState.State.OFF);
        return false;
    }

    private static boolean hasUsablePlotNumber(RewarpPointPair pair) {
        // "0" is the config default for FLY pairs, so treat it as "not configured".
        return pair.plotTpNumber != null
                && !pair.plotTpNumber.isBlank()
                && !pair.plotTpNumber.trim().equals("0");
    }

    private static void ensureFlight(Minecraft client) {
        if (isPlayerFlying(client) || !canPlayerFly(client)) {
            return;
        }

        long flyStart = System.currentTimeMillis();
        while (!isPlayerFlying(client) && (System.currentTimeMillis() - flyStart) < 3000) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, false));
                return;
            }

            long elapsed = System.currentTimeMillis() - flyStart;
            long cycle = elapsed % 250;
            client.execute(() -> {
                if (cycle < 50) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, true);
                } else if (cycle < 100) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, false);
                } else if (cycle < 150) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, true);
                } else {
                    ClientUtils.setKeyMappingState(client.options.keyJump, false);
                }
            });
            MacroWorkerThread.sleep(20);
        }

        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, false));
    }

    private static void rotateToRewarpStart(Minecraft client, RewarpPointPair pair) {
        client.execute(() -> RotationManager.initiateRotation(
                client,
                new Vec3(
                        pair.startX,
                        pair.startY,
                        pair.startZ),
                0));
        while (RotationManager.isRotating()) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                return;
            }
            MacroWorkerThread.sleep(50);
        }
    }

    private static void rotateToRewarpStartBlock(Minecraft client, RewarpPointPair pair) {
        BlockPos targetBlock = BlockPos.containing(
                Math.floor(pair.startX),
                Math.floor(pair.startY),
                Math.floor(pair.startZ));
        client.execute(() -> RotationManager.initiateRotation(
                client,
                Vec3.atCenterOf(targetBlock),
                0));
        while (RotationManager.isRotating()) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                return;
            }
            MacroWorkerThread.sleep(50);
        }
    }

    private static void tapMovementKey(Minecraft client, KeyMapping key) {
        if (client == null || client.options == null || key == null) {
            return;
        }

        client.execute(() -> ClientUtils.setKeyMappingState(key, true));
        MacroWorkerThread.sleep(REWARP_POSITION_TAP_MS);
        client.execute(() -> ClientUtils.setKeyMappingState(key, false));

        // Wait for the flight glide to die down before measuring again. A fixed
        // short settle let each tap coast past the target, which caused the
        // visible back-and-forth wobble above the rewarp point.
        long settleDeadline = System.currentTimeMillis() + REWARP_TAP_SETTLE_MAX_MS;
        while (System.currentTimeMillis() < settleDeadline) {
            MacroWorkerThread.sleep(40);
            Vec3 velocity = getPlayerVelocity(client);
            if (velocity == null
                    || (Math.abs(velocity.x) < 0.03 && Math.abs(velocity.z) < 0.03)) {
                break;
            }
        }
    }

    private static void releaseHorizontalMovementKeys(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        client.execute(() -> {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keyLeft, false);
            ClientUtils.setKeyMappingState(client.options.keyRight, false);
        });
    }

    private static RotationSnapshot getPlayerRotation(Minecraft client) {
        return queryClientThread(client, () -> {
            if (client.player == null) {
                return new RotationSnapshot(0f, 0f);
            }
            return new RotationSnapshot(client.player.getYRot(), client.player.getXRot());
        }, new RotationSnapshot(0f, 0f));
    }

    private static Vec3 getPlayerPosition(Minecraft client) {
        return queryClientThread(client,
                () -> client.player == null ? null : client.player.position(),
                null);
    }

    private static Vec3 getPlayerVelocity(Minecraft client) {
        return queryClientThread(client,
                () -> client.player == null ? null : client.player.getDeltaMovement(),
                null);
    }

    private static boolean isPlayerFlying(Minecraft client) {
        return queryClientThread(client,
                () -> client.player != null && client.player.getAbilities().flying,
                false);
    }

    private static boolean canPlayerFly(Minecraft client) {
        return queryClientThread(client,
                () -> client.player != null && client.player.getAbilities().mayfly,
                false);
    }

    private static float getPlayerYaw(Minecraft client) {
        return queryClientThread(client,
                () -> client.player == null ? 0f : client.player.getYRot(),
                0f);
    }

    private static Vec3 getRewarpWalkGoalPosition(RewarpPointPair pair) {
        int targetX = (int) Math.floor(pair.startX);
        int targetY = (int) Math.floor(pair.startY);
        int targetZ = (int) Math.floor(pair.startZ);
        return new Vec3(targetX + 0.5, targetY, targetZ + 0.5);
    }

    private static <T> T queryClientThread(Minecraft client, Supplier<T> supplier, T fallback) {
        if (client == null) {
            return fallback;
        }
        if (client.isSameThread()) {
            return supplier.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.complete(fallback);
            }
        });

        try {
            return future.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record RotationSnapshot(float yaw, float pitch) {
    }
}
