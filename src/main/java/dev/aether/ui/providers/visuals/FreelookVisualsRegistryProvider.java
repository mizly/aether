package dev.aether.ui;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.ConfigHelpers;
import dev.aether.config.FreelookMode;
import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.Arrays;
import java.util.List;

public final class FreelookVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public FreelookVisualsRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                "Freelook Settings",
                "Orbit the camera around your player while your body keeps facing forward"
        );
        group.add(new KeybindSetting("Freelook Keybind", AetherKeybindRegistry.getFreelookKey()));
        group.add(new DropdownSetting("Activation Mode",
                List.of("Hold", "Toggle"),
                () -> Arrays.asList(FreelookMode.values()).indexOf(ConfigHelpers.getFreelookMode()),
                i -> {
                    AetherConfig.FREELOOK_MODE.set(FreelookMode.values()[i].name());
                    AetherConfig.save();
                }));

        return MainGUIRegistry.toggleSubTab(
                "Freelook",
                "Orbit the camera freely without turning your player",
                () -> AetherConfig.FREELOOK_ENABLED.get(),
                enabled -> {
                    AetherConfig.FREELOOK_ENABLED.set(enabled);
                    AetherConfig.save();
                },
                List.of(group)
        );
    }
}
