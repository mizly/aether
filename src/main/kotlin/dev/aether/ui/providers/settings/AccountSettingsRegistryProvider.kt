package dev.aether.ui.providers.settings

import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.telemetry.AetherAuthService
import dev.aether.telemetry.AetherAuthState
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractSettingsRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.InfoSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup

class AccountSettingsRegistryProvider : AbstractSettingsRegistryProvider(-1) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("Discord", "Connect your Discord account to track Aether usage time")
        group.add(InfoSetting("Status", AccountSettingsRegistryProvider::statusText))
        group.add(InfoSetting("Account", AccountSettingsRegistryProvider::accountText)
            .visibleWhen { AetherAuthService.getState() == AetherAuthState.AUTHENTICATED })
        group.add(ActionSetting("Connect Discord", AetherAuthService::beginLogin)
            .visibleWhen { AetherAuthService.getState() == AetherAuthState.LOGGED_OUT })
        group.add(ActionSetting("Cancel", AetherAuthService::cancelLogin)
            .visibleWhen { AetherAuthService.getState() == AetherAuthState.AUTHENTICATING })
        group.add(ActionSetting("Disconnect", AetherAuthService::logout)
            .visibleWhen { AetherAuthService.getState() == AetherAuthState.AUTHENTICATED })
        return MainGUIRegistry.subTab("Account", "Discord account and usage tracking", listOf(group))
    }

    companion object {
        @JvmStatic private fun statusText(): String = when (AetherAuthService.getState()) {
            AetherAuthState.AUTHENTICATED -> "Connected"
            AetherAuthState.AUTHENTICATING -> {
                val detail = AetherAuthService.getStatusDetail()
                if (detail.isEmpty()) "Waiting for authorization..." else detail
            }
            AetherAuthState.LOGGED_OUT -> "Not connected"
        }

        @JvmStatic private fun accountText(): String {
            val id = AetherAuthService.getDiscordId()
            if (id.isEmpty()) return "Discord account linked"
            return if (StreamerModeManager.isEnabled()) "Hidden" else "Discord ID " + id
        }
    }
}
