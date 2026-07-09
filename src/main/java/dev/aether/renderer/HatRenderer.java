package dev.aether.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.FreecamManager;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.ui.theme.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

public final class HatRenderer {
    private static final float LINE_WIDTH = 2.0f;
    private static final int LINE_ALPHA = 230;
    private static final int FILL_ALPHA = 90;

    private HatRenderer() {}

    public static boolean hasVisibleEffect() {
        if (StreamerModeManager.isEnabled()) {
            return false;
        }
        return AetherConfig.HAT_ENABLED.get() && shouldRender(Minecraft.getInstance());
    }

    public static void render(LevelRenderContext ctx) {
        if (StreamerModeManager.isEnabled()) return;
        if (!AetherConfig.HAT_ENABLED.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !shouldRender(mc)) return;

        int vertices = Math.max(3, Math.min(20, AetherConfig.HAT_VERTICES.get()));
        double radius = AetherConfig.HAT_RADIUS.get();
        double height = AetherConfig.HAT_HEIGHT.get();
        double yOffset = AetherConfig.HAT_Y_OFFSET.get();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        Vec3 playerPos = mc.player.getPosition(partialTick);
        double baseY = playerPos.y + mc.player.getBbHeight() + yOffset;
        Vec3 center = new Vec3(playerPos.x, baseY, playerPos.z);
        Vec3 apex = center.add(0.0, height, 0.0);
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        float time = (System.currentTimeMillis() % 10_000L) / 10_000.0f;

        Vec3 firstPoint = null;
        Vec3 previousPoint = null;
        for (int i = 0; i < vertices; i++) {
            double angle = (Math.PI * 2.0 * i) / vertices;
            Vec3 point = new Vec3(
                    center.x + Math.cos(angle) * radius,
                    center.y,
                    center.z + Math.sin(angle) * radius);

            if (previousPoint != null) {
                drawFace(ctx, cameraPos, apex, previousPoint, point, time, i / (float) vertices);
                drawLine(ctx, cameraPos, previousPoint, point, time, i / (float) vertices);
            } else {
                firstPoint = point;
            }

            drawLine(ctx, cameraPos, apex, point, time, (i + 0.5f) / vertices);
            previousPoint = point;
        }

        if (previousPoint != null && firstPoint != null) {
            drawFace(ctx, cameraPos, apex, previousPoint, firstPoint, time, 1.0f);
            drawLine(ctx, cameraPos, previousPoint, firstPoint, time, 1.0f);
        }
    }

    private static void drawLine(LevelRenderContext ctx, Vec3 cameraPos, Vec3 from, Vec3 to, float time, float offset) {
        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        int color = Theme.getRainbowColor(time, offset, LINE_ALPHA);
        Vec3 fromRelative = from.subtract(cameraPos);
        Vec3 toRelative = to.subtract(cameraPos);
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        float normalX = length > 1.0E-6 ? (float) (direction.x / length) : 0.0f;
        float normalY = length > 1.0E-6 ? (float) (direction.y / length) : 1.0f;
        float normalZ = length > 1.0E-6 ? (float) (direction.z / length) : 0.0f;

        VertexConsumer buffer = consumers.getBuffer(RenderTypes.lines());
        addColoredLineVertex(buffer, fromRelative, color, normalX, normalY, normalZ);
        addColoredLineVertex(buffer, toRelative, color, normalX, normalY, normalZ);
    }

    private static void drawFace(LevelRenderContext ctx, Vec3 cameraPos, Vec3 apex, Vec3 left, Vec3 right, float time,
            float offset) {
        if (!AetherConfig.HAT_FILLED.get()) return;

        int color = Theme.getRainbowColor(time, offset, FILL_ALPHA);
        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        VertexConsumer buffer = consumers.getBuffer(RenderTypes.debugTriangleFan());
        addColoredVertex(buffer, apex.subtract(cameraPos), color);
        addColoredVertex(buffer, left.subtract(cameraPos), color);
        addColoredVertex(buffer, right.subtract(cameraPos), color);
    }

    private static boolean shouldRender(Minecraft mc) {
        return FreecamManager.isEnabled()
                || !mc.options.getCameraType().isFirstPerson()
                || AetherConfig.HAT_RENDER_FIRST_PERSON.get();
    }

    private static void addColoredVertex(VertexConsumer buffer, Vec3 point, int color) {
        buffer.addVertex((float) point.x, (float) point.y, (float) point.z)
                .setColor(color);
    }

    private static void addColoredLineVertex(VertexConsumer buffer, Vec3 point, int color, float normalX, float normalY,
            float normalZ) {
        buffer.addVertex((float) point.x, (float) point.y, (float) point.z)
                .setColor(color)
                .setNormal(normalX, normalY, normalZ)
                .setLineWidth(LINE_WIDTH);
    }
}
