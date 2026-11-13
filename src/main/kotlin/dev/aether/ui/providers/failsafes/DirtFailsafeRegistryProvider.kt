package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class DirtFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(5) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Dirt Check Failsafe",
            "Triggers when a suspicious solid block stays close to the player during farming"
        )
            .add(FailsafeActionSettings.createActionDropdown("Action",
                { AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.DIRT_CHECK,
                { AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.get() }))
            .add(SliderSetting("Trigger Delay", 0.0f, 10.0f,
                { AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.toggleSubTab(
            "Dirt Check",
            "Triggers when a suspicious solid block stays close to the player during farming",
            { AetherConfig.FAILSAFE_DIRT_CHECK.get() },
            { v -> AetherConfig.FAILSAFE_DIRT_CHECK.set(v); AetherConfig.save() },
            listOf(group)
        )
    }
}
