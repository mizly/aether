package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.discord.DiscordRemoteControlManager;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.TextSetting;

import java.util.List;

public final class RemoteControlSettingsRegistryProvider extends AbstractSettingsRegistryProvider {
    public RemoteControlSettingsRegistryProvider() {
        super(2);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
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

        return MainGUIRegistry.subTab(
                "Remote Control",
                "Configure Discord bot access for remote commands",
                List.of(remoteControl));
    }
}
