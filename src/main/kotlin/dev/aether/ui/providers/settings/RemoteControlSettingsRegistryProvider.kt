package dev.aether.ui.providers.settings

import dev.aether.config.AetherConfig
import dev.aether.modules.discord.DiscordRemoteControlManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractSettingsRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.TextSetting

class RemoteControlSettingsRegistryProvider : AbstractSettingsRegistryProvider(2) {
    override fun createSubTab(): ModulesTab.SubTab {
        val remoteControl = SettingGroup.of(
            "Remote Control", "Configure Discord bot access for remote Aether commands",
            { AetherConfig.REMOTE_CONTROL_ENABLED.get() }, { value ->
                AetherConfig.REMOTE_CONTROL_ENABLED.set(value)
                AetherConfig.save()
                DiscordRemoteControlManager.restartFromConfig()
            })
        remoteControl.add(TextSetting("Bot Token", "Paste Discord bot token",
            { AetherConfig.REMOTE_CONTROL_BOT_TOKEN.get() }, { value ->
                AetherConfig.REMOTE_CONTROL_BOT_TOKEN.set(value.trim())
                AetherConfig.save()
                DiscordRemoteControlManager.restartFromConfig()
            }).visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        remoteControl.add(TextSetting("Server ID", "Discord server ID",
            { AetherConfig.REMOTE_CONTROL_GUILD_ID.get() }, { value ->
                AetherConfig.REMOTE_CONTROL_GUILD_ID.set(value.trim())
                AetherConfig.save()
                DiscordRemoteControlManager.restartFromConfig()
            }).visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        remoteControl.add(TextSetting("Channel ID", "Discord channel ID",
            { AetherConfig.REMOTE_CONTROL_CHANNEL_ID.get() }, { value ->
                AetherConfig.REMOTE_CONTROL_CHANNEL_ID.set(value.trim())
                AetherConfig.save()
                DiscordRemoteControlManager.restartFromConfig()
            }).visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        remoteControl.add(TextSetting("Command Prefix", "!aether",
            { AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.get() }, { value ->
                val prefix = value?.trim() ?: ""
                AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.set(if (prefix.isBlank()) "!aether" else prefix)
                AetherConfig.save()
                DiscordRemoteControlManager.restartFromConfig()
            }).visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        return MainGUIRegistry.subTab("Remote Control", "Configure Discord bot access for remote commands", java.util.List.of(remoteControl))
    }
}
