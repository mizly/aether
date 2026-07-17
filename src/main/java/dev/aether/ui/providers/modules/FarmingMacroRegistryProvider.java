package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.config.FarmWaypoint;
import dev.aether.config.FarmingMacroPresetManager;
import dev.aether.config.FarmType;
import dev.aether.config.FarmWaypoints;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class FarmingMacroRegistryProvider extends AbstractModulesRegistryProvider {
    private static final List<SettingGroup> FARMING_GROUPS = new ArrayList<>();
    private static final List<String> MOVEMENT_KEY_OPTIONS = List.of("W", "A", "S", "D");

    public FarmingMacroRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        return MainGUIRegistry.subTab("Farming Macro", "Automatically farms crops", buildGroups());
    }

    private static List<SettingGroup> buildGroups() {
        FARMING_GROUPS.clear();
        FARMING_GROUPS.add(SettingGroup.alwaysOn(
                        "Presets",
                        "Save and load Farming Macro preset JSON files")
                .add(new TextSetting("Preset Name", "e.g. Wheat",
                        () -> AetherConfig.FARMING_MACRO_PRESET_NAME.get(),
                        v -> {
                            AetherConfig.FARMING_MACRO_PRESET_NAME.set(v);
                            AetherConfig.save();
                        }))
                .add(new ActionSetting("Save Preset", FarmingMacroPresetManager::saveCurrentPreset))
                .add(new DropdownSetting("Preset", FarmingMacroPresetManager.getPresetOptions(),
                        FarmingMacroPresetManager::getSelectedPresetIndex,
                        i -> {
                            FarmingMacroPresetManager.applyPresetByIndex(i);
                            if (isCustomFarmType()) {
                                AetherConfig.MACRO_FAST_LANE_SWITCH.set(false);
                                AetherConfig.save();
                            }
                            buildGroups();
                        })
                        .addIconAction("/assets/aether/icons/folder.svg", FarmingMacroPresetManager::openPresetFolder)
                        .addIconAction("/assets/aether/icons/refresh.svg", FarmingMacroPresetManager::refreshPresetOptions)));

        FARMING_GROUPS.add(SettingGroup.alwaysOn(
                        "Farm Macro Settings",
                        "Configure Farming Macro behavior")
                .add(new DropdownSetting("Farm Type",
                        java.util.stream.Stream.of(FarmType.values()).map(FarmType::getLabel).toList(),
                        () -> {
                            try {
                                return FarmType.valueOf(AetherConfig.FARM_TYPE.get()).ordinal();
                            } catch (Exception e) {
                                return 0;
                            }
                        },
                        i -> {
                            if (i >= 0 && i < FarmType.values().length) {
                                FarmType selectedFarmType = FarmType.values()[i];
                                AetherConfig.FARM_TYPE.set(selectedFarmType.name());
                                if (selectedFarmType != FarmType.S_SHAPE) {
                                    AetherConfig.MACRO_HOLD_W_WHILE_FARMING.set(false);
                                }
                                if (selectedFarmType == FarmType.CUSTOM) {
                                    AetherConfig.MACRO_FAST_LANE_SWITCH.set(false);
                                }
                                AetherConfig.save();
                                buildGroups();
                            }
                        }))
                .add(new ToggleSetting("Hold W While Farming",
                        () -> AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get(),
                        v -> {
                            AetherConfig.MACRO_HOLD_W_WHILE_FARMING.set(isBaseSShapeFarmType() && v);
                            AetherConfig.save();
                        })
                        .visibleWhen(FarmingMacroRegistryProvider::isBaseSShapeFarmType))
                .add(new ToggleSetting("D/S+A?",
                        () -> AetherConfig.MACRO_SDS_MUSHROOM_REVERSE_LANE.get(),
                        v -> {
                            AetherConfig.MACRO_SDS_MUSHROOM_REVERSE_LANE.set(isSdsMushroomFarmType() && v);
                            AetherConfig.save();
                        })
                        .visibleWhen(FarmingMacroRegistryProvider::isSdsMushroomFarmType))
                .add(new ToggleSetting(AetherLang.localize("Disable /setspawn"),
                        () -> AetherConfig.MACRO_DISABLE_SETSPAWN.get(),
                        v -> {
                            AetherConfig.MACRO_DISABLE_SETSPAWN.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Rotate on Drop",
                        () -> AetherConfig.MACRO_ROTATE_ON_DROP.get(),
                        v -> {
                            AetherConfig.MACRO_ROTATE_ON_DROP.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Drop Rotation", -180, 180,
                        () -> (float) AetherConfig.MACRO_DROP_ROTATION_DEGREES.get(),
                        v -> {
                            AetherConfig.MACRO_DROP_ROTATION_DEGREES.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("\u00B0")
                        .visibleWhen(() -> AetherConfig.MACRO_ROTATE_ON_DROP.get()))
                .add(new ToggleSetting("Squeaky Mousemat",
                        () -> AetherConfig.SQUEAKY_MOUSEMAT.get(),
                        v -> {
                            AetherConfig.SQUEAKY_MOUSEMAT.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Custom Pitch",
                        () -> AetherConfig.MACRO_USE_CUSTOM_PITCH.get(),
                        v -> {
                            AetherConfig.MACRO_USE_CUSTOM_PITCH.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> !AetherConfig.SQUEAKY_MOUSEMAT.get()))
                .add(new SliderSetting("Pitch", -90, 90,
                        () -> AetherConfig.MACRO_CUSTOM_PITCH.get(),
                        v -> {
                            AetherConfig.MACRO_CUSTOM_PITCH.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("\u00B0")
                        .visibleWhen(() -> !AetherConfig.SQUEAKY_MOUSEMAT.get()
                                && AetherConfig.MACRO_USE_CUSTOM_PITCH.get()))
                .add(new ToggleSetting("Custom Yaw",
                        () -> AetherConfig.MACRO_USE_CUSTOM_YAW.get(),
                        v -> {
                            AetherConfig.MACRO_USE_CUSTOM_YAW.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> !AetherConfig.SQUEAKY_MOUSEMAT.get()))
                .add(new SliderSetting("Yaw", -180, 180,
                        () -> AetherConfig.MACRO_CUSTOM_YAW.get(),
                        v -> {
                            AetherConfig.MACRO_CUSTOM_YAW.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("\u00B0")
                        .visibleWhen(() -> !AetherConfig.SQUEAKY_MOUSEMAT.get()
                                && AetherConfig.MACRO_USE_CUSTOM_YAW.get()))
                .add(FarmingSettingsFactory.farmingPitchRangeSetting()
                        .visibleWhen(() -> !AetherConfig.SQUEAKY_MOUSEMAT.get()))
                .add(FarmingSettingsFactory.farmingYawRangeSetting()
                        .visibleWhen(() -> !AetherConfig.SQUEAKY_MOUSEMAT.get()))
                .add(FarmingSettingsFactory.bpsAverageWindowSetting()));

        if (isCustomFarmType()) {
            FARMING_GROUPS.add(buildFarmWaypointsGroup());
        } else {
            FARMING_GROUPS.add(SettingGroup.of(
                        "Fast Lane Switch (Experimental)",
                        "Switches farming direction at configured plot lane boundaries",
                        () -> !isCustomFarmType() && AetherConfig.MACRO_FAST_LANE_SWITCH.get(),
                        v -> {
                            AetherConfig.MACRO_FAST_LANE_SWITCH.set(!isCustomFarmType() && v);
                            AetherConfig.save();
                        })
                .add(new DropdownSetting("Boundary Axis",
                        List.of("X", "Z"),
                        () -> "Z".equalsIgnoreCase(AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get()) ? 1 : 0,
                        i -> {
                            AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.set(i == 1 ? "Z" : "X");
                            AetherConfig.save();
                        }))
                .add(new TextSetting("Left Boundary", "e.g. -48",
                        () -> String.valueOf(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get()),
                        v -> {
                            Integer parsed = parseBoundary(v);
                            if (parsed != null) {
                                AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.set(parsed);
                                AetherConfig.save();
                            }
                        }))
                .add(new TextSetting("Right Boundary", "e.g. 48 blocks",
                        () -> String.valueOf(AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get()),
                        v -> {
                            Integer parsed = parseBoundary(v);
                            if (parsed != null) {
                                AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.set(parsed);
                                AetherConfig.save();
                            }
                        })));
        }

        return FARMING_GROUPS;
    }

    private static SettingGroup buildFarmWaypointsGroup() {
        SettingGroup group = SettingGroup.alwaysOn(
                "Farm Waypoints",
                "Configure custom farm waypoint positions and movement keys");

        int count = Math.max(1, FarmWaypoints.get().size());
        for (int i = 0; i < count; i++) {
            final int index = i;
            group.add(buildFarmWaypointSetting(index));
            group.add(new MultiDropdownSetting("Movement Keys #" + (index + 1),
                    MOVEMENT_KEY_OPTIONS,
                    () -> FarmWaypoints.get(index).movementMask(),
                    mask -> FarmWaypoints.update(index, waypoint -> waypoint.withMovementMask(mask))));
        }

        group.add(new ActionSetting("Add Waypoint", () -> {
            FarmWaypoints.add(captureCurrentWaypoint());
            buildGroups();
        }));
        group.add(new ActionSetting("Remove Waypoint", () -> {
            FarmWaypoints.remove(Math.max(0, FarmWaypoints.get().size() - 1));
            buildGroups();
        }).visibleWhen(() -> FarmWaypoints.get().size() > 1));
        return group;
    }

    private static PositionSetting buildFarmWaypointSetting(int index) {
        return new PositionSetting("Farm Waypoint #" + (index + 1),
                () -> FarmWaypoints.get(index).x(),
                v -> FarmWaypoints.update(index, waypoint -> waypoint.withPosition(v, waypoint.y(), waypoint.z())),
                () -> FarmWaypoints.get(index).y(),
                v -> FarmWaypoints.update(index, waypoint -> waypoint.withPosition(waypoint.x(), v, waypoint.z())),
                () -> FarmWaypoints.get(index).z(),
                v -> FarmWaypoints.update(index, waypoint -> waypoint.withPosition(waypoint.x(), waypoint.y(), v)),
                () -> FarmWaypoints.get(index).highlighted(),
                v -> FarmWaypoints.update(index, waypoint -> waypoint.withHighlighted(v)),
                () -> captureWaypoint(index));
    }

    private static Integer parseBoundary(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isBaseSShapeFarmType() {
        try {
            return FarmType.valueOf(AetherConfig.FARM_TYPE.get()) == FarmType.S_SHAPE;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSdsMushroomFarmType() {
        try {
            return FarmType.valueOf(AetherConfig.FARM_TYPE.get()) == FarmType.SDS_MUSHROOM;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isCustomFarmType() {
        try {
            return FarmType.valueOf(AetherConfig.FARM_TYPE.get()) == FarmType.CUSTOM;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static FarmWaypoint captureCurrentWaypoint() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return FarmWaypoint.emptyAt(0.0, 0.0, 0.0);
        }
        return FarmWaypoint.emptyAt(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    private static void captureWaypoint(int index) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        FarmWaypoints.update(index, waypoint -> waypoint.withPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
    }
}
