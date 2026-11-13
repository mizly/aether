package dev.aether.ui.providers.modules

import java.util.function.Supplier

import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider
import dev.aether.ui.settings.InfoSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup

class MiningRegistryProvider : AbstractMiningRegistryProvider(3) {
    override fun createSubTab(): ModulesTab.SubTab {
        val metalDetector = SettingGroup.alwaysOn(
            "Metal Detector",
            "Crystal Hollows metal detector automation and backpack filling"
        )
            .add(InfoSetting("Trigger") { "Run /aether metaldetector to start or stop the solver. This menu only edits backpack options." }
                .multiline())
            .add(ListSetting("Blacklist Backpacks", "Add backpack number (1-18)",
                Supplier { java.util.ArrayList(AetherConfig.METAL_DETECTOR_BACKPACK_BLACKLIST.get()) },
                { values ->
                    AetherConfig.METAL_DETECTOR_BACKPACK_BLACKLIST.set(java.util.ArrayList(values))
                    AetherConfig.save()
                }))

        return MainGUIRegistry.subTab(
            "Metal Detector",
            "Crystal Hollows metal detector automation settings",
            java.util.List.of<SettingGroup>(metalDetector)
        )
    }
}
