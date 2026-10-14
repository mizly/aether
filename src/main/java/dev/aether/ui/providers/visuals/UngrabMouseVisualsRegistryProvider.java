package dev.aether.ui.providers.visuals;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.UngrabMouseManager;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class UngrabMouseVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public UngrabMouseVisualsRegistryProvider() {
        super(4);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                "Ungrab Mouse Settings",
                "Release the mouse cursor so you can move it outside the game window"
        );
        group.add(new ToggleSetting("Smart Ungrab Mouse",
                AetherConfig.SMART_UNGRAB_MOUSE::get,
                value -> {
                    AetherConfig.SMART_UNGRAB_MOUSE.set(value);
                    AetherConfig.save();
                }));
        group.add(new KeybindSetting("Toggle Keybind", AetherKeybindRegistry.getUngrabMouseKey()));

        return MainGUIRegistry.toggleSubTab(
                "Ungrab Mouse",
                "Release the mouse cursor so you can move it outside the game window",
                UngrabMouseManager::isEnabled,
                UngrabMouseManager::setEnabled,
                List.of(group)
        );
    }
}
