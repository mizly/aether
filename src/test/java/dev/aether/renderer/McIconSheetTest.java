package dev.aether.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.ui.util.Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// renders every McIcons constant plus the gui texture helpers to build/reports/icons/icon-sheet.png for eyeballing
@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class McIconSheetTest {
    private static final int WIDTH = 1880;
    private static final int HEIGHT = 2000;
    private static final int[] SIZES = {16, 24, 32, 48};
    // at 1x a 24px icon snaps to 32px (two device pixels per texel), so each size gets its snapped width plus a gap
    private static final int[] SIZE_ADVANCE = {22, 38, 38, 54};
    private static final int COLUMNS = 12;
    private static final int CELL_W = 154;
    private static final int CELL_H = 72;
    private static final int DARK = 0xFF1E1F22;
    private static final int SLOT_GRAY = 0xFFC6C6C6;

    private static long window;
    private static GLFWErrorCallback errors;
    private static int fbo;
    private static int colorTexture;
    private static int depthStencil;

    @BeforeAll
    static void createContext() {
        errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        if (System.getenv("DISPLAY") != null && System.getProperty("os.name").equals("Linux")) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "Aether icon sheet", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        NanoVGManager.init();

        colorTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        depthStencil = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencil);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthStencil);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
    }

    @AfterAll
    static void destroyContext() {
        NanoVGManager.destroy();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (depthStencil != 0) GL30.glDeleteRenderbuffers(depthStencil);
        if (colorTexture != 0) GL11.glDeleteTextures(colorTexture);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        if (errors != null) errors.free();
    }

    @Test
    void rendersEveryIconConstantToASheet() throws Exception {
        List<Map.Entry<String, McIcon>> icons = McIconsTest.constants();
        int rows = (icons.size() + COLUMNS - 1) / COLUMNS;
        int gridHeight = rows * CELL_H + 40;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glClearColor(0.07f, 0.07f, 0.08f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        NanoVGManager.beginFrame(WIDTH, HEIGHT, 1f);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            grid(nvg, icons, 0, DARK, 0xFFB8BCC4, "dark");
            grid(nvg, icons, gridHeight, SLOT_GRAY, 0xFF404040, "inventory gray");
            extras(nvg, gridHeight * 2);
        } finally {
            NanoVGManager.endFrame();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

        BufferedImage sheet = capture();
        Path out = Path.of("build/reports/icons/icon-sheet.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());

        for (int i = 0; i < icons.size(); i++) {
            int x = (i % COLUMNS) * CELL_W + 12 + SIZE_ADVANCE[0] + SIZE_ADVANCE[1] + SIZE_ADVANCE[2];
            int y = 40 + (i / COLUMNS) * CELL_H + 6;
            assertTrue(inked(sheet, x, y, 48, DARK) > 30, icons.get(i).getKey() + " drew at 48px");
        }
    }

    private static void grid(NVGRenderer nvg, List<Map.Entry<String, McIcon>> icons, int top, int background,
                             int label, String title) {
        int rows = (icons.size() + COLUMNS - 1) / COLUMNS;
        nvg.rect(0, top, WIDTH, rows * CELL_H + 40, background);
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "McIcons at 16 / 24 / 32 / 48 px on " + title, 12, top + 12, 14, label);
        for (int i = 0; i < icons.size(); i++) {
            float x = (i % COLUMNS) * CELL_W + 12;
            float y = top + 40 + (i / COLUMNS) * CELL_H;
            float iconX = x;
            for (int s = 0; s < SIZES.length; s++) {
                int size = SIZES[s];
                float drawn = nvg.mcIconSnap(size);
                nvg.mcIcon(icons.get(i).getValue(), iconX + (drawn - size) / 2f, y + 6 + (48 - drawn) + (drawn - size) / 2f,
                        size, McIcon.UNTINTED);
                iconX += SIZE_ADVANCE[s];
            }
            String name = icons.get(i).getKey().toLowerCase().replace("_stained_glass", "_glass");
            nvg.textLiteral(Fonts.UI_REGULAR, name, x, y + 57, 10, label);
        }
    }

    private static void extras(NVGRenderer nvg, int top) {
        nvg.rect(0, top, WIDTH, HEIGHT - top, DARK);
        int text = 0xFFB8BCC4;

        // the vanilla 6-row chest, scaled 2x: container rows plus the player inventory strip, as ContainerScreen blits it
        McTextures.Texture chest = McTextures.get("minecraft:textures/gui/container/generic_54.png");
        float cx = 12;
        float cy = top + 30;
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "generic_54 at 2x with items", cx, top + 8, 14, text);
        nvg.save();
        nvg.translate(cx, cy);
        nvg.scale(2f, 2f);
        nvg.imageRegion(chest.handle(), chest.width(), chest.height(), 0, 0, 176, 125, 0, 0, 176, 125, McIcon.UNTINTED);
        nvg.imageRegion(chest.handle(), chest.width(), chest.height(), 0, 126, 176, 96, 0, 125, 176, 96, McIcon.UNTINTED);
        McIcon[] row = {McIcons.WHEAT, McIcons.CARROT, McIcons.POTATO, McIcons.PUMPKIN, McIcons.MELON, McIcons.SUGAR_CANE,
                McIcons.CACTUS, McIcons.COCOA_BEANS, McIcons.NETHER_WART};
        McIcon[] plots = {McIcons.BLACK_STAINED_GLASS_PANE, McIcons.BLACK_STAINED_GLASS_PANE, McIcons.LIME_STAINED_GLASS_PANE,
                McIcons.ORANGE_STAINED_GLASS_PANE, McIcons.DARK_OAK_PLANKS, McIcons.RED_STAINED_GLASS_PANE,
                McIcons.OAK_BUTTON, McIcons.BLACK_STAINED_GLASS_PANE, McIcons.BLACK_STAINED_GLASS_PANE};
        McIcon[] tools = {McIcons.DIAMOND_HOE, McIcons.GOLDEN_HOE, McIcons.NETHERITE_HOE, McIcons.GRASS_BLOCK,
                McIcons.CHEST, McIcons.PLAYER_HEAD, McIcons.RED_BED, McIcons.COMPOSTER, McIcons.WHITE_STAINED_GLASS};
        McIcon[] bottom = {McIcons.BARRIER, McIcons.ARROW, McIcons.COMPASS, McIcons.CLOCK, McIcons.LIME_DYE,
                McIcons.GRAY_DYE, McIcons.LAVA_BUCKET, McIcons.FILLED_MAP, McIcons.ENCHANTED_BOOK};
        McIcon[][] rows = {row, plots, tools, bottom};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 9; c++) nvg.mcIcon(rows[r][c], 8 + c * 18, 18 + r * 18, 16, McIcon.UNTINTED);
        }
        nvg.restore();

        // nine-slice samples: tooltip background + frame and the stretched button
        float nx = 400;
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "nine-slice: tooltip background + frame, button", nx, top + 8, 14, text);
        McTextures.Texture background = McTextures.get("minecraft:textures/gui/sprites/tooltip/background.png");
        McTextures.Texture frame = McTextures.get("minecraft:textures/gui/sprites/tooltip/frame.png");
        McTextures.Texture button = McTextures.get("minecraft:textures/gui/sprites/widget/button.png");
        float[][] boxes = {{nx, top + 30, 180, 60}, {nx + 200, top + 30, 90, 140}, {nx, top + 110, 40, 30}};
        for (float[] box : boxes) {
            nvg.nineSlice(background.handle(), 100, 100, 0, 0, 100, 100, 9, 9, 9, 9, box[0], box[1], box[2], box[3], McIcon.UNTINTED);
            nvg.nineSlice(frame.handle(), 100, 100, 0, 0, 100, 100, 10, 10, 10, 10, box[0], box[1], box[2], box[3], McIcon.UNTINTED);
        }
        nvg.nineSlice(button.handle(), 200, 20, 0, 0, 200, 20, 3, 3, 3, 3, nx, top + 190, 150, 20, McIcon.UNTINTED);
        nvg.save();
        nvg.translate(nx, top + 220);
        nvg.scale(2f, 2f);
        nvg.nineSlice(button.handle(), 200, 20, 0, 0, 200, 20, 3, 3, 3, 3, 0, 0, 140, 20, McIcon.UNTINTED);
        nvg.restore();

        // glint over time, tint and fade, and fractional-scale snapping
        float gx = 720;
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "glint at t = 0.3, 0.7, 1.1, 1.5, 1.9 s", gx, top + 8, 14, text);
        McIcon[] glinted = {McIcons.DIAMOND_SWORD, McIcons.GOLDEN_HOE, McIcons.ENCHANTED_BOOK, McIcons.GRASS_BLOCK,
                McIcons.CHEST, McIcons.PLAYER_HEAD};
        for (int i = 0; i < glinted.length; i++) {
            for (int t = 0; t < 5; t++) {
                nvg.mcIconGlint(glinted[i], gx + t * 56, top + 30 + i * 52, 48, McIcon.UNTINTED, 0.3f + t * 0.4f);
            }
        }
        float tx = 1040;
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "tint and alpha (paint alpha, inside a 0.5 globalAlpha)", tx, top + 8, 14, text);
        int[] tints = {McIcon.UNTINTED, 0x80FFFFFF, 0xFFFF7070, 0xFF70A0FF};
        for (int i = 0; i < tints.length; i++) {
            nvg.mcIcon(McIcons.WHEAT, tx + i * 56, top + 30, 48, tints[i]);
            nvg.mcIcon(McIcons.PUMPKIN, tx + i * 56, top + 84, 48, tints[i]);
        }
        nvg.save();
        nvg.globalAlpha(0.5f);
        nvg.mcIcon(McIcons.PUMPKIN, tx, top + 138, 48, 0x80FFFFFF);
        nvg.mcIcon(McIcons.CHEST, tx + 56, top + 138, 48, McIcon.UNTINTED);
        nvg.restore();

        nvg.textLiteral(Fonts.UI_SEMIBOLD, "ui scale 1.5: asked 10.67 / 16 / 21.33 / 32 units", tx, top + 200, 14, text);
        nvg.save();
        nvg.translate(tx, top + 224);
        nvg.scale(1.5f, 1.5f);
        float[] asked = {32f / 3f, 16f, 64f / 3f, 32f};
        float px = 0.3f;
        for (float size : asked) {
            nvg.mcIcon(McIcons.DIAMOND_HOE, px, 0.4f, size, McIcon.UNTINTED);
            nvg.mcIcon(McIcons.CRAFTING_TABLE, px, 36.4f, size, McIcon.UNTINTED);
            px += 36f;
        }
        assertEquals(64f / 3f, nvg.mcIconSnap(16f), 1e-3f, "24 device px rounds to two pixels per texel");
        assertEquals(32f / 3f, nvg.mcIconSnap(32f / 3f), 1e-3f);
        nvg.restore();
        assertEquals(16f, nvg.mcIconSnap(16f), 1e-4f);
        assertEquals(10f, nvg.mcIconSnap(10f), 1e-4f, "below 16 device px it only rounds to whole pixels");
    }

    private static int inked(BufferedImage image, int x, int y, int size, int background) {
        int count = 0;
        for (int py = y; py < y + size; py++) {
            for (int px = x; px < x + size; px++) {
                if ((image.getRGB(px, py) & 0xFFFFFF) != (background & 0xFFFFFF)) count++;
            }
        }
        return count;
    }

    private static BufferedImage capture() {
        ByteBuffer data = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int i = ((HEIGHT - y - 1) * WIDTH + x) * 4;
                image.setRGB(x, y, (data.get(i) & 255) << 16 | (data.get(i + 1) & 255) << 8 | data.get(i + 2) & 255);
            }
        }
        return image;
    }
}
