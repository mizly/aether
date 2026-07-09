package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class GhostBlockFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public GhostBlockFailsafeRegistryProvider() {
        super(4);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Ghost Block Failsafe",
                        "Triggers when the farming exp text disappears while the macro is actively farming")
                .add(FailsafeActionSettings.createActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.GHOST_BLOCK,
                        () -> AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.get()))
                .add(new SliderSetting("Window", 1, 30,
                        () -> (float) AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("s"))
                .add(new SliderSetting("Trigger Delay", 0, 5,
                        () -> AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "Ghost Block",
                "Triggers when the farming exp text disappears while the macro is actively farming",
                () -> AetherConfig.FAILSAFE_GHOST_BLOCK.get(),
                v -> {
                    AetherConfig.FAILSAFE_GHOST_BLOCK.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
