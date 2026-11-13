package dev.aether.ui

import dev.aether.config.AetherConfig
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.ToggleSetting
import java.util.List

class BootstrapSettingsRegistryProvider : MainGUIRegistryProvider {
    override fun register(registrar: MainGUIRegistry.Registrar) {
        registrar.registerSettings(ORDER, createSubTab())
    }

    private fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Bootstrap",
            "Settings that apply before premium modules are loaded"
        )
        group.add(ToggleSetting("Custom UI",
            { AetherConfig.CUSTOM_UI_ENABLED.get() },
            { value ->
                AetherConfig.CUSTOM_UI_ENABLED.set(value)
                AetherConfig.save()
            }))
        return MainGUIRegistry.subTab("Bootstrap", "Pre-login bootstrap settings", listOf(group))
    }

    companion object {
        private const val ORDER = 1
    }
}
