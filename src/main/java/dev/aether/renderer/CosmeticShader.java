package dev.aether.renderer;

import dev.aether.util.AetherResources;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One bounded upload, one opaque wing draw and one additive effect draw. */
public final class CosmeticShader implements AutoCloseable {
    private int program;
    private int vao;
    private int vbo;
    private int matrixUniform;
    private boolean failed;

    public void draw(CosmeticMesh mesh, Matrix4f projection, int framebuffer, int width, int height,
                     int wingVertices) {
        if (mesh.size() == 0 || failed) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int previousDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean offset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            var colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            try {
                if (program == 0) initialize();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL11.glViewport(0, 0, width, height);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glColorMask(true, true, true, true);
                GL20.glUseProgram(program);
                GL20.glUniformMatrix4fv(matrixUniform, false, projection.get(stack.mallocFloat(16)));
                GL30.glBindVertexArray(vao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                        (long) CosmeticMesh.MAX_VERTICES * CosmeticMesh.FLOATS_PER_VERTEX * Float.BYTES, GL15.GL_STREAM_DRAW);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, mesh.data());
                if (wingVertices > 0) {
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glDepthMask(true);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, wingVertices);
                }
                if (mesh.size() > wingVertices) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glDepthMask(false);
                    GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                    GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, wingVertices, mesh.size() - wingVertices);
                }
            } catch (RuntimeException error) {
                close();
                failed = true;
                System.err.println("[Aether] Cosmetic shader disabled: " + error.getMessage());
            } finally {
                GL30.glBindVertexArray(previousVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                GL20.glUseProgram(previousProgram);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFbo);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                GL11.glDepthMask(depthMask);
                GL11.glDepthFunc(depthFunc);
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
                GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
                GL14.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
                capability(GL11.GL_BLEND, blend);
                capability(GL11.GL_DEPTH_TEST, depth);
                capability(GL11.GL_CULL_FACE, cull);
                capability(GL11.GL_SCISSOR_TEST, scissor);
                capability(GL11.GL_STENCIL_TEST, stencil);
                capability(GL11.GL_POLYGON_OFFSET_FILL, offset);
            }
        }
    }

    public boolean isFailed() { return failed; }

    private void initialize() {
        int vertex = 0, fragment = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, "cosmetic_world.vsh");
            fragment = compile(GL20.GL_FRAGMENT_SHADER, "cosmetic_world.fsh");
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            matrixUniform = GL20.glGetUniformLocation(program, "ViewProjection");
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            int stride = CosmeticMesh.FLOATS_PER_VERTEX * Float.BYTES;
            int[] sizes = {3, 2, 4, 3};
            int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                GL20.glEnableVertexAttribArray(i);
                GL20.glVertexAttribPointer(i, sizes[i], GL11.GL_FLOAT, false, stride, (long) offset * Float.BYTES);
                offset += sizes[i];
            }
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String name) {
        int shader = GL20.glCreateShader(type);
        try (var stream = AetherResources.open("/assets/aether/shaders/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing " + name);
            GL20.glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            return shader;
        } catch (IOException | RuntimeException error) {
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Cannot compile " + name, error);
        }
    }

    private static void capability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    @Override public void close() {
        if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        program = vao = vbo = 0;
        failed = false;
    }
}
