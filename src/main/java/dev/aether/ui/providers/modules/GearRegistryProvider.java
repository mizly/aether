package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.List;

public final class GearRegistryProvider extends AbstractModulesRegistryProvider {
    public GearRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.of(
                        "Auto Loadout",
                        "Handles loadout swaps for pests and visitors",
                        GearRegistryProvider::isAutoLoadoutEnabled,
                        GearRegistryProvider::setAutoLoadoutEnabled)
                .add(new ToggleSetting("Auto Loadout Pest",
                        () -> AetherConfig.AUTO_LOADOUT_PEST.get(),
                        v -> {
                            AetherConfig.AUTO_LOADOUT_PEST.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Auto Loadout Visitor",
                        () -> AetherConfig.AUTO_LOADOUT_VISITOR.get(),
                        v -> {
                            AetherConfig.AUTO_LOADOUT_VISITOR.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Farming Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_FARMING.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_FARMING.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_LOADOUT_PEST.get()
                                || AetherConfig.AUTO_LOADOUT_VISITOR.get()))
                .add(new SliderSetting("Pest Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_PEST.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_PEST.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_LOADOUT_PEST.get()))
                .add(new SliderSetting("Pest Loadout Swap Time", 0, 180,
                        () -> (float) AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get(),
                        v -> {
                            AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .withSuffix("s")
                        .visibleWhen(() -> AetherConfig.AUTO_LOADOUT_PEST.get()))
                .add(new SliderSetting("Visitor Loadout Slot", 1, 12,
                        () -> (float) AetherConfig.LOADOUT_SLOT_VISITOR.get(),
                        v -> {
                            AetherConfig.LOADOUT_SLOT_VISITOR.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_LOADOUT_VISITOR.get())));

        return MainGUIRegistry.toggleSubTab(
                "Auto Loadout",
                "Automatically swaps loadouts",
                GearRegistryProvider::isAutoLoadoutEnabled,
                GearRegistryProvider::setAutoLoadoutEnabled,
                groups);
    }

    private static boolean isAutoLoadoutEnabled() {
        return AetherConfig.AUTO_LOADOUT_PEST.get() || AetherConfig.AUTO_LOADOUT_VISITOR.get();
    }

    private static void setAutoLoadoutEnabled(boolean enabled) {
        AetherConfig.AUTO_LOADOUT_PEST.set(enabled);
        AetherConfig.AUTO_LOADOUT_VISITOR.set(enabled);
        AetherConfig.save();
    }
}
