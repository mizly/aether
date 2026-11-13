package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.modules.ComposterManager
import dev.aether.notification.NotificationManager
import dev.aether.ui.ComposterSettingsBridge
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.util.AetherLang
import net.minecraft.client.Minecraft

class ComposterRegistryProvider : AbstractModulesRegistryProvider(8) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Composter Settings",
            "Configure Auto Composter behavior"
        )
            .add(PositionSetting("Composter Position",
                { AetherConfig.AUTO_COMPOSTER_X.get().toDouble() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_X.set(Math.round(value).toInt())
                    AetherConfig.save()
                },
                { AetherConfig.AUTO_COMPOSTER_Y.get().toDouble() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_Y.set(Math.round(value).toInt())
                    AetherConfig.save()
                },
                { AetherConfig.AUTO_COMPOSTER_Z.get().toDouble() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_Z.set(Math.round(value).toInt())
                    AetherConfig.save()
                },
                { AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.get() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_HIGHLIGHT.set(value)
                    AetherConfig.save()
                }, {
                    val player = Minecraft.getInstance().player
                    if (player != null) {
                        AetherConfig.AUTO_COMPOSTER_X.set(player.blockX)
                        AetherConfig.AUTO_COMPOSTER_Y.set(player.blockY)
                        AetherConfig.AUTO_COMPOSTER_Z.set(player.blockZ)
                        AetherConfig.save()
                        NotificationManager.success(AetherLang.localize("Composter Position Set"),
                            String.format("X: %d, Y: %d, Z: %d",
                                AetherConfig.AUTO_COMPOSTER_X.get(),
                                AetherConfig.AUTO_COMPOSTER_Y.get(),
                                AetherConfig.AUTO_COMPOSTER_Z.get()))
                    }
                }))
            .add(SliderSetting("Run Interval", 1.0f, 1440.0f,
                { AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.get().toFloat() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0).withSuffix(" min"))
            .add(DropdownSetting("Source Mode", listOf("Sacks", "Bazaar"),
                ComposterManager::getSourceModeIndex,
                ComposterSettingsBridge::setSourceModeIndex))
            .add(SliderSetting("Minimum Purse", 0.0f, 2000000000.0f,
                { AetherConfig.AUTO_COMPOSTER_MIN_PURSE.get().toFloat() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_MIN_PURSE.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0).withSuffix(" coins")
                .visibleWhen(ComposterManager::isBazaarMode))
            .add(TextSetting("Crop Material", "e.g. Box of Seeds",
                { AetherConfig.AUTO_COMPOSTER_CROP_MATERIAL.get() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_CROP_MATERIAL.set(value)
                    AetherConfig.save()
                })
                .visibleWhen(ComposterManager::isBazaarMode))
            .add(SliderSetting("Crop Amount", 1.0f, 2000000.0f,
                { AetherConfig.AUTO_COMPOSTER_CROP_AMOUNT.get().toFloat() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_CROP_AMOUNT.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0)
                .visibleWhen(ComposterManager::isBazaarMode))
            .add(TextSetting("Fuel Material", "e.g. Volta",
                { AetherConfig.AUTO_COMPOSTER_FUEL_MATERIAL.get() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_FUEL_MATERIAL.set(value)
                    AetherConfig.save()
                })
                .visibleWhen(ComposterManager::isBazaarMode))
            .add(SliderSetting("Fuel Amount", 1.0f, 2000000.0f,
                { AetherConfig.AUTO_COMPOSTER_FUEL_AMOUNT.get().toFloat() }, { value ->
                    AetherConfig.AUTO_COMPOSTER_FUEL_AMOUNT.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0)
                .visibleWhen(ComposterManager::isBazaarMode))
            .add(ActionSetting("Run Now", ComposterManager::manualTrigger))

        return MainGUIRegistry.toggleSubTab(
            "Auto Composter",
            "Automatically refills the Garden composter",
            { AetherConfig.AUTO_COMPOSTER.get() }, { value ->
                AetherConfig.AUTO_COMPOSTER.set(value)
                AetherConfig.save()
            },
            listOf(group)
        )
    }
}
