package dev.aether.ui.providers.modules

import java.util.ArrayList
import dev.aether.config.AetherConfig
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import net.minecraft.client.Minecraft

class GearRegistryProvider : AbstractModulesRegistryProvider(3) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()

        groups.add(SettingGroup.alwaysOn(
            "Auto Loadout",
            "Handles loadout swaps for pests and visitors"
        )
            .add(SliderSetting("Farming Loadout Slot", 1.0f, 12.0f,
                { AetherConfig.LOADOUT_SLOT_FARMING.get().toFloat() }, { value ->
                    AetherConfig.LOADOUT_SLOT_FARMING.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0))
            .add(SliderSetting("Pest Spawn Loadout Slot", 1.0f, 12.0f,
                { AetherConfig.LOADOUT_SLOT_PEST.get().toFloat() }, { value ->
                    AetherConfig.LOADOUT_SLOT_PEST.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0))
            .add(SliderSetting("Pest Kill Loadout Slot", 1.0f, 12.0f,
                { AetherConfig.LOADOUT_SLOT_PEST_KILL.get().toFloat() }, { value ->
                    AetherConfig.LOADOUT_SLOT_PEST_KILL.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0))
            .add(SliderSetting("Visitor Loadout Slot", 1.0f, 12.0f,
                { AetherConfig.LOADOUT_SLOT_VISITOR.get().toFloat() }, { value ->
                    AetherConfig.LOADOUT_SLOT_VISITOR.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0))
            .add(SliderSetting("Pest Loadout Swap Time (~170s for eq swap, ~5s for no eq swap)", 0.0f, 180.0f,
                { AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get().toFloat() }, { value ->
                    AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0)
                .withSuffix("s")))

        return MainGUIRegistry.toggleSubTab(
            "Auto Loadout",
            "Automatically swaps loadouts",
            { AetherConfig.AUTO_LOADOUT_ENABLED.get() },
            { enabled ->
                AetherConfig.AUTO_LOADOUT_ENABLED.set(enabled)
                if (!enabled) {
                    LoadoutManager.cancelIfDisabled(Minecraft.getInstance())
                }
                AetherConfig.save()
            },
            groups
        )
    }
}
