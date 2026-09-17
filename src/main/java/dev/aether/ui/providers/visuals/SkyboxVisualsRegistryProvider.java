package dev.aether.ui.providers.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.Skybox;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class SkyboxVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public SkyboxVisualsRegistryProvider() {
        super(4);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn("Sky Shader", "Choose a sky and adjust its motion and brightness");
        group.add(new DropdownSetting("Shader", Skybox.PRESETS, Skybox::preset,
                value -> { AetherConfig.SKYBOX_PRESET.set(value); AetherConfig.save(); }));
        group.add(new SliderSetting("Animation Speed", 0f, 2f, AetherConfig.SKYBOX_SPEED::get,
                value -> { AetherConfig.SKYBOX_SPEED.set(value); AetherConfig.save(); }).withDecimals(1));
        group.add(new SliderSetting("Sky Brightness", 0.5f, 1.5f, AetherConfig.SKYBOX_BRIGHTNESS::get,
                value -> { AetherConfig.SKYBOX_BRIGHTNESS.set(value); AetherConfig.save(); }).withDecimals(1).withSuffix("x"));
        return MainGUIRegistry.toggleSubTab("Skybox", "Animated shader skies",
                Skybox::isEnabled,
                value -> { AetherConfig.SKYBOX_ENABLED.set(value); AetherConfig.save(); }, List.of(group));
    }
}
