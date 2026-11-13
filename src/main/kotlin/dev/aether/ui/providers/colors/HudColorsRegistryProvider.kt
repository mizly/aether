package dev.aether.ui.providers.colors

import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractColorsRegistryProvider
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.theme.Theme
import java.util.ArrayList

class HudColorsRegistryProvider : AbstractColorsRegistryProvider(0) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        val hudColors = SettingGroup.alwaysOn("HUD Colors", "Customize HUD element colors")
        for (entry in Theme.HUD_ENTRIES) {
            hudColors.add(ColorSetting(entry.label, entry.getter) { value ->
                entry.setter.accept(value)
                Theme.saveTheme()
            })
        }
        groups.add(hudColors)
        return MainGUIRegistry.subTab("HUD Colors", "Customize HUD element colors", groups.toList())
    }
}
