package dev.aether.config.entries

import com.google.gson.JsonElement
import dev.aether.config.Config

abstract class ConfigEntry<T>(
    @JvmField protected val key: String,
    @JvmField protected val defaultValue: T
) {
    @JvmField
    protected var value: T = defaultValue

    private var persistent = true

    open fun getKey(): String = key

    open fun get(): T = value

    open fun set(value: T) {
        this.value = value
        markChanged()
    }

    open fun reset() {
        this.value = defaultValue
        markChanged()
    }

    open fun getDefault(): T = defaultValue

    open fun isPersistent(): Boolean = persistent

    // volatile: never written to the config file
    @Suppress("UNCHECKED_CAST")
    fun <E : ConfigEntry<T>> nonPersistent(): E {
        this.persistent = false
        return this as E
    }

    protected fun markChanged() {
        Config.onEntryChanged()
    }

    abstract fun toJson(): JsonElement

    abstract fun fromJson(element: JsonElement?)
}
