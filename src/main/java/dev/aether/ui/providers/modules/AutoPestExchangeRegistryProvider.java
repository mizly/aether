package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class AutoPestExchangeRegistryProvider extends AbstractModulesRegistryProvider {
    public AutoPestExchangeRegistryProvider() {
        super(7);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Pest Exchange",
                        "Configure Auto Pest Exchange behavior")
                .add(new dev.aether.ui.settings.ToggleSetting("Pathfind to Phillip",
                        () -> AetherConfig.PEST_EXCHANGE_PATHFIND.get(),
                        v -> {
                            AetherConfig.PEST_EXCHANGE_PATHFIND.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_PEST_EXCHANGE.get()))
                .add(new PositionSetting("Exchange Desk",
                        () -> (double) AetherConfig.PEST_EXCHANGE_DESK_X.get(),
                        v -> {
                            AetherConfig.PEST_EXCHANGE_DESK_X.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.PEST_EXCHANGE_DESK_Y.get(),
                        v -> {
                            AetherConfig.PEST_EXCHANGE_DESK_Y.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.PEST_EXCHANGE_DESK_Z.get(),
                        v -> {
                            AetherConfig.PEST_EXCHANGE_DESK_Z.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> AetherConfig.PEST_HIGHLIGHT_DESK.get(),
                        v -> {
                            AetherConfig.PEST_HIGHLIGHT_DESK.set(v);
                            AetherConfig.save();
                        },
                        () -> {
                            var player = Minecraft.getInstance().player;
                            if (player != null) {
                                AetherConfig.PEST_EXCHANGE_DESK_X.set(player.getBlockX());
                                AetherConfig.PEST_EXCHANGE_DESK_Y.set(player.getBlockY());
                                AetherConfig.PEST_EXCHANGE_DESK_Z.set(player.getBlockZ());
                                AetherConfig.save();
                        NotificationManager.success(AetherLang.localize("Pest Exchange Desk Set"),
                                String.format("X: %d, Y: %d, Z: %d",
                                        AetherConfig.PEST_EXCHANGE_DESK_X.get(),
                                        AetherConfig.PEST_EXCHANGE_DESK_Y.get(),
                                                AetherConfig.PEST_EXCHANGE_DESK_Z.get()));
                            }
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_PEST_EXCHANGE.get()
                                && AetherConfig.PEST_EXCHANGE_PATHFIND.get()))
                .add(FarmingSettingsFactory.pestExchangeFovRangeSetting()
                        .visibleWhen(() -> AetherConfig.AUTO_PEST_EXCHANGE.get()
                                && AetherConfig.PEST_EXCHANGE_PATHFIND.get()));

        group.add(new dev.aether.ui.settings.ToggleSetting("Use Abiphone",
                () -> AetherConfig.AUTO_PEST_USE_ABIPHONE.get(),
                v -> {
                    AetherConfig.AUTO_PEST_USE_ABIPHONE.set(v);
                    AetherConfig.save();
                })
                .visibleWhen(() -> AetherConfig.AUTO_PEST_EXCHANGE.get()));

        return MainGUIRegistry.toggleSubTab(
                "Auto Pest Exchange",
                "Automatically visits the pest exchange desk when ready",
                () -> AetherConfig.AUTO_PEST_EXCHANGE.get(),
                v -> {
                    AetherConfig.AUTO_PEST_EXCHANGE.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
