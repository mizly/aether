package dev.aether.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

public final class FarmWaypoints {
    private static volatile Integer cachedLastWaypoint = null;

    private FarmWaypoints() {
    }

    public static List<FarmWaypoint> get() {
        List<FarmWaypoint> parsed = new ArrayList<>();
        for (String encoded : AetherConfig.MACRO_FARM_WAYPOINTS.get()) {
            FarmWaypoint waypoint = FarmWaypoint.parse(encoded);
            if (waypoint != null) {
                parsed.add(waypoint);
            }
        }
        return parsed;
    }

    public static void set(List<FarmWaypoint> waypoints) {
        List<String> encoded = new ArrayList<>();
        for (FarmWaypoint waypoint : waypoints) {
            if (waypoint != null) {
                encoded.add(waypoint.encode());
            }
        }
        AetherConfig.MACRO_FARM_WAYPOINTS.set(encoded);
    }

    public static FarmWaypoint get(int index) {
        List<FarmWaypoint> waypoints = get();
        if (waypoints.isEmpty()) {
            return FarmWaypoint.emptyAt(0.0, 0.0, 0.0);
        }
        int safeIndex = Math.max(0, Math.min(index, waypoints.size() - 1));
        return waypoints.get(safeIndex);
    }

    public static void update(int index, UnaryOperator<FarmWaypoint> updater) {
        List<FarmWaypoint> waypoints = get();
        while (waypoints.size() <= index) {
            waypoints.add(FarmWaypoint.emptyAt(0.0, 0.0, 0.0));
        }
        waypoints.set(index, updater.apply(waypoints.get(index)));
        set(waypoints);
        AetherConfig.save();
    }

    public static void add(FarmWaypoint waypoint) {
        List<FarmWaypoint> waypoints = get();
        waypoints.add(waypoint);
        set(waypoints);
        AetherConfig.save();
    }

    public static void insertAfter(int index, FarmWaypoint waypoint) {
        List<FarmWaypoint> waypoints = get();
        int insertIndex = Math.max(0, Math.min(index + 1, waypoints.size()));
        waypoints.add(insertIndex, waypoint);
        set(waypoints);
        AetherConfig.save();
    }

    public static void remove(int index) {
        List<FarmWaypoint> waypoints = get();
        if (waypoints.size() <= 1 || index < 0 || index >= waypoints.size()) {
            return;
        }
        waypoints.remove(index);
        set(waypoints);
        AetherConfig.save();
    }

    public static int getLastWaypoint() {
        if (cachedLastWaypoint == null) {
            loadLastWaypoint();
        }
        return cachedLastWaypoint == null ? -1 : cachedLastWaypoint;
    }

    public static void saveLastWaypoint(int index) {
        cachedLastWaypoint = index;
        try {
            Files.writeString(lastWaypointPath(), Integer.toString(index));
        } catch (Exception ignored) {
        }
    }

    private static void loadLastWaypoint() {
        try {
            Path path = lastWaypointPath();
            if (Files.exists(path)) {
                cachedLastWaypoint = Integer.parseInt(Files.readString(path).trim());
            }
        } catch (Exception ignored) {
            cachedLastWaypoint = -1;
        }
    }

    private static Path lastWaypointPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("aether_last_waypoint.txt");
    }
}
