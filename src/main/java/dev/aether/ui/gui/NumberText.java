package dev.aether.ui.gui;

import java.util.Locale;
import java.util.OptionalDouble;

// numbers in fields: always written with a dot, read with a dot or a comma, so a pt_pt or ru_ru player's
// "1,5" works instead of failing silently
public final class NumberText {
    private NumberText() {
    }

    public static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", value);
    }

    public static OptionalDouble parse(String text) {
        if (text == null) {
            return OptionalDouble.empty();
        }
        String normalized = text.trim().replace(',', '.');
        if (normalized.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(normalized);
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
