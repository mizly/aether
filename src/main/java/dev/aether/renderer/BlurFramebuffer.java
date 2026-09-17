package dev.aether.renderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

// offscreen fbo for capturing a region of the screen to post-process
// NVGRenderer.blur() is a cheaper frosted-glass fake; use this when you need the real thing
public final class BlurFramebuffer {

    private int fboId   = -1;
    private int texId   = -1;
    private int depthId = -1;

    private int width;
    private int height;

    // -- Construction ----------------------------------------------------------

    // throws when the fbo comes back incomplete
    public BlurFramebuffer(int width, int height) {
        this.width  = width;
        this.height = height;
        create();
    }

    private void create() {
        // Colour texture
        texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Depth renderbuffer
        depthId = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthId);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, width, height);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        // FBO
        fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texId, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_RENDERBUFFER, depthId);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("[aether] BlurFramebuffer incomplete: status=0x" + Integer.toHexString(status));
        }
    }

    // -- Frame binding ---------------------------------------------------------

    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
    }

    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public void clear() {
        bind();
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        unbind();
    }

    // -- Resize ----------------------------------------------------------------

    // no-op when the size has not changed
    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        destroy();
        width  = newWidth;
        height = newHeight;
        create();
    }

    // -- Accessors -------------------------------------------------------------

    public int getTextureId() { return texId; }

    public int getFboId() { return fboId; }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }

    // -- Lifecycle -------------------------------------------------------------

    // after this the object must not be used
    public void destroy() {
        if (fboId   != -1) { GL30.glDeleteFramebuffers(fboId);   fboId   = -1; }
        if (texId   != -1) { GL11.glDeleteTextures(texId);       texId   = -1; }
        if (depthId != -1) { GL30.glDeleteRenderbuffers(depthId); depthId = -1; }
    }
}
