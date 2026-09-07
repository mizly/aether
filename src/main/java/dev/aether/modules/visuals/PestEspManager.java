package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
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

    private PestEspManager() {
    }

    public static boolean hasVisibleHighlights() {
        return AetherConfig.PEST_ESP_ENABLED.get()
                && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
                && (AetherConfig.PEST_ESP_HIGHLIGHT.get() || AetherConfig.PEST_ESP_TRACER.get());
    }

    public static void renderWorld() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null
                || StreamerModeManager.isEnabled()
                || !hasVisibleHighlights()) {
            return;
        }

        for (PestData pest : getRenderablePests(client)) {
            int rgb = pestColor(AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get());
            if (AetherConfig.PEST_ESP_HIGHLIGHT.get() && !usesGlow()) {
                int stroke = argb(220, rgb);
                int fill = argb(45, rgb);
                Gizmos.cuboid(pest.box(), GizmoStyle.strokeAndFill(stroke, 2.0f, fill)).setAlwaysOnTop();
            }
        }
    }

    public static void renderTracerOverlay() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null
                || client.screen != null
                || StreamerModeManager.isEnabled()
                || !AetherConfig.PEST_ESP_ENABLED.get()
                || !AetherConfig.PEST_ESP_TRACER.get()
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
        int tracerColor = argb(255, pestColor(AetherConfig.PEST_ESP_TRACER_COLOR.get()));

        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }
        NanoVGManager.beginFrame(width, height);
        NVGRenderer renderer = NanoVGManager.getRenderer();
        try {
            float startX = width * 0.5f;
            float startY = height * 0.5f;
            for (PestData pest : pests) {
                ScreenPoint screenPoint = projectToScreen(pest.position(), cameraPosition, viewProjection, width, height);
                if (screenPoint != null) {
                    renderer.line(startX, startY, screenPoint.x(), screenPoint.y(), 2.0f, tracerColor);
                }
            }
        } finally {
            NanoVGManager.endFrame();
        }
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
            AABB box = entity.getBoundingBox().move(position.subtract(entity.position()));
            pests.add(new PestData(box.getCenter(), box));
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
