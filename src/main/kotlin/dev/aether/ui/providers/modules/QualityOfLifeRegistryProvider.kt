package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import java.util.ArrayList
import java.util.function.Supplier

class QualityOfLifeRegistryProvider : AbstractModulesRegistryProvider(9) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        groups.add(SettingGroup.of("Book Combine", "Automatically combines books when the threshold is met",
            { AetherConfig.AUTO_BOOK_COMBINE.get() }, { v -> AetherConfig.AUTO_BOOK_COMBINE.set(v); AetherConfig.save() })
            .add(ToggleSetting("Always Active", { AetherConfig.ALWAYS_ACTIVE_COMBINE.get() },
                { v -> AetherConfig.ALWAYS_ACTIVE_COMBINE.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_BOOK_COMBINE.get() })
            .add(SliderSetting("Book Threshold", 1.0f, 20.0f, { AetherConfig.BOOK_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.BOOK_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).visibleWhen { AetherConfig.AUTO_BOOK_COMBINE.get() })
            .add(SliderSetting("Book Combine Delay", 50.0f, 5000.0f, { AetherConfig.BOOK_COMBINE_DELAY.get().toFloat() },
                { v -> AetherConfig.BOOK_COMBINE_DELAY.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("ms").visibleWhen { AetherConfig.AUTO_BOOK_COMBINE.get() })
            .add(ListSetting("Custom Enchantment Levels", "Add Name:Level entry", Supplier { java.util.ArrayList(AetherConfig.CUSTOM_ENCHANTMENT_LEVELS.get()) },
                { v -> AetherConfig.CUSTOM_ENCHANTMENT_LEVELS.set(java.util.ArrayList(v)); AetherConfig.save() })
                .visibleWhen { AetherConfig.AUTO_BOOK_COMBINE.get() }))
        groups.add(SettingGroup.of("Auto George Sell", "Automatically sells pets through George",
            { AetherConfig.AUTO_GEORGE_SELL.get() }, { v -> AetherConfig.AUTO_GEORGE_SELL.set(v); AetherConfig.save() })
            .add(SliderSetting("George Sell Threshold", 1.0f, 10.0f, { AetherConfig.GEORGE_SELL_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.GEORGE_SELL_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).visibleWhen { AetherConfig.AUTO_GEORGE_SELL.get() })
            .add(FarmingSettingsFactory.georgePostSellDelaySetting().visibleWhen { AetherConfig.AUTO_GEORGE_SELL.get() })
            .add(FarmingSettingsFactory.farmWhileCallingGeorgeSetting().visibleWhen { AetherConfig.AUTO_GEORGE_SELL.get() }))
        groups.add(SettingGroup.of("Auto Sell", "Automatically sells configured items",
            ::isAutoSellCategoryEnabled, ::setAutoSellCategoryEnabled)
            .add(SliderSetting("Inventory Threshold", 1.0f, 100.0f, { AetherConfig.AUTO_SELL_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.AUTO_SELL_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("%").visibleWhen { AetherConfig.AUTO_SELL.get() })
            .add(SliderSetting("Inventory Full Time", 1.0f, 30.0f, { AetherConfig.AUTO_SELL_TIME.get().toFloat() },
                { v -> AetherConfig.AUTO_SELL_TIME.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("s").visibleWhen { AetherConfig.AUTO_SELL.get() })
            .add(ToggleSetting("NPC Autosell", { AetherConfig.AUTO_SELL_NPC.get() },
                { v -> AetherConfig.AUTO_SELL_NPC.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_SELL.get() })
            .add(ToggleSetting("Bazaar Autosell", { AetherConfig.AUTO_SELL_BAZAAR.get() },
                { v -> AetherConfig.AUTO_SELL_BAZAAR.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_SELL.get() })
            .add(ToggleSetting("Sell Before Visitors", { AetherConfig.AUTO_SELL_BEFORE_VISITORS.get() },
                { v -> AetherConfig.AUTO_SELL_BEFORE_VISITORS.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_SELL.get() })
            .add(ToggleSetting("Sell Before Pest Traps", { AetherConfig.AUTO_SELL_BEFORE_PEST_TRAPS.get() },
                { v -> AetherConfig.AUTO_SELL_BEFORE_PEST_TRAPS.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_SELL.get() })
            .add(ToggleSetting("Auto Sell (Passive)", { AetherConfig.AUTOSELL_PASSIVE.get() },
                { v -> AetherConfig.AUTOSELL_PASSIVE.set(v); AetherConfig.save() }))
            .add(ListSetting("Auto Sell Items", "Add item name", Supplier { java.util.ArrayList(AetherConfig.AUTO_SELL_ITEMS.get()) },
                { v -> AetherConfig.AUTO_SELL_ITEMS.set(java.util.ArrayList(v)); AetherConfig.save() })
                .visibleWhen { AetherConfig.AUTO_SELL.get() || AetherConfig.AUTOSELL_PASSIVE.get() }))
        groups.add(SettingGroup.of("Stash Manager", "Automatically picks items up from stash",
            { AetherConfig.AUTO_STASH_MANAGER.get() }, { v -> AetherConfig.AUTO_STASH_MANAGER.set(v); AetherConfig.save() })
            .add(FarmingSettingsFactory.pickUpStashDelaySetting().visibleWhen { AetherConfig.AUTO_STASH_MANAGER.get() }))
        groups.add(SettingGroup.of("Junk Manager", "Drops configured junk items once the threshold is reached",
            { AetherConfig.AUTO_DROP_JUNK.get() }, { v -> AetherConfig.AUTO_DROP_JUNK.set(v); AetherConfig.save() })
            .add(SliderSetting("Junk Threshold", 1.0f, 10.0f, { AetherConfig.JUNK_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.JUNK_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix(" items").visibleWhen { AetherConfig.AUTO_DROP_JUNK.get() })
            .add(FarmingSettingsFactory.junkDropDelaySetting().visibleWhen { AetherConfig.AUTO_DROP_JUNK.get() })
            .add(TextSetting("Drop at Plot TP", "Plot number (e.g. 5)", { AetherConfig.DROP_JUNK_PLOT_TP.get() },
                { v -> AetherConfig.DROP_JUNK_PLOT_TP.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.AUTO_DROP_JUNK.get() })
            .add(ListSetting("Junk Items", "Add item name", Supplier { java.util.ArrayList(AetherConfig.JUNK_ITEMS.get()) },
                { v -> AetherConfig.JUNK_ITEMS.set(java.util.ArrayList(v)); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_DROP_JUNK.get() }))
        groups.add(SettingGroup.alwaysOn("Chat Filtering", "Controls chat cleanup for farming-related messages")
            .add(ToggleSetting("Hide Pest Drops", { AetherConfig.HIDE_FILTERED_CHAT.get() },
                { v -> AetherConfig.HIDE_FILTERED_CHAT.set(v); AetherConfig.save() })))
        return MainGUIRegistry.subTab("Farming QOL", "Various quality-of-life features for farming", groups.toList())
    }

    private fun isAutoSellCategoryEnabled(): Boolean = AetherConfig.AUTO_SELL.get() || AetherConfig.AUTOSELL_PASSIVE.get()

    private fun setAutoSellCategoryEnabled(enabled: Boolean) {
        AetherConfig.AUTO_SELL.set(enabled)
        if (!enabled) AetherConfig.AUTOSELL_PASSIVE.set(false)
        AetherConfig.save()
    }
}
