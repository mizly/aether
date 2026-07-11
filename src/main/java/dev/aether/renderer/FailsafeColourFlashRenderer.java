package dev.aether.renderer;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;
import dev.aether.util.AetherResources;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class FailsafeColourFlashRenderer {
    private static int program;
    private static int colourUniform;
    private static int quadVao;
    private static boolean initializationFailed;

    private FailsafeColourFlashRenderer() {
    }

    public static void render(int argb, float opacity) {
        if (initializationFailed || opacity <= 0.0f) {
            return;
        }

        if (program == 0 && !initialize()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var target = client.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            return;
        }

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        try {
            int targetFbo = ((GlTexture) target.getColorTexture()).getFbo(resolveDirectStateAccess(), null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GL11.glViewport(0, 0, target.width, target.height);

            GL11.glEnable(GL11.GL_BLEND);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            GL20.glUseProgram(program);
            GL20.glUniform4f(colourUniform,
                    ((argb >>> 16) & 0xFF) / 255.0f,
                    ((argb >>> 8) & 0xFF) / 255.0f,
                    (argb & 0xFF) / 255.0f,
                    Math.max(0.0f, Math.min(1.0f, opacity)) * ((argb >>> 24) & 0xFF) / 255.0f);
            GL30.glBindVertexArray(quadVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[Aether] Failed to render the failsafe colour flash: " + e.getMessage());
        } finally {
            GL30.glBindVertexArray(previousVao);
            GL20.glUseProgram(previousProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);

            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            GL14.glBlendFuncSeparate(blendSourceRgb, blendDestinationRgb,
                    blendSourceAlpha, blendDestinationAlpha);
            restoreCapability(GL11.GL_BLEND, blendEnabled);
            restoreCapability(GL11.GL_CULL_FACE, cullEnabled);
            restoreCapability(GL11.GL_DEPTH_TEST, depthEnabled);
            restoreCapability(GL11.GL_SCISSOR_TEST, scissorEnabled);
            restoreCapability(GL11.GL_STENCIL_TEST, stencilEnabled);
        }
    }

    private static boolean initialize() {
        try {
            int vertexShader = compile(GL20.GL_VERTEX_SHADER,
                    load("/assets/aether/shaders/failsafe_colour_flash.vsh"));
            int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER,
                    load("/assets/aether/shaders/failsafe_colour_flash.fsh"));

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }

            colourUniform = GL20.glGetUniformLocation(program, "FlashColour");
            quadVao = GL30.glGenVertexArrays();
            return true;
        } catch (RuntimeException e) {
            initializationFailed = true;
            if (program != 0) {
                GL20.glDeleteProgram(program);
                program = 0;
            }
            System.err.println("[Aether] Failed to initialize the failsafe colour flash shader: " + e.getMessage());
            return false;
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    private static String load(String path) {
        try (InputStream stream = AetherResources.open(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing shader resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read shader resource: " + path, e);
        }
    }

    private static DirectStateAccess resolveDirectStateAccess() {
        return ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }
}
