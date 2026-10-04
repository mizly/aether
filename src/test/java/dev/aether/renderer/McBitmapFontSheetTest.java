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
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

// renders McBitmapFont samples to build/reports/font/font-sheet.png and checks whole strings pixel for pixel
// against a software blit of the font textures
@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class McBitmapFontSheetTest {
    private static final int WIDTH = 1400;
    private static final int HEIGHT = 1000;
    private static final int BACKGROUND = 0xFF202124;
    private static final int TITLE = 0xFF404040;

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
        window = GLFW.glfwCreateWindow(64, 64, "Aether font sheet", 0, 0);
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
    void rendersTheFontSheet() throws Exception {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        NanoVGManager.beginFrame(WIDTH, HEIGHT, 1f);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            nvg.rect(0, 0, WIDTH, HEIGHT, BACKGROUND);
            chestTitles(nvg, 12, 12);
            tooltips(nvg, 12, 300);
            mixed(nvg, 700, 12);
            exactStrings(nvg, 700, 640);
        } finally {
            NanoVGManager.endFrame();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

        BufferedImage sheet = capture();
        Path out = Path.of("build/reports/font/font-sheet.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());

        for (int scale = 1; scale <= 3; scale++) {
            int x = 700 + 8;
            int y = 640 + 30 + (scale - 1) * 40;
            assertBlitMatches(sheet, "Configure Plots", x, y, scale, 0xFFFFFFFF, true);
            assertBlitMatches(sheet, "§aPlot §7- §b5", x + 300, y, scale, 0xFFFFFFFF, true);
        }
    }

    @Test
    void rendersVanillaWidgetSprites() throws Exception {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        NanoVGManager.beginFrame(WIDTH, HEIGHT, 1f);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            nvg.rect(0, 0, WIDTH, HEIGHT, BACKGROUND);
            nvg.textLiteral(Fonts.UI_SEMIBOLD, "guiSprite at 2x: nine-slice with tiled middles, stretch_inner frame, plain sprites",
                    12, 12, 14, 0xFFB8BCC4);
            nvg.save();
            nvg.translate(12, 40);
            nvg.scale(2f, 2f);
            String[] buttons = {"widget/button", "widget/button_highlighted", "widget/button_disabled"};
            float[] widths = {60, 150, 300};
            float y = 0;
            for (float width : widths) {
                float x = 0;
                for (String button : buttons) {
                    nvg.guiSprite("minecraft:" + button, x, y, width, 20, McIcon.UNTINTED);
                    String label = width < 100 ? "Done" : "Leave One Pest Plots: ON";
                    int color = button.endsWith("disabled") ? 0xFFA0A0A0 : 0xFFFFFFFF;
                    nvg.mcText(label, x + (width - McBitmapFont.width(label, 1)) / 2f, y + 6, 1, color, true);
                    x += width + 4;
                    if (width == 300) break;
                }
                y += 24;
            }
            nvg.guiSprite("minecraft:widget/slider", 0, y, 150, 20, McIcon.UNTINTED);
            nvg.guiSprite("minecraft:widget/slider_handle", 60, y, 8, 20, McIcon.UNTINTED);
            String value = "Rotation Speed: 45%";
            nvg.mcText(value, (150 - McBitmapFont.width(value, 1)) / 2f, y + 6, 1, 0xFFFFFFFF, true);
            nvg.guiSprite("minecraft:widget/text_field_highlighted", 154, y, 150, 20, McIcon.UNTINTED);
            nvg.mcTextLiteral("/warp garden§a_", 158, y + 6, 1, 0xFFE0E0E0, true);
            nvg.guiSprite("minecraft:widget/checkbox_selected", 308, y, 20, 20, McIcon.UNTINTED);
            nvg.guiSprite("minecraft:widget/checkbox_highlighted", 332, y, 20, 20, McIcon.UNTINTED);
            y += 24;
            nvg.guiSprite("minecraft:widget/tab_selected", 0, y, 90, 24, McIcon.UNTINTED);
            nvg.guiSprite("minecraft:widget/tab", 92, y, 90, 24, McIcon.UNTINTED);
            nvg.mcText("Farming", 45 - McBitmapFont.width("Farming", 1) / 2f, y + 8, 1, 0xFFFFFFFF, true);
            nvg.mcText("Pests", 137 - McBitmapFont.width("Pests", 1) / 2f, y + 8, 1, 0xFFA0A0A0, true);
            nvg.guiSprite("minecraft:widget/scroller_background", 190, y, 6, 60, McIcon.UNTINTED);
            nvg.guiSprite("minecraft:widget/scroller", 190, y + 10, 6, 20, McIcon.UNTINTED);
            float tipX = 210;
            String[] tip = {"§aPlot §7- §b5", "§7Greenhouse Plot", "", "§eClick to open!"};
            float tipW = 0;
            for (String line : tip) tipW = Math.max(tipW, McBitmapFont.width(line, 1));
            float tipH = tip.length * 10 + 2 - 10 + 8;
            // vanilla pads tooltip text by 3 inside the 9-pixel frame sprite
            nvg.guiSprite("minecraft:tooltip/background", tipX - 12, y + 6 - 12, tipW + 24, tipH + 24, McIcon.UNTINTED);
            nvg.guiSprite("minecraft:tooltip/frame", tipX - 12, y + 6 - 12, tipW + 24, tipH + 24, McIcon.UNTINTED);
            float lineY = y + 6;
            for (int i = 0; i < tip.length; i++) {
                nvg.mcText(tip[i], tipX, lineY, 1, 0xFFFFFFFF, true);
                lineY += i == 0 ? 12 : 10;
            }
            nvg.restore();
        } finally {
            NanoVGManager.endFrame();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        BufferedImage sheet = capture();
        Path out = Path.of("build/reports/font/gui-sprites.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());
    }

    // the real chest title: generic_54's top strip at 1-3x with the title at (8, 6) in 0x404040, no shadow
    private static void chestTitles(NVGRenderer nvg, float x, float y) {
        McTextures.Texture chest = McTextures.get("minecraft:textures/gui/container/generic_54.png");
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "generic_54 title strip at 1x / 2x / 3x", x, y, 14, 0xFFB8BCC4);
        float top = y + 24;
        for (int scale = 1; scale <= 3; scale++) {
            nvg.save();
            nvg.translate(x, top);
            nvg.scale(scale, scale);
            nvg.imageRegion(chest.handle(), chest.width(), chest.height(), 0, 0, 176, 35, 0, 0, 176, 35, McIcon.UNTINTED);
            nvg.mcText("Configure Plots", 8, 6, 1, TITLE, false);
            nvg.mcIcon(McIcons.LIME_STAINED_GLASS_PANE, 8 + 2 * 18, 18, 16, McIcon.UNTINTED);
            nvg.mcIcon(McIcons.OAK_BUTTON, 8 + 3 * 18, 18, 16, McIcon.UNTINTED);
            nvg.restore();
            top += 35 * scale + 8;
        }
    }

    // a vanilla tooltip at 1-3x with Hypixel-style lines
    private static void tooltips(NVGRenderer nvg, float x, float y) {
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "tooltip with shadowed lines at 1x / 2x / 3x (mcText scale)", x, y, 14, 0xFFB8BCC4);
        String[] lines = {"§aPlot §7- §b5", "§7Greenhouse Plot", "§7Pests: §c3", "", "§eClick to open!"};
        float left = x;
        for (int scale = 1; scale <= 3; scale++) {
            float width = 0;
            for (String line : lines) width = Math.max(width, McBitmapFont.width(line, scale));
            float height = (lines.length * 10 + 2) * scale;
            float boxX = left + 9 * scale;
            float boxY = y + 30 + 9 * scale;
            McTextures.Texture background = McTextures.get("minecraft:textures/gui/sprites/tooltip/background.png");
            McTextures.Texture frame = McTextures.get("minecraft:textures/gui/sprites/tooltip/frame.png");
            nvg.save();
            nvg.translate(boxX - 12 * scale, boxY - 12 * scale);
            nvg.scale(scale, scale);
            float w = width / scale + 24;
            float h = height / scale + 24 - 2;
            nvg.nineSlice(background.handle(), 100, 100, 0, 0, 100, 100, 9, 9, 9, 9, 0, 0, w, h, McIcon.UNTINTED);
            nvg.nineSlice(frame.handle(), 100, 100, 0, 0, 100, 100, 10, 10, 10, 10, 0, 0, w, h, McIcon.UNTINTED);
            nvg.restore();
            float lineY = boxY;
            for (int i = 0; i < lines.length; i++) {
                nvg.mcText(lines[i], boxX, lineY, scale, 0xFFFFFFFF, true);
                lineY += (i == 0 ? 12 : 10) * scale;
            }
            left += width + 40 * scale;
        }
    }

    private static void mixed(NVGRenderer nvg, float x, float y) {
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "colours, styles, literal mode, accents, inter fallback", x, y, 14, 0xFFB8BCC4);
        String[] samples = {
                "§00 §11 §22 §33 §44 §55 §66 §77 §88 §99 §aa §bb §cc §dd §ee §ff",
                "§lBold§r §oItalic§r §nUnderline§r §mStrike§r §kObf§r §6§lGold §e§oyellow",
                "Àéîõü Ñç Şğ Ωπλ Жизнь ¿¡ «»",
                "fallback: ✓ ⌥ ↗ ⏎ ∆ next",
                "§a§lAether §r§7v1.0 §8- §bResume §f▶"
        };
        float top = y + 30;
        for (int scale = 1; scale <= 3; scale++) {
            for (String sample : samples) {
                nvg.mcText(sample, x, top, scale, 0xFFFFFFFF, true);
                top += 11 * scale;
            }
            nvg.mcTextLiteral("literal: user §atext §lstays", x, top, scale, 0xFFFFFFFF, true);
            top += 14 * scale;
        }
    }

    private static void exactStrings(NVGRenderer nvg, float x, float y) {
        nvg.textLiteral(Fonts.UI_SEMIBOLD, "pixel-exact against a software blit", x, y, 14, 0xFFB8BCC4);
        for (int scale = 1; scale <= 3; scale++) {
            float top = y + 30 + (scale - 1) * 40;
            nvg.mcText("Configure Plots", x + 8, top, scale, 0xFFFFFFFF, true);
            nvg.mcText("§aPlot §7- §b5", x + 308, top, scale, 0xFFFFFFFF, true);
        }
    }

    // software reference: every inked texel of each glyph cell as a scale x scale block, shadow first
    private static void assertBlitMatches(BufferedImage sheet, String text, int x, int y, int scale, int color,
                                          boolean shadow) {
        float width = McBitmapFont.width(text, scale);
        int w = (int) width + 2 * scale;
        int h = 10 * scale;
        int[] expected = new int[w * h];
        java.util.Arrays.fill(expected, BACKGROUND & 0xFFFFFF);
        if (shadow) blit(expected, w, h, text, scale, scale, scale, color, true);
        blit(expected, w, h, text, 0, 0, scale, color, false);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int actual = sheet.getRGB(x + px, y + py) & 0xFFFFFF;
                assertEquals(Integer.toHexString(expected[py * w + px]), Integer.toHexString(actual),
                        text + " at scale " + scale + ", pixel " + px + "," + py);
            }
        }
    }

    private static void blit(int[] target, int w, int h, String text, int ox, int oy, int scale, int color, boolean shadow) {
        int rgb = color & 0xFFFFFF;
        float pen = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                int code = "0123456789abcdef".indexOf(text.charAt(++i));
                rgb = new int[]{0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF}[code];
                continue;
            }
            McBitmapFont.Glyph glyph = McBitmapFont.table().glyphs().get(c);
            int ink = shadow ? ((rgb >> 16 & 0xFF) / 4) << 16 | ((rgb >> 8 & 0xFF) / 4) << 8 | (rgb & 0xFF) / 4 : rgb;
            if (glyph.texture() != null) {
                McTextures.Pixels pixels = McTextures.decode(glyph.texture());
                try {
                    for (int gy = 0; gy < glyph.height(); gy++) {
                        for (int gx = 0; gx < glyph.width(); gx++) {
                            int alpha = pixels.rgba().get(((glyph.v() + gy) * pixels.width() + glyph.u() + gx) * 4 + 3) & 0xFF;
                            if (alpha == 0) continue;
                            for (int sy = 0; sy < scale; sy++) {
                                for (int sx = 0; sx < scale; sx++) {
                                    int tx = ox + (int) pen + gx * scale + sx;
                                    int ty = oy + (int) (glyph.top() * scale) + gy * scale + sy;
                                    if (tx >= 0 && ty >= 0 && tx < w && ty < h) target[ty * w + tx] = ink;
                                }
                            }
                        }
                    }
                } finally {
                    MemoryUtil.memFree(pixels.rgba());
                }
            }
            pen += glyph.advance() * scale;
        }
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
