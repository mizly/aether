package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.List;

public final class QualityOfLifeRegistryProvider extends AbstractModulesRegistryProvider {
    public QualityOfLifeRegistryProvider() {
        super(9);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.of(
                        "Book Combine",
                        "Automatically combines books when the threshold is met",
                        () -> AetherConfig.AUTO_BOOK_COMBINE.get(),
                        v -> {
                            AetherConfig.AUTO_BOOK_COMBINE.set(v);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Always Active",
                        () -> AetherConfig.ALWAYS_ACTIVE_COMBINE.get(),
                        v -> {
                            AetherConfig.ALWAYS_ACTIVE_COMBINE.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_BOOK_COMBINE.get()))
                .add(new SliderSetting("Book Threshold", 1, 20,
                        () -> (float) AetherConfig.BOOK_THRESHOLD.get(),
                        v -> {
                            AetherConfig.BOOK_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_BOOK_COMBINE.get()))
                .add(new SliderSetting("Book Combine Delay", 50, 5000,
                        () -> (float) AetherConfig.BOOK_COMBINE_DELAY.get(),
                        v -> {
                            AetherConfig.BOOK_COMBINE_DELAY.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")
                        .visibleWhen(() -> AetherConfig.AUTO_BOOK_COMBINE.get()))
                .add(new ListSetting("Custom Enchantment Levels", "Add Name:Level entry",
                        () -> AetherConfig.CUSTOM_ENCHANTMENT_LEVELS.get(),
                        v -> {
                            AetherConfig.CUSTOM_ENCHANTMENT_LEVELS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_BOOK_COMBINE.get())));

        groups.add(SettingGroup.of(
                        "Auto George Sell",
                        "Automatically sells pets through George",
                        () -> AetherConfig.AUTO_GEORGE_SELL.get(),
                        v -> {
                            AetherConfig.AUTO_GEORGE_SELL.set(v);
                            AetherConfig.save();
                        })
                .add(new SliderSetting("George Sell Threshold", 1, 10,
                        () -> (float) AetherConfig.GEORGE_SELL_THRESHOLD.get(),
                        v -> {
                            AetherConfig.GEORGE_SELL_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_GEORGE_SELL.get()))
                .add(FarmingSettingsFactory.georgePostSellDelaySetting()
                        .visibleWhen(() -> AetherConfig.AUTO_GEORGE_SELL.get()))
                .add(FarmingSettingsFactory.farmWhileCallingGeorgeSetting()
                        .visibleWhen(() -> AetherConfig.AUTO_GEORGE_SELL.get())));

        groups.add(SettingGroup.of(
                        "Auto Sell",
                        "Automatically sells configured items",
                        QualityOfLifeRegistryProvider::isAutoSellCategoryEnabled,
                        QualityOfLifeRegistryProvider::setAutoSellCategoryEnabled)
                .add(new SliderSetting("Inventory Threshold", 1, 100,
                        () -> (float) AetherConfig.AUTO_SELL_THRESHOLD.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("%")
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get()))
                .add(new SliderSetting("Inventory Full Time", 1, 30,
                        () -> (float) AetherConfig.AUTO_SELL_TIME.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_TIME.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("s")
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get()))
                .add(new ToggleSetting("NPC Autosell",
                        () -> AetherConfig.AUTO_SELL_NPC.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_NPC.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get()))
                .add(new ToggleSetting("Bazaar Autosell",
                        () -> AetherConfig.AUTO_SELL_BAZAAR.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_BAZAAR.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get()))
                .add(new ToggleSetting("Sell Before Visitors",
                        () -> AetherConfig.AUTO_SELL_BEFORE_VISITORS.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_BEFORE_VISITORS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get()))
                .add(new ToggleSetting("Sell Before Pest Traps",
                        () -> AetherConfig.AUTO_SELL_BEFORE_PEST_TRAPS.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_BEFORE_PEST_TRAPS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get()))
                .add(new ToggleSetting("Auto Sell (Passive)",
                        () -> AetherConfig.AUTOSELL_PASSIVE.get(),
                        v -> {
                            AetherConfig.AUTOSELL_PASSIVE.set(v);
                            AetherConfig.save();
                        }))
                .add(new ListSetting("Auto Sell Items", "Add item name",
                        () -> AetherConfig.AUTO_SELL_ITEMS.get(),
                        v -> {
                            AetherConfig.AUTO_SELL_ITEMS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_SELL.get() || AetherConfig.AUTOSELL_PASSIVE.get())));

        groups.add(SettingGroup.of(
                        "Stash Manager",
                        "Automatically picks items up from stash",
                        () -> AetherConfig.AUTO_STASH_MANAGER.get(),
                        v -> {
                            AetherConfig.AUTO_STASH_MANAGER.set(v);
                            AetherConfig.save();
                        })
                .add(FarmingSettingsFactory.pickUpStashDelaySetting()
                        .visibleWhen(() -> AetherConfig.AUTO_STASH_MANAGER.get())));

        groups.add(SettingGroup.of(
                        "Junk Manager",
                        "Drops configured junk items once the threshold is reached",
                        () -> AetherConfig.AUTO_DROP_JUNK.get(),
                        v -> {
                            AetherConfig.AUTO_DROP_JUNK.set(v);
                            AetherConfig.save();
                        })
                .add(new SliderSetting("Junk Threshold", 1, 10,
                        () -> (float) AetherConfig.JUNK_THRESHOLD.get(),
                        v -> {
                            AetherConfig.JUNK_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" items")
                        .visibleWhen(() -> AetherConfig.AUTO_DROP_JUNK.get()))
                .add(FarmingSettingsFactory.junkDropDelaySetting()
                        .visibleWhen(() -> AetherConfig.AUTO_DROP_JUNK.get()))
                .add(new TextSetting("Drop at Plot TP", "Plot number (e.g. 5)",
                        () -> AetherConfig.DROP_JUNK_PLOT_TP.get(),
                        v -> {
                            AetherConfig.DROP_JUNK_PLOT_TP.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_DROP_JUNK.get()))
                .add(new ListSetting("Junk Items", "Add item name",
                        () -> AetherConfig.JUNK_ITEMS.get(),
                        v -> {
                            AetherConfig.JUNK_ITEMS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_DROP_JUNK.get())));

        groups.add(SettingGroup.alwaysOn(
                        "Chat Filtering",
                        "Controls chat cleanup for farming-related messages")
                .add(new ToggleSetting("Hide Pest Drops",
                        () -> AetherConfig.HIDE_FILTERED_CHAT.get(),
                        v -> {
                            AetherConfig.HIDE_FILTERED_CHAT.set(v);
                            AetherConfig.save();
                        })));

        return MainGUIRegistry.subTab(
                "Farming QOL",
                "Various quality-of-life features for farming",
                groups);
    }

    private static boolean isAutoSellCategoryEnabled() {
        return AetherConfig.AUTO_SELL.get() || AetherConfig.AUTOSELL_PASSIVE.get();
    }

    private static void setAutoSellCategoryEnabled(boolean enabled) {
        AetherConfig.AUTO_SELL.set(enabled);
        if (!enabled) {
            AetherConfig.AUTOSELL_PASSIVE.set(false);
        }
        AetherConfig.save();
    }
}
