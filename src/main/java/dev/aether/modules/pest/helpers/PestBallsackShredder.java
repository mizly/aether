package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Dedicated pre-cleaning AOTV route used by Ballsack Shredder. */
public final class PestBallsackShredder {
    private static final long AOTV_TIMEOUT_MS = 10_000L;
    private static final long RESULT_CONFIRM_TIMEOUT_MS = 2_000L;
    private static final double POSITION_CHANGE_DISTANCE_SQR = 1.0;

    private PestBallsackShredder() {
    }

    record Result(boolean successful, boolean measured, int estimatedKilled, int estimatedRemaining) {
        static Result unmeasured(boolean successful, int startingPests) {
            return new Result(successful, false, 0, Math.max(0, startingPests));
        }
    }

    static Result run(Minecraft client, int sessionId, int startingPests) throws InterruptedException {
        if (client == null || client.player == null || client.options == null) {
            return Result.unmeasured(false, startingPests);
        }

        int aotvSlot = GearManager.findAspectOfTheVoidSlot(client);
        if (aotvSlot < 0 || aotvSlot >= 9) {
            ClientUtils.sendDebugMessage("Ballsack Shredder: no AOTV found; continuing without the route.");
            return Result.unmeasured(true, startingPests);
        }

        // This route intentionally preserves the current aim while firing the AOTV.
        GearManager.swapToAOTVSync(client);
        PestClientThread.run(client, () -> {
            ClientUtils.setKeyMappingState(client.options.keyShift, true);
            ClientUtils.setKeyMappingState(client.options.keyUse, true);
        });

        int requiredWarps = AetherConfig.BALLSACK_WARPS.get();
        int positionChanges = waitForPositionChanges(client, sessionId, requiredWarps);
        releaseAotvKeys(client);
        if (shouldAbort(client, sessionId)) {
            return Result.unmeasured(false, startingPests);
        }
        if (positionChanges < requiredWarps) {
            ClientUtils.sendDebugMessage("Ballsack Shredder: AOTV timed out after "
                    + positionChanges + " of " + requiredWarps
                    + " position change(s); continuing pest cleaning.");
            return Result.unmeasured(true, startingPests);
        }

        List<Entity> trackedPests = PestClientThread.call(
                client,
                () -> List.copyOf(PestTargetTracker.getLoadedPests(client)),
                List.of());
        if (!lookDownAndHoldVacuum(client, sessionId)) {
            return Result.unmeasured(!shouldAbort(client, sessionId), startingPests);
        }
        if (shouldAbort(client, sessionId)) {
            return Result.unmeasured(false, startingPests);
        }

        releaseVacuumUse(client);
        Result result = awaitResultEvidence(
                client, sessionId, startingPests, trackedPests);
        int trackedKills = countTrackedKills(trackedPests);
        int tabRemaining = PestClientThread.call(
                client,
                () -> PestManager.getTabAliveCountNow(client),
                -1);
        ClientUtils.sendDebugMessage("Ballsack Shredder: estimated killed="
                + result.estimatedKilled() + ", remaining=" + result.estimatedRemaining()
                + " (start=" + Math.max(0, startingPests)
                + ", tracked removals=" + trackedKills
                + ", tab remaining=" + tabRemaining + ").");
        return result;
    }

    static boolean shouldRunOnPlot(String plot) {
        return AetherConfig.BALLSACK_SHREDDER.get()
                && isConfiguredForPlot(plot, AetherConfig.BALLSACK_SHREDDER_PLOTS.get());
    }

    public static boolean isConfiguredForPlot(String plot, List<String> configuredPlots) {
        if (configuredPlots == null || configuredPlots.isEmpty()) {
            return true;
        }
        return configuredPlots.stream().anyMatch(configuredPlot -> PestPlotId.equals(configuredPlot, plot));
    }

