package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class NanoVgTextMetricsTest {
    @Test
    void utf8OffsetsMapBackToJavaCharIndices() {
        String text = "aé€😀z";
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(1 + 2 + 3 + 4 + 1, bytes);
        int[] charAt = NanoVgTextMetrics.utf16IndexByUtf8Offset(text, bytes);
        assertArrayEquals(new int[] {0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 5, 6}, charAt);
    }

    @Test
    void emptyTextMapsOnlyItsEnd() {
        assertArrayEquals(new int[] {0}, NanoVgTextMetrics.utf16IndexByUtf8Offset("", 0));
    }
}
