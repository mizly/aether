package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.notification.NotificationManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.ToggleSetting
import dev.aether.util.AetherLang
import net.minecraft.client.Minecraft

class AutoPestExchangeRegistryProvider : AbstractModulesRegistryProvider(7) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Pest Exchange",
            "Configure Auto Pest Exchange behavior"
        )
            .add(ToggleSetting("Pathfind to Phillip",
                { AetherConfig.PEST_EXCHANGE_PATHFIND.get() }, { value ->
                    AetherConfig.PEST_EXCHANGE_PATHFIND.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.AUTO_PEST_EXCHANGE.get() })
            .add(PositionSetting("Exchange Desk",
                { AetherConfig.PEST_EXCHANGE_DESK_X.get().toDouble() }, { value ->
                    AetherConfig.PEST_EXCHANGE_DESK_X.set(Math.round(value).toInt())
                    AetherConfig.save()
                },
                { AetherConfig.PEST_EXCHANGE_DESK_Y.get().toDouble() }, { value ->
                    AetherConfig.PEST_EXCHANGE_DESK_Y.set(Math.round(value).toInt())
                    AetherConfig.save()
                },
                { AetherConfig.PEST_EXCHANGE_DESK_Z.get().toDouble() }, { value ->
                    AetherConfig.PEST_EXCHANGE_DESK_Z.set(Math.round(value).toInt())
                    AetherConfig.save()
                },
                { AetherConfig.PEST_HIGHLIGHT_DESK.get() }, { value ->
                    AetherConfig.PEST_HIGHLIGHT_DESK.set(value)
                    AetherConfig.save()
                }, {
                    val player = Minecraft.getInstance().player
                    if (player != null) {
                        AetherConfig.PEST_EXCHANGE_DESK_X.set(player.blockX)
                        AetherConfig.PEST_EXCHANGE_DESK_Y.set(player.blockY)
                        AetherConfig.PEST_EXCHANGE_DESK_Z.set(player.blockZ)
                        AetherConfig.save()
                        NotificationManager.success(AetherLang.localize("Pest Exchange Desk Set"),
                            String.format("X: %d, Y: %d, Z: %d",
                                AetherConfig.PEST_EXCHANGE_DESK_X.get(),
                                AetherConfig.PEST_EXCHANGE_DESK_Y.get(),
                                AetherConfig.PEST_EXCHANGE_DESK_Z.get()))
                    }
                })
                .visibleWhen { AetherConfig.AUTO_PEST_EXCHANGE.get() && AetherConfig.PEST_EXCHANGE_PATHFIND.get() })
            .add(FarmingSettingsFactory.pestExchangeFovRangeSetting()
                .visibleWhen { AetherConfig.AUTO_PEST_EXCHANGE.get() && AetherConfig.PEST_EXCHANGE_PATHFIND.get() })

        group.add(ToggleSetting("Use Abiphone",
            { AetherConfig.AUTO_PEST_USE_ABIPHONE.get() }, { value ->
                AetherConfig.AUTO_PEST_USE_ABIPHONE.set(value)
                AetherConfig.save()
            })
            .visibleWhen { AetherConfig.AUTO_PEST_EXCHANGE.get() })

        return MainGUIRegistry.toggleSubTab(
            "Auto Pest Exchange",
            "Automatically visits the pest exchange desk when ready",
            { AetherConfig.AUTO_PEST_EXCHANGE.get() }, { value ->
                AetherConfig.AUTO_PEST_EXCHANGE.set(value)
                AetherConfig.save()
            },
            listOf(group)
        )
    }
}
