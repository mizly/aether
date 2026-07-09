package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager.FailsafeReplayType;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;

import java.util.List;

public final class GuiOpenedFailsafeRegistryProvider extends AbstractFailsafesRegistryProvider {
    public GuiOpenedFailsafeRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "GUI Opened",
                        "Triggers when an inventory GUI opens during farming or cleaning")
                .add(FailsafeActionSettings.createActionDropdown("Action",
                        () -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get(),
                        value -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.set(value)))
                .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeReplayType.GUI_OPENED,
                        () -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get()))
                .add(new SliderSetting("Trigger Delay", 0.0f, 5.0f,
                        () -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get(),
                        v -> {
                            AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("s"));

        return MainGUIRegistry.toggleSubTab(
                "GUI Opened",
                "Triggers when an inventory GUI opens during farming or cleaning",
                () -> AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get(),
                v -> {
                    AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
