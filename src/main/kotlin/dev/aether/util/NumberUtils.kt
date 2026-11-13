package dev.aether.util

class NumberUtils private constructor() {
    companion object {
        @JvmStatic
        fun parseShorthand(text: String?): Long {
            if (text == null) {
                throw NumberFormatException("null")
            }
            var s = text.replace(",", "").trim()
            if (s.isEmpty()) {
                throw NumberFormatException("empty")
            }
            var mult = 1.0
            val c = Character.toLowerCase(s[s.length - 1])
            if (c == 'k') {
                mult = 1_000.0
                s = s.substring(0, s.length - 1)
            } else if (c == 'm') {
                mult = 1_000_000.0
                s = s.substring(0, s.length - 1)
            } else if (c == 'b') {
                mult = 1_000_000_000.0
                s = s.substring(0, s.length - 1)
            }
            return (java.lang.Double.parseDouble(s) * mult).toLong()
        }
    }
}
