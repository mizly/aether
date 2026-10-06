package dev.aether.ui.settings;

import java.util.Locale;

// one stored entry of a plot setting: a garden plot 1..24, the barn (0), or anything else, kept as written.
// known entries always carry their canonical text ("5", "barn"), so equal plots are equal tokens
public record PlotToken(int number, String text) {
    public static final int BARN = 0;
    public static final int MAX_PLOT = 24;
    private static final int UNKNOWN = -1;

    public PlotToken {
        if (number > MAX_PLOT) {
            throw new IllegalArgumentException("no garden plot " + number);
        }
        if (number >= BARN) {
            text = number == BARN ? "barn" : Integer.toString(number);
        } else {
            number = UNKNOWN;
            text = text == null ? "" : text.trim();
        }
    }

    public static PlotToken plot(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("no garden plot " + number);
        }
        return new PlotToken(number, null);
    }

    public static PlotToken barn() {
        return new PlotToken(BARN, null);
    }

    public static PlotToken unknown(String raw) {
        return new PlotToken(UNKNOWN, raw);
    }

    // reads digits the way the consumers do (PestPlotId), so "Plot 5", " 5" and "#5" are plot 5 and the
    // picker shows what the macro will match; null for blank
    public static PlotToken parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("barn") || lower.equals("the barn")) {
            return barn();
        }
        String digits = lower.replaceAll("\\D", "");
        if (!digits.isEmpty() && digits.length() <= 2) {
            int number = Integer.parseInt(digits);
            if (number >= 1 && number <= MAX_PLOT) {
                return plot(number);
            }
        }
        return unknown(trimmed);
    }

    public boolean isKnown() {
        return number >= BARN;
    }

    public boolean isBarn() {
        return number == BARN;
    }
}
