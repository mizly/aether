package dev.aether.ui.providers.settings;

import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.telemetry.AetherAuthState;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractSettingsRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.List;

public final class AccountSettingsRegistryProvider extends AbstractSettingsRegistryProvider {
    public AccountSettingsRegistryProvider() {
        super(-1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                "Discord",
                "Connect your Discord account to track Aether usage time");

        group.add(new InfoSetting("Status", AccountSettingsRegistryProvider::statusText));
        group.add(new InfoSetting("Account", AccountSettingsRegistryProvider::accountText)
                .visibleWhen(() -> AetherAuthService.getState() == AetherAuthState.AUTHENTICATED));

        group.add(new ActionSetting("Connect Discord", AetherAuthService::beginLogin)
                .visibleWhen(() -> AetherAuthService.getState() == AetherAuthState.LOGGED_OUT));
        group.add(new ActionSetting("Cancel", AetherAuthService::cancelLogin)
                .visibleWhen(() -> AetherAuthService.getState() == AetherAuthState.AUTHENTICATING));
        group.add(new ActionSetting("Disconnect", AetherAuthService::logout)
                .visibleWhen(() -> AetherAuthService.getState() == AetherAuthState.AUTHENTICATED));

        return MainGUIRegistry.subTab("Account", "Discord account and usage tracking", List.of(group));
    }

    private static String statusText() {
        return switch (AetherAuthService.getState()) {
            case AUTHENTICATED -> "Connected";
            case AUTHENTICATING -> {
                String detail = AetherAuthService.getStatusDetail();
                yield detail.isEmpty() ? "Waiting for authorization..." : detail;
            }
            case LOGGED_OUT -> "Not connected";
        };
    }

    private static String accountText() {
        String id = AetherAuthService.getDiscordId();
        if (id.isEmpty()) {
            return "Discord account linked";
        }
        return StreamerModeManager.isEnabled() ? "Hidden" : "Discord ID " + id;
    }
}
