package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class BpsFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(2) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "BPS Failsafe",
            "Triggers when block breaks per second fall below the configured threshold"
        )
            .add(FailsafeActionSettings.createActionDropdown("Action",
                { AetherConfig.FAILSAFE_BPS_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_BPS_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.BPS,
                { AetherConfig.FAILSAFE_BPS_ACTION.get() }))
            .add(SliderSetting("Threshold", 5.0f, 15.0f,
                { AetherConfig.FAILSAFE_BPS_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_BPS_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0))
            .add(SliderSetting("Window", 5.0f, 30.0f,
                { AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("s"))
            .add(SliderSetting("Trigger Delay", 0.0f, 5.0f,
                { AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.toggleSubTab(
            "BPS",
            "Triggers when block breaks per second fall below the configured threshold",
            { AetherConfig.FAILSAFE_BPS.get() },
            { v -> AetherConfig.FAILSAFE_BPS.set(v); AetherConfig.save() },
            listOf(group)
        )
    }
}
