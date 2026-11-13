package dev.aether.ui.providers.modules

import java.util.function.Supplier

import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting

class VisitorRegistryProvider : AbstractModulesRegistryProvider(4) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.of(
            "Auto Visitor Settings",
            "Automatically fulfills visitors requests",
            { AetherConfig.AUTO_VISITOR.get() },
            { v ->
                AetherConfig.AUTO_VISITOR.set(v)
                AetherConfig.save()
            }
        )
        group.add(SliderSetting("Visitor Threshold", 1.0f, 10.0f,
            { AetherConfig.VISITOR_THRESHOLD.get().toFloat() },
            { v ->
                AetherConfig.VISITOR_THRESHOLD.set(Math.round(v).toInt())
                AetherConfig.save()
            }).withDecimals(0))
        group.add(SliderSetting("Max Visitor Purchase", 0.0f, 20.0f,
            { AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.get() / 1_000_000.0f },
            { v ->
                AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.set(Math.round(v * 1_000_000.0f))
                AetherConfig.save()
            }).withDecimals(1).withSuffix("m"))
        group.add(ToggleSetting("Max Coins/Copper",
            { AetherConfig.VISITOR_COINS_PER_COPPER.get() },
            { v ->
                AetherConfig.VISITOR_COINS_PER_COPPER.set(v)
                AetherConfig.save()
            }))
        group.add(SliderSetting("Coins/Copper Limit", 0.0f, 100.0f,
            { AetherConfig.VISITOR_COINS_PER_COPPER_LIMIT.get() / 1_000.0f },
            { v ->
                AetherConfig.VISITOR_COINS_PER_COPPER_LIMIT.set(Math.round(v * 1_000.0f))
                AetherConfig.save()
            }).withDecimals(1).withSuffix("k")
            .visibleWhen { AetherConfig.VISITOR_COINS_PER_COPPER.get() })
        group.add(ToggleSetting("Only Accept Rare Drops",
            { AetherConfig.VISITOR_ONLY_RARE_DROPS.get() },
            { v ->
                AetherConfig.VISITOR_ONLY_RARE_DROPS.set(v)
                AetherConfig.save()
            }))
        group.add(ListSetting("Visitor Ignored", "Add visitor name",
            Supplier { java.util.ArrayList(AetherConfig.VISITOR_ignore.get()) },
            { v ->
                AetherConfig.VISITOR_ignore.set(java.util.ArrayList(v))
                AetherConfig.save()
            }))
        group.add(ListSetting("Visitor Reject", "Add visitor name",
            Supplier { java.util.ArrayList(AetherConfig.VISITOR_REJECT.get()) },
            { v ->
                AetherConfig.VISITOR_REJECT.set(java.util.ArrayList(v))
                AetherConfig.save()
            }))
        group.add(ToggleSetting("Equip Custom Item",
            { AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.get() },
            { v ->
                AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.set(v)
                AetherConfig.save()
            }))
        group.add(TextSetting("Custom Item", "e.g. Blessed Melon Dicer",
            { AetherConfig.VISITOR_CUSTOM_ITEM.get() },
            { v ->
                AetherConfig.VISITOR_CUSTOM_ITEM.set(v)
                AetherConfig.save()
            }).visibleWhen { AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.get() })
        group.add(ToggleSetting("Empty Fungal Tip Jar",
            { AetherConfig.VISITOR_EMPTY_TIP_JAR.get() },
            { v ->
                AetherConfig.VISITOR_EMPTY_TIP_JAR.set(v)
                AetherConfig.save()
            }))
        group.add(ToggleSetting("Disable Compactors during Visitors",
            { AetherConfig.DISABLE_COMPACTORS_DURING_VISITORS.get() },
            { v ->
                AetherConfig.DISABLE_COMPACTORS_DURING_VISITORS.set(v)
                AetherConfig.save()
            }))
        group.add(ToggleSetting("Disable during Jacob's Contests",
            { AetherConfig.DISABLE_VISITORS_DURING_JACOBS_CONTEST.get() },
            { v ->
                AetherConfig.DISABLE_VISITORS_DURING_JACOBS_CONTEST.set(v)
                AetherConfig.save()
            }))
        group.add(FarmingSettingsFactory.visitorFovRangeSetting())
        group.add(FarmingSettingsFactory.visitorDelaySetting())
        return MainGUIRegistry.toggleSubTab(
            "Auto Visitor",
            "Automatically interacts with visitors and fulfills their requests",
            { AetherConfig.AUTO_VISITOR.get() },
            { v ->
                AetherConfig.AUTO_VISITOR.set(v)
                AetherConfig.save()
            },
            listOf(group)
        )
    }
}
