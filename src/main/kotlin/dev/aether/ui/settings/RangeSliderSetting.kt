package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.BiConsumer
import java.util.function.Supplier

class RangeSliderSetting(name: String, private val min: Float, private val max: Float, private val lowerGetter: Supplier<Float>, private val upperGetter: Supplier<Float>, private val setter: BiConsumer<Float, Float>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private var decimals: Int = 1
    private var suffix: String = ""
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getLowerValue(): Float = lowerGetter.get()
    fun getUpperValue(): Float = upperGetter.get()
    fun getMin(): Float = min
    fun getMax(): Float = max
    fun getDecimals(): Int = decimals
    fun getSuffix(): String = suffix
    fun setLowerValue(value: Float) = setValues(value, getUpperValue())
    fun setUpperValue(value: Float) = setValues(getLowerValue(), value)
    fun setValues(lower: Float, upper: Float) {
        var clampedLower = Math.max(min, Math.min(max, lower))
        var clampedUpper = Math.max(min, Math.min(max, upper))
        if (clampedLower > clampedUpper) { val swap = clampedLower; clampedLower = clampedUpper; clampedUpper = swap }
        setter.accept(clampedLower, clampedUpper)
    }
    fun withDecimals(decimals: Int): RangeSliderSetting { this.decimals = decimals; return this }
    fun withSuffix(suffix: String): RangeSliderSetting { this.suffix = suffix; return this }
    fun visibleWhen(condition: Supplier<Boolean>): RangeSliderSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.RANGE_SLIDER
    override fun isVisible(): Boolean = visibility.get()
}
