package dev.aether.renderer;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.FreecamManager;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.ui.theme.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
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
        Vec3 cameraPos = mc.gameRenderer.mainCamera().position();
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
        int color = Theme.getRainbowColor(time, offset, LINE_ALPHA);
        Gizmos.line(from, to, color, LINE_WIDTH);
    }

    private static void drawFace(LevelRenderContext ctx, Vec3 cameraPos, Vec3 apex, Vec3 left, Vec3 right, float time,
            float offset) {
        return;
    }

    private static boolean shouldRender(Minecraft mc) {
        return FreecamManager.isEnabled()
                || !mc.options.getCameraType().isFirstPerson()
                || AetherConfig.HAT_RENDER_FIRST_PERSON.get();
    }

}
