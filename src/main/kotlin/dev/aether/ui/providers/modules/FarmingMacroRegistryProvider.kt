package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.config.FarmType
import dev.aether.config.FarmWaypoint
import dev.aether.config.FarmWaypoints
import dev.aether.config.FarmingMacroPresetManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.MultiDropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import dev.aether.util.AetherLang
import net.minecraft.client.Minecraft
import java.util.ArrayList
import java.util.List

class FarmingMacroRegistryProvider : AbstractModulesRegistryProvider(0) {
    override fun createSubTab(): ModulesTab.SubTab =
        MainGUIRegistry.subTab("Farming Macro", "Automatically farms crops", buildGroups().toList())

    companion object {
        private val FARMING_GROUPS: MutableList<SettingGroup> = java.util.ArrayList()
        private val MOVEMENT_KEY_OPTIONS: ArrayList<String> = ArrayList(java.util.List.of("W", "A", "S", "D"))

        private fun buildGroups(): List<SettingGroup> {

            FARMING_GROUPS.clear()
            FARMING_GROUPS.add(SettingGroup.alwaysOn(
                "Presets",

                "Save and load Farming Macro preset JSON files"
            )
                .add(TextSetting("Preset Name", "e.g. Wheat",
                    { AetherConfig.FARMING_MACRO_PRESET_NAME.get() }, { value ->
                        AetherConfig.FARMING_MACRO_PRESET_NAME.set(value)
                        AetherConfig.save()
                    }))
                .add(ActionSetting("Save Preset", FarmingMacroPresetManager::saveCurrentPreset))
                .add(DropdownSetting("Preset", ArrayList(FarmingMacroPresetManager.getPresetOptions()),
                    FarmingMacroPresetManager::getSelectedPresetIndex, { index ->
                        FarmingMacroPresetManager.applyPresetByIndex(index)
                        if (isCustomFarmType()) {
                            AetherConfig.MACRO_FAST_LANE_SWITCH.set(false)
                            AetherConfig.save()
                        }
                        buildGroups()
                    })
                    .addIconAction("/assets/aether/icons/folder.svg", FarmingMacroPresetManager::openPresetFolder)
                    .addIconAction("/assets/aether/icons/refresh.svg", FarmingMacroPresetManager::refreshPresetOptions)))

            FARMING_GROUPS.add(SettingGroup.alwaysOn(
                "Farm Macro Settings",
                "Configure Farming Macro behavior"
            )
                .add(DropdownSetting("Farm Type",
                    ArrayList(FarmType.values().map { it.getLabel() }),
                    {
                        try {
                            FarmType.valueOf(AetherConfig.FARM_TYPE.get()).ordinal
                        } catch (_: Exception) {
                            0
                        }
                    }, { index ->
                        if (index >= 0 && index < FarmType.values().size) {
                            val selectedFarmType = FarmType.values()[index]
                            AetherConfig.FARM_TYPE.set(selectedFarmType.name)
                            if (selectedFarmType != FarmType.S_SHAPE) {
                                AetherConfig.MACRO_HOLD_W_WHILE_FARMING.set(false)
                            }
                            if (selectedFarmType == FarmType.CUSTOM) {
                                AetherConfig.MACRO_FAST_LANE_SWITCH.set(false)
                            }
                            AetherConfig.save()
                            buildGroups()
                        }
                    }))
                .add(ToggleSetting("Hold W While Farming",
                    { AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get() }, { value ->
                        AetherConfig.MACRO_HOLD_W_WHILE_FARMING.set(isBaseSShapeFarmType() && value)
                        AetherConfig.save()
                    })
                    .visibleWhen(::isBaseSShapeFarmType))
                .add(ToggleSetting("D/S+A?",
                    { AetherConfig.MACRO_SDS_MUSHROOM_REVERSE_LANE.get() }, { value ->
                        AetherConfig.MACRO_SDS_MUSHROOM_REVERSE_LANE.set(isSdsMushroomFarmType() && value)
                        AetherConfig.save()
                    })
                    .visibleWhen(::isSdsMushroomFarmType))
                .add(ToggleSetting(AetherLang.localize("Disable /setspawn"),
                    { AetherConfig.MACRO_DISABLE_SETSPAWN.get() }, { value ->
                        AetherConfig.MACRO_DISABLE_SETSPAWN.set(value)
                        AetherConfig.save()
                    }))
                .add(ToggleSetting("Squeaky Mousemat",
                    { AetherConfig.SQUEAKY_MOUSEMAT.get() }, { value ->
                        AetherConfig.SQUEAKY_MOUSEMAT.set(value)
                        AetherConfig.save()
                    }))
                .add(ToggleSetting("Custom Pitch",
                    { AetherConfig.MACRO_USE_CUSTOM_PITCH.get() }, { value ->
                        AetherConfig.MACRO_USE_CUSTOM_PITCH.set(value)
                        AetherConfig.save()
                    })
                    .visibleWhen { !AetherConfig.SQUEAKY_MOUSEMAT.get() })
                .add(SliderSetting("Pitch", -90.0f, 90.0f,
                    { AetherConfig.MACRO_CUSTOM_PITCH.get() }, { value ->
                        AetherConfig.MACRO_CUSTOM_PITCH.set(value)
                        AetherConfig.save()
                    })
                    .withDecimals(1).withSuffix("\u00B0")
                    .visibleWhen { !AetherConfig.SQUEAKY_MOUSEMAT.get() && AetherConfig.MACRO_USE_CUSTOM_PITCH.get() })
                .add(ToggleSetting("Custom Yaw",
                    { AetherConfig.MACRO_USE_CUSTOM_YAW.get() }, { value ->
                        AetherConfig.MACRO_USE_CUSTOM_YAW.set(value)
                        AetherConfig.save()
                    })
                    .visibleWhen { !AetherConfig.SQUEAKY_MOUSEMAT.get() })
                .add(SliderSetting("Yaw", -180.0f, 180.0f,
                    { AetherConfig.MACRO_CUSTOM_YAW.get() }, { value ->
                        AetherConfig.MACRO_CUSTOM_YAW.set(value)
                        AetherConfig.save()
                    })
                    .withDecimals(1).withSuffix("\u00B0")
                    .visibleWhen { !AetherConfig.SQUEAKY_MOUSEMAT.get() && AetherConfig.MACRO_USE_CUSTOM_YAW.get() })
                .add(FarmingSettingsFactory.farmingPitchRangeSetting()
                    .visibleWhen { !AetherConfig.SQUEAKY_MOUSEMAT.get() })
                .add(FarmingSettingsFactory.farmingYawRangeSetting()
                    .visibleWhen { !AetherConfig.SQUEAKY_MOUSEMAT.get() })
                .add(FarmingSettingsFactory.bpsAverageWindowSetting()))

            if (isCustomFarmType()) {
                FARMING_GROUPS.add(buildFarmWaypointsGroup())
            } else {
                FARMING_GROUPS.add(SettingGroup.of(
                    "Fast Lane Switch (Experimental)",
                    "Switches farming direction at configured plot lane boundaries",
                    { !isCustomFarmType() && AetherConfig.MACRO_FAST_LANE_SWITCH.get() }, { value ->
                        AetherConfig.MACRO_FAST_LANE_SWITCH.set(!isCustomFarmType() && value)
                        AetherConfig.save()
                    }
                )
                    .add(DropdownSetting("Boundary Axis", ArrayList(java.util.Arrays.asList("X", "Z")),
                        { if ("Z".equals(AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get(), ignoreCase = true)) 1 else 0 }, { index ->
                            AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.set(if (index == 1) "Z" else "X")
                            AetherConfig.save()
                        }))
                    .add(TextSetting("Left Boundary", "e.g. -48",
                        { AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get().toString() }, { value ->
                            val parsed = parseBoundary(value)
                            if (parsed != null) {
                                AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.set(parsed)
                                AetherConfig.save()
                            }
                        }))
                    .add(TextSetting("Right Boundary", "e.g. 48 blocks",
                        { AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get().toString() }, { value ->
                            val parsed = parseBoundary(value)
                            if (parsed != null) {
                                AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.set(parsed)
                                AetherConfig.save()
                            }
                        })))
            }

            return java.util.ArrayList(FARMING_GROUPS) as java.util.List<SettingGroup>
        }

        private fun buildFarmWaypointsGroup(): SettingGroup {
            val group = SettingGroup.alwaysOn(
                "Farm Waypoints",
                "Configure custom farm waypoint positions and movement keys"
            )
            group.add(SliderSetting("Switch Point Radius", 0.05f, 1.00f,
                { AetherConfig.MACRO_CUSTOM_WAYPOINT_SWITCH_RADIUS.get() }, { value ->
                    AetherConfig.MACRO_CUSTOM_WAYPOINT_SWITCH_RADIUS.set(value)
                    AetherConfig.save()
                })
                .withDecimals(2)
                .withSuffix(" blocks"))

            val count = maxOf(1, FarmWaypoints.get().size)
            for (i in 0 until count) {
                group.add(buildFarmWaypointSetting(i))
                group.add(MultiDropdownSetting("Movement Keys #${i + 1}", MOVEMENT_KEY_OPTIONS,
                    { FarmWaypoints.get(i).movementMask() }, { mask ->
                        FarmWaypoints.update(i) { waypoint -> waypoint.withMovementMask(mask) }
                    }))
            }
            return group
        }

        private fun buildFarmWaypointSetting(index: Int): PositionSetting =
            PositionSetting("Farm Waypoint #${index + 1}",
                { FarmWaypoints.get(index).x }, { value ->
                    FarmWaypoints.update(index) { waypoint -> waypoint.withPosition(value, waypoint.y, waypoint.z) }
                },
                { FarmWaypoints.get(index).y }, { value ->
                    FarmWaypoints.update(index) { waypoint -> waypoint.withPosition(waypoint.x, value, waypoint.z) }
                },
                { FarmWaypoints.get(index).z }, { value ->
                    FarmWaypoints.update(index) { waypoint -> waypoint.withPosition(waypoint.x, waypoint.y, value) }
                },
                { FarmWaypoints.get(index).highlighted }, { value ->
                    FarmWaypoints.update(index) { waypoint -> waypoint.withHighlighted(value) }
                },
                { captureWaypoint(index) })
                .addActionButton("+", {
                    FarmWaypoints.insertAfter(index, captureCurrentWaypoint())
                    buildGroups()
                }, { true })
                .addActionButton("-", {
                    FarmWaypoints.remove(index)
                    buildGroups()
                }, { FarmWaypoints.get().size > 1 })

        private fun parseBoundary(value: String?): Int? {
            if (value == null || value.isBlank()) return null
            return try {
                Integer.parseInt(value.trim())
            } catch (_: NumberFormatException) {
                null
            }
        }

        private fun isBaseSShapeFarmType(): Boolean = try {
            FarmType.valueOf(AetherConfig.FARM_TYPE.get()) == FarmType.S_SHAPE
        } catch (_: Exception) {
            false
        }

        private fun isSdsMushroomFarmType(): Boolean = try {
            FarmType.valueOf(AetherConfig.FARM_TYPE.get()) == FarmType.SDS_MUSHROOM
        } catch (_: Exception) {
            false
        }

        private fun isCustomFarmType(): Boolean = try {
            FarmType.valueOf(AetherConfig.FARM_TYPE.get()) == FarmType.CUSTOM
        } catch (_: Exception) {
            false
        }

        private fun captureCurrentWaypoint(): FarmWaypoint {
            val mc = Minecraft.getInstance()
            val player = mc.player
            if (player == null) {
                return FarmWaypoint.emptyAt(0.0, 0.0, 0.0)
            }
            return FarmWaypoint.emptyAt(player.x, player.y, player.z)
        }

        private fun captureWaypoint(index: Int) {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            FarmWaypoints.update(index) { waypoint -> waypoint.withPosition(player.x, player.y, player.z) }
        }
    }
}
