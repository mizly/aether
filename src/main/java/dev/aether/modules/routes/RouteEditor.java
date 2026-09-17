package dev.aether.modules.routes;

import dev.aether.hud.HudStyle;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.PositionHighlighter;
import dev.aether.ui.RouteWaypointScreen;
import dev.aether.ui.RoutesScreen;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;

// in-world route editing: right click a block to add a leg on top of it, left click a highlighted block to drop it
public final class RouteEditor {
    // etherwarp legs can land far across an island, so the pick reaches well past vanilla reach
    private static final double PICK_DISTANCE = 64.0;
    private static final float PANEL_W = 196f;
    private static final float MARGIN = 10f;

    public static final int WALK_COLOR = 0xFF4FC3F7;
    public static final int ETHERWARP_COLOR = 0xFFB388FF;

    private static RouteStore.Folder folder;
    private static Route route;

    private RouteEditor() {
    }

    public static boolean isActive() {
        return route != null;
    }

    public static void begin(Minecraft mc, RouteStore.Folder routeFolder, Route editing) {
        folder = routeFolder;
        route = editing;
        mc.setScreen(null);
    }

    public static void exit(Minecraft mc) {
        if (route == null) {
            return;
        }
        save();
        RouteStore.Folder returnTo = folder;
        route = null;
        folder = null;
        mc.setScreen(new RoutesScreen(returnTo));
    }

    public static void save() {
        if (route != null && folder != null) {
            RouteStore.config().save(folder, route);
        }
    }

    public static void addOrReplace(Route.Waypoint waypoint) {
        if (route == null) {
            return;
        }
        int index = route.indexAt(waypoint.x(), waypoint.y(), waypoint.z());
        if (index >= 0) {
            route.waypoints().set(index, waypoint);
        } else {
            route.add(waypoint);
        }
        save();
    }

    // physical mouse buttons only; true means vanilla never sees the click
    public static boolean onMouseButton(Minecraft mc, int button, int action) {
        if (route == null || mc.screen != null || mc.player == null
                || (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            return false;
        }
        if (action != GLFW.GLFW_PRESS) {
            return true;
        }

        BlockPos clicked = pickBlock(mc);
        if (clicked == null) {
            return true;
        }
        BlockPos standing = clicked.above();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Route.Waypoint existing = findAt(standing);
            mc.setScreen(new RouteWaypointScreen(route, standing, existing == null ? null : existing.type()));
            return true;
        }
        if (route.removeAt(standing.getX(), standing.getY(), standing.getZ())
                || route.removeAt(clicked.getX(), clicked.getY(), clicked.getZ())) {
            save();
        }
        return true;
    }

    public static boolean onKeyPress(Minecraft mc, int key, int action) {
        if (route == null || mc.screen != null || key != GLFW.GLFW_KEY_ESCAPE || action != GLFW.GLFW_PRESS) {
            return false;
        }
        exit(mc);
        return true;
    }

    private static Route.Waypoint findAt(BlockPos standing) {
        int index = route.indexAt(standing.getX(), standing.getY(), standing.getZ());
        return index < 0 ? null : route.waypoints().get(index);
    }

    private static BlockPos pickBlock(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(PICK_DISTANCE));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    public static void renderWorld(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (route == null || mc.level == null || mc.player == null) {
            return;
        }
        MultiBufferSource.BufferSource textBuffer = ctx.bufferSource() instanceof MultiBufferSource.BufferSource source
                ? source
                : null;

        List<Route.Waypoint> waypoints = route.waypoints();
        for (int i = 0; i < waypoints.size(); i++) {
            Route.Waypoint waypoint = waypoints.get(i);
            int color = waypoint.type() == Route.LegType.ETHERWARP ? ETHERWARP_COLOR : WALK_COLOR;
            int floorY = waypoint.y() - 1;
            PositionHighlighter.renderBlockHighlight(ctx, mc, textBuffer,
                    new AABB(waypoint.x(), floorY, waypoint.z(), waypoint.x() + 1, floorY + 1, waypoint.z() + 1),
                    "#" + (i + 1) + " " + legLabel(waypoint.type()) + (i == waypoints.size() - 1 ? " (End)" : ""),
                    Theme.withAlpha(color, 220),
                    Theme.withAlpha(color, 50),
                    2.0f);
            if (i > 0) {
                Route.Waypoint previous = waypoints.get(i - 1);
                Gizmos.line(Vec3.atBottomCenterOf(new BlockPos(previous.x(), previous.y(), previous.z())),
                        Vec3.atBottomCenterOf(new BlockPos(waypoint.x(), waypoint.y(), waypoint.z())),
                        Theme.withAlpha(color, 200), 2.0f).setAlwaysOnTop();
            }
        }

        BlockPos aimed = mc.screen == null ? pickBlock(mc) : null;
        if (aimed != null && findAt(aimed.above()) == null) {
            PositionHighlighter.renderBlockHighlight(ctx, mc, textBuffer,
                    new AABB(aimed),
                    null,
                    ARGB.color(170, 255, 255, 255),
                    ARGB.color(25, 255, 255, 255),
                    1.5f);
        }
    }

