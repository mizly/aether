package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class GuiOpenedFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(3) {
    override fun createSubTab(): ModulesTab.SubTab {
        val group = SettingGroup.alwaysOn(
            "GUI Opened",
            "Triggers when an inventory GUI opens during farming or cleaning"
        )
            .add(FailsafeActionSettings.createActionDropdown("Action",
                { AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.GUI_OPENED,
                { AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get() }))
            .add(SliderSetting("Trigger Delay", 0.0f, 5.0f,
                { AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.toggleSubTab(
            "GUI Opened",
            "Triggers when an inventory GUI opens during farming or cleaning",
            { AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get() },
            { v -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.set(v); AetherConfig.save() },
            listOf(group)
        )
    }
}
