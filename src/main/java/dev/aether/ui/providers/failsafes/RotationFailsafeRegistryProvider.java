package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class RotationFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public RotationFailsafeRegistryProvider() {
        super(6);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup rotationGroup = SettingGroup.alwaysOn(
                        "Rotation Failsafe",
                        "Triggers when player rotation deviates beyond the configured thresholds")
                .add(FailsafeActionSettings.createActionDropdown("Rotation Action",
                        () -> AetherConfig.FAILSAFE_ROTATION_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_ROTATION_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.ROTATION,
                        () -> AetherConfig.FAILSAFE_ROTATION_ACTION.get()))
                .add(new SliderSetting("Pitch Threshold", 5, 30,
                        () -> (float) AetherConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.get(),
                        v -> {
                            AetherConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("\u00B0"))
                .add(new SliderSetting("Yaw Threshold", 5, 30,
                        () -> (float) AetherConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.get(),
                        v -> {
                            AetherConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("\u00B0"))
                .add(new SliderSetting("Trigger Delay", 0, 5,
                        () -> AetherConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"))
                .add(new SliderSetting("Warp Grace Period", 1000, 5000,
                        () -> (float) AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms"));

        SettingGroup pestRotationGroup = SettingGroup.alwaysOn(
                        "Pest Cleaner Rotation",
                        "Controls how rotation failsafes behave while the pest cleaner is rotating")
                .add(new ToggleSetting("Trigger During Pest Cleaner",
                        () -> AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get(),
                        v -> {
                            AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.set(v);
                            AetherConfig.save();
                        }))
                .add(FailsafeActionSettings.createActionDropdown("Pest Rotation Action",
                        () -> AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.PEST_ROTATION,
                        () -> AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.get()))
                .add(new SliderSetting("Pest Cleaner Rotation Failsafe Delay", 0, 5000,
                        () -> (float) AetherConfig.FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")
                        .visibleWhen(() -> AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get()));

        return MainGUIRegistry.toggleSubTab(
                "Rotation",
                "Triggers when player rotation deviates beyond the configured thresholds",
                () -> AetherConfig.FAILSAFE_ROTATION.get(),
                v -> {
                    AetherConfig.FAILSAFE_ROTATION.set(v);
                    AetherConfig.save();
                },
                List.of(rotationGroup, pestRotationGroup));
    }
}
