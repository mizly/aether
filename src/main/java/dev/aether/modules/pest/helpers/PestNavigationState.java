package dev.aether.modules.pest.helpers;

import dev.aether.util.CommandUtils;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PestNavigationState {
    private static final long TRUSTED_PLOT_TTL_MS = 120_000L;

    Vec3 fireworkFirstPos = null;
    Vec3 fireworkLastPos = null;
    int fireworkParticleCount = 0;
    boolean isCapturingFirework = false;
    long fireworkCaptureStartedAt = 0L;
    Vec3 calculatedWaypoint = null;
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

    // The sidebar lags a couple of seconds behind a plot teleport, so a confirmed arrival
    // is trusted over it for a while to avoid re-teleporting to a plot we're already on.
    void trustPlot(String plot, long now) {
        trustedPlot = plot;
        trustedPlotExpiresAt = now + TRUSTED_PLOT_TTL_MS;
    }

    void resetForRun() {
        fireworkFirstPos = null;
        fireworkLastPos = null;
        fireworkParticleCount = 0;
        isCapturingFirework = false;
        fireworkCaptureStartedAt = 0L;
        calculatedWaypoint = null;
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
