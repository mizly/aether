package dev.aether.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

public final class AetherLang {
    private static final Set<String> HARDCODED_TEXT = Set.of(
            "Open NVG Demo",
            "Aether",
            "IP:PORT",
            "Failed to connect to the server",
            "Back to Server List",
            "You are temporarily banned for ",
            " from this server!",
            "Reason: ",
            "Cheating through the use of unfair game advantages.",
            "Find out more: ",
            "Ban ID: ",
            "Sharing your Ban ID may affect the processing of your appeal!",
            "config/aether/features/local-feature.jar",
            "e.g. 1250000000",
            "e.g. 5",
            "e.g. 650000000",
            "e.g. Blessed Melon Dicer",
            "e.g. Box of Seeds",
            "e.g. Nether Wart Hoe",
            "e.g. Rose Dragon",
            "e.g. Volta",
            "Edit HUD Layout",
            "Elephant",
            "https://...",
            "https://discord.com/api/webhooks/...",
            "Loaded from local mod.",
            "Local feature jar load failed.",
            "Rose Dragon:200:650000000:1250000000:LEGENDARY",
            "X: %.1f, Y: %.1f, Z: %.1f",
            "X: %d, Y: %d, Z: %d",
            "yyyy-MM-dd HH:mm:ss z"
    );
    private static final Map<String, String> EN_US_TRANSLATIONS = loadBundledEnglishTranslations();
    private static volatile Map<String, String> externalTranslations = Map.of();
    private static volatile String externalLanguageCode = "en_us";

    private AetherLang() {
    }

    public static String localize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String key = keyFromText(text);
        String externalValue = externalTranslations.get(key);
        if (externalValue != null && !externalValue.isEmpty()) {
            return externalValue;
        }

        if (Language.getInstance().has(key)) {
            return Language.getInstance().getOrDefault(key);
        }

        String englishValue = EN_US_TRANSLATIONS.get(key);
        if (englishValue != null && !englishValue.isEmpty()) {
            return englishValue;
        }

        if (HARDCODED_TEXT.contains(text)) {
            return text;
        }

        return text;
    }

    public static String keyFromText(String text) {
        if (text == null || text.isEmpty()) {
            return "text.aether.empty";
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder("text.aether.");
        boolean lastWasSeparator = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
                lastWasSeparator = false;
            } else if (!lastWasSeparator) {
                builder.append('_');
                lastWasSeparator = true;
            }
        }

        int end = builder.length();
        while (end > "text.aether.".length() && builder.charAt(end - 1) == '_') {
            end--;
        }
        if (end == "text.aether.".length()) {
            return "text.aether.empty";
        }
        return builder.substring(0, end);
    }

    public static String localize(Minecraft client, String text) {
        return localize(text);
    }

    public static void installExternalTranslations(String languageCode, Map<String, String> translations) {
        externalLanguageCode = normalizeLanguageCode(languageCode);
        externalTranslations = translations == null || translations.isEmpty() ? Map.of() : Map.copyOf(translations);
    }

    public static String getExternalLanguageCode() {
        return externalLanguageCode;
    }

    private static String normalizeLanguageCode(String value) {
        if (value == null || value.isBlank()) {
            return "en_us";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Map<String, String> loadBundledEnglishTranslations() {
        try (var stream = AetherLang.class.getClassLoader()
                .getResourceAsStream("assets/aether/lang/en_us.json")) {
            if (stream == null) {
                return Map.of();
            }

            JsonElement root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return Map.of();
            }

            Map<String, String> translations = new LinkedHashMap<>();
            JsonObject object = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    translations.put(entry.getKey(), value.getAsString());
                }
            }
            return Map.copyOf(translations);
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
