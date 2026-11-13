package dev.aether.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

class ConfigManager private constructor() {
    class Entry internal constructor(@JvmField val key: String, @JvmField val label: String, @JvmField val defaultValue: Any, @JvmField val min: Double, @JvmField val max: Double) {
        fun isBool(): Boolean = defaultValue is Boolean
        fun isNumber(): Boolean = defaultValue is Double
        fun isString(): Boolean = defaultValue is String
    }

    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val SCHEMA: MutableList<Entry> = ArrayList()
        private val VALUES: MutableMap<String, Any> = LinkedHashMap()
        private var configPath: Path? = null

        @JvmStatic fun register(key: String, label: String, def: Boolean) { SCHEMA.add(Entry(key, label, def, 0.0, 0.0)); VALUES[key] = def }
        @JvmStatic fun register(key: String, label: String, def: Double, min: Double, max: Double) { SCHEMA.add(Entry(key, label, def, min, max)); VALUES[key] = def }
        @JvmStatic fun register(key: String, label: String, def: String) { SCHEMA.add(Entry(key, label, def, 0.0, 0.0)); VALUES[key] = def }

        @JvmStatic fun init() {
            configPath = FabricLoader.getInstance().configDir.resolve("aether_ui.json")
            registerDefaults()
            load()
        }

        private fun registerDefaults() {
            register("ui.animations", "UI Animations", true)
            register("ui.opacity", "UI Opacity", 0.95, 0.3, 1.0)
            register("hud.enabled", "Show HUD", true)
            register("hud.scale", "HUD Scale", 1.0, 0.5, 2.0)
            register("macro.delay", "Macro Delay (ms)", 150.0, 0.0, 2000.0)
            register("theme.accent", "Accent Color (hex)", "#D32F2F")
        }

        @JvmStatic fun load() {
            val path = configPath ?: return
            if (!Files.exists(path)) { save(); return }
            try {
                Files.newBufferedReader(path).use { r: Reader ->
                    val obj: JsonObject? = GSON.fromJson(r, JsonObject::class.java)
                    if (obj == null) return
                    for (entry in SCHEMA) {
                        if (!obj.has(entry.key)) continue
                        val element: JsonElement = obj.get(entry.key)
                        if (entry.isBool()) VALUES[entry.key] = element.asBoolean
                        else if (entry.isNumber()) VALUES[entry.key] = element.asDouble
                        else VALUES[entry.key] = element.asString
                    }
                }
            } catch (ex: Exception) { System.err.println("[aether] Config load failed: ${ex.message}") }
        }

        @JvmStatic fun save() {
            val path = configPath ?: return
            val obj = JsonObject()
            VALUES.forEach { (k, v) ->
                when (v) {
                    is Boolean -> obj.addProperty(k, v)
                    is Double -> obj.addProperty(k, v)
                    else -> obj.addProperty(k, v.toString())
                }
            }
            try { Files.writeString(path, GSON.toJson(obj)) }
            catch (ex: Exception) { System.err.println("[aether] Config save failed: ${ex.message}") }
        }

        @JvmStatic fun getBool(key: String): Boolean = VALUES.getOrDefault(key, false) as Boolean
        @JvmStatic fun getNumber(key: String): Double = (VALUES.getOrDefault(key, 0.0) as Number).toDouble()
        @JvmStatic fun getString(key: String): String = VALUES.getOrDefault(key, "").toString()
        @JvmStatic fun set(key: String, value: Any) { VALUES[key] = value }
        @JvmStatic fun getSchema(): List<Entry> = Collections.unmodifiableList(SCHEMA)
    }
}
