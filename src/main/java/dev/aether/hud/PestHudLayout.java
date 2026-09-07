package dev.aether.hud;

record PestHudLayout(int rows, int columns, float scale) {
    static final float COLUMN_WIDTH = 190f;
    static final float ROW_HEIGHT = 35f;
    static final float HEADER_HEIGHT = 27f;

    float width() { return columns * COLUMN_WIDTH; }
    float height() { return HEADER_HEIGHT + rows * ROW_HEIGHT + 5f; }

    static PestHudLayout fit(int count, float availableWidth, float availableHeight, float requestedScale) {
        count = Math.max(1, count);
        PestHudLayout best = new PestHudLayout(1, count, 0f);
        for (int rows = 1; rows <= count; rows++) {
            int columns = (count + rows - 1) / rows;
            float scale = Math.min(requestedScale, Math.min(
                    Math.max(1f, availableWidth) / (columns * COLUMN_WIDTH),
                    Math.max(1f, availableHeight) / (HEADER_HEIGHT + rows * ROW_HEIGHT + 5f)));
            // Prefer a vertical list when several arrangements fit at the requested scale.
            if (scale >= best.scale()) best = new PestHudLayout(rows, columns, scale);
        }
        return best;
    }
}
