package dev.aether.renderer;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.nanovg.NanoVG.*;

@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class MinecraftTextRendererTest {
    private static long window;
    private static long vg;
    private static GLFWErrorCallback errors;

    @BeforeAll
    static void createContext() {
        errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        if (System.getenv("DISPLAY") != null && System.getProperty("os.name").equals("Linux")) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }
        assertTrue(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "Aether rendering test", 0, 0);
        assertNotEquals(0, window, "OpenGL context creation");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        vg = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
        assertNotEquals(0, vg);
    }

    @AfterAll
    static void destroyContext() {
        if (vg != 0) NanoVGGL3.nvgDelete(vg);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        if (errors != null) errors.free();
    }

    @Test
    void drawsMonochromeCoverageAndRestoresMinecraftTextureState() {
        renderAndCheck(TextureFormat.RED8, GL33.GL_R8, GL11.GL_RED, new byte[]{-1}, 0xFF12AB34, 0xFF12AB34);
    }

    @Test
    void retainsColoredResourcePackIconPixels() {
        renderAndCheck(TextureFormat.RGBA8, GL11.GL_RGBA8, GL11.GL_RGBA, new byte[]{33, 117, -25, -1}, -1, 0xFF2175E7);
    }

    private void renderAndCheck(TextureFormat format, int internalFormat, int pixelFormat, byte[] pixel, int tint, int expected) {
        int textureId = GL11.glGenTextures();
        try (MemoryStack stack = MemoryStack.stackPush(); NVGPaint paint = NVGPaint.malloc()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            ByteBuffer data = stack.malloc(4 * 4 * pixel.length);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    for (byte channel : pixel) data.put(x > 0 && x < 3 && y > 0 && y < 3 ? channel : (byte) 0);
                }
            }
            data.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, 4, 4, 0, pixelFormat, GL11.GL_UNSIGNED_BYTE, data);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            var texture = new TestTexture(textureId, format);
            var view = new TestView(texture);
            var glyph = new BakedSheetGlyph(null, null, view, 0.25f, 0.75f, 0.25f, 0.75f, 0, 8, 0, 8);
            var renderer = new MinecraftTextRenderer(vg, paint);
            GL11.glViewport(0, 0, 64, 64);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

            renderer.beginFrame();
            nvgBeginFrame(vg, 64, 64, 1);
            try {
                renderer.acceptGlyph(glyph.createGlyph(10, 20, tint, 0, Style.EMPTY, 0, 0));
                nvgEndFrame(vg);
            } finally {
                renderer.endFrame();
            }

            ByteBuffer result = stack.malloc(4);
            for (int offset : new int[]{1, 4, 7}) {
                GL11.glReadPixels(10 + offset, 64 - (20 + offset) - 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, result);
                int actual = (result.get(3) & 255) << 24 | (result.get(0) & 255) << 16
                        | (result.get(1) & 255) << 8 | result.get(2) & 255;
                assertEquals(expected, actual, "Glyph must sample only its own atlas region");
            }
            GL11.glReadPixels(9, 64 - 24 - 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, result);
            assertEquals(0, result.getInt(0), "Glyph must stay within vanilla bounds");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            assertEquals(GL11.GL_LINEAR, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
            assertEquals(GL11.GL_LINEAR, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER));
            assertEquals(GL11.GL_REPEAT, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S));
            assertEquals(GL11.GL_RED, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_R));
            assertEquals(GL11.GL_ALPHA, GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_A));
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

            texture.close();
            renderer.beginFrame();
            assertTrue(GL11.glIsTexture(textureId), "Pruning NVG handles must not delete Minecraft's texture");
        } finally {
            GL11.glDeleteTextures(textureId);
        }
    }

    private static final class TestTexture extends GlTexture {
        TestTexture(int id, TextureFormat format) {
            super(USAGE_TEXTURE_BINDING, "Test font", format, 4, 4, 1, 1, id);
        }
        @Override public void close() { closed = true; }
    }

    private static final class TestView extends GpuTextureView {
        TestView(GlTexture texture) { super(texture, 0, 1); }
        @Override public void close() { }
        @Override public boolean isClosed() { return texture().isClosed(); }
    }
}
