package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

final class PestNavigationCoordinator {
    interface Context {
        PestDestroyerRuntime runtime();
        default long getStateEnteredAt() { return runtime().stateEnteredAt; }
        default void setStateEnteredAt(long value) { runtime().stateEnteredAt = value; }
        default int getVacuumSlot() { return runtime().vacuumSlot; }
        default void setVacuumSlot(int value) { runtime().vacuumSlot = value; }
        default double getVacuumRange() { return runtime().vacuumRange; }
        default int getStuckTicks() { return runtime().stuckTicks; }
        default void setStuckTicks(int value) { runtime().stuckTicks = value; }
        int findVacuumHotbarSlot(Minecraft client);
        void setState(PestDestroyer.State state);
        Entity findClosestPest(Minecraft client);
        void engagePestTarget(Minecraft client, Entity pest);
        boolean tryNextPlot(Minecraft client);
        boolean tryLeaveOneOnCurrentWhitelistedPlot(Minecraft client);
        void startRoofAotv(Minecraft client, String plot);
        void startBallsackShredder(Minecraft client, String plot);
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
            boolean forceCurrentPlotTeleport =
                    AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get();
            if (targetPlot.equals(currentPlot) && !forceCurrentPlotTeleport) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Already on plot " + targetPlot + ", skipping TP.");
                finalizePlotArrival(client, navigationState, context, targetPlot);
                return;
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
            int maxPlotSweeps,
            int maxScanWaypoints
    ) {
        long elapsed = System.currentTimeMillis() - context.getStateEnteredAt();

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

        ClientUtils.setKeyMappingState(client.options.keyAttack, false);

        // Same detection the pest ESP draws: loaded pest mobs and their skull markers.
        Entity pest = context.findClosestPest(client);
        if (pest != null) {
            navigationState.scanPointIdx = 0;
            context.engagePestTarget(client, pest);
            return;
        }

        // Nothing is loaded here, so the remaining pests are outside entity tracking range.
        Vec3 waypoint = PestPlotNavigator.nextScanWaypoint(client, navigationState);
        if (waypoint == null) {
            navigationState.scanPointIdx = 0;
            navigationState.getLocationAttempts++;
            ClientUtils.sendDebugMessage("[PestDestroyer] Plot sweep found no pests (sweep "
                    + navigationState.getLocationAttempts + "/" + maxPlotSweeps + ")");
            if (navigationState.getLocationAttempts >= maxPlotSweeps) {
                navigationState.getLocationAttempts = 0;
                if (!context.tryNextPlot(client)) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] No more plots to check. Finishing.");
                    context.setState(PestDestroyer.State.FINISH);
                }
            }
            return;
        }

        navigationState.waypointCycleCount++;
        if (navigationState.waypointCycleCount > maxScanWaypoints) {
            navigationState.waypointCycleCount = 0;
            navigationState.scanPointIdx = 0;
            ClientUtils.sendDebugMessage("[PestDestroyer] Scan waypoint budget spent without finding a pest.");
            if (!context.tryNextPlot(client)) {
                context.setState(PestDestroyer.State.FINISH);
            }
            return;
        }

        navigationState.calculatedWaypoint = waypoint;
        ClientUtils.sendDebugMessage("[PestDestroyer] No pests loaded. Sweeping to "
                + String.format("%.0f, %.0f, %.0f", waypoint.x, waypoint.y, waypoint.z)
                + " (point " + navigationState.scanPointIdx + "/" + PestPlotNavigator.scanPointCount()
                + ", waypoint " + navigationState.waypointCycleCount + "/" + maxScanWaypoints + ")");
        context.setState(PestDestroyer.State.FLY_TO_WAYPOINT);
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
        // Re-anchor the sweep grid on the first scan, once the TP has actually landed.
        navigationState.plotAnchor = null;
        navigationState.scanPointIdx = 0;
        navigationState.getLocationAttempts = 0;
        if (PestManager.isBallsackShredderActiveForCurrentCycle()) {
            context.startBallsackShredder(client, plot);
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

        context.setState(PestDestroyer.State.CHECK_NEXT);
    }
}
