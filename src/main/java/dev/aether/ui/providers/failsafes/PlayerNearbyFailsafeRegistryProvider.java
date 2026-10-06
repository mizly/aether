package dev.aether.ui.providers.failsafes;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class PlayerNearbyFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public PlayerNearbyFailsafeRegistryProvider() {
        super(Category.FISHING, 0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Player Nearby Failsafe",
                        "Triggers when another player stays close to you while fishing")
                .add(FailsafeActionSettings.createFishingActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_PLAYER_NEARBY_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_PLAYER_NEARBY_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.PLAYER_NEARBY,
                        () -> AetherConfig.FAILSAFE_PLAYER_NEARBY_ACTION.get()))
                .add(new SliderSetting("Distance", 1, 10,
                        () -> AetherConfig.FAILSAFE_PLAYER_NEARBY_RADIUS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_PLAYER_NEARBY_RADIUS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix(" blocks"))
                .add(new SliderSetting("Time Nearby", 0, 120,
                        () -> AetherConfig.FAILSAFE_PLAYER_NEARBY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_PLAYER_NEARBY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "Player Nearby",
                "Triggers when another player stays close to you while fishing",
                () -> AetherConfig.FAILSAFE_PLAYER_NEARBY.get(),
                v -> {
                    AetherConfig.FAILSAFE_PLAYER_NEARBY.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
