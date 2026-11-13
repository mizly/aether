package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import net.minecraft.client.Minecraft

class AutoCarnivalRegistryProvider : AbstractMiningRegistryProvider(12) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Miscellaneous",
            "Shootout settings"
        )
            .add(SliderSetting("Offset", 0.0f, 1000.0f,
                { AetherConfig.AUTO_CARNIVAL_PING.get().toFloat() }, { value ->
                    AetherConfig.AUTO_CARNIVAL_PING.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0))

        return MainGUIRegistry.toggleSubTab(
            "Auto Carnival (Shootout)",
            "Automatically clears shootout rounds and requeues them until tickets run out",
            { AetherConfig.AUTO_CARNIVAL_SHOOTOUT.get() },
            { value -> AutoCarnivalManager.setEnabled(Minecraft.getInstance(), value) },
            listOf(group)
        )
    }
}
