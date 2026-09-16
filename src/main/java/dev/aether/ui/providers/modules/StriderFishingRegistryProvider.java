package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractFishingRegistryProvider;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.List;

public final class StriderFishingRegistryProvider extends AbstractFishingRegistryProvider {

    public StriderFishingRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        return MainGUIRegistry.subTab(
                "Strider Fishing",
                "Fishes Stridersurfers out of lava and kills them",
                buildGroups());
    }

    private static List<SettingGroup> buildGroups() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.alwaysOn(
                        "Hotbar Slots",
                        "Where the fishing rod and the weapon sit in the hotbar")
                .add(new SliderSetting("Fishing Rod Slot", 1, 9,
                        () -> (float) AetherConfig.STRIDER_FISHING_ROD_SLOT.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_ROD_SLOT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Weapon Slot", 1, 9,
                        () -> (float) AetherConfig.STRIDER_FISHING_WEAPON_SLOT.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_WEAPON_SLOT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)));

        groups.add(SettingGroup.alwaysOn(
                        "Behaviour",
                        "How the macro fishes and fights what it catches")
                .add(new ToggleSetting("Always Sneak",
                        () -> AetherConfig.STRIDER_FISHING_ALWAYS_SNEAK.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_ALWAYS_SNEAK.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Continue Sneak In Liquid",
                        () -> AetherConfig.STRIDER_FISHING_SNEAK_IN_LIQUID.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_SNEAK_IN_LIQUID.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Etherwarp To Destination",
                        () -> AetherConfig.STRIDER_FISHING_ETHERWARP_RETURN.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_ETHERWARP_RETURN.set(v);
                            AetherConfig.save();
                        }))
                .add(new TextSetting("Catch Name", "e.g. Stridersurfer",
                        () -> AetherConfig.STRIDER_FISHING_TARGET_NAME.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_TARGET_NAME.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Kill Distance", 1.0f, 3.0f,
                        () -> AetherConfig.STRIDER_FISHING_KILL_DISTANCE.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_KILL_DISTANCE.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(2).withSuffix(" blocks"))
                .add(new SliderSetting("Cast Delay Min", 0, 3000,
                        () -> (float) AetherConfig.STRIDER_FISHING_CAST_DELAY_MIN.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_CAST_DELAY_MIN.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms"))
                .add(new SliderSetting("Cast Delay Max", 0, 3000,
                        () -> (float) AetherConfig.STRIDER_FISHING_CAST_DELAY_MAX.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_CAST_DELAY_MAX.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")));

        return groups;
    }
}
