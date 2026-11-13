package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Consumer
import java.util.function.Supplier

// text input backed by a getter/setter
class TextSetting(name: String, placeholder: String, private val getter: Supplier<String>, private val setter: Consumer<String>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val placeholder: String = AetherLang.localize(placeholder)
    private var multiline: Boolean = false
    private var visibleLines: Int = 4
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getValue(): String = getter.get()
    fun setValue(value: String) = setter.accept(value)
    fun getPlaceholder(): String = placeholder
    fun isMultiline(): Boolean = multiline
    fun getVisibleLines(): Int = visibleLines
    fun visibleWhen(condition: Supplier<Boolean>): TextSetting { visibility = condition; return this }
    fun multiline(): TextSetting = multiline(4)
    fun multiline(visibleLines: Int): TextSetting { multiline = true; this.visibleLines = Math.max(2, visibleLines); return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.TEXT
    override fun isVisible(): Boolean = visibility.get()
}
