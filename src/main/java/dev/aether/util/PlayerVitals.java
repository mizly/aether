package dev.aether.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlayerVitals {
    record Health(long current, long max) {
        double fraction() {
            return Math.min(current, max) / (double) max;
        }
    }

    private record Reading(Health health, long at) {}

    private static final long STALE_AFTER_MS = 5_000L;
    private static final String NUMBER = "(\\d[\\d,]*+(?:\\.\\d++)?+[kKmMbB]?+)";
    private static final String HEART = "[❤♥]";
    private static final Pattern HEALTH_AFTER = Pattern.compile(NUMBER + "\\s*+/\\s*+" + NUMBER + "\\s*+" + HEART);
    // possessive numbers stop "❤ 789/1,000✎" backtracking to "1,00" and slipping past the mana check
    private static final Pattern HEALTH_BEFORE = Pattern.compile(
            HEART + "\\s*+" + NUMBER + "\\s*+/\\s*+" + NUMBER + "(?!\\s*+[✎❈])");

    private static volatile Reading latest;

    private PlayerVitals() {}

    public static void onActionBar(Component component) {
        if (component == null) return;
        try {
            onActionBar(component.getString(), System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // runs inside the action bar mixin, so a bad line must not take the overlay down with it
        }
    }

    static void onActionBar(String text, long now) {
        Health health = parseHealth(TablistUtils.stripColors(text));
        if (health != null) latest = new Reading(health, now);
    }

    static Health parseHealth(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher match = HEALTH_AFTER.matcher(text);
        if (!match.find()) {
            match = HEALTH_BEFORE.matcher(text);
            if (!match.find()) return null;
        }
        long current = NumberUtils.parseShorthand(match.group(1));
        long max = NumberUtils.parseShorthand(match.group(2));
        return max > 0 ? new Health(current, max) : null;
    }

    public static double healthFraction(Minecraft mc, long now) {
        Reading reading = latest;
        boolean hasPlayer = mc != null && mc.player != null;
        return healthFraction(reading == null ? null : reading.health(), reading == null ? 0L : reading.at(), now,
                hasPlayer ? mc.player.getHealth() : 0f, hasPlayer ? mc.player.getMaxHealth() : 0f);
    }

    static double healthFraction(Health parsed, long parsedAt, long now, float vanillaHealth, float vanillaMax) {
        if (parsed != null && now - parsedAt <= STALE_AFTER_MS) return parsed.fraction();
        return vanillaMax > 0f ? Math.min(vanillaHealth, vanillaMax) / vanillaMax : 1.0;
    }
}
