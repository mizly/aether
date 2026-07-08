package dev.typicalfarmingmacro.ui;

import dev.typicalfarmingmacro.config.TfmConfig;
import dev.typicalfarmingmacro.ui.settings.ModulesTab;
import dev.typicalfarmingmacro.ui.settings.SettingGroup;
import dev.typicalfarmingmacro.ui.settings.SliderSetting;
import dev.typicalfarmingmacro.ui.settings.ToggleSetting;

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
                        () -> TfmConfig.AUTO_LOADOUT_PEST.get(),
                        v -> {
                            TfmConfig.AUTO_LOADOUT_PEST.set(v);
                            TfmConfig.save();
                        }))
                .add(new ToggleSetting("Auto Loadout Visitor",
                        () -> TfmConfig.AUTO_LOADOUT_VISITOR.get(),
                        v -> {
                            TfmConfig.AUTO_LOADOUT_VISITOR.set(v);
                            TfmConfig.save();
                        }))
                .add(new SliderSetting("Farming Loadout Slot", 1, 12,
                        () -> (float) TfmConfig.LOADOUT_SLOT_FARMING.get(),
                        v -> {
                            TfmConfig.LOADOUT_SLOT_FARMING.set(Math.round(v));
                            TfmConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> TfmConfig.AUTO_LOADOUT_PEST.get()
                                || TfmConfig.AUTO_LOADOUT_VISITOR.get()))
                .add(new SliderSetting("Pest Loadout Slot", 1, 12,
                        () -> (float) TfmConfig.LOADOUT_SLOT_PEST.get(),
                        v -> {
                            TfmConfig.LOADOUT_SLOT_PEST.set(Math.round(v));
                            TfmConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> TfmConfig.AUTO_LOADOUT_PEST.get()))
                .add(new SliderSetting("Visitor Loadout Slot", 1, 12,
                        () -> (float) TfmConfig.LOADOUT_SLOT_VISITOR.get(),
                        v -> {
                            TfmConfig.LOADOUT_SLOT_VISITOR.set(Math.round(v));
                            TfmConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> TfmConfig.AUTO_LOADOUT_VISITOR.get())));

        return MainGUIRegistry.toggleSubTab(
                "Auto Loadout",
                "Automatically swaps loadouts",
                GearRegistryProvider::isAutoLoadoutEnabled,
                GearRegistryProvider::setAutoLoadoutEnabled,
                groups);
    }

    private static boolean isAutoLoadoutEnabled() {
        return TfmConfig.AUTO_LOADOUT_PEST.get() || TfmConfig.AUTO_LOADOUT_VISITOR.get();
    }

    private static void setAutoLoadoutEnabled(boolean enabled) {
        TfmConfig.AUTO_LOADOUT_PEST.set(enabled);
        TfmConfig.AUTO_LOADOUT_VISITOR.set(enabled);
        TfmConfig.save();
    }
}
