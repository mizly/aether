package dev.aether.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.aether.config.entries.BooleanEntry
import dev.aether.config.entries.ConfigEntry
import dev.aether.config.entries.DoubleEntry
import dev.aether.config.entries.FloatEntry
import dev.aether.config.entries.IntEntry
import dev.aether.config.entries.ListEntry
import dev.aether.config.entries.StringEntry
import java.io.IOException
import java.io.Reader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList

class Config private constructor() {
    companion object {
        private val ENTRIES: MutableList<ConfigEntry<*>> = ArrayList()
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private var configPath: Path? = null
        private var dirty = false
        private var autosaveSuspendDepth = 0

        @JvmStatic
        internal fun setConfigPath(path: Path) { configPath = path }

        private fun <T, E : ConfigEntry<T>> register(entry: E): E {
            ENTRIES.add(entry)
            return entry
        }

        @JvmStatic fun bool(key: String, defaultValue: Boolean): BooleanEntry = register(BooleanEntry(key, defaultValue))
        @JvmStatic fun integer(key: String, defaultValue: Int): IntEntry = register(IntEntry(key, defaultValue))
        @JvmStatic fun floatVal(key: String, defaultValue: Float): FloatEntry = register(FloatEntry(key, defaultValue))
        @JvmStatic fun doubleVal(key: String, defaultValue: Double): DoubleEntry = register(DoubleEntry(key, defaultValue))
        @JvmStatic fun string(key: String, defaultValue: String): StringEntry = register(StringEntry(key, defaultValue))
        @JvmStatic fun <T> list(key: String, defaultValue: kotlin.collections.List<T>, elementType: Class<T>): ListEntry<T> = register(ListEntry(key, defaultValue, elementType))

        @JvmStatic fun save() { dirty = true; flush() }

        @JvmStatic fun onEntryChanged() {
            dirty = true
            if (autosaveSuspendDepth == 0) flush()
        }

        @JvmStatic fun flush() {
            val path = configPath ?: return
            if (!dirty && Files.exists(path)) return
            writeConfig(path)
            dirty = false
        }

        @JvmStatic fun toJsonString(): String = GSON.toJson(toJsonObject())

        @JvmStatic fun loadFrom(path: Path?): Boolean {
            if (path == null || !Files.exists(path)) return false
            try {
                val obj = readJsonObject(path)
                withoutAutosave(Runnable { applyJson(obj, true) })
                dirty = true
                return true
            } catch (e: Exception) {
                System.err.println("[Aether] Config load failed for $path: ${e.message}")
                return false
            }
        }

        @JvmStatic fun loadFromJson(json: String?): Boolean {
            if (json == null || json.isBlank()) return false
            try {
                val element = JsonParser.parseString(json)
                if (element == null || !element.isJsonObject) throw JsonParseException("root value is not a JSON object")
                val obj = element.asJsonObject
                withoutAutosave(Runnable { applyJson(obj, true) })
                dirty = true
                return true
            } catch (e: Exception) {
                System.err.println("[Aether] Config import failed: ${e.message}")
                return false
            }
        }

        @JvmStatic fun load() {
            val path = configPath ?: return
            if (!Files.exists(path)) {
                withoutAutosave(Runnable { resetEntriesOnly() })
                dirty = true
                return
            }
            try {
                val obj = readJsonObject(path)
                withoutAutosave(Runnable { applyJson(obj, true) })
                dirty = false
            } catch (e: Exception) {
                System.err.println("[Aether] Corrupt config file found. Wiping config and starting fresh: ${e.message}")
                withoutAutosave(Runnable { resetEntriesOnly() })
                dirty = true
                try { Files.deleteIfExists(path) } catch (deleteError: IOException) { deleteError.printStackTrace() }
            }
        }

        @JvmStatic fun reset() {
            withoutAutosave(Runnable { resetEntriesOnly() })
            dirty = true
            flush()
        }

        @JvmStatic fun suspendAutosave(action: Runnable) { withoutAutosave(action) }

        private fun writeConfig(path: Path) {
            try {
                val parent = path.parent
                if (parent != null && !Files.exists(parent)) Files.createDirectories(parent)
            } catch (_: IOException) {}
            val obj = toJsonObject()
            try {
                val parent = path.parent
                val tempPath = if (parent == null) Path.of(path.fileName.toString() + ".tmp") else parent.resolve(path.fileName.toString() + ".tmp")
                Files.writeString(tempPath, GSON.toJson(obj))
                try {
                    Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) { e.printStackTrace() }
        }

        private fun toJsonObject(): JsonObject {
            val obj = JsonObject()
            for (entry in ENTRIES) if (entry.isPersistent()) obj.add(entry.getKey(), entry.toJson())
            return obj
        }

        private fun readJsonObject(path: Path): JsonObject {
            Files.newBufferedReader(path).use { r: Reader ->
                val element = JsonParser.parseReader(r)
                if (element == null || !element.isJsonObject) throw JsonParseException("root value is not a JSON object")
                return element.asJsonObject
            }
        }

        private fun applyJson(obj: JsonObject, resetMissing: Boolean) {
            if (resetMissing) resetEntriesOnly()
            for (entry in ENTRIES) if (obj.has(entry.getKey())) entry.fromJson(obj.get(entry.getKey()))
        }

        private fun resetEntriesOnly() { for (entry in ENTRIES) entry.reset() }

        private fun withoutAutosave(action: Runnable) {
            autosaveSuspendDepth++
            try { action.run() } finally { autosaveSuspendDepth-- }
        }
    }
}
