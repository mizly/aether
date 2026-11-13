package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Supplier

class InfoSetting(name: String, private val valueSupplier: Supplier<String?>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private var visibility: Supplier<Boolean> = Supplier { true }
    private var multiline: Boolean = false
    fun getValue(): String { val value = valueSupplier.get(); return value ?: "" }
    fun isMultiline(): Boolean = multiline
    fun multiline(): InfoSetting { multiline = true; return this }
    fun visibleWhen(condition: Supplier<Boolean>): InfoSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.INFO
    override fun isVisible(): Boolean = visibility.get()
}
