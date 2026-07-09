package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class DirtFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public DirtFailsafeRegistryProvider() {
        super(5);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Dirt Check Failsafe",
                        "Triggers when a suspicious solid block stays close to the player during farming")
                .add(FailsafeActionSettings.createActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.DIRT_CHECK,
                        () -> AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.get()))
                .add(new SliderSetting("Trigger Delay", 0, 10,
                        () -> AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "Dirt Check",
                "Triggers when a suspicious solid block stays close to the player during farming",
                () -> AetherConfig.FAILSAFE_DIRT_CHECK.get(),
                v -> {
                    AetherConfig.FAILSAFE_DIRT_CHECK.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
