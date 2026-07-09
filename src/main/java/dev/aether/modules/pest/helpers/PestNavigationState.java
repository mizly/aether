package dev.aether.modules.pest.helpers;

import dev.aether.util.CommandUtils;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PestNavigationState {
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
    int currentPlotIdx = 0;
    String lastTargetPlot = null;
    String trustedPlot = null;
    long trustedPlotExpiresAt = 0;
    boolean discoWalkStarted = false;
    boolean discoTargetReached = false;
    long discoWalkStartedAt = 0L;
}
