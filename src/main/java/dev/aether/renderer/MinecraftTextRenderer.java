package dev.aether.renderer;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.nanovg.NanoVG.*;

final class MinecraftTextRenderer implements Font.GlyphVisitor {
    private static final Matrix4fc IDENTITY = new Matrix4f();
    private static final int[] TEXTURE_PARAMETERS = {
            GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER,
            GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T,
            GL33.GL_TEXTURE_SWIZZLE_R, GL33.GL_TEXTURE_SWIZZLE_G,
            GL33.GL_TEXTURE_SWIZZLE_B, GL33.GL_TEXTURE_SWIZZLE_A
    };

    private final long vg;
    private final NVGPaint paint;
    private final Map<GlTexture, Atlas> atlases = new IdentityHashMap<>();
    private final MinecraftGlyphQuad quad = new MinecraftGlyphQuad(this::drawQuad);
    private int image;

    MinecraftTextRenderer(long vg, NVGPaint paint) {
        this.vg = vg;
        this.paint = paint;
    }

    void beginFrame() {
        atlases.entrySet().removeIf(entry -> {
            if (!entry.getKey().isClosed()) return false;
            nvgDeleteImage(vg, entry.getValue().image);
            return true;
        });
    }

    void endFrame() {
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            for (var entry : atlases.entrySet()) {
                Atlas atlas = entry.getValue();
                if (!atlas.active) continue;
                if (!entry.getKey().isClosed()) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry.getKey().glId());
                    for (int i = 0; i < TEXTURE_PARAMETERS.length; i++) {
                        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, TEXTURE_PARAMETERS[i], atlas.savedParameters[i]);
                    }
                }
                atlas.active = false;
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
        }
    }

    @Override public void acceptGlyph(TextRenderable.Styled glyph) { draw(glyph); }
    @Override public void acceptEffect(TextRenderable effect) { draw(effect); }

    private void draw(TextRenderable glyph) {
        if (!(glyph.textureView().texture() instanceof GlTexture texture) || texture.isClosed()) return;
        Atlas atlas = atlases.computeIfAbsent(texture, this::createAtlas);
        if (!atlas.active) activate(texture, atlas);
        image = atlas.image;
        glyph.render(IDENTITY, quad, 0xF000F0, true);
        quad.flush();
    }

    private Atlas createAtlas(GlTexture texture) {
        int handle = NanoVGGL3.nvglCreateImageFromHandle(vg, texture.glId(), texture.getWidth(0), texture.getHeight(0),
                NanoVGGL3.NVG_IMAGE_NODELETE | NVG_IMAGE_NEAREST);
        if (handle == 0) throw new IllegalStateException("Unable to import Minecraft font texture into NanoVG");
        return new Atlas(handle);
    }

    private void activate(GlTexture texture, Atlas atlas) {
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.glId());
            for (int i = 0; i < TEXTURE_PARAMETERS.length; i++) {
                atlas.savedParameters[i] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, TEXTURE_PARAMETERS[i]);
            }
            atlas.active = true;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
            if (texture.getFormat() == TextureFormat.RED8) {
                // Minecraft stores monochrome coverage in red; NanoVG image paints sample alpha.
                // Restore the shared texture only after NanoVG flushes its deferred draws.
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_R, GL11.GL_ONE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_G, GL11.GL_ONE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_B, GL11.GL_ONE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_A, GL11.GL_RED);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
        }
    }

    private void drawQuad(MinecraftGlyphQuad q) {
        float du = q.u[3] - q.u[0];
        float dv = q.v[1] - q.v[0];
        if (du == 0 || dv == 0 || (q.color >>> 24) == 0) return;
        nvgSave(vg);
        try {
            nvgShapeAntiAlias(vg, false);
            nvgTransform(vg, q.x[3] - q.x[0], q.y[3] - q.y[0],
                    q.x[1] - q.x[0], q.y[1] - q.y[0], q.x[0], q.y[0]);
            nvgImagePattern(vg, -q.u[0] / du, -q.v[0] / dv, 1f / du, 1f / dv, 0f, image, 1f, paint);
            NVGRenderer.color(q.color, paint.innerColor());
            NVGRenderer.color(q.color, paint.outerColor());
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, 1, 1);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        } finally {
            nvgRestore(vg);
        }
    }

    private static final class Atlas {
        final int image;
        final int[] savedParameters = new int[TEXTURE_PARAMETERS.length];
        boolean active;

        Atlas(int image) {
            this.image = image;
        }
    }
}
