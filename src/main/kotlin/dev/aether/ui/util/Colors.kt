package dev.aether.ui.util

// argb ints: alpha in bits 31-24, then red, green, blue
class Colors private constructor() {
    companion object {
        const val BG = 0xFF141414.toInt()
        // panels and cards
        const val SURFACE = 0xFF1C1C1C.toInt()
        const val FIELD = 0xFF1A1A1A.toInt()
        const val HOVER = 0xFF252525.toInt()
        const val ACTIVE = 0xFF222222.toInt()

        // -- Borders ---------------------------------------------------------------
        const val BORDER = 0xFF2A2A2A.toInt()
        const val BORDER_HOV = 0xFF3A3A3A.toInt()
        const val BORDER_ACT = 0xFFD32F2F.toInt()

        // -- Accent ----------------------------------------------------------------
        const val ACCENT = 0xFFD32F2F.toInt()
        const val ACCENT_DARK = 0xFFB71C1C.toInt()
        // accent at 15% opacity, for subtle tinted fills
        const val ACCENT_DIM = 0x26D32F2F
        const val SUCCESS = 0xFF4CAF50.toInt()
        const val WARNING = 0xFFF59E0B.toInt()
        const val ERROR = 0xFFEF4444.toInt()

        // -- Text ------------------------------------------------------------------
        const val TEXT = 0xFFEEEEEE.toInt()
        const val TEXT_DIM = 0xFF888888.toInt()
        const val TEXT_OFF = 0xFF4B5563.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        // -- Utility ---------------------------------------------------------------
        const val TRANSPARENT = 0x00000000

        // -- Color helpers ---------------------------------------------------------
        // alpha is clamped to [0, 255]
        @JvmStatic fun withAlpha(color: Int, alpha: Int): Int {
            val clamped = Math.max(0, Math.min(255, alpha))
            return (clamped shl 24) or (color and 0x00FFFFFF)
        }

        // alpha is normalised, clamped to [0, 1]
        @JvmStatic fun withAlpha(color: Int, alpha: Float): Int = withAlpha(color, (alpha * 255).toInt())

        // per-channel lerp; t is clamped to [0, 1]
        @JvmStatic fun lerp(a: Int, b: Int, t: Float): Int {
            val clamped = Math.max(0f, Math.min(1f, t))
            val aa = (a shr 24) and 0xFF
            val ra = (a shr 16) and 0xFF
            val ga = (a shr 8) and 0xFF
            val ba = a and 0xFF
            val ab = (b shr 24) and 0xFF
            val rb = (b shr 16) and 0xFF
            val gb = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val ar = (aa + (ab - aa) * clamped).toInt()
            val rr = (ra + (rb - ra) * clamped).toInt()
            val gr = (ga + (gb - ga) * clamped).toInt()
            val br = (ba + (bb - ba) * clamped).toInt()
            return (ar shl 24) or (rr shl 16) or (gr shl 8) or br
        }

        // accepts #RRGGBB, #AARRGGBB, RRGGBB or AARRGGBB; alpha defaults to 0xFF
        @JvmStatic fun fromHex(hex: String): Int {
            var s = if (hex.startsWith("#")) hex.substring(1) else hex
            if (s.length == 6) s = "FF" + s
            return java.lang.Long.parseLong(s, 16).toInt()
        }

        @JvmStatic fun alpha(color: Int): Int = (color shr 24) and 0xFF
        @JvmStatic fun red(color: Int): Int = (color shr 16) and 0xFF
        @JvmStatic fun green(color: Int): Int = (color shr 8) and 0xFF
        @JvmStatic fun blue(color: Int): Int = color and 0xFF
    }
}
