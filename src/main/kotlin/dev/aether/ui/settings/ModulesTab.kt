package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Supplier
import java.util.function.BooleanSupplier
import java.util.function.Consumer

// just holds the SubTab record now; MainGUI does the rendering
class ModulesTab private constructor() {
    // named grouping of SettingGroups with its own header metadata and state
    class SubTab(name: String, description: String, enabledGetter: BooleanSupplier?, enabledSetter: Consumer<Boolean>?, groups: List<SettingGroup>) {
        private val name: String = AetherLang.localize(name)
        private val description: String = AetherLang.localize(description)
        private val enabledGetter: BooleanSupplier? = enabledGetter
        private val enabledSetter: Consumer<Boolean>? = enabledSetter
        private val groups: List<SettingGroup> = groups
        constructor(name: String, description: String, groups: List<SettingGroup>) : this(name, description, null, null, groups)
        fun name(): String = name
        fun description(): String = description
        fun enabledGetter(): BooleanSupplier? = enabledGetter
        fun enabledSetter(): Consumer<Boolean>? = enabledSetter
        fun groups(): List<SettingGroup> = groups
        fun hasToggle(): Boolean = enabledGetter != null && enabledSetter != null
        fun isEnabled(): Boolean = !hasToggle() || (enabledGetter?.asBoolean ?: false)
        fun toggle() {
            val setter = enabledSetter
            if (hasToggle() && setter != null) setter.accept(!isEnabled())
        }
        override fun equals(other: Any?): Boolean = other is SubTab && name == other.name && description == other.description && enabledGetter == other.enabledGetter && enabledSetter == other.enabledSetter && groups == other.groups
        override fun hashCode(): Int = java.util.Objects.hash(name, description, enabledGetter, enabledSetter, groups)
        override fun toString(): String = "SubTab[name=$name, description=$description, enabledGetter=$enabledGetter, enabledSetter=$enabledSetter, groups=$groups]"
    }
}
