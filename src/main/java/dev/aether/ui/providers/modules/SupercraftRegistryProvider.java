package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.SupercraftManager;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class SupercraftRegistryProvider extends AbstractModulesRegistryProvider {
    public SupercraftRegistryProvider() {
        super(9);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Supercraft Settings",
                        "Configure Auto Supercraft behavior")
                .add(new SliderSetting("Run Interval", 1, 1440,
                        () -> (float) AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.get(),
                        v -> {
                            AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new ListSetting("Items", "Add item name",
                        () -> AetherConfig.AUTO_SUPERCRAFT_ITEMS.get(),
                        v -> {
                            AetherConfig.AUTO_SUPERCRAFT_ITEMS.set(v);
                            AetherConfig.save();
                        }))
                .add(new ActionSetting("Run Now",
                        SupercraftManager::manualTrigger));

        return MainGUIRegistry.toggleSubTab(
                "Auto Supercraft",
                "Automatically crafts configured items with Supercraft",
                () -> AetherConfig.AUTO_SUPERCRAFT.get(),
                v -> {
                    AetherConfig.AUTO_SUPERCRAFT.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
