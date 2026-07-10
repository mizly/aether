package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;

final class DropdownChevron {
    private static final float HALF_WIDTH = 3.5f;
    private static final float HALF_HEIGHT = 2.25f;
    private static final float STROKE_WIDTH = 1.5f;

    private DropdownChevron() {
    }

    static void renderDown(NVGRenderer nvg, float x, float y, float width, float height, int color) {
        float centerX = x + width / 2f;
        float centerY = y + height / 2f;
        nvg.line(centerX - HALF_WIDTH, centerY - HALF_HEIGHT,
                centerX, centerY + HALF_HEIGHT, STROKE_WIDTH, color);
        nvg.line(centerX, centerY + HALF_HEIGHT,
                centerX + HALF_WIDTH, centerY - HALF_HEIGHT, STROKE_WIDTH, color);
    }
}
