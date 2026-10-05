package dev.aether.ui.gui.control;

import java.util.Locale;
import java.util.OptionalInt;

// colour text formats: copy writes AARRGGBB like the old menu; paste takes 6 or 8 hex digits with an
// optional '#', where 6 digits mean an opaque colour
public final class ColorText {
    private ColorText() {
    }

    public static String copy(int argb) {
        return String.format(Locale.ROOT, "%08X", argb);
    }

    public static String rgb(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    public static OptionalInt parse(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String hex = text.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if ((hex.length() != 6 && hex.length() != 8) || !hex.chars().allMatch(ColorText::isHex)) {
            return OptionalInt.empty();
        }
        long value = Long.parseLong(hex, 16);
        return OptionalInt.of(hex.length() == 8 ? (int) value : (int) (0xFF000000L | value));
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
