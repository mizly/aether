package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.update.AutoUpdateInstaller;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractMiningRegistryProvider;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.List;

public final class AutoUpdateRegistryProvider extends AbstractMiningRegistryProvider {
    public AutoUpdateRegistryProvider() {
        super(1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup autoUpdate = SettingGroup.of(
                "Auto Update",
                "Fetches and installs the newest GitHub release automatically",
                () -> AetherConfig.AUTO_UPDATE.get(),
                v -> {
                    AetherConfig.AUTO_UPDATE.set(v);
                    AetherConfig.save();
                    if (v) {
                        AutoUpdateInstaller.checkAndInstallLatest();
                    }
                })
                .add(new InfoSetting("Status",
                        () -> AetherConfig.AUTO_UPDATE.get()
                                ? AutoUpdateInstaller.getStatus()
                                : "Disabled. Enable to automatically install the newest GitHub release.")
                        .multiline());
        SettingGroup updateNotifications = SettingGroup.of(
                "Update Notifications",
                "Checks for new GitHub releases and shows a notification without downloading or installing",
                () -> AetherConfig.CHECK_FOR_UPDATES.get(),
                v -> {
                    AetherConfig.CHECK_FOR_UPDATES.set(v);
                    AetherConfig.save();
                });

        return MainGUIRegistry.subTab(
                "Auto Update",
                "Fetches and installs the newest GitHub release automatically",
                List.of(autoUpdate, updateNotifications));
    }
}
