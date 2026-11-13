package dev.aether.config.entries

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class DoubleEntry(key: String, defaultValue: Double) : ConfigEntry<Double>(key, defaultValue) {
    override fun toJson(): JsonElement = JsonPrimitive(value)

    override fun fromJson(element: JsonElement?) {
        if (element != null && !element.isJsonNull) {
            set(element.asDouble)
        }
    }
}
