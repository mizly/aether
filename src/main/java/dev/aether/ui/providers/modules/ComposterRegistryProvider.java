package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.ComposterManager;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.util.AetherLang;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ComposterRegistryProvider extends AbstractModulesRegistryProvider {
    public ComposterRegistryProvider() {
        super(8);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Composter Settings",
                        "Configure Auto Composter behavior")
                .add(new PositionSetting("Composter Position",
                        () -> (double) AetherConfig.AUTO_COMPOSTER_X.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_X.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.AUTO_COMPOSTER_Y.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_Y.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.AUTO_COMPOSTER_Z.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_Z.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.set(v);
                            AetherConfig.save();
                        },
                        () -> {
                            var player = Minecraft.getInstance().player;
                            if (player != null) {
                                AetherConfig.AUTO_COMPOSTER_X.set(player.getBlockX());
                                AetherConfig.AUTO_COMPOSTER_Y.set(player.getBlockY());
                                AetherConfig.AUTO_COMPOSTER_Z.set(player.getBlockZ());
                                AetherConfig.save();
                                NotificationManager.success(AetherLang.localize("Composter Position Set"),
                                        String.format("X: %d, Y: %d, Z: %d",
                                                AetherConfig.AUTO_COMPOSTER_X.get(),
                                                AetherConfig.AUTO_COMPOSTER_Y.get(),
                                                AetherConfig.AUTO_COMPOSTER_Z.get()));
                            }
                        }))
                .add(new SliderSetting("Run Interval", 1, 1440,
                        () -> (float) AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new DropdownSetting("Source Mode", List.of("Sacks", "Bazaar"),
                        ComposterManager::getSourceModeIndex,
                        ComposterSettingsBridge::setSourceModeIndex))
                .add(new SliderSetting("Minimum Purse", 0, 2000000000,
                        () -> (float) AetherConfig.AUTO_COMPOSTER_MIN_PURSE.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_MIN_PURSE.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" coins")
                        .visibleWhen(ComposterManager::isBazaarMode))
                .add(new TextSetting("Crop Material", "e.g. Box of Seeds",
                        () -> AetherConfig.AUTO_COMPOSTER_CROP_MATERIAL.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_CROP_MATERIAL.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(ComposterManager::isBazaarMode))
                .add(new SliderSetting("Crop Amount", 1, 2000000,
                        () -> (float) AetherConfig.AUTO_COMPOSTER_CROP_AMOUNT.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_CROP_AMOUNT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(ComposterManager::isBazaarMode))
                .add(new TextSetting("Fuel Material", "e.g. Volta",
                        () -> AetherConfig.AUTO_COMPOSTER_FUEL_MATERIAL.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_FUEL_MATERIAL.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(ComposterManager::isBazaarMode))
                .add(new SliderSetting("Fuel Amount", 1, 2000000,
                        () -> (float) AetherConfig.AUTO_COMPOSTER_FUEL_AMOUNT.get(),
                        v -> {
                            AetherConfig.AUTO_COMPOSTER_FUEL_AMOUNT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(ComposterManager::isBazaarMode))
                .add(new ActionSetting("Run Now",
                        ComposterManager::manualTrigger));

        return MainGUIRegistry.toggleSubTab(
                "Auto Composter",
                "Automatically refills the Garden composter",
                () -> AetherConfig.AUTO_COMPOSTER.get(),
                v -> {
                    AetherConfig.AUTO_COMPOSTER.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
