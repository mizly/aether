package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.experimentation.ExperimentationManager;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;

import java.util.List;

public final class ExperimentationRegistryProvider extends AbstractModulesRegistryProvider {
    public ExperimentationRegistryProvider() {
        super(9);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Experimentation Settings",
                        "Solve and auto-click the Experimentation Table games")
                .add(new ActionSetting("Run Session Now", ExperimentationManager::manualTrigger)
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new PositionSetting("Table Spot",
                        () -> (double) AetherConfig.EXPERIMENTS_TABLE_X.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_TABLE_X.set((int) Math.round(v));
                            AetherConfig.EXPERIMENTS_TABLE_SET.set(true);
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.EXPERIMENTS_TABLE_Y.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_TABLE_Y.set((int) Math.round(v));
                            AetherConfig.EXPERIMENTS_TABLE_SET.set(true);
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.EXPERIMENTS_TABLE_Z.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_TABLE_Z.set((int) Math.round(v));
                            AetherConfig.EXPERIMENTS_TABLE_SET.set(true);
                            AetherConfig.save();
                        },
                        () -> AetherConfig.EXPERIMENTS_TABLE_HIGHLIGHT.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_TABLE_HIGHLIGHT.set(v);
                            AetherConfig.save();
                        },
                        () -> {
                            ExperimentationManager.saveTablePosition();
                            NotificationManager.success(AetherLang.localize("Table Spot Set"),
                                    String.format("X: %d, Y: %d, Z: %d",
                                            AetherConfig.EXPERIMENTS_TABLE_X.get(),
                                            AetherConfig.EXPERIMENTS_TABLE_Y.get(),
                                            AetherConfig.EXPERIMENTS_TABLE_Z.get()));
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new ToggleSetting("Practice Mode (free, no charges)",
                        () -> AetherConfig.EXPERIMENTS_PRACTICE_MODE.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_PRACTICE_MODE.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new ToggleSetting("Stop At Max Reward (from lore)",
                        () -> AetherConfig.EXPERIMENTS_STOP_AT_MAX_REWARD.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_STOP_AT_MAX_REWARD.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new ToggleSetting("Play To Max Clicks",
                        () -> AetherConfig.EXPERIMENTS_MAX_CLICKS.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_MAX_CLICKS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()
                                && !AetherConfig.EXPERIMENTS_STOP_AT_MAX_REWARD.get()))
                .add(new SliderSetting("Metaphysical Serums Used", 0, 3,
                        () -> (float) AetherConfig.EXPERIMENTS_SERUM_COUNT.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_SERUM_COUNT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()
                                && !AetherConfig.EXPERIMENTS_MAX_CLICKS.get()))
                .add(new ToggleSetting("Auto-Buy XP Bottle (Bazaar)",
                        () -> AetherConfig.EXPERIMENTS_AUTO_BUY_XP.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_AUTO_BUY_XP.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new SliderSetting("Bits+XP Renewals Per Day", 0, 3,
                        () -> (float) AetherConfig.EXPERIMENTS_RENEWALS_PER_DAY.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_RENEWALS_PER_DAY.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new SliderSetting("Note Click Delay Min", 50, 1000,
                        () -> (float) AetherConfig.EXPERIMENTS_NOTE_DELAY_MIN.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_NOTE_DELAY_MIN.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new SliderSetting("Note Click Delay Max", 50, 2000,
                        () -> (float) AetherConfig.EXPERIMENTS_NOTE_DELAY_MAX.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_NOTE_DELAY_MAX.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new SliderSetting("Click Delay Min", 50, 1000,
                        () -> (float) AetherConfig.EXPERIMENTS_CLICK_DELAY_MIN.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_CLICK_DELAY_MIN.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()))
                .add(new SliderSetting("Click Delay Max", 50, 2000,
                        () -> (float) AetherConfig.EXPERIMENTS_CLICK_DELAY_MAX.get(),
                        v -> {
                            AetherConfig.EXPERIMENTS_CLICK_DELAY_MAX.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")
                        .visibleWhen(() -> AetherConfig.AUTO_EXPERIMENTS.get()));

        return MainGUIRegistry.toggleSubTab(
                "Auto Experiments",
                "Plays the Experimentation Table hands-off: Superpairs, addons, renewals",
                () -> AetherConfig.AUTO_EXPERIMENTS.get(),
                v -> {
                    AetherConfig.AUTO_EXPERIMENTS.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
