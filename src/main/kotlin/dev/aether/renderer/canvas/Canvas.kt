package dev.aether.renderer.canvas

import com.mojang.blaze3d.ProjectionType
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.MeshData
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.StagedVertexBuffer
import org.joml.Matrix3x2f
import org.joml.Matrix4f
import org.joml.Vector2f
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Objects
import java.util.Optional
import org.joml.Vector4fc
import java.util.OptionalDouble

// backend-neutral replacement for the nanovg gl3 context: paths are tessellated on the cpu with a
// one-pixel alpha fringe and drawn through mc's own gpu api, so vulkan and opengl look the same
class Canvas : AutoCloseable {
    companion object {
        private const val TESS_TOLERANCE = 0.25f
        private const val MAX_MITER = 4f

        @JvmStatic
        fun scaleAlpha(argb: Int, alpha: Float): Int {
            if (alpha >= 1f) return argb
            val a = Math.round(((argb ushr 24) and 0xFF) * Math.max(0f, alpha))
            return (a shl 24) or (argb and 0x00FFFFFF)
        }

        @JvmStatic
        fun lerpColor(from: Int, to: Int, t: Float): Int {
            if (t <= 0f) return from
            if (t >= 1f) return to
            val a = lerp((from ushr 24) and 0xFF, (to ushr 24) and 0xFF, t)
            val r = lerp((from shr 16) and 0xFF, (to shr 16) and 0xFF, t)
            val g = lerp((from shr 8) and 0xFF, (to shr 8) and 0xFF, t)
            val b = lerp(from and 0xFF, to and 0xFF, t)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun lerp(a: Int, b: Int, t: Float): Int = Math.round(a + (b - a) * t)

        internal fun signedArea(p: FloatArray): Float {
            val n = p.size / 2
            var area = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
            }
            return area * 0.5f
        }

        // per-vertex offset whose projection onto both adjacent edge normals is 1; outward for fills
        internal fun miterOffsets(p: FloatArray, closed: Boolean, side: Float): FloatArray {
            val n = p.size / 2
            val normals = FloatArray(n * 2)
            val edges = if (closed) n else n - 1
            for (i in 0 until edges) {
                val j = (i + 1) % n
                val dx = p[j * 2] - p[i * 2]
                val dy = p[j * 2 + 1] - p[i * 2 + 1]
                var len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (len < 1e-6f) len = 1e-6f
                normals[i * 2] = side * dy / len
                normals[i * 2 + 1] = -side * dx / len
            }
            val offsets = FloatArray(n * 2)
            for (i in 0 until n) {
                val nx0: Float
                val ny0: Float
                val nx1: Float
                val ny1: Float
                if (closed) {
                    val prev = (i - 1 + n) % n
                    nx0 = normals[prev * 2]
                    ny0 = normals[prev * 2 + 1]
                    nx1 = normals[i * 2]
                    ny1 = normals[i * 2 + 1]
                } else {
                    val prev = Math.max(0, i - 1)
                    val next = Math.min(edges - 1, i)
                    nx0 = normals[prev * 2]
                    ny0 = normals[prev * 2 + 1]
                    nx1 = normals[next * 2]
                    ny1 = normals[next * 2 + 1]
                }
                var mx = (nx0 + nx1) * 0.5f
                var my = (ny0 + ny1) * 0.5f
                val d2 = mx * mx + my * my
                val scale = if (d2 > 1e-6f) Math.min(1f / d2, MAX_MITER) else 1f
                if (d2 <= 1e-6f) {
                    mx = nx1
                    my = ny1
                }
                offsets[i * 2] = mx * scale
                offsets[i * 2 + 1] = my * scale
            }
            return offsets
        }

        internal fun side(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
            (bx - ax) * (py - ay) - (by - ay) * (px - ax)

        internal fun containsAll(poly: FloatArray, pts: FloatArray): Boolean {
            val s = Math.signum(signedArea(poly))
            val n = poly.size / 2
            var p = 0
            while (p < pts.size) {
                for (e in 0 until n) {
                    val j = (e + 1) % n
                    if (side(poly[e * 2], poly[e * 2 + 1], poly[j * 2], poly[j * 2 + 1], pts[p], pts[p + 1]) * s < -1e-3f) return false
                }
                p += 2
            }
            return true
        }

        internal fun roundedContour(cx: Float, cy: Float, hxValue: Float, hyValue: Float, r: Float, perCorner: Int): FloatArray {
            val hx = Math.max(hxValue, 0f)
            val hy = Math.max(hyValue, 0f)
            val rr = Math.max(0f, Math.min(r, Math.min(hx, hy)))
            val pts = FloatArray(4 * (perCorner + 1) * 2)
            val centers = arrayOf(floatArrayOf(cx + hx - rr, cy - hy + rr), floatArrayOf(cx + hx - rr, cy + hy - rr),
                floatArrayOf(cx - hx + rr, cy + hy - rr), floatArrayOf(cx - hx + rr, cy - hy + rr))
            var idx = 0
            for (c in 0 until 4) {
                val start = -Math.PI / 2 + c * Math.PI / 2
                for (i in 0..perCorner) {
                    val a = start + (Math.PI / 2) * i / perCorner
                    pts[idx++] = centers[c][0] + Math.cos(a).toFloat() * rr
                    pts[idx++] = centers[c][1] + Math.sin(a).toFloat() * rr
                }
            }
            return pts
        }
    }

    internal val atlas = UiAtlas()
    internal val fonts = CanvasFonts(atlas)
    private val vertexBuffer = StagedVertexBuffer({ "Aether UI" }, 1 shl 18)
    private val projection = Projection()
    private val projectionBuffer = ProjectionMatrixBuffer("aether ui")
    private val batches: MutableList<Batch> = ArrayList()
    private var current: Batch? = null
    private val stack: ArrayDeque<State> = ArrayDeque()
    private var state = State()
    private var frameWidth = 0f
    private var frameHeight = 0f
    private var pxRatio = 1f

    private data class Batch(val pipeline: RenderPipeline, val textures: TextureSetup, val scissor: Scissor?, val draw: StagedVertexBuffer.Draw)

    internal class Scissor(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun intersect(other: Scissor?): Scissor {
            if (other == null) return this
            return Scissor(Math.max(x0, other.x0), Math.max(y0, other.y0), Math.min(x1, other.x1), Math.min(y1, other.y1))
        }

        override fun equals(other: Any?): Boolean = other is Scissor && x0 == other.x0 && y0 == other.y0 && x1 == other.x1 && y1 == other.y1
        override fun hashCode(): Int = Objects.hash(x0, y0, x1, y1)
    }

    private class State {
        val xform = Matrix3x2f()
        var alpha = 1f
        var scissor: Scissor? = null

        fun copy(): State {
            val s = State()
            s.xform.set(xform)
            s.alpha = alpha
            s.scissor = scissor
            return s
        }
    }

    // -- frame -----------------------------------------------------------------

    fun beginFrame(width: Float, height: Float, pxRatio: Float) {
        discardPending()
        frameWidth = width
        frameHeight = height
        this.pxRatio = if (pxRatio > 0f) pxRatio else 1f
        stack.clear()
        state = State()
        atlas.beginFrame()
    }

    fun endFrame(target: GpuTextureView?) {
        try {
            if (target != null && batches.isNotEmpty()) draw(target)
        } finally {
            batches.clear()
            current = null
            vertexBuffer.endDraw()
            vertexBuffer.endFrame()
        }
    }

    // a frame that threw halfway still owns vertex data, which upload() has to consume before reuse
    private fun discardPending() {
        if (batches.isEmpty()) return
        vertexBuffer.upload()
        vertexBuffer.endDraw()
        vertexBuffer.endFrame()
        batches.clear()
        current = null
    }

    private fun draw(target: GpuTextureView) {
        vertexBuffer.upload()
        val savedProjection = RenderSystem.getProjectionMatrixBuffer()
        val savedType = RenderSystem.getProjectionType()
        // same ortho setup as GuiRenderer, just sized to the canvas instead of the gui scale
        projection.setupOrtho(1000f, 11000f, frameWidth, frameHeight, true)
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC)
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(Matrix4f().setTranslation(0f, 0f, -11000f))
        val targetWidth = target.getWidth(0)
        val targetHeight = target.getHeight(0)
        try {
            RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "Aether UI" }, target, Optional.empty<Vector4fc>(), null, OptionalDouble.empty()).use { pass ->
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("DynamicTransforms", transforms)
                for (batch in batches) {
                    val info = vertexBuffer.getExecuteInfo(batch.draw) ?: continue
                    if (batch.scissor != null) {
                        if (!applyScissor(pass, batch.scissor, targetWidth, targetHeight)) continue
                    } else pass.disableScissor()
                    pass.setPipeline(RenderSystem.getCompiledPipeline(batch.pipeline))
                    pass.setVertexBuffer(0, info.vertexBuffer().slice())
                    val t = batch.textures
                    if (t.texure0() != null) pass.setUniform("Sampler0", t.texure0(), t.sampler0())
                    if (t.texure1() != null) pass.setUniform("Sampler1", t.texure1(), t.sampler1())
                    if (t.texure2() != null) pass.setUniform("Sampler2", t.texure2(), t.sampler2())
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType())
                    pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
                }
            }
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection ?: return, savedType)
        }
    }

    // render pass scissors are in framebuffer pixels with a bottom-left origin, like GuiRenderer's
    // sub-pixel clips round to zero, which RenderPass rejects, so those batches are skipped instead
    private fun applyScissor(pass: RenderPass, s: Scissor, targetWidth: Int, targetHeight: Int): Boolean {
        val sx = targetWidth / frameWidth
        val sy = targetHeight / frameHeight
        val left = Math.max(0, Math.round(s.x0 * sx))
        val top = Math.max(0, Math.round(s.y0 * sy))
        val right = Math.min(targetWidth, Math.round(s.x1 * sx))
        val bottom = Math.min(targetHeight, Math.round(s.y1 * sy))
        if (right <= left || bottom <= top) return false
        pass.enableScissor(left, targetHeight - bottom, right - left, bottom - top)
        return true
    }

    override fun close() {
        discardPending()
        vertexBuffer.close()
        projectionBuffer.close()
        fonts.close()
        atlas.close()
    }

    fun pxRatio(): Float = pxRatio

    // -- state -----------------------------------------------------------------

    fun save() { stack.push(state.copy()) }
    fun restore() { if (!stack.isEmpty()) state = stack.pop() }
    fun translate(x: Float, y: Float) { state.xform.translate(x, y) }
    fun scale(x: Float, y: Float) { state.xform.scale(x, y) }
    fun skewX(radians: Float) { state.xform.mul(Matrix3x2f(1f, 0f, Math.tan(radians.toDouble()).toFloat(), 1f, 0f, 0f)) }
    // a x b c y d, the same argument order as nvgTransform
    fun transform(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) { state.xform.mul(Matrix3x2f(a, b, c, d, e, f)) }
    fun globalAlpha(alpha: Float) { state.alpha = alpha }

    // like nvgScissor the rect is in local space; rotations collapse to their bounding box
    fun scissor(x: Float, y: Float, w: Float, h: Float) { state.scissor = transformedBounds(x, y, Math.max(0f, w), Math.max(0f, h)) }
    fun intersectScissor(x: Float, y: Float, w: Float, h: Float) {
        val next = transformedBounds(x, y, Math.max(0f, w), Math.max(0f, h))
        state.scissor = next.intersect(state.scissor)
    }
    fun resetScissor() { state.scissor = null }

    private fun transformedBounds(x: Float, y: Float, w: Float, h: Float): Scissor {
        val m = state.xform
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val corners = arrayOf(floatArrayOf(x, y), floatArrayOf(x + w, y), floatArrayOf(x + w, y + h), floatArrayOf(x, y + h))
        for (i in 0..3) {
            xs[i] = m.m00() * corners[i][0] + m.m10() * corners[i][1] + m.m20()
            ys[i] = m.m01() * corners[i][0] + m.m11() * corners[i][1] + m.m21()
        }
        return Scissor(Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3])), Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3])),
            Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3])), Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3])))
    }

    private fun averageScale(): Float {
        val m = state.xform
        val sx = Math.sqrt((m.m00() * m.m00() + m.m01() * m.m01()).toDouble()).toFloat()
        val sy = Math.sqrt((m.m10() * m.m10() + m.m11() * m.m11()).toDouble()).toFloat()
        return (sx + sy) * 0.5f
    }

    private fun fringe(): Float = 1f / pxRatio
    private fun tolerance(): Float = TESS_TOLERANCE / (pxRatio * Math.max(averageScale(), 1e-3f))

    // -- batching --------------------------------------------------------------

    private fun consumer(pipeline: RenderPipeline, textures: TextureSetup): VertexConsumer {
        val scissor = state.scissor
        val previous = current
        if (previous == null || previous.pipeline !== pipeline || !previous.textures.equals(textures) || !Objects.equals(previous.scissor, scissor)) {
            val format = pipeline.getVertexFormatBinding(0) ?: return vertexBuffer.getVertexBuilder(previous!!.draw)
            val draw = vertexBuffer.appendDraw(format, pipeline.getPrimitiveTopology())
            val batch = Batch(pipeline, textures, scissor, draw)
            current = batch
            batches.add(batch)
        }
        return vertexBuffer.getVertexBuilder(current!!.draw)
    }

    // also covers a collapsed transform, which has no inverse for fringe and paint maths
    private fun scissoredOut(): Boolean {
        val s = state.scissor
        if (s != null && (s.x1 <= s.x0 || s.y1 <= s.y0)) return true
        return Math.abs(state.xform.determinant()) < 1e-12f
    }

    private fun atlasTextures(page: UiAtlas.Page): TextureSetup = TextureSetup.singleTexture(page.view, atlas.sampler())

    // -- paints ----------------------------------------------------------------

    // colors and uvs are evaluated in local (pre-transform) space, like nanovg paints
    class Paint private constructor() {
        companion object {
            private const val SOLID = 0
            private const val LINEAR = 1
            private const val IMAGE = 2

            @JvmStatic fun solid(argb: Int): Paint = Paint().also { it.kind = SOLID; it.inner = argb }
            @JvmStatic fun linear(sx: Float, sy: Float, ex: Float, ey: Float, from: Int, to: Int): Paint = Paint().also {
                it.kind = LINEAR; it.inner = from; it.outer = to; it.sx = sx; it.sy = sy; it.dx = ex - sx; it.dy = ey - sy
                val len2 = it.dx * it.dx + it.dy * it.dy
                it.invLen2 = if (len2 > 1e-6f) 1f / len2 else 0f
            }
            // maps the uv rect onto the local rect x, y, w, h; tint multiplies the texel
            @JvmStatic fun image(textures: TextureSetup, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, tint: Int): Paint = Paint().also {
                it.kind = IMAGE; it.textures = textures; it.inner = tint; it.ox = x; it.oy = y; it.ow = if (w == 0f) 1f else w; it.oh = if (h == 0f) 1f else h
                it.u0 = u0; it.v0 = v0; it.u1 = u1; it.v1 = v1
            }
        }
        internal var kind = 0
        private var inner = 0
        private var outer = 0
        private var sx = 0f; private var sy = 0f; private var dx = 0f; private var dy = 0f; private var invLen2 = 0f
        internal var ox = 0f; internal var oy = 0f; internal var ow = 0f; internal var oh = 0f; internal var u0 = 0f; internal var v0 = 0f; internal var u1 = 0f; internal var v1 = 0f
        internal var textures: TextureSetup? = null
        fun color(x: Float, y: Float): Int {
            if (kind != LINEAR) return inner
            val t = ((x - sx) * dx + (y - sy) * dy) * invLen2
            return lerpColor(inner, outer, t)
        }
    }

    private fun paintTextures(paint: Paint, page: UiAtlas.Page): TextureSetup = if (paint.kind == 2) paint.textures!! else atlasTextures(page)

    // -- vertex helpers --------------------------------------------------------

    private fun vertex(c: VertexConsumer, paint: Paint, page: UiAtlas.Page, lx: Float, ly: Float, alpha: Float) {
        val m = state.xform
        val x = m.m00() * lx + m.m10() * ly + m.m20()
        val y = m.m01() * lx + m.m11() * ly + m.m21()
        val u: Float
        val v: Float
        if (paint.kind == 2) {
            u = paint.u0 + (lx - paint.ox) / paint.ow * (paint.u1 - paint.u0)
            v = paint.v0 + (ly - paint.oy) / paint.oh * (paint.v1 - paint.v0)
        } else { u = page.whiteU; v = page.whiteV }
        c.addVertex(x, y, 0f).setUv(u, v).setColor(scaleAlpha(paint.color(lx, ly), alpha * state.alpha))
    }

    private fun rawVertex(c: VertexConsumer, x: Float, y: Float, u: Float, v: Float, argb: Int) {
        c.addVertex(x, y, 0f).setUv(u, v).setColor(scaleAlpha(argb, state.alpha))
    }

    // -- paths -----------------------------------------------------------------
    class Path internal constructor(private val canvas: Canvas) {
        internal val subpaths: MutableList<FloatArray> = ArrayList()
        internal val closed: MutableList<Boolean> = ArrayList()
        private var pts = FloatArray(32)
        private var count = 0

        fun moveTo(x: Float, y: Float): Path { finishSubpath(false); add(x, y); return this }
        fun lineTo(x: Float, y: Float): Path { if (count == 0) return moveTo(x, y); add(x, y); return this }
        fun close(): Path { finishSubpath(true); return this }

        // dir: true walks clockwise in screen space, matching NVG_CW
        fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, clockwise: Boolean): Path {
            var da = a1 - a0
            val twoPi = (Math.PI * 2).toFloat()
            if (clockwise) {
                if (Math.abs(da) >= twoPi) da = twoPi else while (da < 0f) da += twoPi
            } else {
                if (Math.abs(da) >= twoPi) da = -twoPi else while (da > 0f) da -= twoPi
            }
            val segments = canvas.arcSegments(r, Math.abs(da))
            for (i in 0..segments) {
                val a = a0 + da * i / segments
                val x = cx + Math.cos(a.toDouble()).toFloat() * r
                val y = cy + Math.sin(a.toDouble()).toFloat() * r
                if (i == 0 && count == 0) add(x, y) else lineTo(x, y)
            }
            return this
        }

        fun rect(x: Float, y: Float, w: Float, h: Float): Path {
            moveTo(x, y); lineTo(x, y + h); lineTo(x + w, y + h); lineTo(x + w, y); return close()
        }

        fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float): Path {
            val rr = Math.min(Math.max(r, 0f), Math.min(Math.abs(w), Math.abs(h)) * 0.5f)
            if (rr < 0.1f) return rect(x, y, w, h)
            val hp = (Math.PI / 2).toFloat()
            finishSubpath(false)
            arc(x + rr, y + rr, rr, hp * 2, hp * 3, true)
            arc(x + w - rr, y + rr, rr, hp * 3, hp * 4, true)
            arc(x + w - rr, y + h - rr, rr, 0f, hp, true)
            arc(x + rr, y + h - rr, rr, hp, hp * 2, true)
            return close()
        }

        fun circle(cx: Float, cy: Float, r: Float): Path { finishSubpath(false); arc(cx, cy, r, 0f, (Math.PI * 2).toFloat(), true); return close() }

        private fun add(x: Float, y: Float) {
            if (count > 0) {
                val px = pts[count * 2 - 2]
                val py = pts[count * 2 - 1]
                if (Math.abs(px - x) < 1e-5f && Math.abs(py - y) < 1e-5f) return
            }
            if (count * 2 + 2 > pts.size) pts = java.util.Arrays.copyOf(pts, pts.size * 2)
            pts[count * 2] = x
            pts[count * 2 + 1] = y
            count++
        }

        private fun finishSubpath(close: Boolean) {
            if (count == 0) return
            var n = count
            var isClosed = close
            if (n > 1 && Math.abs(pts[0] - pts[n * 2 - 2]) < 1e-5f && Math.abs(pts[1] - pts[n * 2 - 1]) < 1e-5f) { n--; isClosed = true }
            subpaths.add(java.util.Arrays.copyOf(pts, n * 2))
            closed.add(isClosed)
            count = 0
        }

        internal fun done(): List<FloatArray> { finishSubpath(false); return subpaths }
    }

    fun path(): Path = Path(this)

    private fun arcSegments(r: Float, angle: Float): Int {
        val rpx = Math.abs(r) * pxRatio * Math.max(averageScale(), 1e-3f)
        if (rpx < 0.5f) return 1
        val step = 2 * Math.acos(Math.max(-1.0, Math.min(1.0, 1.0 - TESS_TOLERANCE / rpx)))
        val n = Math.ceil(angle / Math.max(step, 0.02)).toInt()
        return Math.max(1, Math.min(n, 256))
    }

    // every caller fills convex shapes (rects, rounded rects, circles), so each subpath is a fan
    fun fill(path: Path, paint: Paint) {
        if (scissoredOut()) return
        val subs = path.done()
        val page = atlas.currentPage()
        val c = consumer(CanvasPipelines.TEXTURED_TRIANGLES, paintTextures(paint, page))
        for (local in subs) if (local.size >= 6) fillConvex(c, local, paint, page)
    }

    private fun fillConvex(c: VertexConsumer, local: FloatArray, paint: Paint, page: UiAtlas.Page) {
        val n = local.size / 2
        val out = toOutput(local)
        val area = signedArea(out)
        if (Math.abs(area) < 1e-6f) return
        val f = fringe()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minX = Math.min(minX, out[i * 2]); maxX = Math.max(maxX, out[i * 2]); minY = Math.min(minY, out[i * 2 + 1]); maxY = Math.max(maxY, out[i * 2 + 1]) }
        val inv = Matrix3x2f(state.xform).invert()
        if (Math.min(maxX - minX, maxY - minY) < f * 2f) {
            for (i in 1 until n - 1) { vertex(c, paint, page, local[0], local[1], 1f); vertex(c, paint, page, local[i * 2], local[i * 2 + 1], 1f); vertex(c, paint, page, local[i * 2 + 2], local[i * 2 + 3], 1f) }
            return
        }
        val offsets = miterOffsets(out, true, if (area > 0) 1f else -1f)
        val innerLocal = FloatArray(n * 2); val outerLocal = FloatArray(n * 2); val tmp = Vector2f()
        for (i in 0 until n) {
            val ox = offsets[i * 2] * f * 0.5f; val oy = offsets[i * 2 + 1] * f * 0.5f
            inv.transformPosition(out[i * 2] - ox, out[i * 2 + 1] - oy, tmp); innerLocal[i * 2] = tmp.x; innerLocal[i * 2 + 1] = tmp.y
            inv.transformPosition(out[i * 2] + ox, out[i * 2 + 1] + oy, tmp); outerLocal[i * 2] = tmp.x; outerLocal[i * 2 + 1] = tmp.y
        }
        for (i in 1 until n - 1) { vertex(c, paint, page, innerLocal[0], innerLocal[1], 1f); vertex(c, paint, page, innerLocal[i * 2], innerLocal[i * 2 + 1], 1f); vertex(c, paint, page, innerLocal[i * 2 + 2], innerLocal[i * 2 + 3], 1f) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            quad(c, paint, page, innerLocal[i * 2], innerLocal[i * 2 + 1], 1f, outerLocal[i * 2], outerLocal[i * 2 + 1], 0f, outerLocal[j * 2], outerLocal[j * 2 + 1], 0f, innerLocal[j * 2], innerLocal[j * 2 + 1], 1f)
        }
    }

    private fun quad(c: VertexConsumer, paint: Paint, page: UiAtlas.Page, x0: Float, y0: Float, a0: Float, x1: Float, y1: Float, a1: Float, x2: Float, y2: Float, a2: Float, x3: Float, y3: Float, a3: Float) {
        vertex(c, paint, page, x0, y0, a0); vertex(c, paint, page, x1, y1, a1); vertex(c, paint, page, x2, y2, a2)
        vertex(c, paint, page, x0, y0, a0); vertex(c, paint, page, x2, y2, a2); vertex(c, paint, page, x3, y3, a3)
    }

    private fun toOutput(local: FloatArray): FloatArray {
        val m = state.xform; val out = FloatArray(local.size)
        for (i in local.indices step 2) { out[i] = m.m00() * local[i] + m.m10() * local[i + 1] + m.m20(); out[i + 1] = m.m01() * local[i] + m.m11() * local[i + 1] + m.m21() }
        return out
    }

    // -- stroke ----------------------------------------------------------------

    fun stroke(path: Path, paint: Paint, width: Float) {
        if (scissoredOut()) return
        val subs = path.done()
        val page = atlas.currentPage()
        val c = consumer(CanvasPipelines.TEXTURED_TRIANGLES, paintTextures(paint, page))
        val f = fringe()
        var w = width * averageScale()
        var alpha = 1f
        // nanovg fades hairlines instead of drawing them thinner than a pixel
        if (w < f) { alpha = Math.max(0f, w / f); w = f }
        val hw = w * 0.5f
        val inner = Math.max(hw - f * 0.5f, 0f)
        val outer = hw + f * 0.5f
        val inv = Matrix3x2f(state.xform).invert()
        for (s in subs.indices) {
            val local = subs[s]
            val closed = path.closed[s]
            val n = local.size / 2
            if (n < 2) continue
            val out = toOutput(local)
            val offsets = miterOffsets(out, closed, 1f)
            val rails = Array(4) { FloatArray(n * 2) }
            val railOffset = floatArrayOf(outer, inner, -inner, -outer)
            val tmp = Vector2f()
            for (i in 0 until n) for (r in 0..3) {
                inv.transformPosition(out[i * 2] + offsets[i * 2] * railOffset[r], out[i * 2 + 1] + offsets[i * 2 + 1] * railOffset[r], tmp)
                rails[r][i * 2] = tmp.x; rails[r][i * 2 + 1] = tmp.y
            }
            val railAlpha = floatArrayOf(0f, alpha, alpha, 0f)
            val segments = if (closed) n else n - 1
            for (i in 0 until segments) {
                val j = (i + 1) % n
                for (r in 0..2) quad(c, paint, page, rails[r][i * 2], rails[r][i * 2 + 1], railAlpha[r], rails[r + 1][i * 2], rails[r + 1][i * 2 + 1], railAlpha[r + 1], rails[r + 1][j * 2], rails[r + 1][j * 2 + 1], railAlpha[r + 1], rails[r][j * 2], rails[r][j * 2 + 1], railAlpha[r])
            }
        }
    }

    // -- gradients that need rings ---------------------------------------------

    // nvgBoxGradient filled into clip: colour ramps linearly with distance from the rounded box
    fun fillBoxGradient(clip: Path, x: Float, y: Float, w: Float, h: Float, radius: Float, feather: Float, inner: Int, outer: Int) {
        if (scissoredOut()) return
        val subs = clip.done()
        if (subs.isEmpty() || subs[0].size < 6) return
        val clipPoly = subs[0]
        val f = Math.max(feather, 1e-3f)
        val rings = Math.max(2, Math.min(12, Math.ceil((f * pxRatio * averageScale() / 3f).toDouble()).toInt()))
        val cx = x + w * 0.5f; val cy = y + h * 0.5f
        val corner = 8
        val contours = arrayOfNulls<FloatArray>(rings + 1)
        for (k in 0..rings) { val d = -f * 0.5f + f * k / rings; contours[k] = roundedContour(cx, cy, w * 0.5f + d, h * 0.5f + d, radius + d, corner) }
        val colors = IntArray(rings + 1)
        for (k in 0..rings) colors[k] = lerpColor(inner, outer, k.toFloat() / rings)
        emitRings(clipPoly, cx, cy, Array(rings + 1) { contours[it]!! }, colors, inner)
    }

    // nvgRadialGradient filled into a disc of the outer radius
    fun fillRadialGradient(cx: Float, cy: Float, innerRadius: Float, outerRadius: Float, inner: Int, outer: Int) {
        if (scissoredOut()) return
        val span = Math.max(outerRadius - innerRadius, 1e-3f)
        val rings = Math.max(2, Math.min(16, Math.ceil((span * pxRatio * averageScale() / 3f).toDouble()).toInt()))
        val segments = Math.max(16, arcSegments(outerRadius, (Math.PI * 2).toFloat()))
        val contours = Array(rings + 1) { FloatArray(segments * 2) }
        val colors = IntArray(rings + 1)
        for (k in 0..rings) {
            val r = innerRadius + span * k / rings
            val ring = contours[k]
            for (i in 0 until segments) { val a = Math.PI * 2 * i / segments; ring[i * 2] = cx + Math.cos(a).toFloat() * r; ring[i * 2 + 1] = cy + Math.sin(a).toFloat() * r }
            colors[k] = lerpColor(inner, outer, k.toFloat() / rings)
        }
        emitRings(null, cx, cy, contours, colors, inner)
    }

    private fun emitRings(clipPoly: FloatArray?, cx: Float, cy: Float, contours: Array<FloatArray>, colors: IntArray, coreColor: Int) {
        val page = atlas.currentPage()
        val c = consumer(CanvasPipelines.TEXTURED_TRIANGLES, atlasTextures(page))
        val clip = clipPoly != null && !containsAll(clipPoly, contours[contours.size - 1])
        val clipSide = if (clipPoly != null) Math.signum(signedArea(clipPoly)) else 0f
        val core = contours[0]
        val n = core.size / 2
        if (((coreColor ushr 24) and 0xFF) != 0) for (i in 0 until n) {
            val j = (i + 1) % n
            triangle(c, page, clip, clipPoly, clipSide, cx, cy, coreColor, core[i * 2], core[i * 2 + 1], coreColor, core[j * 2], core[j * 2 + 1], coreColor)
        }
        for (k in 0 until contours.size - 1) {
            val a = contours[k]; val b = contours[k + 1]
            if ((((colors[k] or colors[k + 1]) ushr 24) and 0xFF) == 0) continue
            for (i in 0 until n) {
                val j = (i + 1) % n
                triangle(c, page, clip, clipPoly, clipSide, a[i * 2], a[i * 2 + 1], colors[k], b[i * 2], b[i * 2 + 1], colors[k + 1], b[j * 2], b[j * 2 + 1], colors[k + 1])
                triangle(c, page, clip, clipPoly, clipSide, a[i * 2], a[i * 2 + 1], colors[k], b[j * 2], b[j * 2 + 1], colors[k + 1], a[j * 2], a[j * 2 + 1], colors[k])
            }
        }
    }

    private fun triangle(c: VertexConsumer, page: UiAtlas.Page, clip: Boolean, clipPoly: FloatArray?, clipSide: Float, x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) {
        if (!clip) { colored(c, page, x0, y0, c0); colored(c, page, x1, y1, c1); colored(c, page, x2, y2, c2); return }
        var px = floatArrayOf(x0, x1, x2); var py = floatArrayOf(y0, y1, y2); var pc = intArrayOf(c0, c1, c2)
        var count = 3
        val n = clipPoly!!.size / 2
        for (e in 0 until n) {
            if (count == 0) break
            val ax = clipPoly[e * 2]; val ay = clipPoly[e * 2 + 1]; val bx = clipPoly[((e + 1) % n) * 2]; val by = clipPoly[((e + 1) % n) * 2 + 1]
            val nx = FloatArray(count * 2); val ny = FloatArray(count * 2); val nc = IntArray(count * 2)
            var m = 0
            for (i in 0 until count) {
                val j = (i + 1) % count
                val dp = side(ax, ay, bx, by, px[i], py[i]) * clipSide; val dq = side(ax, ay, bx, by, px[j], py[j]) * clipSide
                if (dp >= 0) { nx[m] = px[i]; ny[m] = py[i]; nc[m++] = pc[i] }
                if ((dp >= 0) != (dq >= 0)) { val t = dp / (dp - dq); nx[m] = px[i] + (px[j] - px[i]) * t; ny[m] = py[i] + (py[j] - py[i]) * t; nc[m++] = lerpColor(pc[i], pc[j], t) }
            }
            px = nx; py = ny; pc = nc; count = m
        }
        for (i in 1 until count - 1) { colored(c, page, px[0], py[0], pc[0]); colored(c, page, px[i], py[i], pc[i]); colored(c, page, px[i + 1], py[i + 1], pc[i + 1]) }
    }

    private fun colored(c: VertexConsumer, page: UiAtlas.Page, lx: Float, ly: Float, argb: Int) {
        val m = state.xform
        rawVertex(c, m.m00() * lx + m.m10() * ly + m.m20(), m.m01() * lx + m.m11() * ly + m.m21(), page.whiteU, page.whiteV, argb)
    }

    // -- text and images -------------------------------------------------------

    // nanovg's font scale: average transform scale quantised to 1%, capped at 4x, times the pixel ratio
    private fun fontScale(): Float {
        val s = Math.round(averageScale() * 100f) / 100f
        return Math.min(s, 4f) * pxRatio
    }

    fun fontId(name: String): Int = fonts.id(name)

    // the canvas frees data on close, so it must come from MemoryUtil.memAlloc
    fun loadFont(name: String, data: java.nio.ByteBuffer): Int = fonts.load(name, data)
    fun addFallbackFont(baseId: Int, fallbackId: Int) = fonts.addFallback(baseId, fallbackId)

    // a handle into the shared atlas; it goes stale when the atlas drops its pages
    class AtlasImage internal constructor(internal val region: UiAtlas.Region, internal val generation: Int)

    fun uploadToAtlas(rgba: java.nio.ByteBuffer, width: Int, height: Int): AtlasImage? {
        val region = atlas.upload(rgba, width, height)
        return if (region == null) null else AtlasImage(region, atlas.generation())
    }

    fun isCurrent(image: AtlasImage?): Boolean = image != null && image.generation == atlas.generation()

    fun drawAtlasImage(image: AtlasImage?, x: Float, y: Float, w: Float, h: Float, tint: Int) {
        if (isCurrent(image)) atlasRegion(image!!.region, x, y, w, h, tint)
    }

    fun text(fontId: Int, text: String, x: Float, y: Float, size: Float, color: Int): Float {
        if (scissoredOut()) return fonts.layout(fontId, text, x, y, size, fontScale(), null)
        val page = arrayOfNulls<UiAtlas.Page>(1)
        val c = arrayOfNulls<VertexConsumer>(1)
        val m = state.xform
        val argb = color
        return fonts.layout(fontId, text, x, y, size, fontScale(), CanvasFonts.GlyphSink { region, x0, y0, x1, y1 ->
            if (page[0] !== region.page()) {
                page[0] = region.page()
                c[0] = consumer(CanvasPipelines.TEXTURED_TRIANGLES, atlasTextures(region.page()))
            }
            val ax = m.m00() * x0 + m.m10() * y0 + m.m20(); val ay = m.m01() * x0 + m.m11() * y0 + m.m21()
            val bx = m.m00() * x1 + m.m10() * y0 + m.m20(); val by = m.m01() * x1 + m.m11() * y0 + m.m21()
            val cx = m.m00() * x1 + m.m10() * y1 + m.m20(); val cy = m.m01() * x1 + m.m11() * y1 + m.m21()
            val dx = m.m00() * x0 + m.m10() * y1 + m.m20(); val dy = m.m01() * x0 + m.m11() * y1 + m.m21()
            val consumer = c[0]!!
            rawVertex(consumer, ax, ay, region.u0(), region.v0(), argb)
            rawVertex(consumer, dx, dy, region.u0(), region.v1(), argb)
            rawVertex(consumer, cx, cy, region.u1(), region.v1(), argb)
            rawVertex(consumer, ax, ay, region.u0(), region.v0(), argb)
            rawVertex(consumer, cx, cy, region.u1(), region.v1(), argb)
            rawVertex(consumer, bx, by, region.u1(), region.v0(), argb)
        })
    }

    fun textWidth(fontId: Int, text: String, size: Float): Float = fonts.layout(fontId, text, 0f, 0f, size, fontScale(), null)

    // a rect from the shared atlas, e.g. a rasterised svg
    internal fun atlasRegion(region: UiAtlas.Region, x: Float, y: Float, w: Float, h: Float, tint: Int) {
        val textures = atlasTextures(region.page())
        fill(path().rect(x, y, w, h), Paint.image(textures, x, y, w, h, region.u0(), region.v0(), region.u1(), region.v1(), tint))
    }

    fun image(view: GpuTextureView, x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Int) {
        val sampler: GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val textures = TextureSetup.singleTexture(view, sampler)
        fill(path().roundedRect(x, y, w, h, radius), Paint.image(textures, x, y, w, h, 0f, 0f, 1f, 1f, tint))
    }

    // mc's own baked glyphs keep their pipeline, atlas and lightmap; we only add our transform and alpha
    fun minecraftGlyph(glyph: TextRenderable) {
        if (scissoredOut()) return
        val textures = TextureSetup.singleTextureWithLightmap(glyph.textureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
        val c = consumer(glyph.guiPipeline(), textures)
        val target: VertexConsumer = if (state.alpha >= 1f) c else AlphaConsumer(c, state.alpha)
        glyph.render(Matrix4f().mul(state.xform), target, 0xF000F0, true)
    }

    private class AlphaConsumer(private val delegate: VertexConsumer, private val alpha: Float) : VertexConsumer {
        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer { delegate.addVertex(x, y, z); return this }
        override fun setColor(r: Int, g: Int, b: Int, a: Int): VertexConsumer { delegate.setColor(r, g, b, Math.round(a * alpha)); return this }
        override fun setColor(color: Int): VertexConsumer { delegate.setColor(scaleAlpha(color, alpha)); return this }
        override fun setUv(u: Float, v: Float): VertexConsumer { delegate.setUv(u, v); return this }
        override fun setUv1(u: Int, v: Int): VertexConsumer { delegate.setUv1(u, v); return this }
        override fun setUv2(u: Int, v: Int): VertexConsumer { delegate.setUv2(u, v); return this }
        override fun setUv3(u: Float, v: Float): VertexConsumer { delegate.setUv3(u, v); return this }
        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer { delegate.setNormal(x, y, z); return this }
        override fun setLineWidth(width: Float): VertexConsumer { delegate.setLineWidth(width); return this }
    }
}
