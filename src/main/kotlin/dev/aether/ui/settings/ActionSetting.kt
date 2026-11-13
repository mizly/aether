package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Supplier

// button that runs a runnable when clicked
class ActionSetting(name: String, action: Runnable?) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val action: Runnable? = action
    private var visibility: Supplier<Boolean> = Supplier { true }

    fun execute() {
        if (action != null) action.run()
    }

    fun visibleWhen(condition: Supplier<Boolean>): ActionSetting {
        visibility = condition
        return this
    }

    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.ACTION
    override fun isVisible(): Boolean = visibility.get()
}
