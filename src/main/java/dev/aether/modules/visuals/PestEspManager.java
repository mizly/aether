package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.modules.pest.helpers.PestHuntingPolicy;
import dev.aether.modules.pest.helpers.PestTargetTracker;
import dev.aether.modules.pest.helpers.PestDisplayTracker;
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
    private static final float TRACER_WIDTH = 2.0f;
    // Half-extent of the block-sized cube drawn around a pest's visible body.
    private static final double PEST_BODY_HALF = 0.5;

    private PestEspManager() {
    }

    public static boolean hasVisibleHighlights() {
        if (ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) {
            return false;
        }
        boolean esp = AetherConfig.PEST_ESP_ENABLED.get()
                && (AetherConfig.PEST_ESP_HIGHLIGHT.get()
                        || AetherConfig.PEST_ESP_TRACER.get()
                        || AetherConfig.PEST_ESP_PATH.get());
        // Hunt ESP is its own toggle, independent of the Pest ESP master switch.
        return esp || AetherConfig.PEST_ESP_HUNT.get();
    }

    public static void renderWorld() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null
                || StreamerModeManager.isEnabled()
                || !hasVisibleHighlights()) {
            return;
        }

        List<PestData> pests = getRenderablePests(client);

        boolean huntEsp = AetherConfig.PEST_ESP_HUNT.get();
        boolean highlight = AetherConfig.PEST_ESP_ENABLED.get()
                && AetherConfig.PEST_ESP_HIGHLIGHT.get()
                && !usesGlow();
        if (highlight || huntEsp) {
            int normalRgb = pestColor(AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get());
            int huntRgb = pestColor(AetherConfig.PEST_ESP_HUNT_COLOR.get());
            for (PestData pest : pests) {
                boolean hunted = huntEsp && !pest.vacuum();
                if (!hunted && !highlight) {
                    continue;
                }
                int rgb = hunted ? huntRgb : normalRgb;
                int stroke = argb(220, rgb);
                int fill = argb(45, rgb);
                Gizmos.cuboid(pest.box(), GizmoStyle.strokeAndFill(stroke, 2.0f, fill)).setAlwaysOnTop();
            }
        }
    }

    private static List<Vec3> nearestNeighborRoute(Vec3 start, List<PestData> pests) {
        List<Vec3> remaining = new ArrayList<>(pests.size());
        for (PestData pest : pests) {
            remaining.add(pest.position());
        }
        List<Vec3> route = new ArrayList<>(remaining.size());
        Vec3 current = start;
        while (!remaining.isEmpty()) {
            int nearest = 0;
            double nearestDist = current.distanceToSqr(remaining.get(0));
            for (int i = 1; i < remaining.size(); i++) {
                double dist = current.distanceToSqr(remaining.get(i));
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = i;
                }
            }
            current = remaining.remove(nearest);
            route.add(current);
        }
        return route;
    }

    public static void renderTracerOverlay() {
        Minecraft client = Minecraft.getInstance();
        boolean tracer = AetherConfig.PEST_ESP_TRACER.get();
        boolean path = AetherConfig.PEST_ESP_PATH.get();
        if (client == null || client.level == null || client.player == null
                || client.screen != null
                || StreamerModeManager.isEnabled()
                || !AetherConfig.PEST_ESP_ENABLED.get()
                || (!tracer && !path)
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
        ) {
            return;
        }

        List<PestData> pests = getRenderablePests(client);
        if (pests.isEmpty()) {
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
            if (tracer) {
                int tracerColor = argb(255, pestColor(AetherConfig.PEST_ESP_TRACER_COLOR.get()));
                float startX = width * 0.5f;
                float startY = height * 0.5f;
                for (PestData pest : pests) {
                    ScreenPoint screenPoint = projectToScreen(pest.position(), cameraPosition, viewProjection, width, height);
                    if (screenPoint != null) {
                        renderer.line(startX, startY, screenPoint.x(), screenPoint.y(), TRACER_WIDTH, tracerColor);
                    }
                }
            }
            if (path) {
                renderPath(client, pests, cameraPosition, viewProjection, width, height, renderer);
            }
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private static void renderPath(Minecraft client, List<PestData> pests, Vec3 cameraPosition,
                                   Matrix4f viewProjection, float width, float height, NVGRenderer renderer) {
        List<Vec3> route = nearestNeighborRoute(client.player.position(), pests);
        if (route.isEmpty()) {
            return;
        }
        int leadColor = argb(255, pestColor(AetherConfig.PEST_ESP_TRACER_COLOR.get()));
        int futureColor = argb(255, pestColor(AetherConfig.PEST_ESP_PATH_COLOR.get()));

        // The leg to the pest we are hunting next reuses the tracer colour; the
        // remaining legs of the route use the path colour.
        ScreenPoint first = projectFront(route.get(0), cameraPosition, viewProjection, width, height);
        if (first != null) {
            renderer.line(width * 0.5f, height * 0.5f, first.x(), first.y(), TRACER_WIDTH, leadColor);
        }
        for (int i = 0; i < route.size() - 1; i++) {
            ScreenPoint from = projectFront(route.get(i), cameraPosition, viewProjection, width, height);
            ScreenPoint to = projectFront(route.get(i + 1), cameraPosition, viewProjection, width, height);
            if (from != null && to != null) {
                renderer.line(from.x(), from.y(), to.x(), to.y(), TRACER_WIDTH, futureColor);
            }
        }
    }

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
        return new ScreenPoint((ndcX + 1.0f) * 0.5f * width, (1.0f - ndcY) * 0.5f * height);
    }

    private static boolean usesGlow() {
        return "GLOW".equalsIgnoreCase(AetherConfig.PEST_ESP_MODE.get());
    }

    public static int outlineColor(Entity entity) {
        if (!usesGlow() || !hasVisibleHighlights() || !AetherConfig.PEST_ESP_HIGHLIGHT.get()
                || StreamerModeManager.isEnabled()) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) return 0;
        for (var pest : PestDisplayTracker.getPests(client)) {
            if (pest.skull() == entity && !entity.isRemoved()) {
                return argb(255, pestColor(AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get()));
            }
        }
        return 0;
    }

    private static List<PestData> getRenderablePests(Minecraft client) {
        List<PestData> pests = new ArrayList<>();
        boolean classify = AetherConfig.PEST_ESP_HUNT.get();
        // Do not render the armor-stand skull-marker fallback used by the pest targeter. The
        // marker may remain loaded for a short time after its backing pest mob has died.
        for (Entity entity : PestTargetTracker.getLoadedPestMobs(client)) {
            if (entity == null || entity.isRemoved() || isDead(entity)) {
                continue;
            }
            float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                    !client.level.tickRateManager().isEntityFrozen(entity));
            Vec3 position = new Vec3(
                    net.minecraft.util.Mth.lerp(partialTick, entity.xOld, entity.getX()),
                    net.minecraft.util.Mth.lerp(partialTick, entity.yOld, entity.getY()),
                    net.minecraft.util.Mth.lerp(partialTick, entity.zOld, entity.getZ()));
            boolean vacuum = classify && PestHuntingPolicy.isVacuumTarget(client, entity);
            pests.add(new PestData(position, pestBodyBox(entity, position), vacuum));
        }
        return pests;
    }

    // The pest mob (Bat/Silverfish) has a tiny hitbox that does not cover the
    // player-head model shown in-game, so wrap the whole visible body with a
    // block-sized cube centred on the mob's rendered position.
    private static AABB pestBodyBox(Entity entity, Vec3 renderPosition) {
        Vec3 hitboxCenter = entity.getBoundingBox().getCenter();
        double dx = renderPosition.x - entity.position().x;
        double dy = renderPosition.y - entity.position().y;
        double dz = renderPosition.z - entity.position().z;
        double cx = hitboxCenter.x + dx;
        double cy = hitboxCenter.y + dy;
        double cz = hitboxCenter.z + dz;
        return new AABB(
                cx - PEST_BODY_HALF, cy - PEST_BODY_HALF, cz - PEST_BODY_HALF,
                cx + PEST_BODY_HALF, cy + PEST_BODY_HALF, cz + PEST_BODY_HALF);
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

    private record PestData(Vec3 position, AABB box, boolean vacuum) {
    }

    private record ScreenPoint(float x, float y) {
    }
}
