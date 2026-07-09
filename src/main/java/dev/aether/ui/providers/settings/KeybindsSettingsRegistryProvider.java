package dev.aether.ui;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.ArrayList;
import java.util.List;

public final class KeybindsSettingsRegistryProvider extends AbstractKeybindsRegistryProvider {
    public KeybindsSettingsRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();
        SettingGroup keybinds = SettingGroup.alwaysOn(
                "Aether Keybinds",
                "These bindings stay synced with Minecraft's Controls screen"
        );

        for (AetherKeybindRegistry.RegisteredKeybind registeredKeybind : AetherKeybindRegistry.getRegisteredKeybinds()) {
            keybinds.add(new KeybindSetting(registeredKeybind.name(), registeredKeybind.mapping()));
        }

        groups.add(keybinds);
        return MainGUIRegistry.subTab("Aether", "Keyboard shortcuts for the client", groups);
    }
}
