package dev.aether.modules.pest.helpers;

import dev.aether.util.CommandUtils;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PestNavigationState {
    Vec3 calculatedWaypoint = null;
    Vec3 plotAnchor = null;
    int scanPointIdx = 0;
    int getLocationAttempts = 0;
    int waypointCycleCount = 0;
    boolean plotTpSent = false;
    CommandUtils.ChatWindow plotTpWindow = null;
    final List<String> plotQueue = new ArrayList<>();
    final Set<String> leaveOneSkippedPlots = ConcurrentHashMap.newKeySet();
    String leaveOneTrackedPlot = null;
    int leaveOneRemainingKills = -1;
    int leaveOneUnbudgetedKills = 0;
    int leaveOneReservedEntityId = -1;
    int currentPlotIdx = 0;
    String lastTargetPlot = null;
    String trustedPlot = null;
    long trustedPlotExpiresAt = 0;

    void resetForRun() {
        calculatedWaypoint = null;
        plotAnchor = null;
        scanPointIdx = 0;
        getLocationAttempts = 0;
        waypointCycleCount = 0;
        plotTpSent = false;
        plotTpWindow = null;
        plotQueue.clear();
        leaveOneTrackedPlot = null;
        leaveOneRemainingKills = -1;
        leaveOneUnbudgetedKills = 0;
        leaveOneReservedEntityId = -1;
        currentPlotIdx = 0;
        lastTargetPlot = null;
        trustedPlot = null;
        trustedPlotExpiresAt = 0L;
    }

}
