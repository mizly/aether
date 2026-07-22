package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class AutoSprayonatorRegistryProvider extends AbstractModulesRegistryProvider {
    public AutoSprayonatorRegistryProvider() {
        super(5);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<String> sprayMaterials = FarmingSettingsFactory.sprayMaterials();
        SettingGroup group = SettingGroup.alwaysOn(
                        "Sprayonator Settings",
                        "Configure Auto Sprayonator behavior")
                .add(new DropdownSetting("Spray Material", sprayMaterials,
                        () -> {
                            String current = AetherConfig.AUTO_SPRAYONATOR_MATERIAL.get();
                            int idx = sprayMaterials.indexOf(current);
                            return idx >= 0 ? idx : 0;
                        },
                        i -> {
                            if (i >= 0 && i < sprayMaterials.size()) {
                                AetherConfig.AUTO_SPRAYONATOR_MATERIAL.set(sprayMaterials.get(i));
                                AetherConfig.save();
                            }
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SPRAYONATOR.get()))
                .add(new ToggleSetting("Auto Buy Material",
                        () -> AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.get(),
                        v -> {
                            AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SPRAYONATOR.get()))
                .add(new SliderSetting("Auto Buy Amount", 1, 64,
                        () -> (float) AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT.get(),
                        v -> {
                            AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_SPRAYONATOR.get()
                                && AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.get()))
                .add(new SliderSetting("Unsprayed Plot Detect Time", 5, 30,
                        () -> (float) AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.get(),
                        v -> {
                            AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("s")
                        .visibleWhen(() -> AetherConfig.AUTO_SPRAYONATOR.get()));

        return MainGUIRegistry.toggleSubTab(
                "Auto Sprayonator",
                "Automatically detects and sprays unsprayed plots",
                () -> AetherConfig.AUTO_SPRAYONATOR.get(),
                v -> {
                    AetherConfig.AUTO_SPRAYONATOR.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
