package dev.aether.ui.providers.modules

import java.util.function.Supplier

import dev.aether.config.AetherConfig
import dev.aether.modules.SupercraftManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class SupercraftRegistryProvider : AbstractModulesRegistryProvider(9) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Supercraft Settings",
            "Configure Auto Supercraft behavior"
        )
            .add(SliderSetting("Run Interval", 1.0f, 1440.0f,
                { AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.get().toFloat() },
                { v ->
                    AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.set(Math.round(v).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0).withSuffix(" min"))
            .add(ListSetting("Items", "Add item name",
                { java.util.ArrayList(AetherConfig.AUTO_SUPERCRAFT_ITEMS.get()) },
                { v ->
                    AetherConfig.AUTO_SUPERCRAFT_ITEMS.set(java.util.ArrayList(v))
                    AetherConfig.save()
                }))
            .add(ActionSetting("Run Now", SupercraftManager::manualTrigger))

        return MainGUIRegistry.toggleSubTab(
            "Auto Supercraft",
            "Automatically crafts configured items with Supercraft",
            { AetherConfig.AUTO_SUPERCRAFT.get() },
            { v ->
                AetherConfig.AUTO_SUPERCRAFT.set(v)
                AetherConfig.save()
            },
            listOf(group)
        )
    }
}
