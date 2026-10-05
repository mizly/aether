package dev.aether.ui.gui.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.ui.gui.Backdrop;
import dev.aether.ui.gui.GuiView;
import dev.aether.ui.gui.ManualClock;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

// draws GuiView frames into an offscreen framebuffer behind a hidden GLFW window and reads them back.
// one layout unit is one pixel; the world behind the gui is a flat colour with the view's scrim on top
final class PreviewHarness implements AutoCloseable {
    private static final float[] WORLD = {0.20f, 0.24f, 0.22f};

    private final long window;
    private final GLFWErrorCallback errors;
    private int framebuffer;
    private int colour;
    private int depthStencil;
    private int width;
    private int height;

    private PreviewHarness(long window, GLFWErrorCallback errors) {
        this.window = window;
        this.errors = errors;
    }

    static PreviewHarness start() {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        if (System.getenv("DISPLAY") != null && "Linux".equals(System.getProperty("os.name"))) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("GLFW could not start; on NixOS set LD_LIBRARY_PATH as the preview README says");
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "Aether GUI preview", 0L, 0L);
        if (window == 0L) {
            GLFW.glfwTerminate();
            throw new IllegalStateException("no OpenGL 3.3 window; on NixOS set LD_LIBRARY_PATH as the preview README says");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        // GlStateManager asserts the render thread, which the offscreen beginFrame will go through
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
        NanoVGManager.init();
        return new PreviewHarness(window, errors);
    }

    // runs the scenario against a fresh view and returns its last frame
    BufferedImage capture(Scenario scenario, GuiView view, ManualClock clock) {
        useFramebuffer(scenario.width(), scenario.height());
        view.open(null);
        PreviewSession session = new PreviewSession(view, clock, scenario.width(), scenario.height(), this::draw);
        session.frame();
        scenario.steps().forEach(step -> step.accept(session));
        session.frame();
        return readPixels();
    }

    private void draw(GuiView view, float viewWidth, float viewHeight, float mouseX, float mouseY) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(WORLD[0], WORLD[1], WORLD[2], 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        long vg = NanoVGManager.getVg();
        // swap for NanoVGManager.beginFrame(w, h, pxRatio) once the renderer's offscreen overload lands
        NanoVG.nvgBeginFrame(vg, viewWidth, viewHeight, 1f);
        NanoVG.nvgTextAlign(vg, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_TOP);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        // stands in for the vanilla fill the screen shell draws over the blurred world
        if (view.backdrop() != Backdrop.NONE) {
            nvg.rect(0f, 0f, viewWidth, viewHeight, view.scrimArgb());
        }
        view.render(nvg, viewWidth, viewHeight, mouseX, mouseY);
        NanoVG.nvgEndFrame(vg);
    }

    private void useFramebuffer(int newWidth, int newHeight) {
        if (framebuffer != 0 && newWidth == width && newHeight == height) {
            return;
        }
        deleteFramebuffer();
        width = newWidth;
        height = newHeight;
        framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        colour = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colour);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colour);
        // nanovg fills and stencil strokes need the stencil buffer
        depthStencil = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencil);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthStencil);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("preview framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private BufferedImage readPixels() {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = ((height - 1 - y) * width + x) * 4;
                    int rgb = (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | (pixels.get(i + 2) & 0xFF);
                    image.setRGB(x, y, rgb);
                }
            }
            return image;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private void deleteFramebuffer() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            GL30.glDeleteRenderbuffers(colour);
            GL30.glDeleteRenderbuffers(depthStencil);
            framebuffer = 0;
        }
    }

    @Override
    public void close() {
        deleteFramebuffer();
        NanoVGManager.destroy();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        errors.free();
    }
}
