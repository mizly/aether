package dev.aether.config

import dev.aether.ui.theme.Theme
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// named theme profiles under config/aether/themes, each one a Theme.exportJson payload
class ThemeProfileManager private constructor() {
    companion object {
        private val DIR: Path = FabricLoader.getInstance().configDir
            .resolve("aether").resolve("themes")

        init {
            try {
                Files.createDirectories(DIR)
            } catch (_: IOException) {
            }
        }

        @JvmStatic
        fun list(): List<String> {
            try {
                return Files.list(DIR).use { stream ->
                    stream.filter { p -> p.toString().endsWith(".json") }
                        .map { p -> p.fileName.toString().replace(".json", "") }
                        .sorted()
                        .toList()
                }
            } catch (_: IOException) {
                return java.util.Collections.emptyList()
            }
        }

        @JvmStatic
        fun save(name: String) {
            if (name.isBlank()) return
            try {
                Files.writeString(DIR.resolve(sanitize(name) + ".json"), Theme.exportJson())
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun load(name: String) {
            val src = DIR.resolve(sanitize(name) + ".json")
            if (!Files.exists(src)) return
            try {
                val json = Files.readString(src)
                Theme.importJson(json)
                Theme.saveTheme()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun delete(name: String) {
            try {
                Files.deleteIfExists(DIR.resolve(sanitize(name) + ".json"))
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun rename(oldName: String, newName: String): Boolean {
            val oldSanitized = sanitize(oldName)
            val newSanitized = sanitize(newName)
            if (oldSanitized.isBlank() || newSanitized.isBlank()) return false

            val src = DIR.resolve(oldSanitized + ".json")
            if (!Files.exists(src)) return false
            if (oldSanitized == newSanitized) return true

            val dst = DIR.resolve(newSanitized + ".json")
            if (Files.exists(dst)) return false
            try {
                Files.move(src, dst)
                return true
            } catch (e: IOException) {
                e.printStackTrace()
                return false
            }
        }

        @JvmStatic
        fun exportJson(name: String): String {
            try {
                return Files.readString(DIR.resolve(sanitize(name) + ".json"))
            } catch (_: IOException) {
                return ""
            }
        }

        @JvmStatic
        fun importJson(name: String, json: String) {
            if (name.isBlank() || json.isBlank()) return
            try {
                Files.writeString(DIR.resolve(sanitize(name) + ".json"), json)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim()
    }
}
