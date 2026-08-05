package dev.aether.modules.rewarp;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.config.RewarpPointPair;
import dev.aether.config.RewarpPointPairs;
import dev.aether.config.RewarpMode;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class RewarpManager {
    private static final long REWARP_COOLDOWN_MS = 5000;
    private static final long CLIENT_THREAD_TIMEOUT_MS = 1000L;
    private static final int REWARP_LANDING_ATTEMPTS = 3;
    private static final long REWARP_POSITION_ADJUST_TIMEOUT_MS = 6000L;
    private static final long REWARP_DESCENT_TIMEOUT_MS = 12000L;
    private static final long REWARP_RECOVERY_TIMEOUT_MS = 4000L;
    private static final long REWARP_MOVEMENT_TICK_MS = 50L;
    private static final double REWARP_RECOVERY_HEIGHT_ABOVE_TARGET = 4.0;
    private static final double REWARP_RECOVERY_HEIGHT_ABOVE_OBSTACLE = 2.0;
    private static final double REWARP_CENTER_TOLERANCE = 0.18;
    private static final double REWARP_DESCENT_DRIFT_LIMIT = 0.32;
    private static final double REWARP_SETTLED_SPEED = 0.025;
    private static final double REWARP_CORRECTION_AXIS_THRESHOLD = 0.05;
    private static final long REWARP_CORRECTION_PULSE_MS = 50L;
    private static final int REWARP_SETTLED_SAMPLES = 3;

    private static long lastRewarpTime = 0;

    private RewarpManager() {
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
        ensureFlight(client);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        rotateToRewarpStart(client, pair);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, true));
        MacroWorkerThread.sleepRandom(1300, 300);
        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, false));
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        client.execute(() -> PathfindingManager.startFlyPathfind(
                client,
                (int) Math.floor(pair.startX),
                74,
                (int) Math.floor(pair.startZ)));
        if (!waitForNavigationToFinish(client)) {
            return false;
        }

        return landOnRewarpBlock(client, pair);
    }

    private static boolean landOnRewarpBlock(Minecraft client, RewarpPointPair pair) {
        Vec3 target = getRewarpWalkGoalPosition(pair);
        for (int attempt = 1; attempt <= REWARP_LANDING_ATTEMPTS; attempt++) {
            if (!prepareLandingAttempt(client, target, attempt)) {
                ClientUtils.sendDebugMessage("Rewarp landing attempt " + attempt
                        + " could not recover flight above the landing area");
                continue;
            }
            ClientUtils.sendDebugMessage("Rewarp landing attempt " + attempt + "/" + REWARP_LANDING_ATTEMPTS
                    + " target=" + formatVec(target) + " current=" + formatVec(getPlayerPosition(client))
                    + " flying=" + isPlayerFlying(client));
            if (!stabilizeAboveTarget(client, target)) {
                ClientUtils.sendDebugMessage("Rewarp landing attempt " + attempt
                        + " could not center above the target");
                continue;
            }
            if (descendOntoTarget(client, pair, target)) {
                try {
                    PestReturnManager.performUnfly(client);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                MacroWorkerThread.sleepRandom(225, 75);
                return isStandingOnTarget(client, pair);
            }
            ClientUtils.sendDebugMessage("Rewarp landing attempt " + attempt
                    + " drifted away from the target; retrying");
        }

        releaseMovementKeys(client);
        ClientUtils.sendMessage("\u00A7cCould not land on the rewarp start block.", true);
        return false;
    }

    private static boolean stabilizeAboveTarget(Minecraft client, Vec3 target) {
        releaseHorizontalMovementKeys(client);
        MacroWorkerThread.sleep(150);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return false;
        }

        long deadline = System.currentTimeMillis() + REWARP_POSITION_ADJUST_TIMEOUT_MS;
        long nextDebugAt = 0L;
        int settledSamples = 0;
        while (System.currentTimeMillis() < deadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                releaseHorizontalMovementKeys(client);
                return false;
            }

            FlightSnapshot snapshot = getFlightSnapshot(client);
            if (snapshot == null) {
                return false;
            }
            if (snapshot.onGround()) {
                ClientUtils.sendDebugMessage("Rewarp centering touched a solid block: target=" + formatVec(target)
                        + " current=" + formatVec(snapshot.position())
                        + " flying=" + isPlayerFlying(client) + "; retrying from above");
                releaseMovementKeys(client);
                return false;
            }

            double deltaX = target.x - snapshot.position().x;
            double deltaZ = target.z - snapshot.position().z;
            boolean centered = Math.abs(deltaX) <= REWARP_CENTER_TOLERANCE
                    && Math.abs(deltaZ) <= REWARP_CENTER_TOLERANCE;
            HorizontalCorrection correction = calculateHorizontalCorrection(
                    deltaX, deltaZ, snapshot.velocity(), getPlayerYaw(client));
            double horizontalSpeed = Math.hypot(snapshot.velocity().x, snapshot.velocity().z);
            long now = System.currentTimeMillis();
            if (now >= nextDebugAt) {
                ClientUtils.sendDebugMessage("Rewarp centering: target=" + formatVec(target)
                        + " current=" + formatVec(snapshot.position())
                        + " delta=" + formatPair(deltaX, deltaZ)
                        + " velocity=" + formatVec(snapshot.velocity())
                        + " correction=" + formatCorrection(correction)
                        + " phase=" + (centered ? "settling" : horizontalSpeed <= REWARP_SETTLED_SPEED ? "tap" : "coast")
                        + " centered=" + centered + " settledSamples=" + settledSamples);
                nextDebugAt = now + 500L;
            }
            if (centered) {
                releaseHorizontalMovementKeys(client);
                if (horizontalSpeed <= REWARP_SETTLED_SPEED) {
                    settledSamples++;
                    if (settledSamples >= REWARP_SETTLED_SAMPLES) {
                        return true;
                    }
                } else {
                    settledSamples = 0;
                }
            } else {
                settledSamples = 0;
                releaseHorizontalMovementKeys(client);
                if (horizontalSpeed <= REWARP_SETTLED_SPEED) {
                    applyHorizontalCorrection(client, correction);
                    MacroWorkerThread.sleep(REWARP_CORRECTION_PULSE_MS);
                    releaseHorizontalMovementKeys(client);
                }
            }
            MacroWorkerThread.sleep(REWARP_MOVEMENT_TICK_MS);
        }

        releaseHorizontalMovementKeys(client);
        ClientUtils.sendDebugMessage("Rewarp centering timed out after " + REWARP_POSITION_ADJUST_TIMEOUT_MS
                + "ms; target=" + formatVec(target) + " current=" + formatVec(getPlayerPosition(client)));
        return false;
    }

    private static boolean descendOntoTarget(Minecraft client, RewarpPointPair pair, Vec3 target) {
        releaseHorizontalMovementKeys(client);
        long deadline = System.currentTimeMillis() + REWARP_DESCENT_TIMEOUT_MS;
        long nextDebugAt = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                releaseMovementKeys(client);
                return false;
            }

            FlightSnapshot snapshot = getFlightSnapshot(client);
            if (snapshot == null) {
                releaseMovementKeys(client);
                return false;
            }
            if (isStandingOnTarget(snapshot, pair)) {
                releaseMovementKeys(client);
                return true;
            }
            if (snapshot.onGround()) {
                ClientUtils.sendDebugMessage("Rewarp descent landed on an unexpected solid block: target="
                        + formatVec(target)
                        + " current=" + formatVec(snapshot.position())
                        + " targetFeetY=" + format(pair.startY)
                        + "; retrying from above");
                releaseMovementKeys(client);
                return false;
            }

            double deltaX = target.x - snapshot.position().x;
            double deltaZ = target.z - snapshot.position().z;
            if (Math.abs(deltaX) > REWARP_DESCENT_DRIFT_LIMIT
                    || Math.abs(deltaZ) > REWARP_DESCENT_DRIFT_LIMIT) {
                ClientUtils.sendDebugMessage("Rewarp descent drifted outside limit: target=" + formatVec(target)
                        + " current=" + formatVec(snapshot.position())
                        + " delta=" + formatPair(deltaX, deltaZ)
                        + " velocity=" + formatVec(snapshot.velocity())
                        + " limit=" + REWARP_DESCENT_DRIFT_LIMIT);
                releaseMovementKeys(client);
                return false;
            }

            long now = System.currentTimeMillis();
            if (now >= nextDebugAt) {
                ClientUtils.sendDebugMessage("Rewarp descending: current=" + formatVec(snapshot.position())
                        + " delta=" + formatPair(deltaX, deltaZ)
                        + " velocity=" + formatVec(snapshot.velocity())
                        + " horizontalKeys=released"
                        + " onGround=" + snapshot.onGround());
                nextDebugAt = now + 500L;
            }

            client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyShift, true));
            MacroWorkerThread.sleep(REWARP_MOVEMENT_TICK_MS);
        }

        releaseMovementKeys(client);
        ClientUtils.sendDebugMessage("Rewarp descent timed out after " + REWARP_DESCENT_TIMEOUT_MS
                + "ms; target=" + formatVec(target) + " current=" + formatVec(getPlayerPosition(client)));
        return false;
    }

    private static boolean prepareLandingAttempt(Minecraft client, Vec3 target, int attempt) {
        FlightSnapshot initial = getFlightSnapshot(client);
        if (initial == null) {
            return false;
        }
        if (!initial.onGround() && isPlayerFlying(client)) {
            return true;
        }

        releaseMovementKeys(client);
        double recoveryY = Math.max(
                target.y + REWARP_RECOVERY_HEIGHT_ABOVE_TARGET,
                initial.position().y + REWARP_RECOVERY_HEIGHT_ABOVE_OBSTACLE);
        ClientUtils.sendDebugMessage("Rewarp landing attempt " + attempt
                + " recovering from solid block: current=" + formatVec(initial.position())
                + " flying=" + isPlayerFlying(client)
                + " recoveryY=" + format(recoveryY));

        ensureFlight(client);
        if (!isPlayerFlying(client)) {
            ClientUtils.sendDebugMessage("Rewarp recovery could not enable flight: current="
                    + formatVec(getPlayerPosition(client))
                    + " canFly=" + canPlayerFly(client));
            return false;
        }

        long deadline = System.currentTimeMillis() + REWARP_RECOVERY_TIMEOUT_MS;
        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, true));
        while (System.currentTimeMillis() < deadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                releaseMovementKeys(client);
                return false;
            }

            FlightSnapshot snapshot = getFlightSnapshot(client);
            if (snapshot == null) {
                releaseMovementKeys(client);
                return false;
            }
            if (!snapshot.onGround() && snapshot.position().y >= recoveryY) {
                client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyJump, false));
                MacroWorkerThread.sleep(100);
                ClientUtils.sendDebugMessage("Rewarp recovery reached safe height: current="
                        + formatVec(snapshot.position()) + " recoveryY=" + format(recoveryY));
                return true;
            }
            MacroWorkerThread.sleep(REWARP_MOVEMENT_TICK_MS);
        }

        releaseMovementKeys(client);
        ClientUtils.sendDebugMessage("Rewarp recovery timed out after " + REWARP_RECOVERY_TIMEOUT_MS
                + "ms: current=" + formatVec(getPlayerPosition(client))
                + " recoveryY=" + format(recoveryY)
                + " flying=" + isPlayerFlying(client));
        return false;
    }

    private static HorizontalCorrection calculateHorizontalCorrection(
            double deltaX, double deltaZ, Vec3 velocity, float yaw) {
        double yawRad = Math.toRadians(yaw);
        double forwardError = deltaX * -Math.sin(yawRad) + deltaZ * Math.cos(yawRad);
        double strafeError = deltaX * -Math.cos(yawRad) + deltaZ * -Math.sin(yawRad);
        double forwardVelocity = velocity.x * -Math.sin(yawRad) + velocity.z * Math.cos(yawRad);
        double strafeVelocity = velocity.x * -Math.cos(yawRad) + velocity.z * -Math.sin(yawRad);

        boolean forward = forwardError > REWARP_CORRECTION_AXIS_THRESHOLD;
        boolean backward = forwardError < -REWARP_CORRECTION_AXIS_THRESHOLD;
        boolean right = strafeError > REWARP_CORRECTION_AXIS_THRESHOLD;
        boolean left = strafeError < -REWARP_CORRECTION_AXIS_THRESHOLD;

        return new HorizontalCorrection(forwardError, strafeError,
                forwardVelocity, strafeVelocity,
                forward, backward, right, left, yaw);
    }

    private static void applyHorizontalCorrection(Minecraft client, HorizontalCorrection correction) {
        client.execute(() -> {
            ClientUtils.setKeyMappingState(client.options.keyUp, correction.forward());
            ClientUtils.setKeyMappingState(client.options.keyDown, correction.backward());
            ClientUtils.setKeyMappingState(client.options.keyRight, correction.right());
            ClientUtils.setKeyMappingState(client.options.keyLeft, correction.left());
        });
    }

    private static boolean waitForNavigationToFinish(Minecraft client) {
        MacroWorkerThread.sleepRandom(170, 60);
        while (PathfindingManager.isNavigating()) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                PathfindingManager.stop();
                return false;
            }
            MacroWorkerThread.sleep(100);
        }
        return MacroStateManager.getCurrentState() == MacroState.State.REWARPING;
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

    private static void releaseMovementKeys(Minecraft client) {
        releaseHorizontalMovementKeys(client);
        if (client == null || client.options == null) {
            return;
        }
        client.execute(() -> {
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
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

    private static String formatVec(Vec3 value) {
        if (value == null) {
            return "null";
        }
        return String.format("(%.3f, %.3f, %.3f)", value.x, value.y, value.z);
    }

    private static String formatPair(double first, double second) {
        return String.format("(%.3f, %.3f)", first, second);
    }

    private static String formatCorrection(HorizontalCorrection correction) {
        return "(yaw=" + format(correction.yaw())
                + ", error=" + formatPair(correction.forwardError(), correction.strafeError())
                + ", speed=" + formatPair(correction.forwardVelocity(), correction.strafeVelocity())
                + ", keys=" + (correction.forward() ? "W" : correction.backward() ? "S" : "-")
                + (correction.right() ? "D" : correction.left() ? "A" : "-") + ")";
    }

    private static String format(float value) {
        return String.format("%.1f", value);
    }

    private static String format(double value) {
        return String.format("%.1f", value);
    }

    private static FlightSnapshot getFlightSnapshot(Minecraft client) {
        return queryClientThread(client, () -> {
            if (client.player == null) {
                return null;
            }
            return new FlightSnapshot(
                    client.player.position(),
                    client.player.getDeltaMovement(),
                    client.player.onGround());
        }, null);
    }

    private static boolean isStandingOnTarget(Minecraft client, RewarpPointPair pair) {
        FlightSnapshot snapshot = getFlightSnapshot(client);
        return snapshot != null && isStandingOnTarget(snapshot, pair);
    }

    private static boolean isStandingOnTarget(FlightSnapshot snapshot, RewarpPointPair pair) {
        return snapshot.onGround()
                && (int) Math.floor(snapshot.position().x) == (int) Math.floor(pair.startX)
                && (int) Math.floor(snapshot.position().y) == (int) Math.floor(pair.startY)
                && (int) Math.floor(snapshot.position().z) == (int) Math.floor(pair.startZ);
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

    private record FlightSnapshot(Vec3 position, Vec3 velocity, boolean onGround) {
    }

    private record HorizontalCorrection(
            double forwardError,
            double strafeError,
            double forwardVelocity,
            double strafeVelocity,
            boolean forward,
            boolean backward,
            boolean right,
            boolean left,
            float yaw) {
    }
}
