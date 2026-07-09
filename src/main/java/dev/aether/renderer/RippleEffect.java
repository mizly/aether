package dev.aether.renderer;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.aether.util.AetherResources;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Offscreen scene FBO + GLSL shader that composites an expanding shockwave
 * ripple with Gaussian blur.
 *
 * <p>Usage per frame when ripples are active:</p>
 * <ol>
 *   <li>{@link #ensureReady(int, int)} - resize if RT dimensions changed.</li>
 *   <li>{@code NanoVGManager.setOverrideTargetFbo(rippleEffect.getSceneFbo())}
 *       - redirect NVG into the offscreen scene buffer.</li>
 *   <li>Normal NVG render + {@code NanoVGManager.endFrame()}.</li>
 *   <li>{@link #composite} - blit scene -> {@code displayFbo} with the ripple
 *       shader. {@code displayFbo} must be {@code NanoVGManager.getMainRtFbo()}
 *       (the FBO that MC actually displays - confirmed via GuiGraphicsExtractor diagnostic).</li>
 * </ol>
 */
public final class RippleEffect {

    // -- GL objects ------------------------------------------------------------

    /** Offscreen FBO that NVG renders the scene into. */
    private int sceneFbo      = 0;
    /** Color texture attached to sceneFbo - sampled by the ripple shader. */
    private int sceneColorTex = 0;
    /** Stencil renderbuffer attached to sceneFbo (NVG_STENCIL_STROKES needs it). */
    private int sceneStencil  = 0;

    private int program = 0;
    private int quadVao = 0;
    private int quadVbo = 0;

    private int texW = 0, texH = 0;

    // -- Uniform locations -----------------------------------------------------

    private int uScene, uNumRipples, uCenters, uRadii, uThicknesses, uStrengths, uAspect;

    // -- Lifecycle -------------------------------------------------------------

    /**
     * Creates or resizes the offscreen scene FBO to match the render target.
     * Safe to call every frame - no-op when already the right size.
     */
    public void ensureReady(int w, int h) {
        if (sceneFbo != 0 && w == texW && h == texH) return;

        destroyFbo();
        texW = w;
        texH = h;

        // -- Color texture -----------------------------------------------------
        sceneColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneColorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, 0x8058 /* GL_RGBA8 */, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // -- Stencil renderbuffer - required for NVG_STENCIL_STROKES -----------
        sceneStencil = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, sceneStencil);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_STENCIL_INDEX8, w, h);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        // -- Framebuffer -------------------------------------------------------
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        sceneFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, sceneColorTex, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                GL30.GL_RENDERBUFFER, sceneStencil);

        // Clear stencil so stale state from a previous use doesn't leak.
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);

        // Restore using a direct GL call - ensureReady uses direct calls
        // throughout, so GlStateManager's cache is unaffected; we must use
        // a direct call to actually restore the FBO.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);

        if (program == 0) initShaderAndQuad();
    }

    private void initShaderAndQuad() {
        int v = compile(GL20.GL_VERTEX_SHADER,   load("/assets/aether/shaders/ripple.vsh"));
        int f = compile(GL20.GL_FRAGMENT_SHADER, load("/assets/aether/shaders/ripple.fsh"));

        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, v);
        GL20.glAttachShader(program, f);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV");
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new RuntimeException("[Aether] RippleEffect link: " + GL20.glGetProgramInfoLog(program));

        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);

        uScene       = GL20.glGetUniformLocation(program, "Scene");
        uNumRipples  = GL20.glGetUniformLocation(program, "NumRipples");
        uCenters     = GL20.glGetUniformLocation(program, "Centers[0]");
        uRadii       = GL20.glGetUniformLocation(program, "Radii[0]");
        uThicknesses = GL20.glGetUniformLocation(program, "Thicknesses[0]");
        uStrengths   = GL20.glGetUniformLocation(program, "Strengths[0]");
        uAspect      = GL20.glGetUniformLocation(program, "Aspect");

        // Screen-space quad (NDC triangle strip): pos.xy  uv.xy
        FloatBuffer buf = MemoryUtil.memAllocFloat(16);
        buf.put(new float[]{
            -1f,  1f,   0f, 1f,
            -1f, -1f,   0f, 0f,
             1f,  1f,   1f, 1f,
             1f, -1f,   1f, 0f,
        }).flip();

        quadVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(quadVao);
        quadVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // -- Composite -------------------------------------------------------------

    /**
     * Reads the scene from the internal offscreen FBO (where NVG rendered) and
     * composites it with ripple distortion into {@code displayFbo}.
     *
     * <p>{@code displayFbo} must be {@code NanoVGManager.getMainRtFbo()} - the
     * FBO that MC actually displays (verified by GuiGraphicsExtractor diagnostic test).
     * All coordinates are in <b>logical</b> pixels (top-left origin).
     * {@code lToP} is the logical->physical scale factor.</p>
     */
    public void composite(int displayFbo, int w, int h,
                          float[] cx, float[] cy, float[] radius,
                          float[] thickness, float[] strength,
                          int count, float lToP) {
        if (sceneFbo == 0 || program == 0) return;
        count = Math.min(count, 4);

        float[] centers     = new float[8];
        float[] radii       = new float[4];
        float[] thicknesses = new float[4];
        float[] strengths   = new float[count];

        float scaleY = 1f / h;
        for (int i = 0; i < count; i++) {
            float physCx = cx[i] * lToP;
            float physCy = cy[i] * lToP;
            centers[i * 2]     = physCx / w;
            centers[i * 2 + 1] = 1f - physCy / h;
            radii[i]           = radius[i]    * lToP * scaleY;
            thicknesses[i]     = thickness[i] * lToP * scaleY;
            strengths[i]       = strength[i];
        }

        int prevProgram = GL11.glGetInteger(0x8B8D /* GL_CURRENT_PROGRAM */);
        int prevVao     = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        // Bind the display FBO directly - GlStateManager's cache may disagree
        // about what is current, so we use a direct GL call to ensure the bind
        // actually executes (confirmed correct by GuiGraphicsExtractor test).
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, displayFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);

        GL20.glUniform1i(uScene, 0);
        GL20.glUniform1i(uNumRipples, count);
        GL20.glUniform2fv(uCenters, centers);
        GL20.glUniform1fv(uRadii, radii);
        GL20.glUniform1fv(uThicknesses, thicknesses);
        GL20.glUniform1fv(uStrengths, strengths);
        GL20.glUniform1f(uAspect, (float) w / h);

        // Scene texture: the color attachment of our offscreen FBO - NVG
        // rendered the title screen directly into this texture.
        // Unbind any sampler on unit 0 - MC's sampler may have compare-mode set
        // (shadow maps), which would override the texture read and return black.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevSampler = GL33C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL33C.glBindSampler(0, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneColorTex);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindVertexArray(prevVao);

        // Restore state Minecraft expects.
        GL33C.glBindSampler(0, prevSampler);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GlStateManager._glUseProgram(prevProgram);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(770, 771, 1, 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
    }

    // -- Accessors -------------------------------------------------------------

    /** The offscreen FBO for NVG to render the scene into. */
    public int getSceneFbo() { return sceneFbo; }

    /** The color texture attached to the scene FBO - sampled by the ripple shader. */
    public int getSceneColorTex() { return sceneColorTex; }

    // -- Cleanup ---------------------------------------------------------------

    private void destroyFbo() {
        if (sceneFbo      != 0) { GL30.glDeleteFramebuffers(sceneFbo);      sceneFbo = 0; }
        if (sceneColorTex != 0) { GL11.glDeleteTextures(sceneColorTex);     sceneColorTex = 0; }
        if (sceneStencil  != 0) { GL30.glDeleteRenderbuffers(sceneStencil); sceneStencil = 0; }
    }

    /** Releases all GPU resources. Call from {@code Screen.removed()}. */
    public void destroy() {
        destroyFbo();
        if (quadVao != 0) { GL30.glDeleteVertexArrays(quadVao); quadVao = 0; }
        if (quadVbo != 0) { GL15.glDeleteBuffers(quadVbo);      quadVbo = 0; }
        if (program != 0) { GL20.glDeleteProgram(program);      program = 0; }
    }

    // -- Helpers ---------------------------------------------------------------

    private static int compile(int type, String src) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
            throw new RuntimeException("[Aether] RippleEffect compile: " + GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static String load(String path) {
        try (InputStream in = AetherResources.open(path)) {
            if (in == null) throw new RuntimeException("[Aether] RippleEffect: missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("[Aether] RippleEffect: failed to read " + path, e);
        }
    }
}

