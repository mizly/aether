package dev.aether.renderer;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class McTexturesTest {

    @Test
    void decodesVanillaItemSpritesFromTheClasspath() throws Exception {
        assertMatchesSource("minecraft:textures/item/wheat.png", 16, 16);
    }

    @Test
    void keepsOnlyTheFirstFrameOfAnimatedStrips() throws Exception {
        assertMatchesSource("minecraft:textures/block/magma.png", 16, 16);
        assertMatchesSource("minecraft:textures/block/sea_lantern.png", 16, 16);
    }

    @Test
    void expandsGrayAlphaGlassToStraightRgba() throws Exception {
        assertTrue(assertMatchesSource("minecraft:textures/block/gray_stained_glass.png", 16, 16) > 0,
                "glass keeps its partial alpha");
    }

    @Test
    void readsWholeGuiSheets() throws Exception {
        assertMatchesSource("minecraft:textures/gui/container/generic_54.png", 256, 256);
    }

    @Test
    void reportsMissingAndMalformedIdsAsNull() {
        assertNull(McTextures.decode("minecraft:textures/item/definitely_not_a_texture.png"));
        assertNull(McTextures.decode("Not A Valid Id"));
    }

    // compares every decoded pixel with the raw samples of the png's top-left frame; returns the translucent count
    private static int assertMatchesSource(String id, int width, int height) throws Exception {
        McTextures.Pixels pixels = McTextures.decode(id);
        assertNotNull(pixels, id);
        try {
            assertEquals(width, pixels.width(), id);
            assertEquals(height, pixels.height(), id);
            BufferedImage source = readSource(id);
            Raster raster = source.getRaster();
            boolean gray = source.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY;
            int translucent = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int[] expected = gray ? grayAlpha(raster, x, y) : argb(source.getRGB(x, y));
                    int i = (y * width + x) * 4;
                    int alpha = pixels.rgba().get(i + 3) & 255;
                    assertEquals(expected[3], alpha, id + " alpha at " + x + "," + y);
                    if (alpha == 0) continue;
                    if (alpha < 255) translucent++;
                    for (int c = 0; c < 3; c++) {
                        assertEquals(expected[c], pixels.rgba().get(i + c) & 255, id + " channel " + c + " at " + x + "," + y);
                    }
                }
            }
            return translucent;
        } finally {
            MemoryUtil.memFree(pixels.rgba());
        }
    }

    // java maps gray pngs through a linear colour space, so compare the raw samples instead of getRGB
    private static int[] grayAlpha(Raster raster, int x, int y) {
        int value = raster.getSample(x, y, 0);
        int alpha = raster.getNumBands() == 2 ? raster.getSample(x, y, 1) : 255;
        return new int[]{value, value, value, alpha};
    }

    private static int[] argb(int pixel) {
        return new int[]{(pixel >> 16) & 255, (pixel >> 8) & 255, pixel & 255, pixel >>> 24};
    }

    private static BufferedImage readSource(String id) throws Exception {
        String path = "/assets/" + id.replace(':', '/');
        try (InputStream in = McTexturesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }
}
