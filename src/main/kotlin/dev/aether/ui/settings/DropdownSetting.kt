package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.ArrayList
import java.util.List as JList
import java.util.function.Consumer
import java.util.function.Supplier

// dropdown backed by an index getter/setter
class DropdownSetting(name: String, private val options: kotlin.collections.List<String>, private val indexGetter: Supplier<Int>, private val indexSetter: Consumer<Int>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val iconActions: MutableList<IconAction> = ArrayList()
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getOptions(): kotlin.collections.List<String> = options.map { o -> AetherLang.localize(o) }
    fun getIconActions(): List<IconAction> = iconActions
    fun getSelectedIndex(): Int = indexGetter.get()
    fun setSelectedIndex(index: Int) = indexSetter.accept(index)
    fun getSelectedOption(): String {
        if (options.isEmpty()) return ""
        val index = getSelectedIndex()
        if (index < 0 || index >= options.size) return AetherLang.localize(options.first())
        return AetherLang.localize(options[index])
    }
    fun addIconAction(iconPath: String, action: Runnable?): DropdownSetting { iconActions.add(IconAction(iconPath, action)); return this }
    fun visibleWhen(condition: Supplier<Boolean>): DropdownSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.DROPDOWN
    override fun isVisible(): Boolean = visibility.get()

    class IconAction(private val iconPath: String, private val action: Runnable?) {
        fun iconPath(): String = iconPath
        fun action(): Runnable? = action
        fun execute() { if (action != null) action.run() }
        override fun equals(other: Any?): Boolean = other is IconAction && iconPath == other.iconPath && action == other.action
        override fun hashCode(): Int = java.util.Objects.hash(iconPath, action)
        override fun toString(): String = "IconAction[iconPath=$iconPath, action=$action]"
    }
}
