package dev.aether.ui.theme

import java.io.IOException
import java.nio.charset.StandardCharsets

enum class ThemePreset(private val presetLabel: String, private val resourceName: String) {
    SLATE("Slate", "slate"),
    SAGE("Sage", "sage"),
    MATCHA("Matcha", "matcha"),
    DUSK("Dusk", "dusk"),
    EMBER("Ember", "ember");

    fun label(): String = presetLabel

    fun json(): String {
        val path = "/assets/aether/themes/$resourceName.json"
        try {
            ThemePreset::class.java.getResourceAsStream(path).use { stream ->
                if (stream == null) throw IllegalStateException("Missing theme preset: $path")
                return String(stream.readAllBytes(), StandardCharsets.UTF_8)
            }
        } catch (e: IOException) {
            throw IllegalStateException("Could not read theme preset: $path", e)
        }
    }

    fun apply() {
        Theme.importJson(json())
    }
}
