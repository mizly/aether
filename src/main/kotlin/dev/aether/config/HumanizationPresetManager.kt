package dev.aether.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.config.entries.ConfigEntry
import dev.aether.util.AetherResources
import net.fabricmc.loader.api.FabricLoader
import java.awt.Desktop
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.Locale

class HumanizationPresetManager private constructor() {
    companion object {
        private val PRESET_GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val PRESET_OPTIONS: List<String> = listOf("Safe", "Normal", "Efficient")
        private val BUNDLED_PRESET_IDS: List<String> = listOf("safe", "normal", "efficient")
        private const val RESOURCE_BASE = "assets/aether/humanization-presets/"
        private val PRESET_DIR: Path = FabricLoader.getInstance().configDir.resolve("aether").resolve("humanization-presets")
        private val PRESET_ENTRIES: Map<String, ConfigEntry<*>> = createPresetEntries()

        @JvmStatic
        fun init() {
            ensurePresetDirectory()
            for (presetId in BUNDLED_PRESET_IDS) {
                ensureBundledPreset(presetId)
                syncBundledPresetDefaults(presetId)
            }
        }

        @JvmStatic
        fun getPresetOptions(): List<String> = PRESET_OPTIONS

        @JvmStatic
        fun getSelectedPresetIndex(): Int {
            val selectedPreset = AetherConfig.HUMANIZATION_PRESET.get()
            if (selectedPreset == null || selectedPreset.isBlank()) return 1
            for (i in PRESET_OPTIONS.indices) {
                if (PRESET_OPTIONS[i].equals(selectedPreset, ignoreCase = true)) return i
            }
            return 1
        }

        @JvmStatic
        fun applyPresetByIndex(index: Int) {
            if (index < 0 || index >= PRESET_OPTIONS.size) return
            applyPreset(PRESET_OPTIONS[index])
        }

        @JvmStatic
        fun reapplySelectedPreset() {
            applyPreset(PRESET_OPTIONS[getSelectedPresetIndex()])
        }

        @JvmStatic
        fun openPresetFolder() {
            init()
            try {
                if (isWindows()) {
                    ProcessBuilder("explorer.exe", PRESET_DIR.toAbsolutePath().toString()).start()
                    return
                }
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(PRESET_DIR.toFile())
            } catch (e: IOException) {
                System.err.println("[Aether] Failed to open humanization preset folder: ${e.message}")
            }
        }

        private fun applyPreset(presetName: String) {
            init()
            val presetId = normalizePresetId(presetName)
            val presetPath = PRESET_DIR.resolve(presetId + ".json")
            if (!Files.exists(presetPath)) {
                System.err.println("[Aether] Humanization preset file missing: ${presetPath.toAbsolutePath()}")
                return
            }
            try {
                Files.newBufferedReader(presetPath).use { reader: Reader ->
                    val root = JsonParser.parseReader(reader)
                    if (root == null || !root.isJsonObject) throw IllegalStateException("root value is not a JSON object")
                    val preset = root.asJsonObject
                    Config.suspendAutosave {
                        for ((key, entry) in PRESET_ENTRIES) {
                            val value: JsonElement? = preset.get(key)
                            if (value != null && !value.isJsonNull) entry.fromJson(value)
                        }
                        AetherConfig.HUMANIZATION_PRESET.set(presetId.uppercase(java.util.Locale.ROOT))
                    }
                }
                AetherConfig.save()
            } catch (e: Exception) {
                System.err.println("[Aether] Failed to apply humanization preset '$presetId': ${e.message}")
            }
        }

        private fun createPresetEntries(): Map<String, ConfigEntry<*>> {
            val entries = LinkedHashMap<String, ConfigEntry<*>>()
            addEntry(entries, AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN)
            addEntry(entries, AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX)
            addEntry(entries, AetherConfig.REWARP_DELAY_MIN)
            addEntry(entries, AetherConfig.REWARP_DELAY_MAX)
            addEntry(entries, AetherConfig.PEST_CHAT_TRIGGER_DELAY_MIN)
            addEntry(entries, AetherConfig.PEST_CHAT_TRIGGER_DELAY_MAX)
            addEntry(entries, AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MIN)
            addEntry(entries, AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MAX)
            addEntry(entries, AetherConfig.PEST_EXCHANGE_DELAY_MIN)
            addEntry(entries, AetherConfig.PEST_EXCHANGE_DELAY_MAX)
            addEntry(entries, AetherConfig.PEST_AOTV_DELAY_MIN)
            addEntry(entries, AetherConfig.PEST_AOTV_DELAY_MAX)
            addEntry(entries, AetherConfig.ROD_SWAP_DELAY_MIN)
            addEntry(entries, AetherConfig.ROD_SWAP_DELAY_MAX)
            addEntry(entries, AetherConfig.GUI_FIRST_CLICK_DELAY_MIN)
            addEntry(entries, AetherConfig.GUI_FIRST_CLICK_DELAY_MAX)
            addEntry(entries, AetherConfig.GUI_CLICK_DELAY_MIN)
            addEntry(entries, AetherConfig.GUI_CLICK_DELAY_MAX)
            addEntry(entries, AetherConfig.PICK_UP_STASH_DELAY_MIN)
            addEntry(entries, AetherConfig.PICK_UP_STASH_DELAY_MAX)
            addEntry(entries, AetherConfig.JUNK_ITEM_DROP_DELAY_MIN)
            addEntry(entries, AetherConfig.JUNK_ITEM_DROP_DELAY_MAX)
            addEntry(entries, AetherConfig.GEORGE_POST_SELL_DELAY_MIN_MS)
            addEntry(entries, AetherConfig.GEORGE_POST_SELL_DELAY_MAX_MS)
            addEntry(entries, AetherConfig.FARM_WHILE_CALLING_GEORGE)
            addEntry(entries, AetherConfig.VISITOR_DELAY_MIN)
            addEntry(entries, AetherConfig.VISITOR_DELAY_MAX)
            addEntry(entries, AetherConfig.BAZAAR_DELAY_MIN)
            addEntry(entries, AetherConfig.BAZAAR_DELAY_MAX)
            addEntry(entries, AetherConfig.ROTATION_TIME)
            addEntry(entries, AetherConfig.ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE)
            addEntry(entries, AetherConfig.ROTATION_EASE_IN)
            addEntry(entries, AetherConfig.ROTATION_EASE_IN_FACTOR)
            addEntry(entries, AetherConfig.ROTATION_EASE_OUT)
            addEntry(entries, AetherConfig.ROTATION_EASE_OUT_FACTOR)
            addEntry(entries, AetherConfig.ROTATION_TRACKING_NOISE_MIN)
            addEntry(entries, AetherConfig.ROTATION_TRACKING_NOISE_MAX)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION)
            addEntry(entries, AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION)
            addEntry(entries, AetherConfig.PEST_FOV_RANGE)
            addEntry(entries, AetherConfig.PEST_MAX_TURN_SPEED)
            addEntry(entries, AetherConfig.PEST_TRACKING_SMOOTHING_MS)
            addEntry(entries, AetherConfig.PEST_APPROACH_SPEED)
            addEntry(entries, AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE)
            addEntry(entries, AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE)
            addEntry(entries, AetherConfig.PEST_HUNTING_MAX_DISTANCE)
            addEntry(entries, AetherConfig.PEST_HUNTING_TRACKING_SMOOTHING_MS)
            addEntry(entries, AetherConfig.PEST_HUNTING_MAX_TURN_SPEED)
            addEntry(entries, AetherConfig.PEST_AIM_DRIFT)
            addEntry(entries, AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN)
            addEntry(entries, AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX)
            addEntry(entries, AetherConfig.VISITOR_FOV_RANGE)
            addEntry(entries, AetherConfig.PEST_EXCHANGE_FOV_RANGE)
            return entries
        }

