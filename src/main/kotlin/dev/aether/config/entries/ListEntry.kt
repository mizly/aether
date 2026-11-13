package dev.aether.config.entries

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import java.util.ArrayList

class ListEntry<T>(key: String, defaultValue: List<T>, private val elementType: Class<T>) :
    ConfigEntry<List<T>>(key, ArrayList<T>(defaultValue)) {

    init {
        value = ArrayList<T>(defaultValue)
    }

    fun addItem(item: T) {
        (value as MutableList<T>).add(item)
        markChanged()
    }

    fun removeItem(item: T) {
        (value as MutableList<T>).remove(item)
        markChanged()
    }

    fun contains(item: T): Boolean = value.contains(item)

    override fun set(value: List<T>) {
        this.value = ArrayList<T>(value)
        markChanged()
    }

    override fun reset() {
        value = ArrayList<T>(defaultValue)
        markChanged()
    }

    override fun toJson(): JsonElement {
        val array = JsonArray()
        for (item in value) {
            array.add(item.toString())
        }
        return array
    }

    @Suppress("UNCHECKED_CAST")
    override fun fromJson(element: JsonElement?) {
        if (element == null || !element.isJsonArray) return
        val list = ArrayList<T>()
        for (entry in element.asJsonArray) {
            if (elementType == String::class.java) {
                list.add(entry.asString as T)
            } else if (elementType == Int::class.javaObjectType) {
                list.add(entry.asInt as T)
            }
        }
        set(list)
    }
}
