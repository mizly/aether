package dev.aether.ui.orbit;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;

// the gl state a raw draw inside minecraft's frame touches, captured before and put back after so the game's own
// passes find everything as they left it
final class GlSnapshot {
    private final int program, vao, buffer, drawFbo, active, texture, sampler;
    private final boolean blend, depth, cull, scissor, stencil, depthMask;
    private final int depthFunc, srcRgb, dstRgb, srcAlpha, dstAlpha, eqRgb, eqAlpha;
    private final boolean[] colorMask = new boolean[4];
    private final int[] viewport = new int[4];
    private final float[] clearColor = new float[4];
    private final double clearDepth;

    private GlSnapshot() {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        buffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        sampler = GL11.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        java.nio.ByteBuffer m = org.lwjgl.BufferUtils.createByteBuffer(16);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
        for (int i = 0; i < 4; i++) colorMask[i] = m.get(i) != 0;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
    }

    static GlSnapshot capture() {
        return new GlSnapshot();
    }

    void restore() {
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33C.glBindSampler(0, sampler);
        GL13.glActiveTexture(active);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glDepthMask(depthMask);
        GL11.glDepthFunc(depthFunc);
        GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        GL11.glClearDepth(clearDepth);
        set(GL11.GL_BLEND, blend);
        set(GL11.GL_DEPTH_TEST, depth);
        set(GL11.GL_CULL_FACE, cull);
        set(GL11.GL_SCISSOR_TEST, scissor);
        set(GL11.GL_STENCIL_TEST, stencil);
    }

    private static void set(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }
}
