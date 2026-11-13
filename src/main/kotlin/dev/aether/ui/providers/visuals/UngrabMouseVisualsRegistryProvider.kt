package dev.aether.ui.providers.visuals

import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.modules.visuals.UngrabMouseManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup

class UngrabMouseVisualsRegistryProvider : AbstractVisualsRegistryProvider(4) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("Ungrab Mouse Settings", "Release the mouse cursor so you can move it outside the game window")
        group.add(KeybindSetting("Toggle Keybind", AetherKeybindRegistry.getUngrabMouseKey()))
        return MainGUIRegistry.toggleSubTab("Ungrab Mouse", "Release the mouse cursor so you can move it outside the game window",
            UngrabMouseManager::isEnabled, UngrabMouseManager::setEnabled, listOf(group))
    }
}
