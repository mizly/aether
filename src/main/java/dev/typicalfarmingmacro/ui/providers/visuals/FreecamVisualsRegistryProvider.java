package dev.typicalfarmingmacro.ui;

import dev.typicalfarmingmacro.bootstrap.TfmKeybindRegistry;
import dev.typicalfarmingmacro.config.TfmConfig;
import dev.typicalfarmingmacro.modules.visuals.FreecamManager;
import dev.typicalfarmingmacro.ui.settings.KeybindSetting;
import dev.typicalfarmingmacro.ui.settings.ModulesTab;
import dev.typicalfarmingmacro.ui.settings.SettingGroup;
import dev.typicalfarmingmacro.ui.settings.SliderSetting;

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
        group.add(new KeybindSetting("Toggle Keybind", TfmKeybindRegistry.getFreecamKey()));
        group.add(new KeybindSetting("Teleport To Player Keybind", TfmKeybindRegistry.getFreecamTeleportToPlayerKey()));
        group.add(new SliderSetting("Movement Speed", 0.1f, 2.5f,
                () -> TfmConfig.FREECAM_SPEED.get(),
                value -> {
                    TfmConfig.FREECAM_SPEED.set(value);
                    TfmConfig.save();
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
