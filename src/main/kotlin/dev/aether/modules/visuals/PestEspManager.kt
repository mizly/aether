package dev.aether.modules.visuals

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.PestTargetTracker
import dev.aether.modules.pest.helpers.PestDisplayTracker
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NanoVGManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.ArrayList
import java.util.List

class PestEspManager private constructor() {
    companion object {
        private const val TRACER_EDGE_MARGIN = 20.0f
        @JvmStatic fun hasVisibleHighlights(): Boolean = AetherConfig.PEST_ESP_ENABLED.get()
            && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
            && (AetherConfig.PEST_ESP_HIGHLIGHT.get() || AetherConfig.PEST_ESP_TRACER.get() || AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.get())

        @JvmStatic fun renderWorld() {
            val client = Minecraft.getInstance()
            if (client == null || client.level == null || client.player == null || StreamerModeManager.isEnabled() || !hasVisibleHighlights()) return
            for (pest in getRenderablePests(client)) {
                val rgb = pestColor(AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get())
                if (AetherConfig.PEST_ESP_HIGHLIGHT.get() && !usesGlow())
                    Gizmos.cuboid(pest.box, GizmoStyle.strokeAndFill(argb(220, rgb), 2.0f, argb(45, rgb))).setAlwaysOnTop()
            }
        }

        @JvmStatic fun renderTracerOverlay() {
            val client = Minecraft.getInstance()
            if (client == null || client.level == null || client.player == null || client.gui.screen() != null
                || StreamerModeManager.isEnabled() || !AetherConfig.PEST_ESP_ENABLED.get()
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) return
            val tracer = AetherConfig.PEST_ESP_TRACER.get()
            val optimizedRoute = AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.get()
            if (!tracer && !optimizedRoute) return
            val pests = if (tracer) getRenderablePests(client) else List.of<PestData>()
            val route = if (optimizedRoute && PestDestroyer.isActive()) PestDestroyer.getPlannedPestRoute(client) else List.of<Entity>()
            if (pests.isEmpty() && route.isEmpty()) return
            val camera = client.gameRenderer.mainCamera()
            val cameraPosition = camera.position()
            val viewProjection = camera.getViewRotationProjectionMatrix(Matrix4f())
            val width = client.window.guiScaledWidth.toFloat()
            val height = client.window.guiScaledHeight.toFloat()
            if (!NanoVGManager.ensureInitialized()) return
            NanoVGManager.beginFrame(width, height)
            val renderer = NanoVGManager.getRenderer() ?: run { NanoVGManager.endFrame(); return }
            try {
                val startX = width * 0.5f
                val startY = height * 0.5f
                if (tracer) {
                    val tracerColor = argb(255, pestColor(AetherConfig.PEST_ESP_TRACER_COLOR.get()))
                    for (pest in pests) projectToScreen(pest.position, cameraPosition, viewProjection, width, height)?.let {
                        renderer.line(startX, startY, it.x, it.y, 2.0f, tracerColor)
                    }
                }
                if (route.isNotEmpty()) renderOptimizedRouteOverlay(route, cameraPosition, viewProjection, width, height, renderer)
            } finally { NanoVGManager.endFrame() }
        }

        private fun renderOptimizedRouteOverlay(route: kotlin.collections.List<Entity>, cameraPosition: Vec3, viewProjection: Matrix4f, width: Float, height: Float, renderer: NVGRenderer) {
            val routeColor = argb(255, pestColor(AetherConfig.PEST_ESP_OPTIMIZED_ROUTE_COLOR.get()))
            val firstEntity = route.firstOrNull { it != null && !it.isRemoved && !isDead(it) } ?: return
            projectFront(firstEntity.position(), cameraPosition, viewProjection, width, height)?.let { renderer.line(width * 0.5f, height * 0.5f, it.x, it.y, 2.0f, routeColor) }
            var previous = firstEntity
            var passedFirst = false
            for (next in route) {
                if (next == null || next.isRemoved || isDead(next)) continue
                if (!passedFirst) { if (next.id == firstEntity.id) passedFirst = true; continue }
                val from = projectFront(previous.position(), cameraPosition, viewProjection, width, height)
                val to = projectFront(next.position(), cameraPosition, viewProjection, width, height)
                if (from != null && to != null) renderer.line(from.x, from.y, to.x, to.y, 2.0f, routeColor)
                previous = next
            }
        }

        private fun projectFront(position: Vec3, cameraPosition: Vec3, viewProjection: Matrix4f, width: Float, height: Float): ScreenPoint? {
            val clip = Vector4f((position.x - cameraPosition.x).toFloat(), (position.y - cameraPosition.y).toFloat(), (position.z - cameraPosition.z).toFloat(), 1.0f).mul(viewProjection)
            if (clip.w <= 0.001f) return null
            return ScreenPoint((clip.x / clip.w + 1.0f) * 0.5f * width, (1.0f - clip.y / clip.w) * 0.5f * height)
        }
        private fun usesGlow(): Boolean = "GLOW".equals(AetherConfig.PEST_ESP_MODE.get(), ignoreCase = true)
        @JvmStatic fun outlineColor(entity: Entity): Int {
            if (!usesGlow() || !hasVisibleHighlights() || !AetherConfig.PEST_ESP_HIGHLIGHT.get() || StreamerModeManager.isEnabled()) return 0
            val client = Minecraft.getInstance()
            if (client == null || client.level == null || client.player == null) return 0
            for (pest in PestDisplayTracker.getPests(client)) if (pest.skull() == entity && !entity.isRemoved) return argb(255, pestColor(AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get()))
            return 0
        }
        private fun getRenderablePests(client: Minecraft): kotlin.collections.List<PestData> {
            val pests = ArrayList<PestData>()
            val level = client.level ?: return pests
            for (entity in PestTargetTracker.getLoadedPestMobs(client)) {
                if (entity == null || entity.isRemoved || isDead(entity)) continue
                val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity))
                val partial = partialTick.toDouble()
                val position = Vec3(net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.x), net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.y), net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.z))
                val box = entity.boundingBox.move(position.subtract(entity.position()))
                pests.add(PestData(box.center, box))
            }
            return pests
        }
        private fun projectToScreen(position: Vec3, cameraPosition: Vec3, viewProjection: Matrix4f, width: Float, height: Float): ScreenPoint {
            val clip = Vector4f((position.x - cameraPosition.x).toFloat(), (position.y - cameraPosition.y).toFloat(), (position.z - cameraPosition.z).toFloat(), 1.0f).mul(viewProjection)
            if (clip.w > 0.001f) return ScreenPoint((clip.x / clip.w + 1.0f) * 0.5f * width, (1.0f - clip.y / clip.w) * 0.5f * height)
            val depth = Math.max(Math.abs(clip.w), 0.001f)
            val directionX = clip.x / depth
            var directionY = -clip.y / depth
            if (Math.abs(directionX) < 0.001f && Math.abs(directionY) < 0.001f) directionY = 1.0f
            val centerX = width * 0.5f; val centerY = height * 0.5f
            val margin = Math.min(TRACER_EDGE_MARGIN, Math.min(width, height) * 0.1f)
            val availableX = Math.max(1.0f, centerX - margin); val availableY = Math.max(1.0f, centerY - margin)
            val scale = 1.0f / Math.max(Math.abs(directionX) / availableX, Math.abs(directionY) / availableY)
            return ScreenPoint(centerX + directionX * scale, centerY + directionY * scale)
        }
        private fun pestColor(value: Int): Int = value and 0x00FFFFFF
        private fun isDead(entity: Entity): Boolean = entity is LivingEntity && entity.isDeadOrDying
        private fun argb(alpha: Int, rgb: Int): Int = ARGB.color(alpha, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        private class PestData(val position: Vec3, val box: AABB)
        private class ScreenPoint(val x: Float, val y: Float)
    }
}
