package dev.aether.ui.orbit;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;
import dev.aether.util.AetherResources;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

// draws orbit panels as textured quads inside the level pass, depth-tested so the player stands in front of them
final class OrbitWorldRenderer implements AutoCloseable {
    // bend pushes the left and right edges along bendDir by that much, curving the quad across its width
    record Quad(Vector3d topLeft, Vector3d topRight, Vector3d bottomRight, Vector3d bottomLeft, int texture,
                float alpha, float dim, Vector3d bendDir, double bend) {
        Quad(Vector3d topLeft, Vector3d topRight, Vector3d bottomRight, Vector3d bottomLeft, int texture, float alpha,
             float dim) {
            this(topLeft, topRight, bottomRight, bottomLeft, texture, alpha, dim, null, 0);
        }
    }

    private static final int BEND_COLUMNS = 16;

    private final Matrix4f projection = new Matrix4f();
    private int program;
    private int vao;
    private int vbo;
    private int matrixUniform;
    private int tintUniform;
    private int dimUniform;
    private int samplerUniform;
    private boolean failed;

    boolean failed() {
        return failed;
    }

    void draw(List<Quad> quads) {
        if (failed || quads.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        var target = client.getMainRenderTarget();
        if (!(target.getColorTexture() instanceof GlTexture color) || !(target.getDepthTexture() instanceof GlTexture)) {
            return;
        }
        var camera = client.gameRenderer.getMainCamera();
        var eye = camera.position();
        camera.getViewRotationProjectionMatrix(projection);
        var access = ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
        int framebuffer = color.getFbo(access, target.getDepthTexture());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int previousDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int previousSampler = GL11.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            int eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            var colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            try {
                if (program == 0) initialize();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL11.glViewport(0, 0, target.width, target.height);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(false);
                GL11.glColorMask(true, true, true, true);
                GL11.glEnable(GL11.GL_BLEND);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                        GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL33C.glBindSampler(0, 0);
                GL20.glUseProgram(program);
                GL20.glUniformMatrix4fv(matrixUniform, false, projection.get(stack.mallocFloat(16)));
                GL20.glUniform1i(samplerUniform, 0);
                GL30.glBindVertexArray(vao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                FloatBuffer data = stack.mallocFloat(6 * 5 * BEND_COLUMNS);
                for (Quad q : quads) {
                    if (q.texture() == 0 || q.alpha() <= 0.001f) continue;
                    data.clear();
                    int columns = q.bend() > 0 && q.bendDir() != null ? BEND_COLUMNS : 1;
                    for (int c = 0; c < columns; c++) {
                        float u0 = (float) c / columns, u1 = (float) (c + 1) / columns;
                        Vector3d t0 = edge(q.topLeft(), q.topRight(), u0, q), t1 = edge(q.topLeft(), q.topRight(), u1, q);
                        Vector3d b0 = edge(q.bottomLeft(), q.bottomRight(), u0, q), b1 = edge(q.bottomLeft(), q.bottomRight(), u1, q);
                        // framebuffer textures store rows bottom-up, so the panel's top edge samples v = 1
                        put(data, t0, eye, u0, 1);
                        put(data, b0, eye, u0, 0);
                        put(data, b1, eye, u1, 0);
                        put(data, t0, eye, u0, 1);
                        put(data, b1, eye, u1, 0);
                        put(data, t1, eye, u1, 1);
                    }
                    data.flip();
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, q.texture());
                    float a = q.alpha();
                    GL20.glUniform4f(tintUniform, a, a, a, a);
                    GL20.glUniform1f(dimUniform, q.dim());
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6 * columns);
                }
            } catch (RuntimeException error) {
                close();
                failed = true;
                System.err.println("[Aether] Orbit panel renderer disabled: " + error.getMessage());
            } finally {
                GL30.glBindVertexArray(previousVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                GL20.glUseProgram(previousProgram);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL33C.glBindSampler(0, previousSampler);
                GL13.glActiveTexture(previousActive);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFbo);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                GL11.glDepthMask(depthMask);
                GL11.glDepthFunc(depthFunc);
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0,
                        colorMask.get(3) != 0);
                GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
                GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                capability(GL11.GL_BLEND, blend);
                capability(GL11.GL_DEPTH_TEST, depth);
                capability(GL11.GL_CULL_FACE, cull);
                capability(GL11.GL_SCISSOR_TEST, scissor);
                capability(GL11.GL_STENCIL_TEST, stencil);
            }
        }
    }

    private static Vector3d edge(Vector3d a, Vector3d b, float u, Quad q) {
        Vector3d p = new Vector3d(a).lerp(b, u);
        if (q.bend() <= 0 || q.bendDir() == null) return p;
        double k = u * 2 - 1;
        return p.fma(q.bend() * k * k, q.bendDir());
    }

    private static void put(FloatBuffer data, Vector3d p, net.minecraft.world.phys.Vec3 eye, float u, float v) {
        data.put((float) (p.x - eye.x)).put((float) (p.y - eye.y)).put((float) (p.z - eye.z)).put(u).put(v);
    }

    private void initialize() {
        int vertex = 0, fragment = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, "orbit_panel.vsh");
            fragment = compile(GL20.GL_FRAGMENT_SHADER, "orbit_panel.fsh");
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            matrixUniform = GL20.glGetUniformLocation(program, "ViewProjection");
            tintUniform = GL20.glGetUniformLocation(program, "Tint");
            dimUniform = GL20.glGetUniformLocation(program, "Dim");
            samplerUniform = GL20.glGetUniformLocation(program, "Panel");
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            int stride = 5 * Float.BYTES;
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String name) {
        int shader = GL20.glCreateShader(type);
        try (var stream = AetherResources.open("/assets/aether/shaders/" + name)) {
            if (stream == null) throw new IllegalStateException("missing " + name);
            GL20.glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (IOException | RuntimeException error) {
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("cannot compile " + name, error);
        }
    }

    private static void capability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    @Override
    public void close() {
        if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        program = vao = vbo = 0;
    }
}
