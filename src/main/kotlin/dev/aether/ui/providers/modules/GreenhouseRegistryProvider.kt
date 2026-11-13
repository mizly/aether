package dev.aether.ui.providers.modules

import java.util.List
import java.util.function.Supplier
import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting

class GreenhouseRegistryProvider : AbstractModulesRegistryProvider(7) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("Greenhouse Settings", "Configure Auto Greenhouse behavior")
            .add(SliderSetting("Run Interval", 1.0f, 1440.0f,
                { AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.get().toFloat() },
                { v ->
                    AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.set(Math.round(v).toInt())
                    AetherConfig.save()
                }).withDecimals(0).withSuffix(" min"))
            .add(ListSetting("Plots", "Add plot number",
                Supplier { java.util.ArrayList(AetherConfig.GREENHOUSE_PLOTS.get()) },
                { v -> AetherConfig.GREENHOUSE_PLOTS.set(java.util.ArrayList(v)); AetherConfig.save() }))
            .add(ToggleSetting("Equip Custom Item", { AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.get() },
                { v -> AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.set(v); AetherConfig.save() }))
            .add(TextSetting("Custom Item", "e.g. Nether Wart Hoe",
                { AetherConfig.GREENHOUSE_CUSTOM_ITEM.get() },
                { v -> AetherConfig.GREENHOUSE_CUSTOM_ITEM.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.get() })
            .add(ToggleSetting("Harvest Ashwreath", { AetherConfig.HARVEST_ASHWREATH.get() },
                { v -> AetherConfig.HARVEST_ASHWREATH.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Harvest Turtellini", { AetherConfig.HARVEST_TURTELLINI.get() },
                { v -> AetherConfig.HARVEST_TURTELLINI.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Harvest Glasscorn", { AetherConfig.HARVEST_GLASSCORN.get() },
                { v -> AetherConfig.HARVEST_GLASSCORN.set(v); AetherConfig.save() }))
            .add(ActionSetting("Harvest Now", dev.aether.modules.GreenhouseManager::harvest))
        return MainGUIRegistry.toggleSubTab("Auto Greenhouse", "Automatically harvests your greenhouse",
            { AetherConfig.AUTO_GREENHOUSE.get() },
            { v -> AetherConfig.AUTO_GREENHOUSE.set(v); AetherConfig.save() },
            listOf(group))
    }
}
