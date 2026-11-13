package dev.aether.ui.providers.settings

import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractKeybindsRegistryProvider
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import java.util.ArrayList

class KeybindsSettingsRegistryProvider : AbstractKeybindsRegistryProvider(0) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        val keybinds = SettingGroup.alwaysOn("Aether Keybinds", "These bindings stay synced with Minecraft's Controls screen")
        for (registeredKeybind in AetherKeybindRegistry.getRegisteredKeybinds()) {
            keybinds.add(KeybindSetting(registeredKeybind.name, registeredKeybind.mapping, registeredKeybind.description))
        }
        groups.add(keybinds)
        return MainGUIRegistry.subTab("Aether", "Keyboard shortcuts for the client", groups.toList())
    }
}
