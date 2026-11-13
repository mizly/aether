package dev.aether.ui.providers.visuals

import dev.aether.config.AetherConfig
import dev.aether.modules.discord.DiscordRemoteControlManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.MainGUIRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting

class DiscordVisualsRegistryProvider : MainGUIRegistryProvider {
    override fun register(registrar: MainGUIRegistry.Registrar) {
        registrar.registerModuleSection("other", "Other", 150, 20, createSubTab())
    }

    private fun createSubTab(): ModulesTab.SubTab {
        val webhook = SettingGroup.of("Discord Webhook", "Sends macro status updates to a Discord webhook",
            AetherConfig.SEND_DISCORD_STATUS::get) { value ->
            AetherConfig.SEND_DISCORD_STATUS.set(value)
            AetherConfig.save()
        }
        webhook.add(TextSetting("Webhook URL", "https://discord.com/api/webhooks/...", AetherConfig.DISCORD_WEBHOOK_URL::get) { value ->
            AetherConfig.DISCORD_WEBHOOK_URL.set(value.trim())
            AetherConfig.save()
        }.visibleWhen { AetherConfig.SEND_DISCORD_STATUS.get() })
        webhook.add(SliderSetting("Update Interval", 1.0f, 60.0f, { AetherConfig.DISCORD_STATUS_UPDATE_TIME.get().toFloat() }, { value ->
            AetherConfig.DISCORD_STATUS_UPDATE_TIME.set(Math.round(value).toInt())
            AetherConfig.save()
        }).withDecimals(0).withSuffix(" min").visibleWhen { AetherConfig.SEND_DISCORD_STATUS.get() })
        val remoteControl = SettingGroup.of("Discord Remote Control", "Configure Discord bot access for remote Aether commands",
            AetherConfig.REMOTE_CONTROL_ENABLED::get) { value ->
            AetherConfig.REMOTE_CONTROL_ENABLED.set(value)
            AetherConfig.save()
            DiscordRemoteControlManager.restartFromConfig()
        }
        remoteControl.add(TextSetting("Bot Token", "Paste Discord bot token", AetherConfig.REMOTE_CONTROL_BOT_TOKEN::get) { value ->
            AetherConfig.REMOTE_CONTROL_BOT_TOKEN.set(value.trim())
            AetherConfig.save()
            DiscordRemoteControlManager.restartFromConfig()
        }.visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        remoteControl.add(TextSetting("Server ID", "Discord server ID", AetherConfig.REMOTE_CONTROL_GUILD_ID::get) { value ->
            AetherConfig.REMOTE_CONTROL_GUILD_ID.set(value.trim())
            AetherConfig.save()
            DiscordRemoteControlManager.restartFromConfig()
        }.visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        remoteControl.add(TextSetting("Channel ID", "Discord channel ID", AetherConfig.REMOTE_CONTROL_CHANNEL_ID::get) { value ->
            AetherConfig.REMOTE_CONTROL_CHANNEL_ID.set(value.trim())
            AetherConfig.save()
            DiscordRemoteControlManager.restartFromConfig()
        }.visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        remoteControl.add(TextSetting("Command Prefix", "!aether", AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX::get) { value ->
            val prefix = value?.trim() ?: ""
            AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.set(if (prefix.isBlank()) "!aether" else prefix)
            AetherConfig.save()
            DiscordRemoteControlManager.restartFromConfig()
        }.visibleWhen { AetherConfig.REMOTE_CONTROL_ENABLED.get() })
        return MainGUIRegistry.subTab("Discord", "Configure Discord webhook and remote control integrations", java.util.List.of(webhook, remoteControl))
    }
}
