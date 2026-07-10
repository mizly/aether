package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

final class PestNavigationCoordinator {
    private static final long HINT_PITCH_WAIT_TIMEOUT_MS = 3000L;

    interface Context {
        long getStateEnteredAt();
        void setStateEnteredAt(long enteredAt);
        int getVacuumSlot();
        void setVacuumSlot(int slot);
        double getVacuumRange();
        int getStuckTicks();
        void setStuckTicks(int stuckTicks);
        int findVacuumHotbarSlot(Minecraft client);
        void setState(PestDestroyer.State state);
        Entity findClosestPest(Minecraft client);
        void engagePestTarget(Minecraft client, Entity pest);
        int countVisiblePestSkulls(Minecraft client);
        boolean tryNextPlot(Minecraft client);
        boolean tryLeaveOneOnCurrentWhitelistedPlot(Minecraft client);
        void startRoofAotv(Minecraft client, String plot);
    }

    private PestNavigationCoordinator() {
    }

    static void handleTeleportToPlot(
            Minecraft client,
            PestNavigationState navigationState,
            Context context,
            long plotTpWaitMs
    ) {
        if (!navigationState.plotTpSent) {
            String targetPlot = PestPlotNavigator.getNextPlotTarget(navigationState);
            if (targetPlot == null) {
                context.setState(PestDestroyer.State.EQUIP_VACUUM);
                return;
            }

            String currentPlot = ClientUtils.getCurrentPlot();
            boolean forceCurrentPlotTeleport = AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get()
                    || PestDiscoDestinationManager.shouldForcePlotTeleport(targetPlot);
            if (targetPlot.equals(currentPlot) && !forceCurrentPlotTeleport) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Already on plot " + targetPlot + ", skipping TP.");
                finalizePlotArrival(client, navigationState, context, targetPlot);
                return;
            }

            if (PestDiscoDestinationManager.matchesPlot(targetPlot)) {
                if (context.getVacuumSlot() == -1) {
                    context.setVacuumSlot(context.findVacuumHotbarSlot(client));
                }
                if (context.getVacuumSlot() == -1) {
                    context.setState(PestDestroyer.State.EQUIP_VACUUM);
                    return;
                }
                if (client.player == null
                        || ((AccessorInventory) client.player.getInventory()).getSelected() != context.getVacuumSlot()) {
                    client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()));
                    return;
                }
            }

            ClientUtils.sendDebugMessage("[PestDestroyer] Teleporting to plot " + targetPlot);
            navigationState.plotTpWindow = CommandUtils.beginChatWindow();
            CommandUtils.initiatePlotTp(targetPlot);
            navigationState.lastTargetPlot = targetPlot;
            navigationState.plotTpSent = true;
            context.setStateEnteredAt(System.currentTimeMillis());
            return;
        }

        long elapsed = System.currentTimeMillis() - context.getStateEnteredAt();
        boolean confirmed = navigationState.lastTargetPlot != null
                ? CommandUtils.hasPlotTp(navigationState.plotTpWindow, navigationState.lastTargetPlot)
                : CommandUtils.hasPlotTp(navigationState.plotTpWindow);

        if (confirmed && navigationState.lastTargetPlot != null) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Teleport to plot " + navigationState.lastTargetPlot
                    + " confirmed via chat. Trusting this location.");
            finalizePlotArrival(client, navigationState, context, navigationState.lastTargetPlot);
            return;
        }

        if (elapsed > plotTpWaitMs) {
            String targetPlot = navigationState.lastTargetPlot;
            String currentPlot = PestPlotNavigator.getEffectivePlot(client, navigationState);
            if (targetPlot != null && PestPlotNavigator.plotsEqual(targetPlot, currentPlot)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Teleport to plot " + targetPlot + " confirmed by current plot.");
                finalizePlotArrival(client, navigationState, context, targetPlot);
                return;
            }

            if (targetPlot != null && PestDiscoDestinationManager.matchesPlot(targetPlot)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Waiting for disco plot " + targetPlot
                                + " confirmation; not retrying plottp.");
                context.setStateEnteredAt(System.currentTimeMillis());
                return;
            }

            ClientUtils.sendDebugMessage("[PestDestroyer] Waiting for plot " + targetPlot + " arrival; current plot is " + currentPlot + ".");
            navigationState.plotTpSent = false;
            navigationState.plotTpWindow = null;
            context.setStateEnteredAt(System.currentTimeMillis());
        }
    }

    static void handleGetLocation(
            Minecraft client,
            PestNavigationState navigationState,
            Context context,
            int maxGetLocationAttempts,
            int maxWaypointCycles,
            long fireworkCaptureDurationMs,
            double fireworkExtrapolateDistance
    ) {
        long now = System.currentTimeMillis();
        long elapsed = now - context.getStateEnteredAt();

        if (!client.player.getAbilities().flying && client.player.getAbilities().mayfly) {
            long flyElapsed = elapsed % 250;
            if (flyElapsed < 50) {
                ClientUtils.setKeyMappingState(client.options.keyJump, true);
            } else if (flyElapsed < 100) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            } else if (flyElapsed < 150) {
                ClientUtils.setKeyMappingState(client.options.keyJump, true);
            } else {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            }
            if (elapsed > 3000) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            }
            if (client.player.getAbilities().flying) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
                context.setStateEnteredAt(System.currentTimeMillis());
            }
            return;
        }

        if (!navigationState.isCapturingFirework) {
            if (context.getVacuumSlot() == -1) {
                context.setVacuumSlot(context.findVacuumHotbarSlot(client));
            }
            if (context.getVacuumSlot() != -1
                    && ((AccessorInventory) client.player.getInventory()).getSelected() != context.getVacuumSlot()) {
                client.execute(() -> FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()));
            }
            boolean pitchTimedOut = elapsed > HINT_PITCH_WAIT_TIMEOUT_MS;
            if (pitchTimedOut) {
                if (RotationManager.isRotating()) {
                    RotationManager.cancelRotation();
                }
                ClientUtils.sendDebugMessage("PestDestroyer: hint pitch did not settle; probing vacuum anyway.");
            } else if (!ensureHintPitchReady(client, context)) {
                ClientUtils.setKeyMappingState(client.options.keyAttack, false);
                return;
            }

            client.execute(() -> {
                ClientUtils.setKeyMappingState(client.options.keyAttack, true);
                ClientUtils.clickKeyMapping(client.options.keyAttack);
            });
            navigationState.isCapturingFirework = true;
            navigationState.fireworkCaptureStartedAt = now;
            navigationState.fireworkFirstPos = null;
            navigationState.fireworkLastPos = null;
            navigationState.fireworkParticleCount = 0;
            return;
        }

        long captureElapsed = now - navigationState.fireworkCaptureStartedAt;
        if (captureElapsed < fireworkCaptureDurationMs) {
            ClientUtils.setKeyMappingState(client.options.keyAttack, false);
            return;
        }

        navigationState.isCapturingFirework = false;
        navigationState.fireworkCaptureStartedAt = 0L;
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);

        Vec3 waypoint = PestPlotNavigator.calculateWaypoint(navigationState, fireworkExtrapolateDistance);
        if (waypoint != null) {
            navigationState.waypointCycleCount++;
            navigationState.calculatedWaypoint = waypoint;
            ClientUtils.sendDebugMessage("[PestDestroyer] Firework trail: " + navigationState.fireworkParticleCount
                            + " particles. Waypoint: "
                            + String.format("%.0f, %.0f, %.0f", waypoint.x, waypoint.y, waypoint.z)
                            + " (cycle " + navigationState.waypointCycleCount + "/" + maxWaypointCycles + ")");

            if (navigationState.waypointCycleCount > maxWaypointCycles) {
                navigationState.waypointCycleCount = 0;
                ClientUtils.sendDebugMessage("[PestDestroyer] Max waypoint cycles reached without finding pest entity.");
                if (!context.tryNextPlot(client)) {
                    context.setState(PestDestroyer.State.FINISH);
                }
            } else {
                context.setState(PestDestroyer.State.FLY_TO_WAYPOINT);
            }
            return;
        }

        navigationState.getLocationAttempts++;
        ClientUtils.sendDebugMessage("[PestDestroyer] No firework trail detected (attempt "
                        + navigationState.getLocationAttempts + "/" + maxGetLocationAttempts + ")");

        if (navigationState.getLocationAttempts >= maxGetLocationAttempts) {
            if (!context.tryNextPlot(client)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] No more plots to check. Finishing.");
                context.setState(PestDestroyer.State.FINISH);
            }
        } else {
            context.setState(PestDestroyer.State.GET_LOCATION);
        }
    }

    static void handleFlyToWaypoint(
            Minecraft client,
            PestNavigationState navigationState,
            Context context,
            int pathfinderStuckRetryTicks,
            long stateTimeoutMs
    ) {
        if (navigationState.calculatedWaypoint == null) {
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        if (context.tryLeaveOneOnCurrentWhitelistedPlot(client)) {
            return;
        }

        Entity pest = context.findClosestPest(client);
        if (pest != null) {
            PathfindingManager.stop();
            navigationState.waypointCycleCount = 0;
            ClientUtils.sendDebugMessage("PestDestroyer: found pest while flying to waypoint at "
                            + String.format("%.0f, %.0f, %.0f", pest.getX(), pest.getY(), pest.getZ())
                            + " (dist: " + String.format("%.1f", client.player.distanceTo(pest)) + ")");
            context.engagePestTarget(client, pest);
            return;
        }

        double dist = client.player.position().distanceTo(navigationState.calculatedWaypoint);
        if (dist < 15) {
            PathfindingManager.stop();
            context.setState(PestDestroyer.State.GET_LOCATION);
            return;
        }

        if (!PathfindingManager.isNavigating()) {
            context.setStuckTicks(context.getStuckTicks() + 1);
            if (context.getStuckTicks() > pathfinderStuckRetryTicks) {
                context.setStuckTicks(0);
                PathfindingManager.stop();
                context.setState(PestDestroyer.State.GET_LOCATION);
                return;
            } else if (context.getStuckTicks() == 1) {
                int targetY = (int) navigationState.calculatedWaypoint.y;
                PathfindingManager.startPathfind(client,
                        (int) navigationState.calculatedWaypoint.x, targetY, (int) navigationState.calculatedWaypoint.z, true);
            }
        } else {
            context.setStuckTicks(0);
        }

        if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Fly-to-waypoint timed out.");
            PathfindingManager.stop();
            context.setState(PestDestroyer.State.GET_LOCATION);
        }
    }

    private static void finalizePlotArrival(
            Minecraft client,
            PestNavigationState navigationState,
            Context context,
            String plot
    ) {
        navigationState.trustedPlot = plot;
        navigationState.trustedPlotExpiresAt = System.currentTimeMillis() + 120_000;
        navigationState.plotTpSent = false;
        navigationState.plotTpWindow = null;
        navigationState.discoWalkStarted = false;
        navigationState.discoTargetReached = false;
        navigationState.discoWalkStartedAt = 0L;

        if (PestDiscoDestinationManager.matchesPlot(plot)) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Disco destination active on plot " + plot + ". Holding position after plot TP.");
            navigationState.discoTargetReached = true;
            context.setState(PestDestroyer.State.DISCO_SPIN);
            return;
        }

        if (PestAotvManager.shouldDoAotvOnCurrentPlot(client, plot, true)) {
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV to roof needed for plot " + plot);
            context.startRoofAotv(client, plot);
            return;
        }

        if (!client.player.getAbilities().flying && client.player.getAbilities().mayfly) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Not flying after arrival, triggering flight.");
            context.setState(PestDestroyer.State.FLY_UP);
            return;
        }

        if (context.getVacuumSlot() < 0) {
            context.setState(PestDestroyer.State.EQUIP_VACUUM);
            return;
        }

        if (context.tryLeaveOneOnCurrentWhitelistedPlot(client)) {
            return;
        }

        Entity immediatePest = context.findClosestPest(client);
        if (immediatePest != null) {
            ClientUtils.sendDebugMessage("PestDestroyer: pest detected right after arrival. Engaging.");
            context.engagePestTarget(client, immediatePest);
            return;
        }

        if (context.countVisiblePestSkulls(client) == 0
                && ((AccessorInventory) client.player.getInventory()).getSelected() == context.getVacuumSlot()) {
            ClientUtils.performUseClick();
            ClientUtils.sendDebugMessage("[PestDestroyer] No skulls visible. Probing vacuum.");
        }

        context.setState(PestDestroyer.State.CHECK_NEXT);
    }

    private static boolean ensureHintPitchReady(Minecraft client, Context context) {
        if (client.player == null) {
            return false;
        }

        float minPitch = AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.get();
        float maxPitch = AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.get();
        if (maxPitch < minPitch) {
            float swap = minPitch;
            minPitch = maxPitch;
            maxPitch = swap;
        }

        float currentPitch = client.player.getXRot();
        if (currentPitch >= minPitch && currentPitch <= maxPitch && !RotationManager.isRotating()) {
            return true;
        }

        if (RotationManager.isRotating()) {
            return false;
        }

        float targetPitch = Math.max(minPitch, Math.min(maxPitch, currentPitch));
        RotationManager.rotateToYawPitch(
                client,
                client.player.getYRot(),
                targetPitch,
                AetherConfig.ROTATION_TIME.get());
        return false;
    }
}
