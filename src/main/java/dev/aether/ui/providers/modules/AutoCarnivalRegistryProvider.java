package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.modules.misc.AutoCarnivalManager;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class AutoCarnivalRegistryProvider extends AbstractMiningRegistryProvider {
    public AutoCarnivalRegistryProvider() {
        super(12);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn(
                        "Miscellaneous",
                        "Shootout settings")
                .add(new SliderSetting("Offset", 0, 1000,
                        () -> (float) AetherConfig.AUTO_CARNIVAL_PING.get(),
                        value -> {
                            AetherConfig.AUTO_CARNIVAL_PING.set(Math.round(value));
                            AetherConfig.save();
                        })
                        .withDecimals(0));

        return MainGUIRegistry.toggleSubTab(
                "Auto Carnival (Shootout)",
                "Automatically clears shootout rounds and requeues them until tickets run out",
                () -> AetherConfig.AUTO_CARNIVAL_SHOOTOUT.get(),
                value -> AutoCarnivalManager.setEnabled(Minecraft.getInstance(), value),
                List.of(group));
    }
}
