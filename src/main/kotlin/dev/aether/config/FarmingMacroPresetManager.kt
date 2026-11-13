package dev.aether.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.config.entries.ConfigEntry
import dev.aether.notification.NotificationManager
import net.fabricmc.loader.api.FabricLoader
import java.awt.Desktop
import java.io.IOException
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Locale

class FarmingMacroPresetManager private constructor() {
    companion object {
        private val PRESET_GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private const val EMPTY_OPTION = "No Presets"
        private val PRESET_DIR: Path = FabricLoader.getInstance().configDir.resolve("aether").resolve("farming-macro-presets")
        private val PRESET_ENTRIES: Map<String, ConfigEntry<*>> = createPresetEntries()
        private val PRESET_OPTIONS: MutableList<String> = ArrayList()

        @JvmStatic
        fun init() {
            ensurePresetDirectory()
            refreshPresetOptions()
        }

        @JvmStatic
        fun getPresetOptions(): List<String> {
            if (PRESET_OPTIONS.isEmpty()) refreshPresetOptions()
            return PRESET_OPTIONS
        }

        @JvmStatic
        fun getSelectedPresetIndex(): Int {
            refreshPresetOptions()
            if (PRESET_OPTIONS.size == 1 && EMPTY_OPTION == PRESET_OPTIONS.first()) return 0
            val selectedPreset = AetherConfig.FARMING_MACRO_PRESET.get()
            for (i in PRESET_OPTIONS.indices) {
                if (PRESET_OPTIONS[i].equals(selectedPreset, ignoreCase = true)) return i
            }
            return 0
        }

        @JvmStatic
        fun applyPresetByIndex(index: Int) {
            refreshPresetOptions()
            if (index < 0 || index >= PRESET_OPTIONS.size) return
            val presetName = PRESET_OPTIONS[index]
            if (EMPTY_OPTION == presetName) return
            applyPreset(presetName)
        }

        @JvmStatic
        fun reapplySelectedPreset() {
            refreshPresetOptions()
            if (PRESET_OPTIONS.size == 1 && EMPTY_OPTION == PRESET_OPTIONS.first()) {
                NotificationManager.warning("No Farming Presets", "Save a preset before loading one.")
                return
            }
            applyPresetByIndex(getSelectedPresetIndex())
        }

        @JvmStatic
        fun saveCurrentPreset() {
            init()
            val presetName = AetherConfig.FARMING_MACRO_PRESET_NAME.get()
            val presetId = normalizePresetId(presetName)
            if (presetId.isBlank()) {
                NotificationManager.warning("Preset Name Required", "Enter a preset name before saving.")
                return
            }

            val preset = JsonObject()
            for ((key, entry) in PRESET_ENTRIES) preset.add(key, entry.toJson())
            val presetPath = PRESET_DIR.resolve(presetId + ".json")
            try {
                val tempPath = PRESET_DIR.resolve(presetId + ".json.tmp")
                Files.writeString(tempPath, PRESET_GSON.toJson(preset))
                try {
                    Files.move(tempPath, presetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: IOException) {
                    Files.move(tempPath, presetPath, StandardCopyOption.REPLACE_EXISTING)
                }
                AetherConfig.FARMING_MACRO_PRESET.set(displayNameFromId(presetId))
                AetherConfig.save()
                refreshPresetOptions()
                NotificationManager.success("Farming Preset Saved", "Preset saved successfully.")
            } catch (e: Exception) {
                NotificationManager.error("Farming Preset Save Failed", "Could not save preset.")
                System.err.println("[Aether] Failed to save farming macro preset '$presetId': ${e.message}")
            }
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
                NotificationManager.error("Farming Preset Folder Failed", "Could not open preset folder.")
                System.err.println("[Aether] Failed to open farming macro preset folder: ${e.message}")
            }
        }

        @JvmStatic
        fun refreshPresetOptions() {
            ensurePresetDirectory()
            val options = ArrayList<String>()
            try {
                Files.list(PRESET_DIR).use { stream ->
                    stream.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".json") }
                        .sorted(Comparator.comparing<Path, String> { path -> path.fileName.toString().lowercase(java.util.Locale.ROOT) })
                        .map { path -> path.fileName.toString() }
                        .map { fileName -> fileName.substring(0, fileName.length - ".json".length) }
                        .map { fileName -> displayNameFromId(fileName) }
                        .forEach { options.add(it) }
                }
            } catch (e: IOException) {
                System.err.println("[Aether] Failed to list farming macro presets: ${e.message}")
            }
            PRESET_OPTIONS.clear()
            if (options.isEmpty()) PRESET_OPTIONS.add(EMPTY_OPTION) else PRESET_OPTIONS.addAll(options)
        }

        private fun applyPreset(presetName: String) {
            init()
            val presetId = normalizePresetId(presetName)
            val presetPath = PRESET_DIR.resolve(presetId + ".json")
            if (!Files.exists(presetPath)) {
                NotificationManager.error("Farming Preset Load Failed", "Preset file is missing.")
                System.err.println("[Aether] Farming macro preset file missing: ${presetPath.toAbsolutePath()}")
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
                        AetherConfig.FARMING_MACRO_PRESET.set(displayNameFromId(presetId))
                    }
                }
                AetherConfig.save()
                NotificationManager.success("Farming Preset Loaded", "Preset loaded successfully.")
            } catch (e: Exception) {
                NotificationManager.error("Farming Preset Load Failed", "Could not load preset.")
                System.err.println("[Aether] Failed to apply farming macro preset '$presetId': ${e.message}")
            }
        }

        private fun createPresetEntries(): Map<String, ConfigEntry<*>> {
            val entries = LinkedHashMap<String, ConfigEntry<*>>()
            addEntry(entries, AetherConfig.FARM_TYPE)
            addEntry(entries, AetherConfig.MACRO_HOLD_W_WHILE_FARMING)
            addEntry(entries, AetherConfig.MACRO_FAST_LANE_SWITCH)
            addEntry(entries, AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS)
            addEntry(entries, AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY)
            addEntry(entries, AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY)
            addEntry(entries, AetherConfig.MACRO_FARM_WAYPOINTS)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_WAYPOINT_SWITCH_RADIUS)
            addEntry(entries, AetherConfig.SQUEAKY_MOUSEMAT)
            addEntry(entries, AetherConfig.MACRO_USE_CUSTOM_PITCH)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_PITCH)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION)
            addEntry(entries, AetherConfig.MACRO_USE_CUSTOM_YAW)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_YAW)
            addEntry(entries, AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION)
            addEntry(entries, AetherConfig.BPS_AVERAGE_WINDOW)
            return entries
        }

        private fun addEntry(entries: MutableMap<String, ConfigEntry<*>>, entry: ConfigEntry<*>) {
            entries[entry.getKey()] = entry
        }

        private fun ensurePresetDirectory() {
            try {
                Files.createDirectories(PRESET_DIR)
            } catch (e: IOException) {
                throw IllegalStateException("Failed to create farming macro preset directory: $PRESET_DIR", e)
            }
        }

        private fun normalizePresetId(presetName: String?): String {
            if (presetName == null) return ""
            val lower = presetName.trim().lowercase(java.util.Locale.ROOT)
            val builder = StringBuilder()
            var lastWasSeparator = false
            for (ch in lower) {
                if (Character.isLetterOrDigit(ch)) {
                    builder.append(ch)
                    lastWasSeparator = false
                } else if (!lastWasSeparator) {
                    builder.append('-')
                    lastWasSeparator = true
                }
            }
            var end = builder.length
            while (end > 0 && builder[end - 1] == '-') end--
            return builder.substring(0, end)
        }

        private fun displayNameFromId(presetId: String?): String {
            if (presetId == null || presetId.isBlank()) return ""
            val parts = presetId.replace('_', '-').split("-")
            val builder = StringBuilder()
            for (part in parts) {
                if (part.isBlank()) continue
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(Character.toUpperCase(part[0]))
                if (part.length > 1) builder.append(part.substring(1))
            }
            return builder.toString()
        }

        private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase(java.util.Locale.ROOT).contains("win")
    }
}
