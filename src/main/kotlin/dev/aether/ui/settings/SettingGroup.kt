package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.ArrayList
import java.util.List as JList
import java.util.function.BooleanSupplier
import java.util.function.Consumer

// a named group of settings that can be toggled and expanded, roughly one module
class SettingGroup private constructor(name: String, description: String, private val enabledGetter: BooleanSupplier, private val enabledSetter: Consumer<Boolean>, private val alwaysOnFlag: Boolean) {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val description: String = AetherLang.localize(description)
    private val rawDescription: String = description
    private val settings: kotlin.collections.MutableList<Setting> = ArrayList()
    fun add(setting: Setting): SettingGroup { settings.add(setting); return this }
    fun isEnabled(): Boolean = enabledGetter.asBoolean
    fun toggle() = enabledSetter.accept(!isEnabled())
    fun setEnabled(value: Boolean) = enabledSetter.accept(value)
    fun getName(): String = name
    fun getRawName(): String = rawName
    fun getDescription(): String = description
    fun getRawDescription(): String = rawDescription
    fun getSettings(): kotlin.collections.List<Setting> = settings
    fun hasSettings(): Boolean = !settings.isEmpty()
    fun isAlwaysOn(): Boolean = alwaysOnFlag
    companion object {
        @JvmStatic fun of(name: String, description: String, enabledGetter: BooleanSupplier, enabledSetter: Consumer<Boolean>): SettingGroup = SettingGroup(name, description, enabledGetter, enabledSetter, false)
        // no enable/disable toggle; always visible
        @JvmStatic fun alwaysOn(name: String, description: String): SettingGroup = SettingGroup(name, description, BooleanSupplier { true }, Consumer { }, true)
    }
}
