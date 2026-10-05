package dev.aether.ui.gui.overlay;

// hue, saturation, value and alpha in 0..1, for the colour picker
public record Hsv(float h, float s, float v, float a) {
    public static Hsv fromArgb(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue = 0f;
        if (delta > 0f) {
            if (max == r) {
                hue = ((g - b) / delta) % 6f;
            } else if (max == g) {
                hue = (b - r) / delta + 2f;
            } else {
                hue = (r - g) / delta + 4f;
            }
            hue /= 6f;
            if (hue < 0f) {
                hue += 1f;
            }
        }
        return new Hsv(hue, max == 0f ? 0f : delta / max, max, ((argb >>> 24) & 0xFF) / 255f);
    }

    public int toArgb() {
        float hue = (h % 1f + 1f) % 1f * 6f;
        int sector = (int) Math.floor(hue);
        float f = hue - sector;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (channel(a) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    public Hsv withHue(float value) {
        return new Hsv(clamp(value), s, v, a);
    }

    public Hsv withSv(float saturation, float brightness) {
        return new Hsv(h, clamp(saturation), clamp(brightness), a);
    }

    public Hsv withAlpha(float value) {
        return new Hsv(h, s, v, clamp(value));
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
