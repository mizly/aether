package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.session.DailyFarmTimeTracker;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class DynamicRestRegistryProvider extends AbstractModulesRegistryProvider {
    public DynamicRestRegistryProvider() {
        super(8);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Dynamic Rest",
                        "Schedules automatic breaks during scripting")
                .add(new SliderSetting("Scripting Time", 1, 600,
                        () -> (float) AetherConfig.REST_SCRIPTING_TIME.get(),
                        v -> {
                            AetherConfig.REST_SCRIPTING_TIME.set(Math.round(v));
                            AetherConfig.save();
                            DynamicRestManager.refreshCurrentSession();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new SliderSetting("Scripting Offset", 0, 300,
                        () -> (float) AetherConfig.REST_SCRIPTING_TIME_OFFSET.get(),
                        v -> {
                            AetherConfig.REST_SCRIPTING_TIME_OFFSET.set(Math.round(v));
                            AetherConfig.save();
                            DynamicRestManager.refreshCurrentSession();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new SliderSetting("Break Time", 1, 600,
                        () -> (float) AetherConfig.REST_BREAK_TIME.get(),
                        v -> {
                            AetherConfig.REST_BREAK_TIME.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new SliderSetting("Break Offset", 0, 300,
                        () -> (float) AetherConfig.REST_BREAK_TIME_OFFSET.get(),
                        v -> {
                            AetherConfig.REST_BREAK_TIME_OFFSET.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" min"))
                .add(new SliderSetting("Daily Threshold", 0, 24,
                        () -> (float) (double) AetherConfig.DAILY_FARM_THRESHOLD_HOURS.get(),
                        v -> {
                            AetherConfig.DAILY_FARM_THRESHOLD_HOURS.set((double) Math.max(0f, Math.min(24f, v)));
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix(" hr"))
                .add(new ToggleSetting("Close Game On Daily Threshold",
                        () -> AetherConfig.CLOSE_GAME_ON_DAILY_THRESHOLD.get(),
                        v -> {
                            AetherConfig.CLOSE_GAME_ON_DAILY_THRESHOLD.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.DAILY_FARM_THRESHOLD_HOURS.get() > 0.0))
                .add(new ActionSetting("Reset Daily Timer", DailyFarmTimeTracker::resetToday));

        return MainGUIRegistry.toggleSubTab(
                "Dynamic Rest",
                "Schedules automatic breaks during scripting",
                () -> AetherConfig.DYNAMIC_REST_ENABLED.get(),
                DynamicRestRegistryProvider::setDynamicRestEnabled,
                List.of(group));
    }

    private static void setDynamicRestEnabled(boolean enabled) {
        AetherConfig.DYNAMIC_REST_ENABLED.set(enabled);
        AetherConfig.save();
        if (!enabled) {
            DynamicRestManager.reset();
        } else if (MacroStateManager.isMacroRunning()) {
            DynamicRestManager.scheduleNextRest();
        }
    }
}
