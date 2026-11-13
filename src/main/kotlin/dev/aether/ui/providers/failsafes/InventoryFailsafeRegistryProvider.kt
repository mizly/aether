package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting

class InventoryFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(2) {
    override fun createSubTab(): ModulesTab.SubTab {
        val slotGroup = SettingGroup.alwaysOn(
            "Inventory Slot Changed",
            "Triggers when the selected hotbar slot changes unexpectedly"
        )
            .add(FailsafeActionSettings.createActionDropdown("Action",
                { AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.INVENTORY_SLOT,
                { AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get() }))
            .add(SliderSetting("Trigger Delay", 0.0f, 5.0f,
                { AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.toggleSubTab(
            "Inventory Slot Changed",
            "Triggers when the selected hotbar slot changes unexpectedly",
            { AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get() },
            { v -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.set(v); AetherConfig.save() },
            java.util.List.of<SettingGroup>(slotGroup)
        )
    }
}
