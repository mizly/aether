package dev.aether.ui.util;

// argb ints: alpha in bits 31-24, then red, green, blue
public final class Colors {

    private Colors() {}

    // -- Base backgrounds ------------------------------------------------------

    public static final int BG          = 0xFF141414;
    // panels and cards
    public static final int SURFACE     = 0xFF1C1C1C;
    public static final int FIELD       = 0xFF1A1A1A;
    public static final int HOVER       = 0xFF252525;
    public static final int ACTIVE      = 0xFF222222;

    // -- Borders ---------------------------------------------------------------

    public static final int BORDER      = 0xFF2A2A2A;
    public static final int BORDER_HOV  = 0xFF3A3A3A;
    public static final int BORDER_ACT  = 0xFFD32F2F;

    // -- Accent ----------------------------------------------------------------

    public static final int ACCENT      = 0xFFD32F2F;
    public static final int ACCENT_DARK = 0xFFB71C1C;
    // accent at 15% opacity, for subtle tinted fills
    public static final int ACCENT_DIM  = 0x26D32F2F;
    public static final int SUCCESS     = 0xFF4CAF50;
    public static final int WARNING     = 0xFFF59E0B;
    public static final int ERROR       = 0xFFEF4444;

    // -- Text ------------------------------------------------------------------

    public static final int TEXT        = 0xFFEEEEEE;
    public static final int TEXT_DIM    = 0xFF888888;
    public static final int TEXT_OFF    = 0xFF4B5563;
    public static final int WHITE       = 0xFFFFFFFF;
    public static final int BLACK       = 0xFF000000;

    // -- Utility ---------------------------------------------------------------

    public static final int TRANSPARENT = 0x00000000;

    // -- Color helpers ---------------------------------------------------------

    // alpha is clamped to [0, 255]
    public static int withAlpha(int color, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    // alpha is normalised, clamped to [0, 1]
    public static int withAlpha(int color, float alpha) {
        return withAlpha(color, (int) (alpha * 255));
    }

    // per-channel lerp; t is clamped to [0, 1]
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

    // accepts #RRGGBB, #AARRGGBB, RRGGBB or AARRGGBB; alpha defaults to 0xFF
    public static int fromHex(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 6) s = "FF" + s;
        return (int) Long.parseLong(s, 16);
    }

    public static int alpha(int color) { return (color >> 24) & 0xFF; }
    public static int red(int color)   { return (color >> 16) & 0xFF; }
    public static int green(int color) { return (color >>  8) & 0xFF; }
    public static int blue(int color)  { return  color        & 0xFF; }
}
