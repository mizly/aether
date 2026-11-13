package dev.aether.renderer

import com.mojang.blaze3d.vertex.VertexConsumer

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.FreecamManager
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.ui.theme.Theme
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.Vec3

class HatRenderer private constructor() {
    companion object {
        private const val LINE_WIDTH = 2.0f
        private const val LINE_ALPHA = 230
        private const val FILL_ALPHA = 90

        @JvmStatic fun hasVisibleEffect(): Boolean {
            if (StreamerModeManager.isEnabled()) {
                return false
            }
            return AetherConfig.HAT_ENABLED.get() && shouldRender(Minecraft.getInstance())
        }

        @JvmStatic fun render(ctx: LevelRenderContext) {
            if (StreamerModeManager.isEnabled()) return
            if (!AetherConfig.HAT_ENABLED.get()) return

            val mc = Minecraft.getInstance()
            if (mc.level == null || mc.player == null || !shouldRender(mc)) return

            val vertices = Math.max(3, Math.min(20, AetherConfig.HAT_VERTICES.get()))
            val radius = AetherConfig.HAT_RADIUS.get().toDouble()
            val height = AetherConfig.HAT_HEIGHT.get().toDouble()
            val yOffset = AetherConfig.HAT_Y_OFFSET.get().toDouble()
            val partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)

            val playerPos = mc.player!!.getPosition(partialTick)
            val baseY = playerPos.y + mc.player!!.getBbHeight() + yOffset
            val center = Vec3(playerPos.x, baseY, playerPos.z)
            val apex = center.add(0.0, height, 0.0)
            val cameraPos = mc.gameRenderer.mainCamera().position()
            val time = (System.currentTimeMillis() % 10_000L).toFloat() / 10_000.0f

            var firstPoint: Vec3? = null
            var previousPoint: Vec3? = null
            for (i in 0 until vertices) {
                val angle = (Math.PI * 2.0 * i) / vertices.toDouble()
                val point = Vec3(
                    center.x + Math.cos(angle) * radius,
                    center.y,
                    center.z + Math.sin(angle) * radius)

                if (previousPoint != null) {
                    drawFace(ctx, cameraPos, apex, previousPoint, point, time, i / vertices.toFloat())
                    drawLine(ctx, cameraPos, previousPoint, point, time, i / vertices.toFloat())
                } else {
                    firstPoint = point
                }

                drawLine(ctx, cameraPos, apex, point, time, (i + 0.5f) / vertices)
                previousPoint = point
            }

            if (previousPoint != null && firstPoint != null) {
                drawFace(ctx, cameraPos, apex, previousPoint, firstPoint, time, 1.0f)
                drawLine(ctx, cameraPos, previousPoint, firstPoint, time, 1.0f)
            }
        }

        private fun drawLine(ctx: LevelRenderContext, cameraPos: Vec3, from: Vec3, to: Vec3, time: Float, offset: Float) {
            val color = Theme.getRainbowColor(time, offset, LINE_ALPHA)
            val fromRelative = from.subtract(cameraPos)
            val toRelative = to.subtract(cameraPos)
            val direction = to.subtract(from)
            val length = direction.length()
            val normalX = if (length > 1.0E-6) (direction.x / length).toFloat() else 0.0f
            val normalY = if (length > 1.0E-6) (direction.y / length).toFloat() else 1.0f
            val normalZ = if (length > 1.0E-6) (direction.z / length).toFloat() else 0.0f

            submit(ctx, RenderTypes.lines()) { buffer ->
                addColoredLineVertex(buffer, fromRelative, color, normalX, normalY, normalZ)
                addColoredLineVertex(buffer, toRelative, color, normalX, normalY, normalZ)
            }
        }

        private fun drawFace(ctx: LevelRenderContext, cameraPos: Vec3, apex: Vec3, left: Vec3, right: Vec3, time: Float,
                offset: Float) {
            if (!AetherConfig.HAT_FILLED.get()) return

            val color = Theme.getRainbowColor(time, offset, FILL_ALPHA)
            val a = apex.subtract(cameraPos)
            val l = left.subtract(cameraPos)
            val r = right.subtract(cameraPos)
            submit(ctx, RenderTypes.debugTriangleFan()) { buffer ->
                addColoredVertex(buffer, a, color)
                addColoredVertex(buffer, l, color)
                addColoredVertex(buffer, r, color)
            }
        }

        // 26.2 removed MultiBufferSource from the level render context; geometry is now queued
        // through the SubmitNodeCollector and drawn by the feature dispatcher.
        private fun submit(ctx: LevelRenderContext, type: RenderType, writer: java.util.function.Consumer<VertexConsumer>) {
            val collector: SubmitNodeCollector = ctx.submitNodeCollector() ?: return
            if (ctx.poseStack() == null) return
            collector.submitCustomGeometry(ctx.poseStack(), type) { pose, buffer -> writer.accept(buffer) }
        }

        private fun shouldRender(mc: Minecraft): Boolean {
            return FreecamManager.isEnabled()
                    || !mc.options.getCameraType().isFirstPerson()
                    || AetherConfig.HAT_RENDER_FIRST_PERSON.get()
        }

        private fun addColoredVertex(buffer: VertexConsumer, point: Vec3, color: Int) {
            buffer.addVertex(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                .setColor(color)
        }

        private fun addColoredLineVertex(buffer: VertexConsumer, point: Vec3, color: Int, normalX: Float, normalY: Float,
                normalZ: Float) {
            buffer.addVertex(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                .setColor(color)
                .setNormal(normalX, normalY, normalZ)
                .setLineWidth(LINE_WIDTH)
        }
    }
}
