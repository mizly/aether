package dev.aether.renderer

import com.mojang.blaze3d.vertex.PoseStack
import dev.aether.config.AetherConfig
import dev.aether.config.FarmWaypoint
import dev.aether.config.FarmWaypoints
import dev.aether.config.RewarpPointPair
import dev.aether.config.RewarpPointPairs
import dev.aether.macro.MacroState
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.util.ClientUtils
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

class PositionHighlighter private constructor() {
    companion object {
        private var labelsPass = false
        private fun showDeskHighlight() = AetherConfig.PEST_HIGHLIGHT_DESK.get() && !AetherConfig.PEST_EXCHANGE_PATHFIND.get()
        private fun showTrapsHighlight() = AetherConfig.PEST_TRAPS_HIGHLIGHT.get() && !AetherConfig.PEST_TRAPS_PATHFIND.get()

        @JvmStatic fun hasVisibleHighlights(): Boolean {
            if (StreamerModeManager.isEnabled()) return false
            if (hasVisibleGardenHighlights()) return true
            return dev.aether.modules.metaldetector.MetalDetectorSolver.hasVisibleHighlights()
        }

        private fun hasVisibleGardenHighlights(): Boolean {
            val mc = Minecraft.getInstance()
            if (mc.level == null || mc.player == null || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) return false
            if (showDeskHighlight() || showTrapsHighlight() || AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.get()) return true
            for (pair in RewarpPointPairs.get()) if ((pair.highlightStart && pair.hasStart()) || (pair.highlightEnd && pair.hasEnd())) return true
            if (!dev.aether.modules.GreenhouseManager.highlightedSkulls.isEmpty()) return true
            val path = dev.aether.modules.GreenhouseManager.currentPath
            if (path != null && !path.isEmpty()) return true
            if (dev.aether.modules.GreenhouseManager.getCurrentTarget() != null) return true
            if (dev.aether.modules.GreenhouseManager.getCurrentAimTarget() != null) return true
            if (dev.aether.modules.farming.FastLaneSwitchManager.hasVisibleHighlights()) return true
            if (hasVisibleFarmWaypoints()) return true
            return dev.aether.modules.farming.BedrockPlotMaker.hasVisibleHighlights()
        }

        private fun hasVisibleFarmWaypoints(): Boolean {
            if ("CUSTOM" != AetherConfig.FARM_TYPE.get()) return false
            for (waypoint in FarmWaypoints.get()) if (waypoint.highlighted) return true
            return false
        }

        @JvmStatic fun collectLabels(ctx: LevelRenderContext) {
            labelsPass = true
            try { renderWorld(ctx) } finally { labelsPass = false }
        }

        @JvmStatic fun renderWorld(ctx: LevelRenderContext) {
            val mc = Minecraft.getInstance()
            if (StreamerModeManager.isEnabled() || mc.level == null || mc.player == null || !hasVisibleHighlights()) return
            val inGarden = ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
            if (inGarden) {
                val textBuffer = if (labelsPass) ctx.submitNodeCollector() else null
                if (showDeskHighlight()) {
                    val x = AetherConfig.PEST_EXCHANGE_DESK_X.get(); val y = AetherConfig.PEST_EXCHANGE_DESK_Y.get(); val z = AetherConfig.PEST_EXCHANGE_DESK_Z.get()
                    renderBlockHighlight(ctx, mc, textBuffer, AABB(x.toDouble(), y.toDouble(), z.toDouble(), (x + 1).toDouble(), (y + 1).toDouble(), (z + 1).toDouble()), "Pest Desk", ARGB.color(200, 255, 210, 0), ARGB.color(40, 255, 210, 0), 2f)
                }
                if (showTrapsHighlight()) {
                    val x = AetherConfig.PEST_TRAPS_X.get(); val y = AetherConfig.PEST_TRAPS_Y.get(); val z = AetherConfig.PEST_TRAPS_Z.get()
                    renderBlockHighlight(ctx, mc, textBuffer, AABB(x.toDouble(), y.toDouble(), z.toDouble(), (x + 1).toDouble(), (y + 1).toDouble(), (z + 1).toDouble()), "Pest Traps", ARGB.color(200, 90, 220, 90), ARGB.color(40, 90, 220, 90), 2f)
                }
                if (AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.get()) {
                    val x = AetherConfig.AUTO_COMPOSTER_X.get(); val y = AetherConfig.AUTO_COMPOSTER_Y.get(); val z = AetherConfig.AUTO_COMPOSTER_Z.get()
                    renderBlockHighlight(ctx, mc, textBuffer, AABB(x.toDouble(), y.toDouble(), z.toDouble(), (x + 1).toDouble(), (y + 1).toDouble(), (z + 1).toDouble()), "Composter", ARGB.color(200, 90, 230, 120), ARGB.color(40, 90, 230, 120), 2f)
                }
                for (pair in RewarpPointPairs.get()) renderRewarpPair(ctx, mc, textBuffer, pair)
                for (box in dev.aether.modules.GreenhouseManager.highlightedSkulls) renderBlockHighlight(ctx, mc, textBuffer, box, "Greenhouse Skull", ARGB.color(200, 255, 100, 255), ARGB.color(40, 255, 100, 255), 2f)
                val path = dev.aether.modules.GreenhouseManager.currentPath
                if (path != null && !path.isEmpty()) {
                    val lineArgb = ARGB.color(217, 255, 100, 255)
                    for (i in 0 until path.size - 1) Gizmos.line(path[i], path[i + 1], lineArgb, 2f).setAlwaysOnTop()
                }
                val target = dev.aether.modules.GreenhouseManager.getCurrentTarget()
                if (target != null) renderBlockHighlight(ctx, mc, textBuffer, AABB(target.x - 0.3, target.y - 0.3, target.z - 0.3, target.x + 0.3, target.y + 0.3, target.z + 0.3), "Greenhouse Target", ARGB.color(255, 0, 255, 0), ARGB.color(60, 0, 255, 0), 2.5f)
                val aimTarget = dev.aether.modules.GreenhouseManager.getCurrentAimTarget()
                if (aimTarget != null) renderBlockHighlight(ctx, mc, textBuffer, AABB(aimTarget.x - 0.05, aimTarget.y - 0.05, aimTarget.z - 0.05, aimTarget.x + 0.05, aimTarget.y + 0.05, aimTarget.z + 0.05), "Greenhouse Aim", ARGB.color(255, 255, 255, 0), ARGB.color(90, 255, 255, 0), 2f)
                dev.aether.modules.farming.FastLaneSwitchManager.renderWorld()
                renderFarmWaypoints(ctx, mc, textBuffer)
                if (!labelsPass) dev.aether.modules.farming.BedrockPlotMaker.renderWorld()
            }
            if (!labelsPass) dev.aether.modules.metaldetector.MetalDetectorSolver.renderWorld()
        }

        private fun renderRewarpPair(ctx: LevelRenderContext, mc: Minecraft, textBuffer: SubmitNodeCollector?, pair: RewarpPointPair) {
            if (pair.highlightStart && pair.hasStart()) renderRewarpPoint(ctx, mc, textBuffer, pair.startX, pair.startY, pair.startZ, pair.displayName() + " Start", ARGB.color(200, 50, 255, 100), ARGB.color(40, 50, 255, 100))
            if (pair.highlightEnd && pair.hasEnd()) renderRewarpPoint(ctx, mc, textBuffer, pair.endX, pair.endY, pair.endZ, pair.displayName() + " End", ARGB.color(200, 255, 50, 50), ARGB.color(40, 255, 50, 50))
        }

        private fun renderFarmWaypoints(ctx: LevelRenderContext, mc: Minecraft, textBuffer: SubmitNodeCollector?) {
            if (!hasVisibleFarmWaypoints()) return
            val waypoints = FarmWaypoints.get()
            for (i in waypoints.indices) {
                val waypoint: FarmWaypoint = waypoints[i]
                if (!waypoint.highlighted) continue
                val x = Math.floor(waypoint.x).toInt(); val y = Math.floor(waypoint.y).toInt(); val z = Math.floor(waypoint.z).toInt()
                renderBlockHighlight(ctx, mc, textBuffer, AABB(x.toDouble(), y.toDouble(), z.toDouble(), (x + 1).toDouble(), (y + 1).toDouble(), (z + 1).toDouble()), "Farm Waypoint #${i + 1} - ${waypoint.movementLabel()}", ARGB.color(210, 80, 220, 255), ARGB.color(45, 80, 220, 255), 2f)
            }
        }

        private fun renderRewarpPoint(ctx: LevelRenderContext, mc: Minecraft, textBuffer: SubmitNodeCollector?, x: Double, y: Double, z: Double, label: String, strokeColor: Int, fillColor: Int) {
            val ix = Math.floor(x).toInt(); val iy = Math.floor(y).toInt(); val iz = Math.floor(z).toInt()
            renderBlockHighlight(ctx, mc, textBuffer, AABB(ix.toDouble(), iy.toDouble(), iz.toDouble(), (ix + 1).toDouble(), (iy + 1).toDouble(), (iz + 1).toDouble()), label, strokeColor, fillColor, 2f)
        }

        private fun renderBlockHighlight(ctx: LevelRenderContext?, mc: Minecraft, textBuffer: SubmitNodeCollector?, box: AABB, label: String?, strokeColor: Int, fillColor: Int, lineWidth: Float) {
            if (!labelsPass) Gizmos.cuboid(box, GizmoStyle.strokeAndFill(strokeColor, lineWidth, fillColor)).setAlwaysOnTop()
            if (label != null && label.isNotBlank()) renderBillboardText(ctx, mc, textBuffer, label, (box.minX + box.maxX) * 0.5, box.maxY + 0.35, (box.minZ + box.maxZ) * 0.5)
        }

        private fun renderBillboardText(ctx: LevelRenderContext?, mc: Minecraft, textBuffer: SubmitNodeCollector?, text: String, x: Double, y: Double, z: Double) {
            if (ctx == null || ctx.poseStack() == null || textBuffer == null) return
            val camera: Camera = mc.gameRenderer.mainCamera()
            val poseStack: PoseStack = ctx.poseStack()!!
            val cameraPos: Vec3 = camera.position()
            val font: Font = mc.font
            val scale = 0.025f
            poseStack.pushPose()
            poseStack.translate((x - cameraPos.x).toFloat(), (y - cameraPos.y).toFloat(), (z - cameraPos.z).toFloat())
            poseStack.mulPose(org.joml.Matrix4f().rotation(camera.rotation()))
            poseStack.scale(scale, -scale, scale)
            val textX = -font.width(text).toFloat() / 2.0f
            textBuffer.submitText(poseStack, textX, 0f, Component.literal(text).visualOrderText, true, Font.DisplayMode.SEE_THROUGH, 0x00F000F0, 0xFFFFFFFF.toInt(), 0, 0)
            poseStack.popPose()
        }
    }
}
