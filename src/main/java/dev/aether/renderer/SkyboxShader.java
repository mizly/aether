package dev.aether.renderer;

import dev.aether.modules.visuals.Skybox;
import dev.aether.util.AetherResources;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SkyboxShader implements AutoCloseable {
    private int program;
    private int vao;
    private int matrixUniform;
    private int presetUniform;
    private int timeUniform;
    private int brightnessUniform;
    private boolean failed;

    public boolean draw(Matrix4f inverseViewProjection, int preset, float time, float brightness,
                        int framebuffer, int width, int height) {
        if (failed || preset < 0 || preset >= Skybox.PRESETS.size() || width <= 0 || height <= 0) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            var polygonMode = stack.mallocInt(2);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygonMode);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean discard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            var colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            try {
                if (program == 0) initialize();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL11.glViewport(0, 0, width, height);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
                GL11.glDepthMask(false);
                GL11.glColorMask(true, true, true, true);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vao);
                GL20.glUniformMatrix4fv(matrixUniform, false, inverseViewProjection.get(stack.mallocFloat(16)));
                GL20.glUniform1i(presetUniform, preset);
                GL20.glUniform1f(timeUniform, time);
                GL20.glUniform1f(brightnessUniform, brightness);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                return true;
            } catch (RuntimeException error) {
                close();
                failed = true;
                System.err.println("[Aether] Sky shader disabled: " + error.getMessage());
                return false;
            } finally {
                GL20.glUseProgram(previousProgram);
                GL30.glBindVertexArray(previousVao);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polygonMode.get(0));
                GL11.glDepthMask(depthMask);
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
                capability(GL11.GL_BLEND, blend);
                capability(GL11.GL_DEPTH_TEST, depth);
                capability(GL11.GL_CULL_FACE, cull);
                capability(GL11.GL_SCISSOR_TEST, scissor);
                capability(GL11.GL_STENCIL_TEST, stencil);
                capability(GL30.GL_RASTERIZER_DISCARD, discard);
            }
        }
    }

    private void initialize() {
        int vertex = 0;
        int fragment = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, "skybox.vsh");
            fragment = compile(GL20.GL_FRAGMENT_SHADER, "skybox.fsh");
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            matrixUniform = GL20.glGetUniformLocation(program, "InverseViewProjection");
            presetUniform = GL20.glGetUniformLocation(program, "Preset");
            timeUniform = GL20.glGetUniformLocation(program, "Time");
            brightnessUniform = GL20.glGetUniformLocation(program, "Brightness");
            vao = GL30.glGenVertexArrays();
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
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (IOException | RuntimeException error) {
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Cannot compile " + name, error);
        }
    }

    public boolean isFailed() {
        return failed;
    }

    private static void capability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    @Override
    public void close() {
        if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        program = vao = 0;
        failed = false;
    }
}
