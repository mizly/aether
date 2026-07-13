package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.config.FarmingMacroPresetManager;
import dev.aether.config.FarmType;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

public final class FarmingMacroRegistryProvider extends AbstractModulesRegistryProvider {
    public FarmingMacroRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();
        groups.add(SettingGroup.alwaysOn(
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
                        FarmingMacroPresetManager::applyPresetByIndex)
                        .addIconAction("/assets/aether/icons/folder.svg", FarmingMacroPresetManager::openPresetFolder)
                        .addIconAction("/assets/aether/icons/refresh.svg", FarmingMacroPresetManager::refreshPresetOptions)));

        groups.add(SettingGroup.alwaysOn(
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
                                AetherConfig.save();
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

        groups.add(SettingGroup.of(
                        "Fast Lane Switch (Experimental)",
                        "Switches farming direction at configured plot lane boundaries",
                        () -> AetherConfig.MACRO_FAST_LANE_SWITCH.get(),
                        v -> {
                            AetherConfig.MACRO_FAST_LANE_SWITCH.set(v);
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

        return MainGUIRegistry.subTab("Farming Macro", "Automatically farms crops", groups);
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
}
