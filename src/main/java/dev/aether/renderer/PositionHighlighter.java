package dev.aether.renderer;

import dev.aether.config.AetherConfig;
import dev.aether.config.RewarpPointPair;
import dev.aether.config.RewarpPointPairs;
import dev.aether.macro.MacroState;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.util.ClientUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;

public final class PositionHighlighter {
    private PositionHighlighter() {}

    public static boolean hasVisibleHighlights() {
        if (StreamerModeManager.isEnabled()) {
            return false;
        }
        if (hasVisibleGardenHighlights()) {
            return true;
        }
        return dev.aether.modules.metaldetector.MetalDetectorSolver.hasVisibleHighlights();
    }

    private static boolean hasVisibleGardenHighlights() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) {
            return false;
        }
        if (AetherConfig.PEST_HIGHLIGHT_DESK.get()) {
            return true;
        }
        if (AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.get()) {
            return true;
        }
        for (RewarpPointPair pair : RewarpPointPairs.get()) {
            if ((pair.highlightStart && pair.hasStart()) || (pair.highlightEnd && pair.hasEnd())) {
                return true;
            }
        }
        if (!dev.aether.modules.GreenhouseManager.highlightedSkulls.isEmpty()) {
            return true;
        }
        List<Vec3> path = dev.aether.modules.GreenhouseManager.currentPath;
        if (path != null && !path.isEmpty()) {
            return true;
        }
        if (dev.aether.modules.GreenhouseManager.getCurrentTarget() != null) {
            return true;
        }
        if (dev.aether.modules.GreenhouseManager.getCurrentAimTarget() != null) {
            return true;
        }
        if (dev.aether.modules.farming.FastLaneSwitchManager.hasVisibleHighlights()) {
            return true;
        }
        return false;
    }

    public static void renderWorld(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (StreamerModeManager.isEnabled()) return;
        if (mc.level == null || mc.player == null) return;
        if (!hasVisibleHighlights()) return;
        boolean inGarden = ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;

        if (inGarden) {
            MultiBufferSource.BufferSource textBuffer = ctx.bufferSource() instanceof MultiBufferSource.BufferSource bufferSource
                    ? bufferSource
                    : null;

            // Desk Position (Block highlight)
            if (AetherConfig.PEST_HIGHLIGHT_DESK.get()) {
                int x = AetherConfig.PEST_EXCHANGE_DESK_X.get();
                int y = AetherConfig.PEST_EXCHANGE_DESK_Y.get();
                int z = AetherConfig.PEST_EXCHANGE_DESK_Z.get();

                renderBlockHighlight(ctx, mc, textBuffer,
                        new AABB(x, y, z, x + 1, y + 1, z + 1),
                        "Pest Desk",
                        ARGB.color(200, 255, 210, 0),
                        ARGB.color(40, 255, 210, 0),
                        2.0f);
            }

            if (AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.get()) {
                int x = AetherConfig.AUTO_COMPOSTER_X.get();
                int y = AetherConfig.AUTO_COMPOSTER_Y.get();
                int z = AetherConfig.AUTO_COMPOSTER_Z.get();

                renderBlockHighlight(ctx, mc, textBuffer,
                        new AABB(x, y, z, x + 1, y + 1, z + 1),
                        "Composter",
                        ARGB.color(200, 90, 230, 120),
                        ARGB.color(40, 90, 230, 120),
                        2.0f);
            }

            for (RewarpPointPair pair : RewarpPointPairs.get()) {
                renderRewarpPair(ctx, mc, textBuffer, pair);
            }

            // Greenhouse Skulls
            for (net.minecraft.world.phys.AABB box : dev.aether.modules.GreenhouseManager.highlightedSkulls) {
                renderBlockHighlight(ctx, mc, textBuffer,
                        box,
                        "Greenhouse Skull",
                        ARGB.color(200, 255, 100, 255),
                        ARGB.color(40, 255, 100, 255),
                        2.0f);
            }

            // Greenhouse Path
            var path = dev.aether.modules.GreenhouseManager.currentPath;
            if (path != null && !path.isEmpty()) {
                int lineArgb = ARGB.color(217, 255, 100, 255);
                for (int i = 0; i < path.size() - 1; i++) {
                    var lp = Gizmos.line(path.get(i), path.get(i + 1), lineArgb, 2.0f);
                    lp.setAlwaysOnTop();
                }
            }

            // Greenhouse Current Target
            var target = dev.aether.modules.GreenhouseManager.getCurrentTarget();
            if (target != null) {
                renderBlockHighlight(ctx, mc, textBuffer,
                        new AABB(target.x - 0.3, target.y - 0.3, target.z - 0.3,
                                target.x + 0.3, target.y + 0.3, target.z + 0.3),
                        "Greenhouse Target",
                        ARGB.color(255, 0, 255, 0),
                        ARGB.color(60, 0, 255, 0),
                        2.5f);
            }

            // Greenhouse Aim Target
            var aimTarget = dev.aether.modules.GreenhouseManager.getCurrentAimTarget();
            if (aimTarget != null) {
                renderBlockHighlight(ctx, mc, textBuffer,
                        new AABB(aimTarget.x - 0.05, aimTarget.y - 0.05, aimTarget.z - 0.05,
                                aimTarget.x + 0.05, aimTarget.y + 0.05, aimTarget.z + 0.05),
                        "Greenhouse Aim",
                        ARGB.color(255, 255, 255, 0),
                        ARGB.color(90, 255, 255, 0),
                        2.0f);
            }

            dev.aether.modules.farming.FastLaneSwitchManager.renderWorld();
        }

        dev.aether.modules.metaldetector.MetalDetectorSolver.renderWorld();
    }

    private static void renderRewarpPair(LevelRenderContext ctx, Minecraft mc,
                                         MultiBufferSource.BufferSource textBuffer,
                                         RewarpPointPair pair) {
        if (pair.highlightStart && pair.hasStart()) {
            renderRewarpPoint(ctx, mc, textBuffer, pair.startX, pair.startY, pair.startZ,
                    pair.displayName() + " Start",
                    ARGB.color(200, 50, 255, 100),
                    ARGB.color(40, 50, 255, 100));
        }
        if (pair.highlightEnd && pair.hasEnd()) {
            renderRewarpPoint(ctx, mc, textBuffer, pair.endX, pair.endY, pair.endZ,
                    pair.displayName() + " End",
                    ARGB.color(200, 255, 50, 50),
                    ARGB.color(40, 255, 50, 50));
        }
    }

    private static void renderRewarpPoint(LevelRenderContext ctx, Minecraft mc,
                                          MultiBufferSource.BufferSource textBuffer,
                                          double x, double y, double z,
                                          String label, int strokeColor, int fillColor) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);

        renderBlockHighlight(ctx, mc, textBuffer,
                new AABB(ix, iy, iz, ix + 1, iy + 1, iz + 1),
                label,
                strokeColor,
                fillColor,
                2.0f);
    }

    private static void renderBlockHighlight(LevelRenderContext ctx, Minecraft mc,
                                             MultiBufferSource.BufferSource textBuffer,
                                             AABB box, String label, int strokeColor,
                                             int fillColor, float lineWidth) {
        GizmoStyle style = GizmoStyle.strokeAndFill(strokeColor, lineWidth, fillColor);
        var props = Gizmos.cuboid(box, style);
        props.setAlwaysOnTop();

        if (label != null && !label.isBlank()) {
            renderBillboardText(ctx, mc, textBuffer, label,
                    (box.minX + box.maxX) * 0.5,
                    box.maxY + 0.35,
                    (box.minZ + box.maxZ) * 0.5);
        }
    }

    private static void renderBillboardText(LevelRenderContext ctx, Minecraft mc,
                                            MultiBufferSource.BufferSource textBuffer,
                                            String text, double x, double y, double z) {
        if (ctx == null || ctx.poseStack() == null || textBuffer == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        PoseStack poseStack = ctx.poseStack();
        Vec3 cameraPos = camera.position();
        Font font = mc.font;
        float scale = 0.025f;

        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();
        matrix.translate((float) x, (float) y, (float) z)
                .translate((float) -cameraPos.x, (float) -cameraPos.y, (float) -cameraPos.z)
                .rotate(camera.rotation())
                .scale(scale, -scale, scale);
        float textX = -font.width(text) / 2.0f;
        font.drawInBatch(text, textX, 0.0f, 0xFFFFFFFF, true, matrix, textBuffer,
                Font.DisplayMode.SEE_THROUGH, 0, 0x00F000F0);
        poseStack.popPose();
    }
}
