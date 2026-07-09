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
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
    private static final long REWARP_POSITION_SETTLE_MS = 75L;
    private static final double REWARP_AOTV_ALIGN_XZ_TOLERANCE = 0.5;

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
            ConfigHelpers.executeRewarpCommand(client, pair.rewarpMode, pair.plotTpNumber);
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
            ConfigHelpers.executeRewarpCommand(client, pair.rewarpMode, pair.plotTpNumber);
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
                87,
                (int) Math.floor(pair.startZ)));
        if (!waitForNavigationToFinish(client)) {
            return false;
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

        return true;
    }

    private static void performAotvAlign(Minecraft client, RewarpPointPair pair) {
        if (!pair.aotvAlign || getPlayerPosition(client) == null) {
            return;
        }

        int aotvSlot = GearManager.findAspectOfTheVoidSlot(client);
        if (aotvSlot < 0 || aotvSlot > 8) {
            ClientUtils.sendDebugMessage("Rewarp align skipped: no AOTV/AOTE in hotbar");
            return;
        }

        if (!stabilizeFlyPositionForAotv(client, pair)) {
            ClientUtils.sendDebugMessage("Rewarp align skipped: could not stabilize above target");
            return;
        }

        rotateToRewarpStartBlock(client, pair);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return;
        }

        Vec3 playerPos = getPlayerPosition(client);
        if (playerPos == null) {
            return;
        }

        GearManager.swapToAOTVSync(client);
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
            return;
        }

        ClientUtils.performUseClick(client);
        ClientUtils.waitForYChange(client, playerPos.y, 900);
        MacroWorkerThread.sleepRandom(170, 60);
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

        long deadline = System.currentTimeMillis() + REWARP_POSITION_ADJUST_TIMEOUT_MS;
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
        return false;
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
        MacroWorkerThread.sleep(REWARP_POSITION_SETTLE_MS);
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
