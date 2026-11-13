package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class WorldChangeFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(7) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "World Change Failsafe",
            "Handles unexpected world changes while farming"
        )
            .add(FailsafeActionSettings.createActionDropdown("Action",
                { AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.WORLD_CHANGE,
                { AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.get() }))
            .add(SliderSetting("Recovery Wait", 0.0f, 30.0f,
                { AetherConfig.FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.toggleSubTab(
            "World Change",
            "Handles unexpected world changes while farming",
            { AetherConfig.FAILSAFE_WORLD_CHANGE.get() },
            { v -> AetherConfig.FAILSAFE_WORLD_CHANGE.set(v); AetherConfig.save() },
            listOf(group)
        )
    }
}
