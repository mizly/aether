package dev.aether.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.bootstrap.AetherBootstrapHooks
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.List as JavaList

class ConfigProfileManager private constructor() {
    companion object {
        private val DIR: Path = FabricLoader.getInstance().configDir.resolve("aether").resolve("profiles")
        @Volatile private var activeProfilePath: Path? = null

        init { try { Files.createDirectories(DIR) } catch (_: IOException) {} }

        @JvmStatic fun list(): JavaList<String> {
            try {
                Files.list(DIR).use { paths ->
                    return paths.filter { it.toString().endsWith(".json") }
                        .map { it.fileName.toString().replace(".json", "") }
                        .sorted().toList().let { java.util.ArrayList(it) as JavaList<String> }
                }
            } catch (_: IOException) { return java.util.Collections.emptyList<String>() as JavaList<String> }
        }

        @JvmStatic fun save(name: String) {
            if (name.isBlank()) return
            val profilePath = DIR.resolve(sanitize(name) + ".json")
            try {
                Files.writeString(profilePath, AetherConfig.toJsonString())
                activeProfilePath = profilePath
            } catch (e: IOException) { e.printStackTrace() }
        }

        @JvmStatic fun load(name: String): Boolean {
            val src = DIR.resolve(sanitize(name) + ".json")
            if (!Files.exists(src)) return false
            val loaded = AetherConfig.loadFrom(src.toFile())
            if (loaded) {
                activeProfilePath = src
                AetherBootstrapHooks.onConfigProfileLoaded(src.toFile())
                AetherConfig.flush()
                syncActiveProfileFromLiveConfig()
            }
            return loaded
        }

        @JvmStatic fun delete(name: String) {
            val profilePath = DIR.resolve(sanitize(name) + ".json")
            try {
                Files.deleteIfExists(profilePath)
                if (profilePath == activeProfilePath) activeProfilePath = null
            } catch (e: IOException) { e.printStackTrace() }
        }

        @JvmStatic fun rename(oldName: String, newName: String): Boolean {
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
                if (src == activeProfilePath) activeProfilePath = dst
                return true
            } catch (e: IOException) { e.printStackTrace(); return false }
        }

        @JvmStatic fun exportJson(name: String): String {
            try { return Files.readString(DIR.resolve(sanitize(name) + ".json")) }
            catch (_: IOException) { return "" }
        }

        @JvmStatic fun exportJsonSanitized(name: String): String {
            val json = exportJson(name)
            if (json.isBlank()) return json
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                obj.addProperty("discordWebhookUrl", "")
                obj.addProperty("remoteControlBotToken", "")
                obj.add("coopNames", JsonArray())
                obj.addProperty("customUsername", "")
                obj.addProperty("serverNick", "")
                return GsonBuilder().setPrettyPrinting().create().toJson(obj)
            } catch (e: Exception) { e.printStackTrace(); return json }
        }

        @JvmStatic fun importJson(name: String, json: String) {
            if (name.isBlank() || json.isBlank()) return
            try { Files.writeString(DIR.resolve(sanitize(name) + ".json"), json) }
            catch (e: IOException) { e.printStackTrace() }
        }

        @JvmStatic fun importFromClipboard(name: String, json: String) { importJson(if (name.isBlank()) "imported" else name, json) }

        private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim()

        @JvmStatic internal fun syncActiveProfileFromLiveConfig() {
            val profilePath = activeProfilePath ?: return
            try {
                Files.createDirectories(profilePath.parent)
                Files.writeString(profilePath, AetherConfig.toJsonString())
            } catch (e: IOException) { e.printStackTrace() }
        }
    }
}