    public static String legLabel(Route.LegType type) {
        return AetherLang.localize(type == Route.LegType.ETHERWARP ? "Etherwarp" : "Walk");
    }

    public static void registerHud() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aether", "route_editor"), (graphics, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (route == null || mc.player == null || mc.screen != null) {
                return;
            }
            var window = mc.getWindow();
            float width = window.getGuiScaledWidth();
            float height = window.getGuiScaledHeight();
            AetherRenderQueue.enqueue(() -> renderPanelFrame(width, height));
        });
    }

    private static void renderPanelFrame(float width, float height) {
        if (route == null || NanoVGManager.isDrawing()) {
            return;
        }
        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            nvg.save();
            nvg.translate(width - PANEL_W - MARGIN, MARGIN);
            renderPanel(nvg);
            nvg.restore();
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private static void renderPanel(NVGRenderer nvg) {
        float pad = HudStyle.PAD;
        float innerW = PANEL_W - pad * 2f;
        float panelH = 170f;

        HudStyle.panel(nvg, PANEL_W, panelH);
        HudStyle.accent(nvg, PANEL_W, Theme.HUD_ACCENT, Theme.HUD_ACCENT);
        HudStyle.text(nvg, Fonts.BOLD, "Route Editor", pad, 12f, innerW * 0.55f, 12f, Theme.HUD_TITLE);
        String name = route.name();
        float nameW = Math.min(nvg.textWidth(Fonts.MONO, name, 9f), innerW * 0.45f);
        HudStyle.text(nvg, Fonts.MONO, name, PANEL_W - pad - nameW, 14f, nameW + 1f, 9f, Theme.HUD_LABEL);
        nvg.rect(pad, 31f, innerW, 0.7f, Theme.HUD_SEP);

        float y = 40f;
        y = renderHint(nvg, pad, y, "RMB", "Right click to add waypoint");
        y = renderHint(nvg, pad, y, "LMB", "Left click to delete waypoint");
        y = renderHint(nvg, pad, y, "ESC", "Esc to exit");

        nvg.rect(pad, y + 2f, innerW, 0.7f, Theme.HUD_SEP);
        y += 10f;
        String warp = route.warpCommand();
        renderRow(nvg, pad, y, innerW, "Rewarp", warp.isEmpty() ? AetherLang.localize("No rewarp") : warp);
        y += 16f;
        renderRow(nvg, pad, y, innerW, "Waypoints", String.valueOf(route.waypoints().size()));
        y += 20f;

        float legendX = pad;
        legendX = renderLegend(nvg, legendX, y, WALK_COLOR, legLabel(Route.LegType.WALK));
        renderLegend(nvg, legendX + 12f, y, ETHERWARP_COLOR, legLabel(Route.LegType.ETHERWARP));
    }

    private static float renderHint(NVGRenderer nvg, float x, float y, String key, String label) {
        float chipW = 30f;
        nvg.roundedRect(x, y, chipW, 15f, 4f, HudStyle.alpha(Theme.HUD_ACCENT, 0.16f));
        nvg.textCentered(Fonts.MONO, key, x, y, chipW, 15f, 8f, Theme.HUD_ACCENT);
        HudStyle.text(nvg, Fonts.REGULAR, label, x + chipW + 8f, y + 3f, PANEL_W - x * 2f - chipW - 8f, 10f,
                Theme.HUD_VALUE);
        return y + 21f;
    }

    private static void renderRow(NVGRenderer nvg, float x, float y, float w, String label, String value) {
        HudStyle.text(nvg, Fonts.REGULAR, label, x, y, w * 0.4f, 10f, Theme.HUD_LABEL);
        float valueW = Math.min(nvg.textWidth(Fonts.MONO, value, 9f), w * 0.6f);
        HudStyle.text(nvg, Fonts.MONO, value, x + w - valueW, y + 1f, valueW + 1f, 9f, Theme.HUD_VALUE);
    }

    private static float renderLegend(NVGRenderer nvg, float x, float y, int color, String label) {
        nvg.circle(x + 4f, y + 5f, 4f, color);
        nvg.text(Fonts.REGULAR, label, x + 13f, y, 10f, Theme.HUD_LABEL);
        return x + 13f + nvg.textWidth(Fonts.REGULAR, label, 10f);
    }
}
