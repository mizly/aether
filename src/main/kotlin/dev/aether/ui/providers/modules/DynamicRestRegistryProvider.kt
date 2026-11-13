package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import dev.aether.modules.session.DailyFarmTimeTracker
import dev.aether.modules.session.DynamicRestManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting
import java.util.List
import java.util.function.Consumer
import java.util.function.Supplier

class DynamicRestRegistryProvider : AbstractModulesRegistryProvider(8) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn("Dynamic Rest", "Schedules automatic breaks during scripting")
            .add(SliderSetting("Scripting Time", 1.0f, 600.0f,
                Supplier { AetherConfig.REST_SCRIPTING_TIME.get().toFloat() },
                Consumer { v ->
                    AetherConfig.REST_SCRIPTING_TIME.set(Math.round(v).toInt())
                    AetherConfig.save()
                    DynamicRestManager.refreshCurrentSession()
                })
                .withDecimals(0).withSuffix(" min"))
            .add(SliderSetting("Scripting Offset", 0.0f, 300.0f,
                Supplier { AetherConfig.REST_SCRIPTING_TIME_OFFSET.get().toFloat() },
                Consumer { v ->
                    AetherConfig.REST_SCRIPTING_TIME_OFFSET.set(Math.round(v).toInt())
                    AetherConfig.save()
                    DynamicRestManager.refreshCurrentSession()
                })
                .withDecimals(0).withSuffix(" min"))
            .add(SliderSetting("Break Time", 1.0f, 600.0f,
                Supplier { AetherConfig.REST_BREAK_TIME.get().toFloat() },
                Consumer { v ->
                    AetherConfig.REST_BREAK_TIME.set(Math.round(v).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0).withSuffix(" min"))
            .add(SliderSetting("Break Offset", 0.0f, 300.0f,
                Supplier { AetherConfig.REST_BREAK_TIME_OFFSET.get().toFloat() },
                Consumer { v ->
                    AetherConfig.REST_BREAK_TIME_OFFSET.set(Math.round(v).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0).withSuffix(" min"))
            .add(SliderSetting("Daily Threshold", 0.0f, 24.0f,
                Supplier { AetherConfig.DAILY_FARM_THRESHOLD_HOURS.get().toFloat() },
                Consumer { v ->
                    AetherConfig.DAILY_FARM_THRESHOLD_HOURS.set(Math.max(0f, Math.min(24f, v)).toDouble())
                    AetherConfig.save()
                })
                .withDecimals(1).withSuffix(" hr"))
            .add(ToggleSetting("Close Game On Daily Threshold",
                Supplier { AetherConfig.CLOSE_GAME_ON_DAILY_THRESHOLD.get() },
                Consumer { v ->
                    AetherConfig.CLOSE_GAME_ON_DAILY_THRESHOLD.set(v)
                    AetherConfig.save()
                })
                .visibleWhen(Supplier { AetherConfig.DAILY_FARM_THRESHOLD_HOURS.get() > 0.0 }))
            .add(ActionSetting("Reset Daily Timer", Runnable { DailyFarmTimeTracker.resetToday() }))

        return MainGUIRegistry.toggleSubTab("Dynamic Rest", "Schedules automatic breaks during scripting",
            { AetherConfig.DYNAMIC_REST_ENABLED.get() },
            Consumer { enabled -> setDynamicRestEnabled(enabled) },
            listOf(group))
    }

    companion object {
        private fun setDynamicRestEnabled(enabled: Boolean) {
            AetherConfig.DYNAMIC_REST_ENABLED.set(enabled)
            AetherConfig.save()
            if (!enabled) {
                DynamicRestManager.reset()
            } else if (MacroStateManager.isMacroRunning()) {
                DynamicRestManager.scheduleNextRest()
            }
        }
    }
}
