package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Supplier

class SectionSetting(name: String, description: String?) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val description: String = if (description == null) "" else AetherLang.localize(description)
    private var visibility: Supplier<Boolean> = Supplier { true }
    override fun getDescription(): String = description
    fun visibleWhen(condition: Supplier<Boolean>): SectionSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.SECTION
    override fun isVisible(): Boolean = visibility.get()
}
