package dev.aether.modules.farming;

import dev.aether.config.AetherConfig;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * Manual fast lane switching based on user-configured X/Z boundaries.
 */
public final class FastLaneSwitchManager {
    private static final int PLOT_SIZE = 96;
    private static final int PLOT_OFFSET = 48;
    private static final int GARDEN_MIN = -240;
    private static final int GARDEN_MAX = 240;
    private static final int MIN_HEIGHT = 66;
    private static final int MAX_HEIGHT = MIN_HEIGHT + 36;
    private static final double MIN_SPEED_BLOCKS_PER_SECOND = 0.25;
    private static final double SWITCH_LEAD_BLOCKS = 0.20;
    private static final double BOUNDARY_HALF_THICKNESS = 0.035;
    private static final long STATE_CHANGE_SUPPRESS_MS = 0L;

    private static AbstractMacro.State trackedState = AbstractMacro.State.NONE;
    private static double previousCoord = 0.0;
    private static double speedBlocksPerSecond = 0.0;
    private static long lastTickMs = 0L;
    private static long suppressFastSwitchUntilMs = 0L;

    private FastLaneSwitchManager() {
    }

    public static void resetRuntime() {
        trackedState = AbstractMacro.State.NONE;
        previousCoord = 0.0;
        speedBlocksPerSecond = 0.0;
        lastTickMs = 0L;
        suppressFastSwitchUntilMs = System.currentTimeMillis() + STATE_CHANGE_SUPPRESS_MS;
    }

    public static void tick(Minecraft mc, AbstractMacro.State state) {
        if (mc == null || mc.player == null || !isRowState(state)) {
            trackedState = state;
            lastTickMs = 0L;
            speedBlocksPerSecond = 0.0;
            return;
        }

        double coord = currentAxisCoord(mc);
        long now = System.currentTimeMillis();
        if (trackedState != state || lastTickMs == 0L) {
            trackedState = state;
            previousCoord = coord;
            lastTickMs = now;
            speedBlocksPerSecond = 0.0;
            return;
        }

        long elapsedMs = Math.max(1L, now - lastTickMs);
        double instantSpeed = Math.abs(coord - previousCoord) * 1000.0 / elapsedMs;
        speedBlocksPerSecond = speedBlocksPerSecond <= 0.0
                ? instantSpeed
                : speedBlocksPerSecond * 0.65 + instantSpeed * 0.35;
        previousCoord = coord;
        lastTickMs = now;
    }

    public static void onStateChanged(AbstractMacro.State from, AbstractMacro.State to) {
        trackedState = to;
        lastTickMs = 0L;
        speedBlocksPerSecond = 0.0;
        suppressFastSwitchUntilMs = System.currentTimeMillis() + STATE_CHANGE_SUPPRESS_MS;
    }

    public static boolean shouldFastSwitch(Minecraft mc, AbstractMacro.State state) {
        if (!AetherConfig.MACRO_FAST_LANE_SWITCH.get() || mc == null || mc.player == null || !isRowState(state)) {
            return false;
        }
        if (System.currentTimeMillis() < suppressFastSwitchUntilMs) {
            return false;
        }

        BoundaryTarget target = getTargetBoundary(mc, state);
        if (!target.valid()) {
            return false;
        }
        return target.distanceBlocks() <= SWITCH_LEAD_BLOCKS;
    }

    public static String getDisplayText() {
        AbstractMacro macro = FarmingMacroManager.getActiveMacro();
        Minecraft mc = Minecraft.getInstance();
        if (!AetherConfig.MACRO_FAST_LANE_SWITCH.get()
                || macro == null
                || mc.player == null
                || MacroStateManager.getCurrentState() != MacroState.State.FARMING
                || !isRowState(macro.getCurrentState())) {
            return "---";
        }

        BoundaryTarget target = getTargetBoundary(mc, macro.getCurrentState());
        if (!target.valid()) {
            return AetherLang.localize("Set Bounds");
        }
        if (target.distanceBlocks() <= SWITCH_LEAD_BLOCKS) {
            return AetherLang.localize("Now");
        }
        if (speedBlocksPerSecond < MIN_SPEED_BLOCKS_PER_SECOND) {
            return AetherLang.localize("Paused");
        }
        long ms = (long) (target.distanceBlocks() / speedBlocksPerSecond * 1000.0);
        return formatDuration(ms);
    }

    public static boolean hasEstimate() {
        AbstractMacro macro = FarmingMacroManager.getActiveMacro();
        Minecraft mc = Minecraft.getInstance();
        if (!AetherConfig.MACRO_FAST_LANE_SWITCH.get() || macro == null || mc.player == null) {
            return false;
        }
        return getTargetBoundary(mc, macro.getCurrentState()).valid()
                && speedBlocksPerSecond >= MIN_SPEED_BLOCKS_PER_SECOND;
    }

    public static boolean hasVisibleHighlights() {
        Minecraft mc = Minecraft.getInstance();
        return AetherConfig.MACRO_FAST_LANE_SWITCH.get()
                && mc.level != null
                && mc.player != null
                && ClientUtils.getCurrentLocation(mc) == MacroState.Location.GARDEN
                && hasValidBoundaries();
    }

