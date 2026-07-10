package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.ArrayList;
import java.util.List;

public final class GearRegistryProvider extends AbstractModulesRegistryProvider {
    public GearRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.alwaysOn(
                        "Auto Loadout",
                        "Handles loadout swaps for pests and visitors")
                .add(new SliderSetting("Farming Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_FARMING.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_FARMING.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Pest Spawn Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_PEST.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_PEST.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Pest Kill Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_PEST_KILL.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_PEST_KILL.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Visitor Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_VISITOR.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_VISITOR.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Pest Loadout Swap Time (~170s for eq swap, ~5s for no eq swap)", 0, 180,
                        () -> (float) AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get(),
                        v -> {
                            AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .withSuffix("s")));

        return MainGUIRegistry.subTab(
                "Auto Loadout",
                "Automatically swaps loadouts",
                groups);
    }
}
