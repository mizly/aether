package dev.aether.ui.providers.visuals

import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.config.FreelookMode
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup

class FreelookVisualsRegistryProvider : AbstractVisualsRegistryProvider(3) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("Freelook Settings", "Orbit the camera around your player while your body keeps facing forward")
        group.add(KeybindSetting("Freelook Keybind", AetherKeybindRegistry.getFreelookKey()))
        group.add(DropdownSetting("Activation Mode", listOf("Hold", "Toggle"),
            { FreelookMode.values().toList().indexOf(ConfigHelpers.getFreelookMode()) }, { index ->
                AetherConfig.FREELOOK_MODE.set(FreelookMode.values()[index].name)
                AetherConfig.save()
            }))
        return MainGUIRegistry.toggleSubTab("Freelook", "Orbit the camera freely without turning your player",
            { AetherConfig.FREELOOK_ENABLED.get() }, { enabled ->
                AetherConfig.FREELOOK_ENABLED.set(enabled)
                AetherConfig.save()
            }, listOf(group))
    }
}
