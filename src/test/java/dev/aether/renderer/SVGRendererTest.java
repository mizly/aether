package dev.aether.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SVGRendererTest {

    @Test
    void roundsDeviceSizesUpToEvenPixels() {
        assertEquals(2, SVGRenderer.bucket(0f));
        assertEquals(2, SVGRenderer.bucket(0.4f));
        assertEquals(16, SVGRenderer.bucket(16f));
        assertEquals(16, SVGRenderer.bucket(15.9999f), "float noise from scale transforms stays in its bucket");
        assertEquals(18, SVGRenderer.bucket(16.5f));
        assertEquals(18, SVGRenderer.bucket(17f));
        assertEquals(24, SVGRenderer.bucket(16f * 1.5f));
    }
}
