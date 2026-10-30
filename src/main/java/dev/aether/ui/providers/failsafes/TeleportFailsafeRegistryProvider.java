package dev.aether.ui.providers.failsafes;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class TeleportFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public TeleportFailsafeRegistryProvider() {
        super(8);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn("Teleport Failsafe",
                        "Detects unexpected server position changes during macro operation; ignores requested teleports")
                .add(new ToggleSetting("Only During Farming",
                        () -> AetherConfig.FAILSAFE_TELEPORT_ONLY_DURING_FARMING.get(), value -> {
                            AetherConfig.FAILSAFE_TELEPORT_ONLY_DURING_FARMING.set(value);
                            AetherConfig.save();
                        }))
                .add(FailsafeActionSettings.createActionDropdown("Teleport Action",
                        () -> AetherConfig.FAILSAFE_TELEPORT_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_TELEPORT_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.TELEPORT,
                        () -> AetherConfig.FAILSAFE_TELEPORT_ACTION.get()))
                .add(new SliderSetting("Horizontal Ignore Limit", 0, 64,
                        () -> AetherConfig.FAILSAFE_TELEPORT_HORIZONTAL_LIMIT.get(), value -> {
                            AetherConfig.FAILSAFE_TELEPORT_HORIZONTAL_LIMIT.set(Math.round(value * 2) / 2.0f);
                            AetherConfig.save();
                        }).withDecimals(1))
                .add(new SliderSetting("Vertical Ignore Limit", 0, 10,
                        () -> AetherConfig.FAILSAFE_TELEPORT_VERTICAL_LIMIT.get(), value -> {
                            AetherConfig.FAILSAFE_TELEPORT_VERTICAL_LIMIT.set(Math.round(value * 2) / 2.0f);
                            AetherConfig.save();
                        }).withDecimals(1))
                .add(new SliderSetting("Teleport Settle Time", 0, 30,
                        () -> AetherConfig.FAILSAFE_TELEPORT_SETTLE_SECONDS.get(), value -> {
                            AetherConfig.FAILSAFE_TELEPORT_SETTLE_SECONDS.set(Math.round(value * 2) / 2.0f);
                            AetherConfig.save();
                        }).withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab("Teleport",
                "Detects unexpected server position changes during macro operation; ignores requested teleports",
                () -> AetherConfig.FAILSAFE_TELEPORT.get(), value -> {
                    AetherConfig.FAILSAFE_TELEPORT.set(value);
                    AetherConfig.save();
                }, List.of(group));
    }
}
