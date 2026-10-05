package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.modules.routes.RouteStore;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.RoutesScreen;
import dev.aether.ui.providers.base.AbstractFishingRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

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
                        "Where the fishing rod, the weapon and the Soul Whip sit in the hotbar")
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
                        .withDecimals(0))
                .add(new SliderSetting("Soul Whip Slot", 1, 9,
                        () -> (float) AetherConfig.STRIDER_FISHING_SOUL_WHIP_SLOT.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_SOUL_WHIP_SLOT.set(Math.round(v));
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

        // always shown, since the sawyer spot pools and whips whatever the toggle says
        groups.add(SettingGroup.alwaysOn(
                        "Soul Whip",
                        "How the pool fills up and what clears it")
                .add(new ToggleSetting("Soul Whip Fishing",
                        () -> AetherConfig.STRIDER_FISHING_SOUL_WHIP_FISHING.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_SOUL_WHIP_FISHING.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Striders Before Kill", 1, 10,
                        () -> (float) AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new ToggleSetting("Use Soul Whip",
                        () -> AetherConfig.STRIDER_FISHING_SOUL_WHIP.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_SOUL_WHIP.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Weapon Swap Delay Min", 0, 250,
                        () -> (float) AetherConfig.STRIDER_FISHING_WHIP_SWAP_MIN.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_WHIP_SWAP_MIN.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms"))
                .add(new SliderSetting("Weapon Swap Delay Max", 0, 250,
                        () -> (float) AetherConfig.STRIDER_FISHING_WHIP_SWAP_MAX.get(),
                        v -> {
                            AetherConfig.STRIDER_FISHING_WHIP_SWAP_MAX.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")));

        groups.add(SettingGroup.alwaysOn(
                        "Restart",
                        "Where the macro warps and walks before it starts fishing")
                .add(new ActionSetting("Restart Route",
                        () -> Minecraft.getInstance().setScreen(new RoutesScreen(RouteStore.STRIDER_FISHING))))
                .add(new InfoSetting("Selected Route", () -> {
                    String selected = AetherConfig.STRIDER_FISHING_RESTART_ROUTE.get();
                    return selected == null || selected.isBlank()
                            ? AetherLang.localize("No route selected: fishes at the Sawyer spot (-694 120 78)")
                            : selected;
                })));

        return groups;
    }
}
