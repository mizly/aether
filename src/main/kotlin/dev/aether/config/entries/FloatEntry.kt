package dev.aether.config.entries

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class FloatEntry(key: String, defaultValue: Float) : ConfigEntry<Float>(key, defaultValue) {
    private var min: Float = -Float.MAX_VALUE
    private var max: Float = Float.MAX_VALUE

    fun range(min: Float, max: Float): FloatEntry {
        this.min = min
        this.max = max
        return this
    }

    override fun set(value: Float) {
        this.value = maxOf(min, minOf(max, value))
        markChanged()
    }

    override fun toJson(): JsonElement = JsonPrimitive(value)

    override fun fromJson(element: JsonElement?) {
        if (element != null && !element.isJsonNull) {
            set(element.asFloat)
        }
    }
}
