package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider
import dev.aether.ui.settings.InfoSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.update.AutoUpdateInstaller

class AutoUpdateRegistryProvider : AbstractMiningRegistryProvider(1) {
    override fun createSubTab(): ModulesTab.SubTab {
        val autoUpdate = SettingGroup.of(
            "Auto Update",
            "Fetches and installs the newest GitHub release automatically",
            { AetherConfig.AUTO_UPDATE.get() }, { value ->
                AetherConfig.AUTO_UPDATE.set(value)
                AetherConfig.save()
                if (value) {
                    AutoUpdateInstaller.checkAndInstallLatest()
                }
            }
        )
            .add(InfoSetting("Status") {
                if (AetherConfig.AUTO_UPDATE.get()) AutoUpdateInstaller.getStatus()
                else "Disabled. Enable to automatically install the newest GitHub release."
            }.multiline())

        val updateNotifications = SettingGroup.of(
            "Update Notifications",
            "Checks for new GitHub releases and shows a notification without downloading or installing",
            { AetherConfig.CHECK_FOR_UPDATES.get() }, { value ->
                AetherConfig.CHECK_FOR_UPDATES.set(value)
                AetherConfig.save()
            }
        )

        return MainGUIRegistry.subTab(
            "Auto Update",
            "Fetches and installs the newest GitHub release automatically",
            listOf(autoUpdate, updateNotifications)
        )
    }
}
