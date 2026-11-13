package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Consumer
import java.util.function.Supplier

// on/off backed by a getter/setter
class ToggleSetting(name: String, private val getter: Supplier<Boolean>, private val setter: Consumer<Boolean>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getValue(): Boolean = getter.get()
    fun setValue(value: Boolean) = setter.accept(value)
    fun toggle() = setValue(!getValue())
    fun visibleWhen(condition: Supplier<Boolean>): ToggleSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.TOGGLE
    override fun isVisible(): Boolean = visibility.get()
}
