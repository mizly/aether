package dev.aether.ui.providers.failsafes;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class TpCheckFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public TpCheckFailsafeRegistryProvider() {
        super(Category.FISHING, 1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "TP Check Failsafe",
                        "Triggers when something other than the macro teleports you while fishing")
                .add(FailsafeActionSettings.createFishingActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_TP_CHECK_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_TP_CHECK_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.TP_CHECK,
                        () -> AetherConfig.FAILSAFE_TP_CHECK_ACTION.get()))
                .add(new SliderSetting("Teleport Distance", 2, 30,
                        () -> AetherConfig.FAILSAFE_TP_CHECK_DISTANCE.get(),
                        v -> {
                            AetherConfig.FAILSAFE_TP_CHECK_DISTANCE.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix(" blocks"));

        return MainGUIRegistry.toggleSubTab(
                "TP Check",
                "Triggers when something other than the macro teleports you while fishing",
                () -> AetherConfig.FAILSAFE_TP_CHECK.get(),
                v -> {
                    AetherConfig.FAILSAFE_TP_CHECK.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
