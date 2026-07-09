package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.List;

public final class BootstrapSettingsRegistryProvider implements MainGUIRegistryProvider {
    private static final int ORDER = 1;

    @Override
    public void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerSettings(ORDER, createSubTab());
    }

    private ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                "Bootstrap",
                "Settings that apply before premium modules are loaded");
        group.add(new ToggleSetting("Custom UI",
                () -> AetherConfig.CUSTOM_UI_ENABLED.get(),
                value -> {
                    AetherConfig.CUSTOM_UI_ENABLED.set(value);
                    AetherConfig.save();
                }));
        return MainGUIRegistry.subTab("Bootstrap", "Pre-login bootstrap settings", List.of(group));
    }
}
