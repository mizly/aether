package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgbTest {
    @Test
    void multiplyAlphaScalesTheExistingAlpha() {
        assertEquals(0x404080C0, Argb.multiplyAlpha(0x804080C0, 0.5f));
        assertEquals(0x004080C0, Argb.multiplyAlpha(0xFF4080C0, 0f));
        assertEquals(0xFF4080C0, Argb.multiplyAlpha(0xFF4080C0, 1f));
    }

    @Test
    void withAlphaReplacesIt() {
        assertEquals(0x804080C0, Argb.withAlpha(0x204080C0, 128f / 255f));
        assertEquals(0xFF000000, Argb.withAlpha(0x00000000, 2f));
    }

    @Test
    void mixBlendsEveryChannel() {
        assertEquals(0x80808080, Argb.mix(0x00000000, 0xFFFFFFFF, 0.5f));
        assertEquals(0xFF102030, Argb.mix(0xFF102030, 0x00FFFFFF, 0f));
        assertEquals(0xFF000000, Argb.mix(0xFF000000, 0xFFFFFFFF, -1f));
        assertEquals(0xFFFFFFFF, Argb.mix(0xFF000000, 0xFFFFFFFF, 3f));
    }

    @Test
    void contrastMatchesWcag() {
        assertEquals(21.0, Argb.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
        assertEquals(1.0, Argb.contrast(0xFF336699, 0x00336699), 1e-9);
        assertTrue(Argb.luminance(0xFFFFFFFF) > Argb.luminance(0xFF808080));
    }
}
