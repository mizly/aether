package dev.aether.ui.providers.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;

// where the orbit menu builds its ring: around you, or at a spot in your garden with open air around it
public final class MenuSceneVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public MenuSceneVisualsRegistryProvider() {
        super(9);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.alwaysOn("Scene", "Pick an open spot so blocks never cover the menu");
        group.add(new DropdownSetting("Scene", List.of("Around you", "Garden spot"), AetherConfig.ORBIT_SCENE::get,
                value -> { AetherConfig.ORBIT_SCENE.set(value); AetherConfig.save(); })
                .describe("In the Garden the menu opens on a copy of your spot instead of where you stand"));
        group.add(new ActionSetting("Use Where I'm Standing", MenuSceneVisualsRegistryProvider::captureSpot)
                .describe("Saves your position and facing as the garden spot"));
        group.add(new InfoSetting("Saved Spot", MenuSceneVisualsRegistryProvider::spotText));
        return MainGUIRegistry.subTab("Menu Scene", "Where the menu opens", List.of(group));
    }

    private static void captureSpot() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        AetherConfig.ORBIT_SPOT_X.set(player.getX());
        AetherConfig.ORBIT_SPOT_Y.set(player.getY());
        AetherConfig.ORBIT_SPOT_Z.set(player.getZ());
        AetherConfig.ORBIT_SPOT_YAW.set(player.getYRot());
        AetherConfig.ORBIT_SPOT_SET.set(true);
        AetherConfig.ORBIT_SCENE.set(1);
        AetherConfig.save();
    }

    private static String spotText() {
        if (!AetherConfig.ORBIT_SPOT_SET.get()) return "No spot saved yet";
        return String.format(Locale.ROOT, "%.0f, %.0f, %.0f", AetherConfig.ORBIT_SPOT_X.get(),
                AetherConfig.ORBIT_SPOT_Y.get(), AetherConfig.ORBIT_SPOT_Z.get());
    }
}
