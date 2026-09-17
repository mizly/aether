package dev.aether.renderer;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class CapeTextureTest {
    @Test
    void textureCoversEveryVanillaCapeFaceWithoutTransparentHoles() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/aether/textures/cosmetic/aether_cape.png")) {
            assertNotNull(stream);
            var image = ImageIO.read(stream);
            assertEquals(128, image.getWidth());
            assertEquals(64, image.getHeight());
            for (int y = 0; y < 34; y++) {
                int start = y < 2 ? 2 : 0;
                int end = y < 2 ? 42 : 44;
                for (int x = start; x < end; x++) {
                    assertEquals(255, image.getRGB(x, y) >>> 24, "All six cape faces must be opaque");
                }
            }
            int emblemPixels = 0;
            for (int y = 2; y < 34; y++) for (int x = 2; x < 22; x++) {
                if ((image.getRGB(x, y) & 255) > 150) emblemPixels++;
            }
            assertTrue(emblemPixels > 50 && emblemPixels < 250, "The emblem must be legible with space around it");
        }
    }
}
