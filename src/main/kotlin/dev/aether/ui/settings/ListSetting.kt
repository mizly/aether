package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.ArrayList
import java.util.function.Consumer
import java.util.function.Supplier

class ListSetting(name: String, placeholder: String, private val getter: Supplier<ArrayList<String>>, private val setter: Consumer<ArrayList<String>>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val placeholder: String = AetherLang.localize(placeholder)
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getValues(): ArrayList<String> = ArrayList(getter.get())
    fun setValues(values: kotlin.collections.List<String>) = setter.accept(ArrayList(values))
    fun getPlaceholder(): String = placeholder
    fun visibleWhen(condition: Supplier<Boolean>): ListSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.LIST
    override fun isVisible(): Boolean = visibility.get()
}
