package dev.aether.hud;

import com.mojang.blaze3d.font.GlyphInfo;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.ui.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.nanovg.NanoVG.*;

@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class ScoreboardAppearanceTest {
    @Test
    void rendersThemedPanelAndExportsPreview() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-scoreboard-test"));
        String savedTheme = Theme.exportJson();
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
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
        long window = GLFW.glfwCreateWindow(720, 600, "Scoreboard appearance test", 0, 0);
        try {
            assertNotEquals(0, window);
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            NanoVGManager.init();
            Theme.HUD_BG = 0xFF111317;
            Theme.HUD_BORDER = 0xFF68717D;
            Theme.HUD_ACCENT = 0xFFC8CED9;
            Theme.HUD_TITLE = 0xFFF2F3F5;
            Theme.HUD_LABEL = 0xFFAAAFB7;
            Theme.HUD_VALUE = 0xFFD8DCE2;

            GL11.glViewport(0, 0, 720, 600);
            GL11.glClearColor(0.13f, 0.14f, 0.15f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
            NVGRenderer nvg = NanoVGManager.getRenderer();
            // Menu text scaling must not change the scoreboard's own text size or line spacing.
            nvg.setTextScale(2f);
            nvgBeginFrame(NanoVGManager.getVg(), 240, 200, 3);
            nvgTextAlign(NanoVGManager.getVg(), NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
            nvg.translate(20, 20);
            sample().render(nvg);
            nvgEndFrame(NanoVGManager.getVg());

            BufferedImage preview = readPixels(720, 600);
            assertEquals(Theme.HUD_BG, preview.getRGB(30 * 3, 25 * 3));
            long titlePixels = 0;
            for (int y = 31 * 3; y < 40 * 3; y++) {
                for (int x = 30 * 3; x < 185 * 3; x++) {
                    if ((preview.getRGB(x, y) & 0xFFFFFF) > 0xAAAAAA) titlePixels++;
                }
            }
            assertTrue(titlePixels > 100, "The title must be drawn with the bundled HUD font");
            int middleAccent = preview.getRGB(112 * 3, 20 * 3 + 2) >> 16 & 255;
            int leftAccent = preview.getRGB(34 * 3, 20 * 3 + 2) >> 16 & 255;
            int rightAccent = preview.getRGB(190 * 3, 20 * 3 + 2) >> 16 & 255;
            assertTrue(middleAccent > leftAccent + 60, "The accent must fade at the left edge");
            assertTrue(middleAccent > rightAccent + 60, "The accent must fade at the right edge");
            Path output = Path.of("build/reports/tests/scoreboard-preview.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(preview, "png", output.toFile());
            verifyBoldWeight(nvg);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            Theme.importJson(savedTheme);
            if (NanoVGManager.isInitialized()) NanoVGManager.destroy();
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private static void verifyBoldWeight(NVGRenderer nvg) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        nvgBeginFrame(NanoVGManager.getVg(), 240, 200, 3);
        nvgTextAlign(NanoVGManager.getVg(), NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        nvg.textLiteral(dev.aether.ui.util.Fonts.REGULAR, "HHHHH", 20, 20, 9, -1);
        nvg.textLiteral(dev.aether.ui.util.Fonts.SCOREBOARD_BOLD, "HHHHH", 120, 20, 9, -1);
        nvgEndFrame(NanoVGManager.getVg());
        BufferedImage weights = readPixels(720, 600);
        long regularInk = 0, boldInk = 0;
        // Font weights can share advance widths; the rasterized strokes must still differ.
        for (int y = 60; y < 96; y++) {
            for (int x = 60; x < 240; x++) {
                regularInk += weights.getRGB(x, y) & 255;
                boldInk += weights.getRGB(x + 300, y) & 255;
            }
        }
        assertTrue(boldInk > regularInk, "Vanilla bold text must have heavier strokes than the HUD font");
    }

    private static ScoreboardDrawList sample() {
        Font font = measurementFont();
        ScoreboardDrawList list = new ScoreboardDrawList();
        list.fill(0, 0, 164, 9, 0x66000000);
        list.fill(0, 9, 164, 118, 0x4C000000);
        Component title = Component.literal("SKYBLOCK").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
        add(list, font, title, 82 - font.width(title) / 2, 1);
        Component[] rows = {
                Component.literal("09/05/26").withStyle(ChatFormatting.GRAY), Component.empty(),
                Component.literal("Early Autumn 19th"), Component.literal("5:10pm"),
                Component.literal("Plot - 5").withStyle(ChatFormatting.GREEN), Component.empty(),
                value("Purse: ", "12,126,571", ChatFormatting.GOLD).copy().withStyle(ChatFormatting.BOLD),
                value("Bits: ", "1,299", ChatFormatting.AQUA),
                value("Copper: ", "682", ChatFormatting.RED),
                value("Sawdust: ", "205,453", ChatFormatting.GREEN), Component.empty(),
                Component.literal("www.hypixel.net").withStyle(ChatFormatting.YELLOW)
        };
        for (int i = 0; i < rows.length; i++) {
            add(list, font, rows[i], 2, 10 + i * 9);
            add(list, font, Component.empty(), 164, 10 + i * 9);
        }
        return list;
    }

    private static Component value(String label, String value, ChatFormatting color) {
        return Component.literal(label).append(Component.literal(value).withStyle(color));
    }

    private static void add(ScoreboardDrawList list, Font font, Component text, int x, int y) {
        boolean heading = !list.hasText() || ScoreboardText.isServerAddress(text.getVisualOrderText());
        list.text(ScoreboardText.prepare(font, text.getVisualOrderText(), -1, false, heading), x, y, font.width(text));
    }

    private static Font measurementFont() {
        BakedGlyph glyph = new BakedSheetGlyph(GlyphInfo.simple(6), null, null, 0, 1, 0, 1, 0, 5, 0, 8);
        GlyphSource source = new GlyphSource() {
            @Override public BakedGlyph getGlyph(int codePoint) { return glyph; }
            @Override public BakedGlyph getRandomGlyph(RandomSource random, int width) { return glyph; }
        };
        return new Font(new Font.Provider() {
            @Override public GlyphSource glyphs(FontDescription description) { return source; }
            @Override public EffectGlyph effect() { return null; }
        });
    }

    private static BufferedImage readPixels(int width, int height) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = ((height - 1 - y) * width + x) * 4;
                    image.setRGB(x, y, (pixels.get(offset + 3) & 255) << 24 | (pixels.get(offset) & 255) << 16
                            | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255);
                }
            }
            return image;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
