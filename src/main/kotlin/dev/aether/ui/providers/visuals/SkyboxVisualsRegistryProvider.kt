package dev.aether.ui.providers.visuals

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.Skybox
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class SkyboxVisualsRegistryProvider : AbstractVisualsRegistryProvider(4) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("Sky Shader", "Choose a sky and adjust its motion and brightness")
        group.add(DropdownSetting("Shader", Skybox.PRESETS, Skybox::preset) { value ->
            AetherConfig.SKYBOX_PRESET.set(value)
            AetherConfig.save()
        })
        group.add(SliderSetting("Animation Speed", 0.0f, 2.0f, AetherConfig.SKYBOX_SPEED::get) { value ->
            AetherConfig.SKYBOX_SPEED.set(value)
            AetherConfig.save()
        }.withDecimals(1))
        group.add(SliderSetting("Sky Brightness", 0.5f, 1.5f, AetherConfig.SKYBOX_BRIGHTNESS::get) { value ->
            AetherConfig.SKYBOX_BRIGHTNESS.set(value)
            AetherConfig.save()
        }.withDecimals(1).withSuffix("x"))
        return MainGUIRegistry.toggleSubTab("Skybox", "Animated shader skies", Skybox::isEnabled, { value ->
            AetherConfig.SKYBOX_ENABLED.set(value)
            AetherConfig.save()
        }, listOf(group))
    }
}
