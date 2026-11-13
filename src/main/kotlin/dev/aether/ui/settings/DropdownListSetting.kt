package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Consumer
import java.util.function.Supplier

class DropdownListSetting(name: String, allOptions: List<String>, private val getter: Supplier<List<String>>, private val setter: Consumer<List<String>>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val allOptions: List<String> = java.util.List.copyOf(allOptions)
    private val addPicker: DropdownSetting
    private var visibility: Supplier<Boolean> = Supplier { true }
    init {
        val pendingIdx = intArrayOf(0)
        addPicker = DropdownSetting("", allOptions, Supplier { pendingIdx[0] }, Consumer { i: Int -> pendingIdx[0] = i })
    }
    fun getValues(): java.util.ArrayList<String> = java.util.ArrayList(getter.get())
    fun addValue(v: String) { val vals = getValues(); if (!vals.contains(v)) { vals.add(v); setter.accept(java.util.ArrayList(vals)) } }
    fun removeValue(index: Int) { val vals = getValues(); if (index >= 0 && index < vals.size) { vals.removeAt(index); setter.accept(java.util.ArrayList(vals)) } }
    fun moveUp(index: Int) { val vals = getValues(); if (index > 0 && index < vals.size) { val tmp = vals[index - 1]; vals[index - 1] = vals[index]; vals[index] = tmp; setter.accept(java.util.ArrayList(vals)) } }
    fun moveDown(index: Int) { val vals = getValues(); if (index >= 0 && index < vals.size - 1) { val tmp = vals[index + 1]; vals[index + 1] = vals[index]; vals[index] = tmp; setter.accept(java.util.ArrayList(vals)) } }
    fun getAllOptions(): List<String> = allOptions
    fun getAddPicker(): DropdownSetting = addPicker
    fun visibleWhen(condition: Supplier<Boolean>): DropdownListSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.DROPDOWN_LIST
    override fun isVisible(): Boolean = visibility.get()
}
