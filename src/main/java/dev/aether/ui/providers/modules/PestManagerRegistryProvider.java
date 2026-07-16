package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ListSetting;
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

public final class PestManagerRegistryProvider extends AbstractModulesRegistryProvider {
    public PestManagerRegistryProvider() {
        super(2);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<String> sprayMaterials = FarmingSettingsFactory.sprayMaterials();
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.of(
                        "Pest Destroyer",
                        "Cleans pests once past the threshold",
                        () -> AetherConfig.TRIGGER_PEST_ON_CHAT.get(),
                        v -> {
                            AetherConfig.TRIGGER_PEST_ON_CHAT.set(v);
                            AetherConfig.save();
                        })
                .add(new SliderSetting("Pest Threshold", 1, 8,
                        () -> (float) AetherConfig.PEST_THRESHOLD.get(),
                        v -> {
                            AetherConfig.PEST_THRESHOLD.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new ToggleSetting("Skip while Crop Fever Active",
                        () -> AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get(),
                        v -> {
                            AetherConfig.DELAY_PEST_FOR_CROP_FEVER.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Trigger Only After Rewarp",
                        () -> AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.get(),
                        v -> {
                            AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Plot TP for Current Plot",
                        () -> AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get(),
                        v -> {
                            AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Leave One Pest Alive",
                        () -> AetherConfig.LEAVE_ONE_PEST_ALIVE.get(),
                        v -> {
                            AetherConfig.LEAVE_ONE_PEST_ALIVE.set(v);
                            AetherConfig.save();
                        }))
                .add(new ListSetting("Leave One Pest Plots", "Add plot number",
                        () -> AetherConfig.LEAVE_ONE_PEST_PLOTS.get(),
                        v -> {
                            AetherConfig.LEAVE_ONE_PEST_PLOTS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.LEAVE_ONE_PEST_ALIVE.get()))
                .add(new ToggleSetting("Sunset Pests",
                        () -> AetherConfig.SUNSET_PESTS.get(),
                        v -> {
                            AetherConfig.SUNSET_PESTS.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("AOTV Between Distant Pests",
                        () -> AetherConfig.PEST_AOTV_BETWEEN.get(),
                        v -> {
                            AetherConfig.PEST_AOTV_BETWEEN.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Confirm AOTV Between Pests",
                        () -> AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get(),
                        v -> {
                            AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.PEST_AOTV_BETWEEN.get()))
                .add(FarmingSettingsFactory.aotvBetweenPestsDelaySetting()
                        .visibleWhen(() -> AetherConfig.PEST_AOTV_BETWEEN.get()))
                .add(FarmingSettingsFactory.pestFovRangeSetting())
                .add(FarmingSettingsFactory.pestAboveAimPitchRangeSetting()));

        groups.add(SettingGroup.of(
                        "Disco Destination",
                        "Prioritizes a selected plot and holds position for disco pests",
                        () -> AetherConfig.PEST_DISCO_DESTINATION_MODE.get(),
                        v -> {
                            AetherConfig.PEST_DISCO_DESTINATION_MODE.set(v);
                            AetherConfig.save();
                        })
                .add(new TextSetting("Disco Destination Plot", "Plot number (e.g. 5)",
                        () -> AetherConfig.PEST_DISCO_DESTINATION_PLOT.get(),
                        v -> {
                            AetherConfig.PEST_DISCO_DESTINATION_PLOT.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.of(
                        "Discoless Destination",
                        "Plot TP to your farming plot, etherwarp once, then hold and vacuum",
                        () -> AetherConfig.PEST_DISCOLESS_MODE.get(),
                        v -> {
                            AetherConfig.PEST_DISCOLESS_MODE.set(v);
                            AetherConfig.save();
                        })
                .add(new ListSetting("Discoless Plot", "Add plot name",
                        () -> AetherConfig.PEST_DISCOLESS_PLOT.get(),
                        v -> {
                        AetherConfig.PEST_DISCOLESS_PLOT.set(v);
                        AetherConfig.save();
                        })));

        groups.add(SettingGroup.of(
                        "AOTV to Roof",
                        "Teleports to the roof before cleaning pests on selected plots",
                        () -> AetherConfig.AOTV_TO_ROOF.get(),
                        v -> {
                            AetherConfig.AOTV_TO_ROOF.set(v);
                            AetherConfig.save();
                        })
                .add(new ListSetting("AOTV Roof Plots", "Add plot number",
                        () -> AetherConfig.AOTV_ROOF_PLOTS.get(),
                        v -> {
                            AetherConfig.AOTV_ROOF_PLOTS.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("AOTV to Roof Pitch", 20, 90,
                        () -> (float) AetherConfig.AOTV_ROOF_PITCH.get(),
                        v -> {
                            AetherConfig.AOTV_ROOF_PITCH.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("\u00B0"))
                .add(FarmingSettingsFactory.aotvToRoofPitchRangeSetting())
                .add(new ToggleSetting("Break Blocks Before AOTV",
                        () -> AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.get(),
                        v -> {
                            AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.of(
                        "Pest Traps",
                        "Clears and refills pest traps",
                        () -> AetherConfig.ENABLE_PEST_TRAPS.get(),
                        v -> {
                            AetherConfig.ENABLE_PEST_TRAPS.set(v);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Clear Pest Traps",
                        () -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.get(),
                        v -> {
                            AetherConfig.AUTO_CLEAR_PEST_TRAPS.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Pre-equip Mosquito for Pest Traps",
                        () -> AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get(),
                        v -> {
                            AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()))
                .add(new ToggleSetting("Equip Pet After Trap Open",
                        () -> AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.get(),
                        v -> {
                            AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()))
                .add(new TextSetting("Trap Open Pet", "e.g Rose Dragon",
                        () -> AetherConfig.AUTO_PET_AFTER_TRAP_OPEN_PET.get(),
                        v -> {
                            AetherConfig.AUTO_PET_AFTER_TRAP_OPEN_PET.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()
                                && AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.get()))
                .add(new ToggleSetting("Refill Pest Traps",
                        () -> AetherConfig.AUTO_REFILL_PEST_TRAPS.get(),
                        v -> {
                            AetherConfig.AUTO_REFILL_PEST_TRAPS.set(v);
                            AetherConfig.save();
                        }))
                .add(new DropdownSetting("Bait Material", sprayMaterials,
                        () -> {
                            String current = AetherConfig.PEST_TRAPS_BAIT_MATERIAL.get();
                            int idx = sprayMaterials.indexOf(current);
                            return idx >= 0 ? idx : 5;
                        },
                        i -> {
                            if (i >= 0 && i < sprayMaterials.size()) {
                                AetherConfig.PEST_TRAPS_BAIT_MATERIAL.set(sprayMaterials.get(i));
                                AetherConfig.save();
                            }
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_REFILL_PEST_TRAPS.get()))
                .add(new SliderSetting("Bait Amount", 1, 64,
                        () -> (float) AetherConfig.PEST_TRAPS_BAIT_AMOUNT.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_BAIT_AMOUNT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)
                        .visibleWhen(() -> AetherConfig.AUTO_REFILL_PEST_TRAPS.get()))
                .add(new TextSetting("Pest Traps Plot", "Plot number (e.g. 5)",
                        () -> AetherConfig.PEST_TRAPS_PLOT.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_PLOT.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()
                                || AetherConfig.AUTO_REFILL_PEST_TRAPS.get()))
                .add(new ToggleSetting("Pathfind to Traps",
                        () -> AetherConfig.PEST_TRAPS_PATHFIND.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_PATHFIND.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()
                                || AetherConfig.AUTO_REFILL_PEST_TRAPS.get()))
                .add(new PositionSetting("Traps Position",
                        () -> (double) AetherConfig.PEST_TRAPS_X.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_X.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.PEST_TRAPS_Y.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_Y.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> (double) AetherConfig.PEST_TRAPS_Z.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_Z.set((int) Math.round(v));
                            AetherConfig.save();
                        },
                        () -> AetherConfig.PEST_TRAPS_HIGHLIGHT.get(),
                        v -> {
                            AetherConfig.PEST_TRAPS_HIGHLIGHT.set(v);
                            AetherConfig.save();
                        },
                        () -> {
                            var player = Minecraft.getInstance().player;
                            if (player != null) {
                                AetherConfig.PEST_TRAPS_X.set(player.getBlockX());
                                AetherConfig.PEST_TRAPS_Y.set(player.getBlockY());
                                AetherConfig.PEST_TRAPS_Z.set(player.getBlockZ());
                                AetherConfig.save();
                                NotificationManager.success(AetherLang.localize("Pest Traps Position Set"),
                                        String.format("X: %d, Y: %d, Z: %d",
                                                AetherConfig.PEST_TRAPS_X.get(),
                                                AetherConfig.PEST_TRAPS_Y.get(),
                                                AetherConfig.PEST_TRAPS_Z.get()));
                            }
                        })
                        .visibleWhen(() -> AetherConfig.PEST_TRAPS_PATHFIND.get()
                                && (AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()
                                        || AetherConfig.AUTO_REFILL_PEST_TRAPS.get()))));

        return MainGUIRegistry.toggleSubTab(
                "Pest Manager",
                "Automatically cleans pests, and manage your pest traps",
                () -> AetherConfig.TRIGGER_PEST_ON_CHAT.get(),
                v -> {
                    AetherConfig.TRIGGER_PEST_ON_CHAT.set(v);
                    AetherConfig.save();
                },
                groups);
    }

}