    private static Result awaitResultEvidence(
            Minecraft client,
            int sessionId,
            int startingPests,
            List<Entity> trackedPests) throws InterruptedException {
        Result latest = estimateResult(startingPests, countTrackedKills(trackedPests),
                PestClientThread.call(client, () -> PestManager.getTabAliveCountNow(client), -1));
        if (latest.estimatedRemaining() <= 1) {
            return latest;
        }

        long deadline = System.currentTimeMillis() + RESULT_CONFIRM_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline && !shouldAbort(client, sessionId)) {
            int trackedKills = countTrackedKills(trackedPests);
            int tabRemaining = PestClientThread.call(
                    client,
                    () -> PestManager.getTabAliveCountNow(client),
                    -1);
            latest = estimateResult(startingPests, trackedKills, tabRemaining);
            if (latest.estimatedRemaining() <= 1) {
                return latest;
            }

            MacroWorkerThread.sleep(50);
        }
        return latest;
    }

    private static int countTrackedKills(List<Entity> trackedPests) {
        return (int) trackedPests.stream()
                .filter(PestBallsackShredder::isDead)
                .count();
    }

    static Result estimateResult(int startingPests, int trackedKills, int tabRemaining) {
        int normalizedStart = Math.max(0, startingPests);
        int killsFromEntities = Math.min(normalizedStart, Math.max(0, trackedKills));
        int killsFromTab = tabRemaining < 0
                ? normalizedStart
                : Math.max(0, normalizedStart - tabRemaining);
        int estimatedKilled = Math.max(killsFromEntities, killsFromTab);
        return new Result(true, true, estimatedKilled, normalizedStart - estimatedKilled);
    }

    private static int waitForPositionChanges(Minecraft client, int sessionId, int requiredWarps)
            throws InterruptedException {
        Vec3 lastPosition = PestClientThread.call(client, () -> client.player.position(), null);
        if (lastPosition == null) {
            return 0;
        }

        int changes = 0;
        long deadline = System.currentTimeMillis() + AOTV_TIMEOUT_MS;
        while (changes < requiredWarps
                && System.currentTimeMillis() < deadline
                && !shouldAbort(client, sessionId)) {
            MacroWorkerThread.sleep(25);
            Vec3 previousPosition = lastPosition;
            Vec3 currentPosition = PestClientThread.call(client, () -> client.player.position(), previousPosition);
            if (currentPosition.distanceToSqr(previousPosition) >= POSITION_CHANGE_DISTANCE_SQR) {
                changes++;
                lastPosition = currentPosition;
            }
        }
        return changes;
    }

    private static boolean lookDownAndHoldVacuum(Minecraft client, int sessionId) throws InterruptedException {
        PestClientThread.run(client, () -> RotationManager.rotateToYawPitch(
                client, client.player.getYRot(), 90.0f, AetherConfig.ROTATION_TIME.get(), true));
        long rotationDeadline = System.currentTimeMillis() + AetherConfig.ROTATION_TIME.get() + 1_000L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < rotationDeadline
                && !shouldAbort(client, sessionId)) {
            MacroWorkerThread.sleep(20);
        }

        int vacuumSlot = PestLoadoutHelper.findVacuumHotbarSlot(client);
        if (vacuumSlot < 0 || vacuumSlot >= 9) {
            ClientUtils.sendDebugMessage("Ballsack Shredder: no vacuum found for lookdown; continuing pest cleaning.");
            return false;
        }
        PestClientThread.run(client, () -> {
            FailsafeManager.selectHotbarSlot(client, vacuumSlot);
            ClientUtils.setKeyMappingState(client.options.keyUse, true);
        });
        long holdUntil = System.currentTimeMillis() + AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.get();
        while (System.currentTimeMillis() < holdUntil && !shouldAbort(client, sessionId)) {
            MacroWorkerThread.sleep(20);
        }
        return true;
    }

    private static boolean isDead(Entity entity) {
        return entity.isRemoved()
                || entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    private static void releaseAotvKeys(Minecraft client) {
        PestClientThread.run(client, () -> {
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
        });
    }

    private static void releaseVacuumUse(Minecraft client) {
        PestClientThread.run(client, () ->
                ClientUtils.setKeyMappingState(client.options.keyUse, false));
    }

    private static boolean shouldAbort(Minecraft client, int sessionId) {
        return MacroWorkerThread.shouldAbortTask(client)
                || sessionId != PestManager.getCurrentPestSessionId()
                || !PestManager.isCleaningInProgress();
    }
}