        private fun addEntry(entries: MutableMap<String, ConfigEntry<*>>, entry: ConfigEntry<*>) {
            entries[entry.getKey()] = entry
        }

        private fun ensurePresetDirectory() {
            try {
                Files.createDirectories(PRESET_DIR)
            } catch (e: IOException) {
                throw IllegalStateException("Failed to create humanization preset directory: $PRESET_DIR", e)
            }
        }

        private fun ensureBundledPreset(presetId: String) {
            val presetPath = PRESET_DIR.resolve(presetId + ".json")
            if (Files.exists(presetPath)) return
            val resourcePath = RESOURCE_BASE + presetId + ".json"
            try {
                AetherResources.open(resourcePath).use { inputStream ->
                    if (inputStream == null) {
                        System.err.println("[Aether] Bundled humanization preset missing: $resourcePath")
                        return
                    }
                    Files.copy(inputStream, presetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                System.err.println("[Aether] Failed to copy bundled humanization preset '$presetId': ${e.message}")
            }
        }

        private fun syncBundledPresetDefaults(presetId: String) {
            val presetPath = PRESET_DIR.resolve(presetId + ".json")
            if (!Files.exists(presetPath)) return
            val resourcePath = RESOURCE_BASE + presetId + ".json"
            try {
                AetherResources.open(resourcePath).use { inputStream ->
                    if (inputStream == null) {
                        System.err.println("[Aether] Bundled humanization preset missing: $resourcePath")
                        return
                    }
                    val bundledPreset = parsePresetJson(inputStream)
                    if (bundledPreset == null) {
                        System.err.println("[Aether] Failed to parse bundled humanization preset: $resourcePath")
                        return
                    }
                    val diskPreset: JsonObject
                    Files.newBufferedReader(presetPath).use { reader ->
                        val diskRoot = JsonParser.parseReader(reader)
                        if (diskRoot == null || !diskRoot.isJsonObject) return
                        diskPreset = diskRoot.asJsonObject
                    }
                    var updated = false
                    for ((key, value) in bundledPreset.entrySet()) {
                        if (!diskPreset.has(key)) {
                            diskPreset.add(key, value)
                            updated = true
                        }
                    }
                    if (updated) Files.writeString(presetPath, PRESET_GSON.toJson(diskPreset))
                }
            } catch (e: Exception) {
                System.err.println("[Aether] Failed to sync bundled humanization preset '$presetId': ${e.message}")
            }
        }

        @Throws(IOException::class)
        private fun parsePresetJson(inputStream: InputStream): JsonObject? {
            InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (root == null || !root.isJsonObject) return null
                return root.asJsonObject
            }
        }

        private fun normalizePresetId(presetName: String?): String =
            presetName?.trim()?.lowercase(java.util.Locale.ROOT) ?: "normal"

        private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase(java.util.Locale.ROOT).contains("win")
    }
}
