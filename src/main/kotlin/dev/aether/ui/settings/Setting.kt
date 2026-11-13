package dev.aether.ui.settings

// implementations hold typed getter/setter references to AetherConfig entries
interface Setting {
    fun getName(): String
    fun getRawName(): String = getName()
    fun getType(): SettingType
    fun getDescription(): String = SettingDescriptionCatalog.describe(this)
    fun isVisible(): Boolean
}
