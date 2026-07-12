package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.update.AutoUpdateInstaller;
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

        return MainGUIRegistry.subTab(
                "Auto Update",
                "Fetches and installs the newest GitHub release automatically",
                List.of(autoUpdate));
    }
}
