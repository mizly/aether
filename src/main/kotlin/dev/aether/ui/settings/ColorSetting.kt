package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Consumer
import java.util.function.Supplier

class ColorSetting(name: String, private val getter: Supplier<Int>, private val setter: Consumer<Int>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private var visibilityCondition: Supplier<Boolean>? = null

    fun visibleWhen(condition: Supplier<Boolean>): ColorSetting {
        visibilityCondition = condition
        return this
    }

    fun getValue(): Int = getter.get()
    fun setValue(v: Int) = setter.accept(v)

    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.COLOR
    override fun isVisible(): Boolean {
        val condition = visibilityCondition
        return condition == null || condition.get()
    }
}
