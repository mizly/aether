package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestTargetTracker;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/** Renders only currently loaded, confirmed pest entities in the Garden. */
public final class PestEspManager {
    private static final float TRACER_EDGE_MARGIN = 20.0f;

    private PestEspManager() {
    }

    public static boolean hasVisibleHighlights() {
        return AetherConfig.PEST_ESP_ENABLED.get()
                && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
                && (AetherConfig.PEST_ESP_HIGHLIGHT.get()
                        || AetherConfig.PEST_ESP_TRACER.get()
                        || AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.get());
    }

    public static void renderWorld() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null
                || StreamerModeManager.isEnabled()
                || !hasVisibleHighlights()) {
            return;
        }

        if (AetherConfig.PEST_ESP_HIGHLIGHT.get()) {
            int rgb = pestColor(AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get());
            for (PestData pest : getRenderablePests(client)) {
                int stroke = argb(220, rgb);
                int fill = argb(45, rgb);
                Gizmos.cuboid(pest.box(), GizmoStyle.strokeAndFill(stroke, 2.0f, fill)).setAlwaysOnTop();
            }
        }

    }

    public static void renderTracerOverlay() {
        Minecraft client = Minecraft.getInstance();
        boolean tracer = AetherConfig.PEST_ESP_TRACER.get();
        boolean optimizedRoute = AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.get();
        if (client == null || client.level == null || client.player == null
                || client.screen != null
                || StreamerModeManager.isEnabled()
                || !AetherConfig.PEST_ESP_ENABLED.get()
                || (!tracer && !optimizedRoute)
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
        ) {
            return;
        }

        List<PestData> pests = tracer ? getRenderablePests(client) : List.of();
        List<Entity> route = optimizedRoute && PestDestroyer.isActive()
                ? PestDestroyer.getPlannedPestRoute(client)
                : List.of();
        if (pests.isEmpty() && route.isEmpty()) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        float width = client.getWindow().getGuiScaledWidth();
        float height = client.getWindow().getGuiScaledHeight();

        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }
        NanoVGManager.beginFrame(width, height);
        NVGRenderer renderer = NanoVGManager.getRenderer();
        try {
            float startX = width * 0.5f;
            float startY = height * 0.5f;

            if (tracer) {
                int tracerColor = argb(255, pestColor(AetherConfig.PEST_ESP_TRACER_COLOR.get()));
                for (PestData pest : pests) {
                    ScreenPoint screenPoint = projectToScreen(
                            pest.position(), cameraPosition, viewProjection, width, height);
                    if (screenPoint != null) {
                        renderer.line(startX, startY, screenPoint.x(), screenPoint.y(), 2.0f, tracerColor);
                    }
                }
            }

            if (!route.isEmpty()) {
                renderOptimizedRouteOverlay(route, cameraPosition, viewProjection, width, height, renderer);
            }
        } finally {
            NanoVGManager.endFrame();
        }
    }

    /**
     * Draws the planned pest route through the exact same NanoVG screen-space path used
     * by Pest ESP tracers. Route selection remains owned by PestDestroyer; this method
     * is rendering-only.
     */
    private static void renderOptimizedRouteOverlay(List<Entity> route, Vec3 cameraPosition,
                                                     Matrix4f viewProjection, float width, float height,
                                                     NVGRenderer renderer) {
        int routeColor = argb(255, pestColor(AetherConfig.PEST_ESP_OPTIMIZED_ROUTE_COLOR.get()));
        final float lineWidth = 2.0f;

        // Match Pest ESP's lead-line behaviour: the first visible route leg begins at
        // the screen centre, rather than from a separate 3D player-world coordinate.
        Entity firstEntity = null;
        for (Entity entity : route) {
            if (entity != null && !entity.isRemoved() && !isDead(entity)) {
                firstEntity = entity;
                break;
            }
        }
        if (firstEntity == null) {
            return;
        }

        ScreenPoint first = projectFront(firstEntity.position(), cameraPosition, viewProjection, width, height);
        if (first != null) {
            renderer.line(width * 0.5f, height * 0.5f, first.x(), first.y(), lineWidth, routeColor);
        }

        Entity previous = firstEntity;
        boolean passedFirst = false;
        for (Entity next : route) {
            if (next == null || next.isRemoved() || isDead(next)) {
                continue;
            }
            if (!passedFirst) {
                if (next == firstEntity) {
                    passedFirst = true;
                }
                continue;
            }

            ScreenPoint from = projectFront(previous.position(), cameraPosition, viewProjection, width, height);
            ScreenPoint to = projectFront(next.position(), cameraPosition, viewProjection, width, height);
            if (from != null && to != null) {
                renderer.line(from.x(), from.y(), to.x(), to.y(), lineWidth, routeColor);
            }
            previous = next;
        }
    }

    /**
     * Front-facing projection for pest-to-pest route legs. Unlike tracer edge arrows, a
     * point behind the camera is skipped so route segments do not wrap across the screen.
     */
    private static ScreenPoint projectFront(Vec3 position, Vec3 cameraPosition,
                                            Matrix4f viewProjection, float width, float height) {
        Vector4f clip = new Vector4f(
                (float) (position.x - cameraPosition.x),
                (float) (position.y - cameraPosition.y),
                (float) (position.z - cameraPosition.z),
                1.0f).mul(viewProjection);
        if (clip.w <= 0.001f) {
            return null;
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        return new ScreenPoint(
                (ndcX + 1.0f) * 0.5f * width,
                (1.0f - ndcY) * 0.5f * height);
    }

    private static List<PestData> getRenderablePests(Minecraft client) {
        List<PestData> pests = new ArrayList<>();
        // Do not render the armor-stand skull-marker fallback used by the pest targeter. The
        // marker may remain loaded for a short time after its backing pest mob has died.
        for (Entity entity : PestTargetTracker.getLoadedPestMobs(client)) {
            if (entity == null || entity.isRemoved() || isDead(entity)) {
                continue;
            }
            pests.add(new PestData(entity.position(), entity.getBoundingBox()));
        }
        return pests;
    }

    private static ScreenPoint projectToScreen(Vec3 position, Vec3 cameraPosition,
                                                Matrix4f viewProjection, float width, float height) {
        Vector4f clip = new Vector4f(
                (float) (position.x - cameraPosition.x),
                (float) (position.y - cameraPosition.y),
                (float) (position.z - cameraPosition.z),
                1.0f).mul(viewProjection);
        if (clip.w > 0.001f) {
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            return new ScreenPoint(
                    (ndcX + 1.0f) * 0.5f * width,
                    (1.0f - ndcY) * 0.5f * height);
        }
        float depth = Math.max(Math.abs(clip.w), 0.001f);
        float directionX = clip.x / depth;
        float directionY = -clip.y / depth;
        if (Math.abs(directionX) < 0.001f && Math.abs(directionY) < 0.001f) {
            directionY = 1.0f;
        }

        float centerX = width * 0.5f;
        float centerY = height * 0.5f;
        float margin = Math.min(TRACER_EDGE_MARGIN, Math.min(width, height) * 0.1f);
        float availableX = Math.max(1.0f, centerX - margin);
        float availableY = Math.max(1.0f, centerY - margin);
        float scale = 1.0f / Math.max(Math.abs(directionX) / availableX, Math.abs(directionY) / availableY);
        return new ScreenPoint(centerX + directionX * scale, centerY + directionY * scale);
    }

    private static int pestColor(int value) {
        return value & 0x00FFFFFF;
    }

    private static boolean isDead(Entity entity) {
        return entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    private static int argb(int alpha, int rgb) {
        return ARGB.color(alpha, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private record PestData(Vec3 position, AABB box) {
    }

    private record ScreenPoint(float x, float y) {
    }
}
