package dev.aether.ui.providers.visuals

import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.PipManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting

class PipVisualsRegistryProvider : AbstractVisualsRegistryProvider(3) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("PiP Settings", "Open a picture-in-picture view of the game")
        group.add(KeybindSetting("Toggle Keybind", AetherKeybindRegistry.getPipKey()))
        group.add(ToggleSetting("Start Floating", AetherConfig.PIP_START_FLOATING::get) { value ->
            AetherConfig.PIP_START_FLOATING.set(value)
            AetherConfig.save()
        })
        group.add(ToggleSetting("Window Decorations", AetherConfig.PIP_START_DECORATED::get) { value ->
            AetherConfig.PIP_START_DECORATED.set(value)
            AetherConfig.save()
        })
        group.add(SliderSetting("Window Width", 240.0f, 1920.0f, { AetherConfig.PIP_WINDOW_WIDTH.get().toFloat() }, { value ->
            AetherConfig.PIP_WINDOW_WIDTH.set(Math.round(value).toInt())
            AetherConfig.save()
        }).withDecimals(0).withSuffix(" px"))
        group.add(SliderSetting("Window Height", 135.0f, 1080.0f, { AetherConfig.PIP_WINDOW_HEIGHT.get().toFloat() }, { value ->
            AetherConfig.PIP_WINDOW_HEIGHT.set(Math.round(value).toInt())
            AetherConfig.save()
        }).withDecimals(0).withSuffix(" px"))
        return MainGUIRegistry.toggleSubTab("PiP", "Open a picture-in-picture view of the game", PipManager::isEnabled,
            PipManager::setEnabled, listOf(group))
    }
}
