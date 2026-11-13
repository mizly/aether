package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Consumer
import java.util.function.Supplier

// numeric slider backed by a getter/setter
class SliderSetting(name: String, private val min: Float, private val max: Float, private val getter: Supplier<Float>, private val setter: Consumer<Float>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private var decimals: Int = 1
    private var suffix: String = ""
    private var percentage: Boolean = false
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getValue(): Float = getter.get()
    fun setValue(value: Float) = setter.accept(Math.max(min, Math.min(max, value)))
    fun getMin(): Float = min
    fun getMax(): Float = max
    fun getDecimals(): Int = decimals
    fun getSuffix(): String = suffix
    fun isPercentage(): Boolean = percentage
    fun withDecimals(decimals: Int): SliderSetting { this.decimals = decimals; return this }
    fun withSuffix(suffix: String): SliderSetting { this.suffix = suffix; return this }
    fun asPercentage(): SliderSetting { percentage = true; return this }
    fun visibleWhen(condition: Supplier<Boolean>): SliderSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.SLIDER
    override fun isVisible(): Boolean = visibility.get()
}
