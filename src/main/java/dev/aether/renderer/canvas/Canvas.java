package dev.aether.renderer.canvas;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

// backend-neutral replacement for the nanovg gl3 context: paths are tessellated on the cpu with a
// one-pixel alpha fringe and drawn through mc's own gpu api, so vulkan and opengl look the same
public final class Canvas implements AutoCloseable {
    private static final float TESS_TOLERANCE = 0.25f;
    private static final float MAX_MITER = 4f;

    final UiAtlas atlas = new UiAtlas();
    final CanvasFonts fonts = new CanvasFonts(atlas);

    private final StagedVertexBuffer vertexBuffer = new StagedVertexBuffer(() -> "Aether UI", 1 << 18);
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("aether ui");
    private final List<Batch> batches = new ArrayList<>();
    private Batch current;

    private final ArrayDeque<State> stack = new ArrayDeque<>();
    private State state = new State();

    private float frameWidth;
    private float frameHeight;
    private float pxRatio = 1f;

    private record Batch(RenderPipeline pipeline, TextureSetup textures, Scissor scissor, StagedVertexBuffer.Draw draw) {}

    record Scissor(float x0, float y0, float x1, float y1) {
        Scissor intersect(Scissor other) {
            if (other == null) return this;
            return new Scissor(Math.max(x0, other.x0), Math.max(y0, other.y0), Math.min(x1, other.x1), Math.min(y1, other.y1));
        }
    }

    private static final class State {
        final Matrix3x2f xform = new Matrix3x2f();
        float alpha = 1f;
        Scissor scissor;

        State copy() {
            State s = new State();
            s.xform.set(xform);
            s.alpha = alpha;
            s.scissor = scissor;
            return s;
        }
    }

    // -- frame -----------------------------------------------------------------

    public void beginFrame(float width, float height, float pxRatio) {
        discardPending();
        this.frameWidth = width;
        this.frameHeight = height;
        this.pxRatio = pxRatio > 0f ? pxRatio : 1f;
        stack.clear();
        state = new State();
        atlas.beginFrame();
    }

    public void endFrame(GpuTextureView target) {
        try {
            if (target != null && !batches.isEmpty()) draw(target);
        } finally {
            batches.clear();
            current = null;
            vertexBuffer.endDraw();
            vertexBuffer.endFrame();
        }
    }

    // a frame that threw halfway still owns vertex data, which upload() has to consume before reuse
    private void discardPending() {
        if (batches.isEmpty()) return;
        vertexBuffer.upload();
        vertexBuffer.endDraw();
        vertexBuffer.endFrame();
        batches.clear();
        current = null;
    }

