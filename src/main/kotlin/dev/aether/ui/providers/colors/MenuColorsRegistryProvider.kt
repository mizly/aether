package dev.aether.ui.providers.colors

import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractColorsRegistryProvider
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.theme.Theme
import java.util.ArrayList

class MenuColorsRegistryProvider : AbstractColorsRegistryProvider(1) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        val menuColors = SettingGroup.alwaysOn("Menu Colors", "Customize config menu colors")
        for (entry in Theme.ENTRIES) {
            menuColors.add(ColorSetting(entry.label, entry.getter) { value ->
                entry.setter.accept(value)
                Theme.saveTheme()
            })
        }
        groups.add(menuColors)
        return MainGUIRegistry.subTab("Menu Colors", "Customize config menu colors", groups.toList())
    }
}
