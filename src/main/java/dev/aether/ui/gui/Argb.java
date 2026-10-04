package dev.aether.ui.gui;

// 0xAARRGGBB helpers; multiplyAlpha keeps nested fades composing, withAlpha replaces the channel
public final class Argb {
    private Argb() {
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int withAlpha(int argb, float alpha) {
        return (channel(alpha * 255f) << 24) | (argb & 0x00FFFFFF);
    }

    public static int multiplyAlpha(int argb, float factor) {
        return (channel(alpha(argb) * factor) << 24) | (argb & 0x00FFFFFF);
    }

    public static int mix(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = channel(lerp(from >>> 24, to >>> 24, k));
        int r = channel(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, k));
        int g = channel(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, k));
        int b = channel(lerp(from & 0xFF, to & 0xFF, k));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // wcag relative luminance of the rgb part, 0 for black and 1 for white
    public static double luminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 0xFF) + 0.7152 * linear((argb >> 8) & 0xFF) + 0.0722 * linear(argb & 0xFF);
    }

    public static double contrast(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static float lerp(int from, int to, float t) {
        return from + (to - from) * t;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
