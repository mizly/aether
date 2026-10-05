package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.macro.fishing.FishingMacro;
import dev.aether.macro.fishing.FishingMacroKind;
import dev.aether.modules.routes.RouteStore;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.RoutesScreen;
import dev.aether.ui.providers.base.AbstractFishingRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class FishingMacroRegistryProvider extends AbstractFishingRegistryProvider {

    public FishingMacroRegistryProvider() {
        super(1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        return MainGUIRegistry.subTab(
                FishingMacroKind.GENERAL.displayName(),
                "Fishes lava or water next to you and fights what it catches",
                buildGroups());
    }

    private static List<SettingGroup> buildGroups() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.alwaysOn(
                        "Hotbar Slots",
                        "Where the fishing rod and the weapon sit in the hotbar")
                .add(new SliderSetting("Fishing Rod Slot", 1, 9,
                        () -> (float) AetherConfig.FISHING_MACRO_ROD_SLOT.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_ROD_SLOT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Weapon Slot", 1, 9,
                        () -> (float) AetherConfig.FISHING_MACRO_WEAPON_SLOT.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_WEAPON_SLOT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)));

        FishingMacro.AimAt[] aimModes = FishingMacro.AimAt.values();
        groups.add(SettingGroup.alwaysOn(
                        "Aiming",
                        "Where the macro casts and how it moves while it waits")
                .add(new DropdownSetting("Aim At", List.of("Lava", "Water", "Hotspot"),
                        () -> FishingMacro.AimAt.fromConfig(AetherConfig.FISHING_MACRO_AIM_AT.get()).ordinal(),
                        index -> {
                            if (index < 0 || index >= aimModes.length) {
                                return;
                            }
                            AetherConfig.FISHING_MACRO_AIM_AT.set(aimModes[index].name());
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Random Look Around",
                        () -> AetherConfig.FISHING_MACRO_RANDOM_LOOK.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_RANDOM_LOOK.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Move Around On Block",
                        () -> AetherConfig.FISHING_MACRO_BLOCK_SHUFFLE.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_BLOCK_SHUFFLE.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Cast Delay Min", 0, 3000,
                        () -> (float) AetherConfig.FISHING_MACRO_CAST_DELAY_MIN.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_CAST_DELAY_MIN.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms"))
                .add(new SliderSetting("Cast Delay Max", 0, 3000,
                        () -> (float) AetherConfig.FISHING_MACRO_CAST_DELAY_MAX.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_CAST_DELAY_MAX.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")));

        groups.add(SettingGroup.alwaysOn(
                        "Targets",
                        "Which catches the macro fights; the rest are left alone")
                .add(new ListSetting("Mob Whitelist", "Add mob name",
                        () -> AetherConfig.FISHING_MACRO_MOB_WHITELIST.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_MOB_WHITELIST.set(v);
                            AetherConfig.save();
                        }))
                .add(new ListSetting("Mob Blacklist", "Add mob name",
                        () -> AetherConfig.FISHING_MACRO_MOB_BLACKLIST.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_MOB_BLACKLIST.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.of(
                "Use Hyperion",
                "Swaps to a Hyperion-family blade, looks down and right-clicks until nearby catches die. "
                        + "Wither Impact also hits ignored mobs within 6 blocks.",
                () -> AetherConfig.FISHING_MACRO_USE_HYPERION.get(),
                v -> {
                    AetherConfig.FISHING_MACRO_USE_HYPERION.set(v);
                    AetherConfig.save();
                }));

        groups.add(SettingGroup.of(
                        "Use Wand of Healing",
                        "Heals with a Wand of Healing, Mending, Restoration or Atonement between casts, "
                                + "only with the line in, once health drops below the set level.",
                        () -> AetherConfig.FISHING_MACRO_USE_WAND.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_USE_WAND.set(v);
                            AetherConfig.save();
                        })
                .add(new SliderSetting("Heal Below", 10, 90,
                        () -> (float) AetherConfig.FISHING_MACRO_HEAL_BELOW_PERCENT.get(),
                        v -> {
                            AetherConfig.FISHING_MACRO_HEAL_BELOW_PERCENT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("%")));

        groups.add(SettingGroup.alwaysOn(
                        "Restart",
                        "Where the macro warps and walks before it starts fishing")
                .add(new ActionSetting("Restart Route",
                        () -> Minecraft.getInstance().setScreen(new RoutesScreen(RouteStore.FISHING))))
                .add(new InfoSetting("Selected Route", () -> {
                    String selected = AetherConfig.FISHING_MACRO_ROUTE.get();
                    return selected == null || selected.isBlank()
                            ? AetherLang.localize("No route selected")
                            : selected;
                })));

        return groups;
    }
}
