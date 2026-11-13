package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class GhostBlockFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(4) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "Ghost Block Failsafe",
            "Triggers when the farming exp text disappears while the macro is actively farming"
        )
            .add(FailsafeActionSettings.createActionDropdown("Action",
                { AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.GHOST_BLOCK,
                { AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.get() }))
            .add(SliderSetting("Window", 1.0f, 30.0f,
                { AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("s"))
            .add(SliderSetting("Trigger Delay", 0.0f, 5.0f,
                { AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.toggleSubTab(
            "Ghost Block",
            "Triggers when the farming exp text disappears while the macro is actively farming",
            { AetherConfig.FAILSAFE_GHOST_BLOCK.get() },
            { v -> AetherConfig.FAILSAFE_GHOST_BLOCK.set(v); AetherConfig.save() },
            listOf(group)
        )
    }
}
