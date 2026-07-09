package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.discord.DiscordRemoteControlManager;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;

import java.util.ArrayList;
import java.util.List;

public final class RemoteControlSettingsRegistryProvider extends AbstractMiningRegistryProvider {
    public RemoteControlSettingsRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        SettingGroup remoteControl = SettingGroup.of(
                "Remote Control",
                "Configure Discord bot access for remote Aether commands",
                () -> AetherConfig.REMOTE_CONTROL_ENABLED.get(),
                v -> {
                    AetherConfig.REMOTE_CONTROL_ENABLED.set(v);
                    AetherConfig.save();
                    DiscordRemoteControlManager.restartFromConfig();
                });

        remoteControl.add(new TextSetting("Bot Token", "Paste Discord bot token",
                () -> AetherConfig.REMOTE_CONTROL_BOT_TOKEN.get(),
                v -> {
                    AetherConfig.REMOTE_CONTROL_BOT_TOKEN.set(v.trim());
                    AetherConfig.save();
                    DiscordRemoteControlManager.restartFromConfig();
                })
                .visibleWhen(() -> AetherConfig.REMOTE_CONTROL_ENABLED.get()));
        remoteControl.add(new TextSetting("Server ID", "Discord server ID",
                () -> AetherConfig.REMOTE_CONTROL_GUILD_ID.get(),
                v -> {
                    AetherConfig.REMOTE_CONTROL_GUILD_ID.set(v.trim());
                    AetherConfig.save();
                    DiscordRemoteControlManager.restartFromConfig();
                })
                .visibleWhen(() -> AetherConfig.REMOTE_CONTROL_ENABLED.get()));
        remoteControl.add(new TextSetting("Channel ID", "Discord channel ID",
                () -> AetherConfig.REMOTE_CONTROL_CHANNEL_ID.get(),
                v -> {
                    AetherConfig.REMOTE_CONTROL_CHANNEL_ID.set(v.trim());
                    AetherConfig.save();
                    DiscordRemoteControlManager.restartFromConfig();
                })
                .visibleWhen(() -> AetherConfig.REMOTE_CONTROL_ENABLED.get()));
        remoteControl.add(new TextSetting("Command Prefix", "!aether",
                () -> AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.get(),
                v -> {
                    String prefix = v == null ? "" : v.trim();
                    AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.set(prefix.isBlank() ? "!aether" : prefix);
                    AetherConfig.save();
                    DiscordRemoteControlManager.restartFromConfig();
                })
                .visibleWhen(() -> AetherConfig.REMOTE_CONTROL_ENABLED.get()));

        groups.add(remoteControl);

        SettingGroup discordStatus = SettingGroup.of(
                "Discord Status",
                "Sends macro status updates to a Discord webhook",
                () -> AetherConfig.SEND_DISCORD_STATUS.get(),
                v -> {
                    AetherConfig.SEND_DISCORD_STATUS.set(v);
                    AetherConfig.save();
                });
        discordStatus.add(new TextSetting("Webhook URL", "https://discord.com/api/webhooks/...",
                () -> AetherConfig.DISCORD_WEBHOOK_URL.get(),
                v -> {
                    AetherConfig.DISCORD_WEBHOOK_URL.set(v.trim());
                    AetherConfig.save();
                })
                .visibleWhen(() -> AetherConfig.SEND_DISCORD_STATUS.get()));
        discordStatus.add(new SliderSetting("Update Interval", 1, 60,
                () -> (float) AetherConfig.DISCORD_STATUS_UPDATE_TIME.get(),
                v -> {
                    AetherConfig.DISCORD_STATUS_UPDATE_TIME.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix(" min")
                .visibleWhen(() -> AetherConfig.SEND_DISCORD_STATUS.get()));
        groups.add(discordStatus);

        return MainGUIRegistry.subTab(
                "Remote Control",
                "Discord bot commands and status updates",
                groups);
    }
}
