package dev.aether.ui;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.PipManager;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class PipVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public PipVisualsRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                "PiP Settings",
                "Open a picture-in-picture view of the game"
        );
        group.add(new KeybindSetting("Toggle Keybind", AetherKeybindRegistry.getPipKey()));
        group.add(new ToggleSetting("Start Floating",
                () -> AetherConfig.PIP_START_FLOATING.get(),
                value -> {
                    AetherConfig.PIP_START_FLOATING.set(value);
                    AetherConfig.save();
                }));
        group.add(new ToggleSetting("Window Decorations",
                () -> AetherConfig.PIP_START_DECORATED.get(),
                value -> {
                    AetherConfig.PIP_START_DECORATED.set(value);
                    AetherConfig.save();
                }));
        group.add(new SliderSetting("Window Width", 240.0f, 1920.0f,
                () -> (float) AetherConfig.PIP_WINDOW_WIDTH.get(),
                value -> {
                    AetherConfig.PIP_WINDOW_WIDTH.set(Math.round(value));
                    AetherConfig.save();
                })
                .withDecimals(0)
                .withSuffix(" px"));
        group.add(new SliderSetting("Window Height", 135.0f, 1080.0f,
                () -> (float) AetherConfig.PIP_WINDOW_HEIGHT.get(),
                value -> {
                    AetherConfig.PIP_WINDOW_HEIGHT.set(Math.round(value));
                    AetherConfig.save();
                })
                .withDecimals(0)
                .withSuffix(" px"));

        return MainGUIRegistry.toggleSubTab(
                "PiP",
                "Open a picture-in-picture view of the game",
                PipManager::isEnabled,
                PipManager::setEnabled,
                List.of(group)
        );
    }
}
