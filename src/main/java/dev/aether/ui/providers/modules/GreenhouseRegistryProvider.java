package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import java.util.List;

public final class GreenhouseRegistryProvider extends AbstractModulesRegistryProvider {
    public GreenhouseRegistryProvider() {
        super(7);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Greenhouse Settings",
                        "Configure Auto Greenhouse behavior")
                .add(new SliderSetting("Run Interval", 1, 1440,
                        () -> (float) AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.get(),
                        v -> {
                            AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new ListSetting("Plots", "Add plot number",
                        () -> AetherConfig.GREENHOUSE_PLOTS.get(),
                        v -> {
                            AetherConfig.GREENHOUSE_PLOTS.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Equip Custom Item",
                        () -> AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.get(),
                        v -> {
                            AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.set(v);
                            AetherConfig.save();
                        }))
                .add(new TextSetting("Custom Item", "e.g. Nether Wart Hoe",
                        () -> AetherConfig.GREENHOUSE_CUSTOM_ITEM.get(),
                        v -> {
                            AetherConfig.GREENHOUSE_CUSTOM_ITEM.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.get()))
                .add(new ToggleSetting("Harvest Ashwreath",
                        () -> AetherConfig.HARVEST_ASHWREATH.get(),
                        v -> {
                            AetherConfig.HARVEST_ASHWREATH.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Harvest Turtellini",
                        () -> AetherConfig.HARVEST_TURTELLINI.get(),
                        v -> {
                            AetherConfig.HARVEST_TURTELLINI.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Harvest Glasscorn",
                        () -> AetherConfig.HARVEST_GLASSCORN.get(),
                        v -> {
                            AetherConfig.HARVEST_GLASSCORN.set(v);
                            AetherConfig.save();
                        }))
                .add(new ActionSetting("Harvest Now",
                        dev.aether.modules.GreenhouseManager::harvest));

        return MainGUIRegistry.toggleSubTab(
                "Auto Greenhouse",
                "Automatically harvests your greenhouse",
                () -> AetherConfig.AUTO_GREENHOUSE.get(),
                v -> {
                    AetherConfig.AUTO_GREENHOUSE.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
