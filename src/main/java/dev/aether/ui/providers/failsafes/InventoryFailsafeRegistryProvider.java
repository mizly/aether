package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class InventoryFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public InventoryFailsafeRegistryProvider() {
        super(2);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup slotGroup = SettingGroup.alwaysOn(
                        "Inventory Slot Changed",
                        "Triggers when the selected hotbar slot changes unexpectedly")
                .add(FailsafeActionSettings.createActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.INVENTORY_SLOT,
                        () -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get()))
                .add(new SliderSetting("Trigger Delay", 0, 5,
                        () -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "Inventory Slot Changed",
                "Triggers when the selected hotbar slot changes unexpectedly",
                () -> AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get(),
                v -> {
                    AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.set(v);
                    AetherConfig.save();
                },
                List.of(slotGroup));
    }
}