    private void draw(GpuTextureView target) {
        vertexBuffer.upload();
        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedType = RenderSystem.getProjectionType();
        // same ortho setup as GuiRenderer, just sized to the canvas instead of the gui scale
        projection.setupOrtho(1000f, 11000f, frameWidth, frameHeight, true);
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().setTranslation(0f, 0f, -11000f));
        int targetWidth = target.getWidth(0);
        int targetHeight = target.getHeight(0);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Aether UI", target, Optional.empty(), null, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            for (Batch batch : batches) {
                StagedVertexBuffer.ExecuteInfo info = vertexBuffer.getExecuteInfo(batch.draw);
                if (info == null) continue;
                pass.setPipeline(batch.pipeline);
                pass.setVertexBuffer(0, info.vertexBuffer().slice());
                if (batch.scissor != null) {
                    applyScissor(pass, batch.scissor, targetWidth, targetHeight);
                } else {
                    pass.disableScissor();
                }
                TextureSetup t = batch.textures;
                if (t.texure0() != null) pass.bindTexture("Sampler0", t.texure0(), t.sampler0());
                if (t.texure1() != null) pass.bindTexture("Sampler1", t.texure1(), t.sampler1());
                if (t.texure2() != null) pass.bindTexture("Sampler2", t.texure2(), t.sampler2());
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, savedType);
        }
    }

    // render pass scissors are in framebuffer pixels with a bottom-left origin, like GuiRenderer's
    private void applyScissor(RenderPass pass, Scissor s, int targetWidth, int targetHeight) {
        float sx = targetWidth / frameWidth;
        float sy = targetHeight / frameHeight;
        int left = Math.max(0, Math.round(s.x0 * sx));
        int top = Math.max(0, Math.round(s.y0 * sy));
        int right = Math.min(targetWidth, Math.round(s.x1 * sx));
        int bottom = Math.min(targetHeight, Math.round(s.y1 * sy));
        pass.enableScissor(left, targetHeight - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    @Override
    public void close() {
        discardPending();
        vertexBuffer.close();
        projectionBuffer.close();
        fonts.close();
        atlas.close();
    }

    public float pxRatio() {
        return pxRatio;
    }

    // -- state -----------------------------------------------------------------

    public void save() {
        stack.push(state.copy());
    }

    public void restore() {
        if (!stack.isEmpty()) state = stack.pop();
    }

    public void translate(float x, float y) {
        state.xform.translate(x, y);
    }

    public void scale(float x, float y) {
        state.xform.scale(x, y);
    }

    public void skewX(float radians) {
        state.xform.mul(new Matrix3x2f(1f, 0f, (float) Math.tan(radians), 1f, 0f, 0f));
    }

    // a x b c y d, the same argument order as nvgTransform
    public void transform(float a, float b, float c, float d, float e, float f) {
        state.xform.mul(new Matrix3x2f(a, b, c, d, e, f));
    }

    public void globalAlpha(float alpha) {
        state.alpha = alpha;
    }

    // like nvgScissor the rect is in local space; rotations collapse to their bounding box
    public void scissor(float x, float y, float w, float h) {
        state.scissor = transformedBounds(x, y, Math.max(0f, w), Math.max(0f, h));
    }

    public void intersectScissor(float x, float y, float w, float h) {
        Scissor next = transformedBounds(x, y, Math.max(0f, w), Math.max(0f, h));
        state.scissor = next.intersect(state.scissor);
    }

    public void resetScissor() {
        state.scissor = null;
    }

    private Scissor transformedBounds(float x, float y, float w, float h) {
        Matrix3x2f m = state.xform;
        float[] xs = new float[4];
        float[] ys = new float[4];
        float[][] corners = {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
        for (int i = 0; i < 4; i++) {
            xs[i] = m.m00 * corners[i][0] + m.m10 * corners[i][1] + m.m20;
            ys[i] = m.m01 * corners[i][0] + m.m11 * corners[i][1] + m.m21;
        }
        return new Scissor(Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3])),
                Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3])),
                Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3])),
                Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3])));
    }

    private float averageScale() {
        Matrix3x2f m = state.xform;
        float sx = (float) Math.sqrt(m.m00 * m.m00 + m.m01 * m.m01);
        float sy = (float) Math.sqrt(m.m10 * m.m10 + m.m11 * m.m11);
        return (sx + sy) * 0.5f;
    }

    private float fringe() {
        return 1f / pxRatio;
    }

    private float tolerance() {
        return TESS_TOLERANCE / (pxRatio * Math.max(averageScale(), 1e-3f));
    }

    // -- batching --------------------------------------------------------------

    private VertexConsumer consumer(RenderPipeline pipeline, TextureSetup textures) {
        Scissor scissor = state.scissor;
        if (current == null || current.pipeline != pipeline || !current.textures.equals(textures)
                || !Objects.equals(current.scissor, scissor)) {
            StagedVertexBuffer.Draw draw = vertexBuffer.appendDraw(
                    Objects.requireNonNull(pipeline.getVertexFormatBinding(0)), pipeline.getPrimitiveTopology());
            current = new Batch(pipeline, textures, scissor, draw);
            batches.add(current);
        }
        return vertexBuffer.getVertexBuilder(current.draw);
    }

    // also covers a collapsed transform, which has no inverse for fringe and paint maths
    private boolean scissoredOut() {
        Scissor s = state.scissor;
        if (s != null && (s.x1 <= s.x0 || s.y1 <= s.y0)) return true;
        return Math.abs(state.xform.determinant()) < 1e-12f;
    }

    private TextureSetup atlasTextures(UiAtlas.Page page) {
        return TextureSetup.singleTexture(page.view, atlas.sampler());
    }

    // -- paints ----------------------------------------------------------------

    // colors and uvs are evaluated in local (pre-transform) space, like nanovg paints
    public static final class Paint {
        private static final int SOLID = 0, LINEAR = 1, IMAGE = 2;
        private int kind;
        private int inner;
        private int outer;
        private float sx, sy, dx, dy, invLen2;
        private float ox, oy, ow, oh, u0, v0, u1, v1;
        private TextureSetup textures;

        public static Paint solid(int argb) {
            Paint p = new Paint();
            p.kind = SOLID;
            p.inner = argb;
            return p;
        }

        public static Paint linear(float sx, float sy, float ex, float ey, int from, int to) {
            Paint p = new Paint();
            p.kind = LINEAR;
            p.inner = from;
            p.outer = to;
            p.sx = sx;
            p.sy = sy;
            p.dx = ex - sx;
            p.dy = ey - sy;
            float len2 = p.dx * p.dx + p.dy * p.dy;
            p.invLen2 = len2 > 1e-6f ? 1f / len2 : 0f;
            return p;
        }

        // maps the uv rect onto the local rect x, y, w, h; tint multiplies the texel
        public static Paint image(TextureSetup textures, float x, float y, float w, float h,
                                  float u0, float v0, float u1, float v1, int tint) {
            Paint p = new Paint();
            p.kind = IMAGE;
            p.textures = textures;
            p.inner = tint;
            p.ox = x;
            p.oy = y;
            p.ow = w == 0 ? 1 : w;
            p.oh = h == 0 ? 1 : h;
            p.u0 = u0;
            p.v0 = v0;
            p.u1 = u1;
            p.v1 = v1;
            return p;
        }

        int color(float x, float y) {
            if (kind != LINEAR) return inner;
            float t = ((x - sx) * dx + (y - sy) * dy) * invLen2;
            return lerpColor(inner, outer, t);
        }
    }

    private TextureSetup paintTextures(Paint paint, UiAtlas.Page page) {
        return paint.kind == Paint.IMAGE ? paint.textures : atlasTextures(page);
    }

    // -- vertex helpers --------------------------------------------------------

    private void vertex(VertexConsumer c, Paint paint, UiAtlas.Page page, float lx, float ly, float alpha) {
        Matrix3x2f m = state.xform;
        float x = m.m00 * lx + m.m10 * ly + m.m20;
        float y = m.m01 * lx + m.m11 * ly + m.m21;
        float u;
        float v;
        if (paint.kind == Paint.IMAGE) {
            u = paint.u0 + (lx - paint.ox) / paint.ow * (paint.u1 - paint.u0);
            v = paint.v0 + (ly - paint.oy) / paint.oh * (paint.v1 - paint.v0);
        } else {
            u = page.whiteU;
            v = page.whiteV;
        }
        c.addVertex(x, y, 0f).setUv(u, v).setColor(scaleAlpha(paint.color(lx, ly), alpha * state.alpha));
    }

    private void rawVertex(VertexConsumer c, float x, float y, float u, float v, int argb) {
        c.addVertex(x, y, 0f).setUv(u, v).setColor(scaleAlpha(argb, state.alpha));
    }

    static int scaleAlpha(int argb, float alpha) {
        if (alpha >= 1f) return argb;
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, alpha));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    static int lerpColor(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int a = lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t);
        int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = lerp(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    // -- paths -----------------------------------------------------------------

    // local-space polylines; arcs are flattened against the current transform's tolerance
    public final class Path {
        final List<float[]> subpaths = new ArrayList<>();
        final List<Boolean> closed = new ArrayList<>();
        private float[] pts = new float[32];
        private int count;

        public Path moveTo(float x, float y) {
            finishSubpath(false);
            add(x, y);
            return this;
        }

        public Path lineTo(float x, float y) {
            if (count == 0) return moveTo(x, y);
            add(x, y);
            return this;
        }

        public Path close() {
            finishSubpath(true);
            return this;
        }

        // dir: true walks clockwise in screen space, matching NVG_CW
        public Path arc(float cx, float cy, float r, float a0, float a1, boolean clockwise) {
            float da = a1 - a0;
            float twoPi = (float) (Math.PI * 2);
            if (clockwise) {
                if (Math.abs(da) >= twoPi) da = twoPi;
                else while (da < 0) da += twoPi;
            } else {
                if (Math.abs(da) >= twoPi) da = -twoPi;
                else while (da > 0) da -= twoPi;
            }
            int segments = arcSegments(r, Math.abs(da));
            for (int i = 0; i <= segments; i++) {
                float a = a0 + da * i / segments;
                float x = cx + (float) Math.cos(a) * r;
                float y = cy + (float) Math.sin(a) * r;
                if (i == 0 && count == 0) add(x, y);
                else lineTo(x, y);
            }
            return this;
        }

        public Path rect(float x, float y, float w, float h) {
            moveTo(x, y);
            lineTo(x, y + h);
            lineTo(x + w, y + h);
            lineTo(x + w, y);
            return close();
        }

        public Path roundedRect(float x, float y, float w, float h, float r) {
            float rr = Math.min(Math.max(r, 0f), Math.min(Math.abs(w), Math.abs(h)) * 0.5f);
            if (rr < 0.1f) return rect(x, y, w, h);
            float hp = (float) (Math.PI / 2);
            finishSubpath(false);
            arc(x + rr, y + rr, rr, hp * 2, hp * 3, true);
            arc(x + w - rr, y + rr, rr, hp * 3, hp * 4, true);
            arc(x + w - rr, y + h - rr, rr, 0f, hp, true);
            arc(x + rr, y + h - rr, rr, hp, hp * 2, true);
            return close();
        }

        public Path circle(float cx, float cy, float r) {
            finishSubpath(false);
            arc(cx, cy, r, 0f, (float) (Math.PI * 2), true);
            return close();
        }

        private void add(float x, float y) {
            if (count > 0) {
                float px = pts[count * 2 - 2];
                float py = pts[count * 2 - 1];
                if (Math.abs(px - x) < 1e-5f && Math.abs(py - y) < 1e-5f) return;
            }
            if (count * 2 + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[count * 2] = x;
            pts[count * 2 + 1] = y;
            count++;
        }

        private void finishSubpath(boolean close) {
            if (count == 0) return;
            int n = count;
            if (n > 1 && Math.abs(pts[0] - pts[n * 2 - 2]) < 1e-5f && Math.abs(pts[1] - pts[n * 2 - 1]) < 1e-5f) {
                n--;
                close = true;
            }
            subpaths.add(java.util.Arrays.copyOf(pts, n * 2));
            closed.add(close);
            count = 0;
        }

        List<float[]> done() {
            finishSubpath(false);
            return subpaths;
        }
    }

    public Path path() {
        return new Path();
    }

    private int arcSegments(float r, float angle) {
        float rpx = Math.abs(r) * pxRatio * Math.max(averageScale(), 1e-3f);
        if (rpx < 0.5f) return 1;
        double step = 2 * Math.acos(Math.max(-1.0, Math.min(1.0, 1.0 - TESS_TOLERANCE / rpx)));
        int n = (int) Math.ceil(angle / Math.max(step, 0.02));
        return Math.max(1, Math.min(n, 256));
    }

    // -- fill ------------------------------------------------------------------

    // every caller fills convex shapes (rects, rounded rects, circles), so each subpath is a fan
    public void fill(Path path, Paint paint) {
        if (scissoredOut()) return;
        List<float[]> subs = path.done();
        UiAtlas.Page page = atlas.currentPage();
        VertexConsumer c = consumer(CanvasPipelines.TEXTURED_TRIANGLES, paintTextures(paint, page));
        for (float[] local : subs) {
            if (local.length >= 6) fillConvex(c, local, paint, page);
        }
    }

    private void fillConvex(VertexConsumer c, float[] local, Paint paint, UiAtlas.Page page) {
        int n = local.length / 2;
        float[] out = toOutput(local);
        float area = signedArea(out);
        if (Math.abs(area) < 1e-6f) return;
        float f = fringe();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, out[i * 2]);
            maxX = Math.max(maxX, out[i * 2]);
            minY = Math.min(minY, out[i * 2 + 1]);
            maxY = Math.max(maxY, out[i * 2 + 1]);
        }
        Matrix3x2f inv = new Matrix3x2f(state.xform).invert();
        if (Math.min(maxX - minX, maxY - minY) < f * 2f) {
            for (int i = 1; i < n - 1; i++) {
                vertex(c, paint, page, local[0], local[1], 1f);
                vertex(c, paint, page, local[i * 2], local[i * 2 + 1], 1f);
                vertex(c, paint, page, local[i * 2 + 2], local[i * 2 + 3], 1f);
            }
            return;
        }

        float[] offsets = miterOffsets(out, true, area > 0 ? 1f : -1f);
        float[] innerLocal = new float[n * 2];
        float[] outerLocal = new float[n * 2];
        Vector2f tmp = new Vector2f();
        for (int i = 0; i < n; i++) {
            float ox = offsets[i * 2] * f * 0.5f;
            float oy = offsets[i * 2 + 1] * f * 0.5f;
            inv.transformPosition(out[i * 2] - ox, out[i * 2 + 1] - oy, tmp);
            innerLocal[i * 2] = tmp.x;
            innerLocal[i * 2 + 1] = tmp.y;
            inv.transformPosition(out[i * 2] + ox, out[i * 2 + 1] + oy, tmp);
            outerLocal[i * 2] = tmp.x;
            outerLocal[i * 2 + 1] = tmp.y;
        }
        for (int i = 1; i < n - 1; i++) {
            vertex(c, paint, page, innerLocal[0], innerLocal[1], 1f);
            vertex(c, paint, page, innerLocal[i * 2], innerLocal[i * 2 + 1], 1f);
            vertex(c, paint, page, innerLocal[i * 2 + 2], innerLocal[i * 2 + 3], 1f);
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            quad(c, paint, page,
                    innerLocal[i * 2], innerLocal[i * 2 + 1], 1f,
                    outerLocal[i * 2], outerLocal[i * 2 + 1], 0f,
                    outerLocal[j * 2], outerLocal[j * 2 + 1], 0f,
                    innerLocal[j * 2], innerLocal[j * 2 + 1], 1f);
        }
    }

    private void quad(VertexConsumer c, Paint paint, UiAtlas.Page page,
                      float x0, float y0, float a0, float x1, float y1, float a1,
                      float x2, float y2, float a2, float x3, float y3, float a3) {
        vertex(c, paint, page, x0, y0, a0);
        vertex(c, paint, page, x1, y1, a1);
        vertex(c, paint, page, x2, y2, a2);
        vertex(c, paint, page, x0, y0, a0);
        vertex(c, paint, page, x2, y2, a2);
        vertex(c, paint, page, x3, y3, a3);
    }

    private float[] toOutput(float[] local) {
        Matrix3x2f m = state.xform;
        float[] out = new float[local.length];
        for (int i = 0; i < local.length; i += 2) {
            out[i] = m.m00 * local[i] + m.m10 * local[i + 1] + m.m20;
            out[i + 1] = m.m01 * local[i] + m.m11 * local[i + 1] + m.m21;
        }
        return out;
    }

    private static float signedArea(float[] p) {
        int n = p.length / 2;
        float area = 0f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1];
        }
        return area * 0.5f;
    }

    // per-vertex offset whose projection onto both adjacent edge normals is 1; outward for fills
    private static float[] miterOffsets(float[] p, boolean closed, float side) {
        int n = p.length / 2;
        float[] normals = new float[n * 2];
        int edges = closed ? n : n - 1;
        for (int i = 0; i < edges; i++) {
            int j = (i + 1) % n;
            float dx = p[j * 2] - p[i * 2];
            float dy = p[j * 2 + 1] - p[i * 2 + 1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) len = 1e-6f;
            normals[i * 2] = side * dy / len;
            normals[i * 2 + 1] = -side * dx / len;
        }
        float[] offsets = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float nx0, ny0, nx1, ny1;
            if (closed) {
                int prev = (i - 1 + n) % n;
                nx0 = normals[prev * 2];
                ny0 = normals[prev * 2 + 1];
                nx1 = normals[i * 2];
                ny1 = normals[i * 2 + 1];
            } else {
                int prev = Math.max(0, i - 1);
                int next = Math.min(edges - 1, i);
                nx0 = normals[prev * 2];
                ny0 = normals[prev * 2 + 1];
                nx1 = normals[next * 2];
                ny1 = normals[next * 2 + 1];
            }
            float mx = (nx0 + nx1) * 0.5f;
            float my = (ny0 + ny1) * 0.5f;
            float d2 = mx * mx + my * my;
            float scale = d2 > 1e-6f ? Math.min(1f / d2, MAX_MITER) : 1f;
            if (d2 <= 1e-6f) {
                mx = nx1;
                my = ny1;
            }
            offsets[i * 2] = mx * scale;
            offsets[i * 2 + 1] = my * scale;
        }
        return offsets;
    }

    // -- stroke ----------------------------------------------------------------

    public void stroke(Path path, Paint paint, float width) {
        if (scissoredOut()) return;
        List<float[]> subs = path.done();
        UiAtlas.Page page = atlas.currentPage();
        VertexConsumer c = consumer(CanvasPipelines.TEXTURED_TRIANGLES, paintTextures(paint, page));
        float f = fringe();
        float w = width * averageScale();
        float alpha = 1f;
        // nanovg fades hairlines instead of drawing them thinner than a pixel
        if (w < f) {
            alpha = Math.max(0f, w / f);
            w = f;
        }
        float hw = w * 0.5f;
        float inner = Math.max(hw - f * 0.5f, 0f);
        float outer = hw + f * 0.5f;
        Matrix3x2f inv = new Matrix3x2f(state.xform).invert();
        for (int s = 0; s < subs.size(); s++) {
            float[] local = subs.get(s);
            boolean closed = path.closed.get(s);
            int n = local.length / 2;
            if (n < 2) continue;
            float[] out = toOutput(local);
            float[] offsets = miterOffsets(out, closed, 1f);
            float[][] rails = new float[4][n * 2];
            float[] railOffset = {outer, inner, -inner, -outer};
            Vector2f tmp = new Vector2f();
            for (int i = 0; i < n; i++) {
                for (int r = 0; r < 4; r++) {
                    inv.transformPosition(out[i * 2] + offsets[i * 2] * railOffset[r],
                            out[i * 2 + 1] + offsets[i * 2 + 1] * railOffset[r], tmp);
                    rails[r][i * 2] = tmp.x;
                    rails[r][i * 2 + 1] = tmp.y;
                }
            }
            float[] railAlpha = {0f, alpha, alpha, 0f};
            int segments = closed ? n : n - 1;
            for (int i = 0; i < segments; i++) {
                int j = (i + 1) % n;
                for (int r = 0; r < 3; r++) {
                    quad(c, paint, page,
                            rails[r][i * 2], rails[r][i * 2 + 1], railAlpha[r],
                            rails[r + 1][i * 2], rails[r + 1][i * 2 + 1], railAlpha[r + 1],
                            rails[r + 1][j * 2], rails[r + 1][j * 2 + 1], railAlpha[r + 1],
                            rails[r][j * 2], rails[r][j * 2 + 1], railAlpha[r]);
                }
            }
        }
    }

    // -- gradients that need rings ---------------------------------------------

    // nvgBoxGradient filled into clip: colour ramps linearly with distance from the rounded box
    public void fillBoxGradient(Path clip, float x, float y, float w, float h, float radius, float feather,
                                int inner, int outer) {
        if (scissoredOut()) return;
        List<float[]> subs = clip.done();
        if (subs.isEmpty() || subs.getFirst().length < 6) return;
        float[] clipPoly = subs.getFirst();
        float f = Math.max(feather, 1e-3f);
        int rings = Math.max(2, Math.min(12, (int) Math.ceil(f * pxRatio * averageScale() / 3f)));
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        int corner = 8;
        float[][] contours = new float[rings + 1][];
        for (int k = 0; k <= rings; k++) {
            float d = -f * 0.5f + f * k / rings;
            contours[k] = roundedContour(cx, cy, w * 0.5f + d, h * 0.5f + d, radius + d, corner);
        }
        int[] colors = new int[rings + 1];
        for (int k = 0; k <= rings; k++) colors[k] = lerpColor(inner, outer, (float) k / rings);
        emitRings(clipPoly, cx, cy, contours, colors, inner);
    }

    // nvgRadialGradient filled into a disc of the outer radius
    public void fillRadialGradient(float cx, float cy, float innerRadius, float outerRadius, int inner, int outer) {
        if (scissoredOut()) return;
        float span = Math.max(outerRadius - innerRadius, 1e-3f);
        int rings = Math.max(2, Math.min(16, (int) Math.ceil(span * pxRatio * averageScale() / 3f)));
        int segments = Math.max(16, arcSegments(outerRadius, (float) (Math.PI * 2)));
        float[][] contours = new float[rings + 1][];
        int[] colors = new int[rings + 1];
        for (int k = 0; k <= rings; k++) {
            float r = innerRadius + span * k / rings;
            float[] ring = new float[segments * 2];
            for (int i = 0; i < segments; i++) {
                double a = Math.PI * 2 * i / segments;
                ring[i * 2] = cx + (float) Math.cos(a) * r;
                ring[i * 2 + 1] = cy + (float) Math.sin(a) * r;
            }
            contours[k] = ring;
            colors[k] = lerpColor(inner, outer, (float) k / rings);
        }
        emitRings(null, cx, cy, contours, colors, inner);
    }

    private static float[] roundedContour(float cx, float cy, float hx, float hy, float r, int perCorner) {
        hx = Math.max(hx, 0f);
        hy = Math.max(hy, 0f);
        float rr = Math.max(0f, Math.min(r, Math.min(hx, hy)));
        float[] pts = new float[4 * (perCorner + 1) * 2];
        float[][] centers = {{cx + hx - rr, cy - hy + rr}, {cx + hx - rr, cy + hy - rr},
                {cx - hx + rr, cy + hy - rr}, {cx - hx + rr, cy - hy + rr}};
        int idx = 0;
        for (int c = 0; c < 4; c++) {
            double start = -Math.PI / 2 + c * Math.PI / 2;
            for (int i = 0; i <= perCorner; i++) {
                double a = start + (Math.PI / 2) * i / perCorner;
                pts[idx++] = centers[c][0] + (float) Math.cos(a) * rr;
                pts[idx++] = centers[c][1] + (float) Math.sin(a) * rr;
            }
        }
        return pts;
    }

    private void emitRings(float[] clipPoly, float cx, float cy, float[][] contours, int[] colors, int coreColor) {
        UiAtlas.Page page = atlas.currentPage();
        VertexConsumer c = consumer(CanvasPipelines.TEXTURED_TRIANGLES, atlasTextures(page));
        boolean clip = clipPoly != null && !containsAll(clipPoly, contours[contours.length - 1]);
        float clipSide = clipPoly != null ? Math.signum(signedArea(clipPoly)) : 0f;
        float[] core = contours[0];
        int n = core.length / 2;
        if (((coreColor >>> 24) & 0xFF) != 0) {
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                triangle(c, page, clip, clipPoly, clipSide,
                        cx, cy, coreColor, core[i * 2], core[i * 2 + 1], coreColor, core[j * 2], core[j * 2 + 1], coreColor);
            }
        }
        for (int k = 0; k + 1 < contours.length; k++) {
            float[] a = contours[k];
            float[] b = contours[k + 1];
            if ((((colors[k] | colors[k + 1]) >>> 24) & 0xFF) == 0) continue;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                triangle(c, page, clip, clipPoly, clipSide,
                        a[i * 2], a[i * 2 + 1], colors[k], b[i * 2], b[i * 2 + 1], colors[k + 1], b[j * 2], b[j * 2 + 1], colors[k + 1]);
                triangle(c, page, clip, clipPoly, clipSide,
                        a[i * 2], a[i * 2 + 1], colors[k], b[j * 2], b[j * 2 + 1], colors[k + 1], a[j * 2], a[j * 2 + 1], colors[k]);
            }
        }
    }

    private void triangle(VertexConsumer c, UiAtlas.Page page, boolean clip, float[] clipPoly, float clipSide,
                          float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2) {
        if (!clip) {
            colored(c, page, x0, y0, c0);
            colored(c, page, x1, y1, c1);
            colored(c, page, x2, y2, c2);
            return;
        }
        float[] px = {x0, x1, x2};
        float[] py = {y0, y1, y2};
        int[] pc = {c0, c1, c2};
        int count = 3;
        int n = clipPoly.length / 2;
        for (int e = 0; e < n && count > 0; e++) {
            float ax = clipPoly[e * 2], ay = clipPoly[e * 2 + 1];
            float bx = clipPoly[((e + 1) % n) * 2], by = clipPoly[((e + 1) % n) * 2 + 1];
            float[] nx = new float[count * 2];
            float[] ny = new float[count * 2];
            int[] nc = new int[count * 2];
            int m = 0;
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                float dp = side(ax, ay, bx, by, px[i], py[i]) * clipSide;
                float dq = side(ax, ay, bx, by, px[j], py[j]) * clipSide;
                if (dp >= 0) {
                    nx[m] = px[i];
                    ny[m] = py[i];
                    nc[m++] = pc[i];
                }
                if ((dp >= 0) != (dq >= 0)) {
                    float t = dp / (dp - dq);
                    nx[m] = px[i] + (px[j] - px[i]) * t;
                    ny[m] = py[i] + (py[j] - py[i]) * t;
                    nc[m++] = lerpColor(pc[i], pc[j], t);
                }
            }
            px = nx;
            py = ny;
            pc = nc;
            count = m;
        }
        for (int i = 1; i + 1 < count; i++) {
            colored(c, page, px[0], py[0], pc[0]);
            colored(c, page, px[i], py[i], pc[i]);
            colored(c, page, px[i + 1], py[i + 1], pc[i + 1]);
        }
    }

    private static float side(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static boolean containsAll(float[] poly, float[] pts) {
        float s = Math.signum(signedArea(poly));
        int n = poly.length / 2;
        for (int p = 0; p < pts.length; p += 2) {
            for (int e = 0; e < n; e++) {
                int j = (e + 1) % n;
                if (side(poly[e * 2], poly[e * 2 + 1], poly[j * 2], poly[j * 2 + 1], pts[p], pts[p + 1]) * s < -1e-3f) {
                    return false;
                }
            }
        }
        return true;
    }

    private void colored(VertexConsumer c, UiAtlas.Page page, float lx, float ly, int argb) {
        Matrix3x2f m = state.xform;
        rawVertex(c, m.m00 * lx + m.m10 * ly + m.m20, m.m01 * lx + m.m11 * ly + m.m21, page.whiteU, page.whiteV, argb);
    }

    // -- text and images -------------------------------------------------------

    // nanovg's font scale: average transform scale quantised to 1%, capped at 4x, times the pixel ratio
    private float fontScale() {
        float s = Math.round(averageScale() * 100f) / 100f;
        return Math.min(s, 4f) * pxRatio;
    }

    public int fontId(String name) {
        return fonts.id(name);
    }

    // the canvas frees data on close, so it must come from MemoryUtil.memAlloc
    public int loadFont(String name, java.nio.ByteBuffer data) {
        return fonts.load(name, data);
    }

    public void addFallbackFont(int baseId, int fallbackId) {
        fonts.addFallback(baseId, fallbackId);
    }

    // a handle into the shared atlas; it goes stale when the atlas drops its pages
    public static final class AtlasImage {
        private final UiAtlas.Region region;
        private final int generation;

        private AtlasImage(UiAtlas.Region region, int generation) {
            this.region = region;
            this.generation = generation;
        }
    }

    public AtlasImage uploadToAtlas(java.nio.ByteBuffer rgba, int width, int height) {
        UiAtlas.Region region = atlas.upload(rgba, width, height);
        return region == null ? null : new AtlasImage(region, atlas.generation());
    }

    public boolean isCurrent(AtlasImage image) {
        return image != null && image.generation == atlas.generation();
    }

    public void drawAtlasImage(AtlasImage image, float x, float y, float w, float h, int tint) {
        if (isCurrent(image)) atlasRegion(image.region, x, y, w, h, tint);
    }

    public float text(int fontId, String text, float x, float y, float size, int color) {
        if (scissoredOut()) return fonts.layout(fontId, text, x, y, size, fontScale(), null);
        UiAtlas.Page[] page = {null};
        VertexConsumer[] c = {null};
        Matrix3x2f m = state.xform;
        int argb = color;
        return fonts.layout(fontId, text, x, y, size, fontScale(), (region, x0, y0, x1, y1) -> {
            if (page[0] != region.page()) {
                page[0] = region.page();
                c[0] = consumer(CanvasPipelines.TEXTURED_TRIANGLES, atlasTextures(region.page()));
            }
            float ax = m.m00 * x0 + m.m10 * y0 + m.m20, ay = m.m01 * x0 + m.m11 * y0 + m.m21;
            float bx = m.m00 * x1 + m.m10 * y0 + m.m20, by = m.m01 * x1 + m.m11 * y0 + m.m21;
            float cx = m.m00 * x1 + m.m10 * y1 + m.m20, cy = m.m01 * x1 + m.m11 * y1 + m.m21;
            float dx = m.m00 * x0 + m.m10 * y1 + m.m20, dy = m.m01 * x0 + m.m11 * y1 + m.m21;
            rawVertex(c[0], ax, ay, region.u0(), region.v0(), argb);
            rawVertex(c[0], dx, dy, region.u0(), region.v1(), argb);
            rawVertex(c[0], cx, cy, region.u1(), region.v1(), argb);
            rawVertex(c[0], ax, ay, region.u0(), region.v0(), argb);
            rawVertex(c[0], cx, cy, region.u1(), region.v1(), argb);
            rawVertex(c[0], bx, by, region.u1(), region.v0(), argb);
        });
    }

    public float textWidth(int fontId, String text, float size) {
        return fonts.layout(fontId, text, 0f, 0f, size, fontScale(), null);
    }

    // a rect from the shared atlas, e.g. a rasterised svg
    void atlasRegion(UiAtlas.Region region, float x, float y, float w, float h, int tint) {
        TextureSetup textures = atlasTextures(region.page());
        fill(path().rect(x, y, w, h),
                Paint.image(textures, x, y, w, h, region.u0(), region.v0(), region.u1(), region.v1(), tint));
    }

    public void image(GpuTextureView view, float x, float y, float w, float h, float radius, int tint) {
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        TextureSetup textures = TextureSetup.singleTexture(view, sampler);
        fill(path().roundedRect(x, y, w, h, radius), Paint.image(textures, x, y, w, h, 0f, 0f, 1f, 1f, tint));
    }

    // mc's own baked glyphs keep their pipeline, atlas and lightmap; we only add our transform and alpha
    public void minecraftGlyph(TextRenderable glyph) {
        if (scissoredOut()) return;
        TextureSetup textures = TextureSetup.singleTextureWithLightmap(glyph.textureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        VertexConsumer c = consumer(glyph.guiPipeline(), textures);
        VertexConsumer target = state.alpha >= 1f ? c : new AlphaConsumer(c, state.alpha);
        glyph.render(new Matrix4f().mul(state.xform), target, 0xF000F0, true);
    }

    private record AlphaConsumer(VertexConsumer delegate, float alpha) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, Math.round(a * alpha)); return this; }
        @Override public VertexConsumer setColor(int color) { delegate.setColor(scaleAlpha(color, alpha)); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
    }
}
