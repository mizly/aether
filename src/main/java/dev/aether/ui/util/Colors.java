package dev.aether.ui.util;

/**
 * Pre-defined ARGB color palette and color utility methods for the NVG UI library.
 *
 * <p>All colors are stored as {@code int} in ARGB format:
 * <pre>
 *   bits 31-24 -> alpha  (0x00 = transparent, 0xFF = opaque)
 *   bits 23-16 -> red
 *   bits 15-08 -> green
 *   bits 07-00 -> blue
 * </pre>
 *
 * <p>Usage example:
 * <pre>{@code
 *   nvg.rect(x, y, w, h, Colors.SURFACE);
 *   nvg.rect(x, y, w, h, Colors.withAlpha(Colors.ACCENT, 0.5f));
 * }</pre>
 */
public final class Colors {

    private Colors() {}

    // -- Base backgrounds ------------------------------------------------------

    /** Very dark background - window / screen base. */
    public static final int BG          = 0xFF141414;
    /** Slightly lighter surface - panels, cards. */
    public static final int SURFACE     = 0xFF1C1C1C;
    /** Input field background. */
    public static final int FIELD       = 0xFF1A1A1A;
    /** Hovered surface. */
    public static final int HOVER       = 0xFF252525;
    /** Active / pressed surface. */
    public static final int ACTIVE      = 0xFF222222;

    // -- Borders ---------------------------------------------------------------

    /** Default border. */
    public static final int BORDER      = 0xFF2A2A2A;
    /** Hovered border. */
    public static final int BORDER_HOV  = 0xFF3A3A3A;
    /** Active / focused border (accent). */
    public static final int BORDER_ACT  = 0xFFD32F2F;

    // -- Accent ----------------------------------------------------------------

    /** Primary accent - bright red. */
    public static final int ACCENT      = 0xFFD32F2F;
    /** Darker accent variant. */
    public static final int ACCENT_DARK = 0xFFB71C1C;
    /** Accent at 15% opacity - subtle tinted fills. */
    public static final int ACCENT_DIM  = 0x26D32F2F;
    /** Success / enabled green. */
    public static final int SUCCESS     = 0xFF4CAF50;
    /** Warning amber. */
    public static final int WARNING     = 0xFFF59E0B;
    /** Error / disabled red. */
    public static final int ERROR       = 0xFFEF4444;

    // -- Text ------------------------------------------------------------------

    /** Primary text - near white. */
    public static final int TEXT        = 0xFFEEEEEE;
    /** Secondary / dimmed text. */
    public static final int TEXT_DIM    = 0xFF888888;
    /** Disabled text. */
    public static final int TEXT_OFF    = 0xFF4B5563;
    /** Pure white. */
    public static final int WHITE       = 0xFFFFFFFF;
    /** Pure black. */
    public static final int BLACK       = 0xFF000000;

    // -- Utility ---------------------------------------------------------------

    /** Fully transparent. */
    public static final int TRANSPARENT = 0x00000000;

    // -- Color helpers ---------------------------------------------------------

    /**
     * Returns {@code color} with the alpha channel replaced by {@code alpha}
     * (0-255 inclusive).
     *
     * @param color ARGB source color
     * @param alpha new alpha value clamped to [0, 255]
     * @return modified ARGB color
     */
    public static int withAlpha(int color, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Returns {@code color} with the alpha channel set to {@code alpha * 255},
     * where {@code alpha} is in [0.0, 1.0].
     *
     * @param color ARGB source color
     * @param alpha normalised alpha, clamped to [0, 1]
     * @return modified ARGB color
     */
    public static int withAlpha(int color, float alpha) {
        return withAlpha(color, (int) (alpha * 255));
    }

    /**
     * Linearly interpolates between two ARGB colors per-channel.
     *
     * @param a     start color
     * @param b     end color
     * @param t     interpolation factor in [0, 1] - 0 returns {@code a}, 1 returns {@code b}
     * @return blended ARGB color
     */
    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >> 24) & 0xFF, ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF;
        int ab = (b >> 24) & 0xFF, rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ar = (int) (aa + (ab - aa) * t);
        int rr = (int) (ra + (rb - ra) * t);
        int gr = (int) (ga + (gb - ga) * t);
        int br = (int) (ba + (bb - ba) * t);
        return (ar << 24) | (rr << 16) | (gr << 8) | br;
    }

    /**
     * Parses a hex string of the form {@code "#RRGGBB"}, {@code "#AARRGGBB"},
     * {@code "RRGGBB"}, or {@code "AARRGGBB"} into an ARGB int.
     * Alpha defaults to 0xFF if not provided.
     *
     * @param hex the hex color string
     * @return ARGB integer
     * @throws NumberFormatException if the string is not a valid hex color
     */
    public static int fromHex(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 6) s = "FF" + s;
        return (int) Long.parseLong(s, 16);
    }

    /** Extracts the alpha component (0-255) from an ARGB color. */
    public static int alpha(int color) { return (color >> 24) & 0xFF; }
    /** Extracts the red component (0-255) from an ARGB color. */
    public static int red(int color)   { return (color >> 16) & 0xFF; }
    /** Extracts the green component (0-255) from an ARGB color. */
    public static int green(int color) { return (color >>  8) & 0xFF; }
    /** Extracts the blue component (0-255) from an ARGB color. */
    public static int blue(int color)  { return  color        & 0xFF; }
}