    public static void renderWorld() {
        if (!hasVisibleHighlights()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        PlotBounds plot = currentPlotBounds(mc);
        int minBoundary = Math.min(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get(),
                AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get());
        int maxBoundary = Math.max(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get(),
                AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get());

        renderBoundary(plot, minBoundary, ARGB.color(190, 80, 180, 255), ARGB.color(55, 80, 180, 255));
        renderBoundary(plot, maxBoundary, ARGB.color(190, 255, 180, 80), ARGB.color(55, 255, 180, 80));
    }

    private static void renderBoundary(PlotBounds plot, int boundary, int strokeColor, int fillColor) {
        double center = boundaryTriggerCoord(boundary);
        AABB box = "X".equalsIgnoreCase(AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get())
                ? new AABB(
                        center - BOUNDARY_HALF_THICKNESS,
                        MIN_HEIGHT,
                        plot.minZ(),
                        center + BOUNDARY_HALF_THICKNESS,
                        MAX_HEIGHT,
                        plot.maxZ())
                : new AABB(
                        plot.minX(),
                        MIN_HEIGHT,
                        center - BOUNDARY_HALF_THICKNESS,
                        plot.maxX(),
                        MAX_HEIGHT,
                        center + BOUNDARY_HALF_THICKNESS);
        GizmoStyle style = GizmoStyle.strokeAndFill(strokeColor, 2.0f, fillColor);
        var props = Gizmos.cuboid(box, style);
    }

    private static BoundaryTarget getTargetBoundary(Minecraft mc, AbstractMacro.State state) {
        if (!hasValidBoundaries() || !isRowState(state)) {
            return BoundaryTarget.invalid();
        }

        double direction = axisDirection(mc, state);
        if (Math.abs(direction) < 0.05) {
            return BoundaryTarget.invalid();
        }

        double coord = currentAxisCoord(mc);
        int minBoundary = Math.min(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get(),
                AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get());
        int maxBoundary = Math.max(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get(),
                AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get());
        double boundary = boundaryTriggerCoord(direction > 0.0 ? maxBoundary : minBoundary);
        double distance = direction > 0.0 ? boundary - coord : coord - boundary;
        return new BoundaryTarget(true, Math.max(0.0, distance));
    }

    private static double boundaryTriggerCoord(int boundaryBlock) {
        return boundaryBlock + 0.5;
    }

    private static double axisDirection(Minecraft mc, AbstractMacro.State state) {
        float yaw = mc.player.getYRot();
        double rad = Math.toRadians(yaw);
        double x;
        double z;
        switch (state) {
            case LEFT -> {
                x = Math.cos(rad);
                z = Math.sin(rad);
            }
            case RIGHT -> {
                x = -Math.cos(rad);
                z = -Math.sin(rad);
            }
            case FORWARD -> {
                x = -Math.sin(rad);
                z = Math.cos(rad);
            }
            case BACKWARD -> {
                x = Math.sin(rad);
                z = -Math.cos(rad);
            }
            default -> {
                return 0.0;
            }
        }
        return "X".equalsIgnoreCase(AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get()) ? x : z;
    }

    private static double currentAxisCoord(Minecraft mc) {
        return "X".equalsIgnoreCase(AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get())
                ? mc.player.getX()
                : mc.player.getZ();
    }

    private static PlotBounds currentPlotBounds(Minecraft mc) {
        int plotX = (int) Math.floor((mc.player.getX() + PLOT_OFFSET) / PLOT_SIZE);
        int plotZ = (int) Math.floor((mc.player.getZ() + PLOT_OFFSET) / PLOT_SIZE);
        int minX = Mth.clamp(plotX * PLOT_SIZE - PLOT_OFFSET, GARDEN_MIN, GARDEN_MAX);
        int minZ = Mth.clamp(plotZ * PLOT_SIZE - PLOT_OFFSET, GARDEN_MIN, GARDEN_MAX);
        int maxX = Mth.clamp(minX + PLOT_SIZE, GARDEN_MIN, GARDEN_MAX);
        int maxZ = Mth.clamp(minZ + PLOT_SIZE, GARDEN_MIN, GARDEN_MAX);
        return new PlotBounds(minX, minZ, maxX, maxZ);
    }

    private static boolean hasValidBoundaries() {
        return AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get()
                != AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get();
    }

    private static boolean isRowState(AbstractMacro.State state) {
        return state == AbstractMacro.State.LEFT
                || state == AbstractMacro.State.RIGHT
                || state == AbstractMacro.State.FORWARD
                || state == AbstractMacro.State.BACKWARD;
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, (ms + 500L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private record BoundaryTarget(boolean valid, double distanceBlocks) {
        static BoundaryTarget invalid() {
            return new BoundaryTarget(false, 0.0);
        }
    }

    private record PlotBounds(int minX, int minZ, int maxX, int maxZ) {
    }
}
