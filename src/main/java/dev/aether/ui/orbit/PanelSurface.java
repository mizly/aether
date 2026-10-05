package dev.aether.ui.orbit;

import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.util.function.Consumer;

// one orbit panel's offscreen texture; nanovg draws into it, the world renderer maps it onto a quad
final class PanelSurface implements AutoCloseable {
    private int texture;
    private int framebuffer;
    private int depthStencil;
    private int width;
    private int height;

    int texture() {
        return texture;
    }

    // design units are what the panel ui lays out in; the texture holds pxRatio device pixels per unit
    void render(float designW, float designH, float pxRatio, Consumer<NVGRenderer> draw) {
        int w = Math.max(16, Math.min(2048, Math.round(designW * pxRatio)));
        int h = Math.max(16, Math.min(2048, Math.round(designH * pxRatio)));
        ensure(w, h);
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();
        int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float[] clear = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, w, h);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            NanoVGManager.beginFrame(designW, designH, w / designW);
            try {
                draw.accept(NanoVGManager.getRenderer());
            } finally {
                NanoVGManager.endFrame();
            }
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
        }
    }

    private void ensure(int w, int h) {
        if (texture != 0 && w == width && h == height) return;
        close();
        width = w;
        height = h;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL13.glActiveTexture(previousActive);

        int previousRb = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        depthStencil = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencil);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, w, h);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRb);

        int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER,
                depthStencil);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("orbit panel framebuffer incomplete: " + status);
        }
    }

    @Override
    public void close() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (depthStencil != 0) GL30.glDeleteRenderbuffers(depthStencil);
        if (texture != 0) GL11.glDeleteTextures(texture);
        framebuffer = depthStencil = texture = 0;
        width = height = 0;
    }
}
