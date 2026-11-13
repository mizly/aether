package dev.aether.config.entries

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class IntEntry(key: String, defaultValue: Int) : ConfigEntry<Int>(key, defaultValue) {
    private var min: Int = Int.MIN_VALUE
    private var max: Int = Int.MAX_VALUE

    fun range(min: Int, max: Int): IntEntry {
        this.min = min
        this.max = max
        return this
    }

    override fun set(value: Int) {
        this.value = maxOf(min, minOf(max, value))
        markChanged()
    }

    override fun toJson(): JsonElement = JsonPrimitive(value)

    override fun fromJson(element: JsonElement?) {
        if (element != null && !element.isJsonNull) {
            set(element.asInt)
        }
    }
}
