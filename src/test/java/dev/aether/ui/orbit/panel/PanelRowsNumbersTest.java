package dev.aether.ui.orbit.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PanelRowsNumbersTest {
    @Test
    void readsTypedValues() {
        assertArrayEquals(new float[]{2.5f}, PanelRows.numbers("2.5s"));
        assertArrayEquals(new float[]{-30f}, PanelRows.numbers("-30°"));
        assertArrayEquals(new float[]{500f, 3000f}, PanelRows.numbers("500 - 3000ms"));
        assertArrayEquals(new float[]{500f, 3000f}, PanelRows.numbers("500-3000"));
        assertArrayEquals(new float[]{500f, 3000f}, PanelRows.numbers("500,3000"));
        assertArrayEquals(new float[]{}, PanelRows.numbers("abc"));
    }
}
