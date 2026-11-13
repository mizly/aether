package dev.aether.config.entries

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class BooleanEntry(key: String, defaultValue: Boolean) : ConfigEntry<Boolean>(key, defaultValue) {
    override fun toJson(): JsonElement = JsonPrimitive(value)

    override fun fromJson(element: JsonElement?) {
        if (element != null && !element.isJsonNull) {
            set(element.asBoolean)
        }
    }
}
