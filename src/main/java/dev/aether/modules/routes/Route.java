package dev.aether.modules.routes;

import java.util.ArrayList;
import java.util.List;

// a warp to an island followed by legs to walk or etherwarp; the last leg ends where the macro works
public final class Route {
    public static final String DEFAULT_WARP = "galatea";

    public enum LegType { WALK, ETHERWARP }

    // x y z is the block the player stands in, one above the block that was clicked
    public record Waypoint(int x, int y, int z, LegType type) {
        public boolean isAt(int bx, int by, int bz) {
            return x == bx && y == by && z == bz;
        }
    }

    private String name;
    private String warp;
    private final List<Waypoint> waypoints = new ArrayList<>();

    public Route(String name, String warp) {
        this.name = name;
        this.warp = warp;
    }

    public String name() {
        return name;
    }

    void rename(String name) {
        this.name = name;
    }

    public String warp() {
        return warp == null ? "" : warp.trim();
    }

    public void setWarp(String warp) {
        this.warp = warp == null ? "" : warp.trim();
    }

    public String warpCommand() {
        String target = warp();
        if (target.startsWith("/")) {
            target = target.substring(1);
        }
        if (target.toLowerCase(java.util.Locale.ROOT).startsWith("warp ")) {
            target = target.substring(5).trim();
        }
        return target.isEmpty() ? "" : "/warp " + target;
    }

    public List<Waypoint> waypoints() {
        return waypoints;
    }

    public Waypoint end() {
        return waypoints.isEmpty() ? null : waypoints.getLast();
    }

    public boolean hasWarp() {
        return !warpCommand().isEmpty();
    }

    public boolean isWarpOnly() {
        return hasWarp() && waypoints.isEmpty();
    }

    public void add(Waypoint waypoint) {
        waypoints.add(waypoint);
    }

    public boolean removeAt(int x, int y, int z) {
        return waypoints.removeIf(waypoint -> waypoint.isAt(x, y, z));
    }

    public int indexAt(int x, int y, int z) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).isAt(x, y, z)) {
                return i;
            }
        }
        return -1;
    }
}
