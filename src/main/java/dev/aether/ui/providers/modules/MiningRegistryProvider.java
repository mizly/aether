package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.List;

public final class MiningRegistryProvider extends AbstractMiningRegistryProvider {
    public MiningRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup metalDetector = SettingGroup.alwaysOn(
                        "Metal Detector",
                        "Crystal Hollows metal detector automation and backpack filling")
                .add(new InfoSetting("Trigger",
                        () -> "Run /aether metaldetector to start or stop the solver. This menu only edits backpack options.")
                        .multiline())
                .add(new ListSetting("Blacklist Backpacks", "Add backpack number (1-18)",
                        () -> AetherConfig.METAL_DETECTOR_BACKPACK_BLACKLIST.get(),
                        values -> {
                            AetherConfig.METAL_DETECTOR_BACKPACK_BLACKLIST.set(values);
                            AetherConfig.save();
                        }));

        return MainGUIRegistry.subTab(
                "Metal Detector",
                "Crystal Hollows metal detector automation settings",
                List.of(metalDetector));
    }
}
