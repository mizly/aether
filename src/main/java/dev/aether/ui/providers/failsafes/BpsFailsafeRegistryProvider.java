package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class BpsFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public BpsFailsafeRegistryProvider() {
        super(2);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "BPS Failsafe",
                        "Triggers when block breaks per second fall below the configured threshold")
                .add(FailsafeActionSettings.createActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_BPS_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_BPS_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.BPS,
                        () -> AetherConfig.FAILSAFE_BPS_ACTION.get()))
                .add(new SliderSetting("Threshold", 5, 15,
                        () -> (float) AetherConfig.FAILSAFE_BPS_THRESHOLD.get(),
                        v -> {
                            AetherConfig.FAILSAFE_BPS_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Window", 5, 30,
                        () -> (float) AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("s"))
                .add(new SliderSetting("Trigger Delay", 0, 5,
                        () -> AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "BPS",
                "Triggers when block breaks per second fall below the configured threshold",
                () -> AetherConfig.FAILSAFE_BPS.get(),
                v -> {
                    AetherConfig.FAILSAFE_BPS.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
