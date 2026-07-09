package dev.aether.util;

/**
 * Shared numeric parsing helpers for SkyBlock-formatted strings.
 */
public final class NumberUtils {

    private NumberUtils() {}

    /**
     * Parses a number with an optional {@code k}/{@code m}/{@code b} suffix
     * (case-insensitive), stripping thousands separators.
     *
     * <p>Examples: {@code "75k" -> 75000}, {@code "1.8M" -> 1800000},
     * {@code "1,234" -> 1234}.</p>
     *
     * @throws NumberFormatException if the text is null, empty, or not numeric
     */
    public static long parseShorthand(String text) {
        if (text == null) {
            throw new NumberFormatException("null");
        }
        String s = text.replace(",", "").trim();
        if (s.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        double mult = 1.0;
        char c = Character.toLowerCase(s.charAt(s.length() - 1));
        if (c == 'k') {
            mult = 1_000.0;
            s = s.substring(0, s.length() - 1);
        } else if (c == 'm') {
            mult = 1_000_000.0;
            s = s.substring(0, s.length() - 1);
        } else if (c == 'b') {
            mult = 1_000_000_000.0;
            s = s.substring(0, s.length() - 1);
        }
        return (long) (Double.parseDouble(s) * mult);
    }
}
