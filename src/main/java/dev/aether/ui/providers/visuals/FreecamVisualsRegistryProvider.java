package dev.aether.ui;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.FreecamManager;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class FreecamVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public FreecamVisualsRegistryProvider() {
        super(2);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                "Freecam Settings",
                "Detach the camera and fly around without moving your player"
        );
        group.add(new KeybindSetting("Toggle Keybind", AetherKeybindRegistry.getFreecamKey()));
        group.add(new KeybindSetting("Teleport To Player Keybind", AetherKeybindRegistry.getFreecamTeleportToPlayerKey()));
        group.add(new SliderSetting("Movement Speed", 0.1f, 2.5f,
                () -> AetherConfig.FREECAM_SPEED.get(),
                value -> {
                    AetherConfig.FREECAM_SPEED.set(value);
                    AetherConfig.save();
                })
                .withDecimals(2));

        return MainGUIRegistry.toggleSubTab(
                "Freecam",
                "Allow the freecam keybind to detach and move the camera freely",
                FreecamManager::isFeatureEnabled,
                FreecamManager::setFeatureEnabled,
                List.of(group)
        );
    }
}
