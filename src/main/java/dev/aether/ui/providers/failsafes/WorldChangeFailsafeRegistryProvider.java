package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class WorldChangeFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public WorldChangeFailsafeRegistryProvider() {
        super(7);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "World Change Failsafe",
                        "Handles unexpected world changes while farming")
                .add(FailsafeActionSettings.createActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.WORLD_CHANGE,
                        () -> AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.get()))
                .add(new SliderSetting("Recovery Wait", 0, 30,
                        () -> AetherConfig.FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "World Change",
                "Handles unexpected world changes while farming",
                () -> AetherConfig.FAILSAFE_WORLD_CHANGE.get(),
                v -> {
                    AetherConfig.FAILSAFE_WORLD_CHANGE.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
