package dev.aether.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.locale.Language
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.HashMap
import kotlin.collections.Map as KMap
import java.util.Locale

class AetherLang private constructor() {
    companion object {
        private val HARDCODED_TEXT: Set<String> = java.util.Collections.unmodifiableSet(
            java.util.LinkedHashSet(
                listOf(
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
                )
            )
        )

        private val EN_US_TRANSLATIONS: KMap<String, String> = loadBundledEnglishTranslations()
        @Volatile private var externalTranslations: KMap<String, String> = emptyMap<String, String>()
        @Volatile private var externalLanguageCode: String = "en_us"

        @JvmStatic
        fun localize(text: String?): String {
            if (text == null || text.isEmpty()) return ""
            val key = keyFromText(text)
            val externalValue = externalTranslations[key]
            if (externalValue != null && externalValue.isNotEmpty()) return externalValue
            if (Language.getInstance().has(key)) return Language.getInstance().getOrDefault(key)
            val englishValue = EN_US_TRANSLATIONS[key]
            if (englishValue != null && englishValue.isNotEmpty()) return englishValue
            if (HARDCODED_TEXT.contains(text)) return text
            return text
        }

        @JvmStatic
        fun keyFromText(text: String?): String {
            if (text == null || text.isEmpty()) return "text.aether.empty"
            val normalized = text.lowercase(Locale.ROOT)
            val builder = StringBuilder("text.aether.")
            var lastWasSeparator = false
            for (ch in normalized) {
                if (Character.isLetterOrDigit(ch)) {
                    builder.append(ch)
                    lastWasSeparator = false
                } else if (!lastWasSeparator) {
                    builder.append('_')
                    lastWasSeparator = true
                }
            }
            var end = builder.length
            while (end > "text.aether.".length && builder[end - 1] == '_') end--
            if (end == "text.aether.".length) return "text.aether.empty"
            return builder.substring(0, end)
        }

        @JvmStatic
        fun localize(client: Minecraft?, text: String?): String = localize(text)

        @JvmStatic
        fun installExternalTranslations(languageCode: String?, translations: KMap<String, String>?) {
            externalLanguageCode = normalizeLanguageCode(languageCode)
            externalTranslations = if (translations == null || translations.isEmpty()) emptyMap<String, String>() else HashMap<String, String>(translations).toMap()
        }

        @JvmStatic
        fun getExternalLanguageCode(): String = externalLanguageCode

        private fun normalizeLanguageCode(value: String?): String {
            if (value == null || value.isBlank()) return "en_us"
            return value.trim().lowercase(Locale.ROOT).replace('-', '_')
        }

        private fun loadBundledEnglishTranslations(): KMap<String, String> {
            try {
                AetherLang::class.java.classLoader.getResourceAsStream("assets/aether/lang/en_us.json").use { stream ->
                    if (stream == null) return HashMap<String, String>()
                    val root: JsonElement = JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                    if (!root.isJsonObject) return HashMap<String, String>()
                    val translations: MutableMap<String, String> = LinkedHashMap<String, String>()
                    val objectValue: JsonObject = root.asJsonObject
                    for ((key, value) in objectValue.entrySet()) {
                        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) translations[key] = value.asString
                    }
                    return java.util.Collections.unmodifiableMap(java.util.HashMap(translations))
                }
            } catch (_: Exception) {
                return HashMap<String, String>()
            }
        }
    }
}
