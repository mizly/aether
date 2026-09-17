package dev.aether.modules.pest.helpers;

import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

public final class PestDisplayValues {
    private static final String NUMBER = "([\\d,]+(?:\\.\\d+)?[kKmMbB]?)";
    private static final Pattern HEALTH = Pattern.compile(NUMBER + "(?:\\s*/\\s*" + NUMBER + ")?\\s*[❤♥]");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern STAMINA = Pattern.compile(
            "(?i)(?:stamina|progress)\\s*:?\\s*" + NUMBER + "\\s*/\\s*" + NUMBER);

    private PestDisplayValues() { }

    public static Health health(String name) {
        var match = HEALTH.matcher(PestHuntingController.stripFormatting(name));
        if (!match.find()) return null;
        double current = number(match.group(1));
        double maximum = match.group(2) == null ? 0 : number(match.group(2));
        return new Health(current, maximum);
    }

    // This is progress through the current stamina bar, not a guessed total catch duration.
    public static float huntingProgress(Component label) {
        String plain = PestHuntingController.stripFormatting(label.getString());
        if (PestHuntingController.isReelPrompt(plain)) return 1f;
        if (health(plain) != null) return -1f;
        var percent = PERCENT.matcher(plain);
        if (percent.find()) {
            float value = Float.parseFloat(percent.group(1)) / 100f;
            return clamp(plain.toLowerCase(java.util.Locale.ROOT).contains("stamina") ? 1f - value : value);
        }
        var stamina = STAMINA.matcher(plain);
        if (stamina.find()) {
            double maximum = number(stamina.group(2));
            if (maximum <= 0) return -1f;
            float value = (float) (number(stamina.group(1)) / maximum);
            return clamp(plain.toLowerCase(java.util.Locale.ROOT).contains("stamina") ? 1f - value : value);
        }
        int[] segments = new int[2];
        label.getVisualOrderText().accept((index, style, codepoint) -> {
            boolean barGlyph = "|▏▎▍▌▋▊▉█▮■▬".indexOf(codepoint) >= 0
                    || style.isBold() && style.isStrikethrough();
            if (!barGlyph || style.getColor() == null) return true;
            int rgb = style.getColor().getValue() & 0xFFFFFF;
            segments[1]++;
            if (rgb != 0x555555 && rgb != 0xAAAAAA && rgb != 0x000000) segments[0]++;
            return true;
        });
        return segments[1] >= 4 ? 1f - (float) segments[0] / segments[1] : -1f;
    }

    private static float clamp(float value) { return Math.clamp(value, 0f, 1f); }

    private static double number(String text) {
        String value = text.replace(",", "").toLowerCase(java.util.Locale.ROOT);
        char suffix = value.charAt(value.length() - 1);
        double multiplier = switch (suffix) { case 'k' -> 1_000; case 'm' -> 1_000_000; case 'b' -> 1_000_000_000; default -> 1; };
        return Double.parseDouble(multiplier == 1 ? value : value.substring(0, value.length() - 1)) * multiplier;
    }

    public record Health(double current, double maximum) { }
}
