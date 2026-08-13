package dev.aether.ui.providers.modules;

import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeSoundManager;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ListSetting;
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

public final class PestManagerRegistryProvider extends AbstractModulesRegistryProvider {
    public PestManagerRegistryProvider() {
        super(2);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<String> sprayMaterials = FarmingSettingsFactory.sprayMaterials();
        List<String> manualPestSoundOptions = getSoundOptions();
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.of(
                        "Pest ESP",
                        "Highlights and traces pests in the Garden",
                        AetherConfig.PEST_ESP_ENABLED::get,
                        value -> {
                            AetherConfig.PEST_ESP_ENABLED.set(value);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Highlight",
                        AetherConfig.PEST_ESP_HIGHLIGHT::get,
                        value -> {
                            AetherConfig.PEST_ESP_HIGHLIGHT.set(value);
                            AetherConfig.save();
                        }))
                .add(new ColorSetting("Highlight Color",
                        AetherConfig.PEST_ESP_HIGHLIGHT_COLOR::get,
                        value -> {
                            AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.set(value);
                            AetherConfig.save();
                        })
                        .visibleWhen(AetherConfig.PEST_ESP_HIGHLIGHT::get))
                .add(new ToggleSetting("Tracer",
                        AetherConfig.PEST_ESP_TRACER::get,
                        value -> {
                            AetherConfig.PEST_ESP_TRACER.set(value);
                            AetherConfig.save();
                        }))
                .add(new ColorSetting("Tracer Color",
                        AetherConfig.PEST_ESP_TRACER_COLOR::get,
                        value -> {
                            AetherConfig.PEST_ESP_TRACER_COLOR.set(value);
                            AetherConfig.save();
                        })
                        .visibleWhen(AetherConfig.PEST_ESP_TRACER::get)));

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
                .add(FarmingSettingsFactory.pestDestroyerTriggerDelaySetting())
                .add(new ToggleSetting("Estimate Pest Destroyer Completion",
                        AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION::get,
                        v -> {
                            AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION.set(v);
                            AetherConfig.save();
                        }))
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
                .add(new SliderSetting("Pest Rotation Speed", 0.5f, 4.0f,
                        AetherConfig.PEST_ROTATION_SPEED::get,
                        v -> {
                            AetherConfig.PEST_ROTATION_SPEED.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix("x"))
                .add(FarmingSettingsFactory.pestFovRangeSetting())
                .add(FarmingSettingsFactory.pestAboveAimPitchRangeSetting()));
        groups.add(SettingGroup.of(
                        "Pest Hunting",
                        "Lassos pests for guaranteed shards instead of vacuuming them (needs a lasso in your hotbar)",
                        AetherConfig.PEST_HUNTING::get,
                        v -> {
                            AetherConfig.PEST_HUNTING.set(v);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Vacuum Stun Before Lasso",
                        AetherConfig.PEST_HUNTING_VACUUM_STUN::get,
                        v -> {
                            AetherConfig.PEST_HUNTING_VACUUM_STUN.set(v);
                            AetherConfig.save();
                        }))
                .add(new MultiDropdownSetting("Vacuum Pest Blacklist",
                        List.of("Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm",
                                "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis"),
                        () -> AetherConfig.PEST_HUNTING_VACUUM_PEST_MASK.get(),
                        v -> {
                            AetherConfig.PEST_HUNTING_VACUUM_PEST_MASK.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Follow Distance", 1, 8,
                        AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE::get,
                        v -> {
                            AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1))
                .add(new SliderSetting("Max Leash Distance", 4, 10,
                        AetherConfig.PEST_HUNTING_MAX_DISTANCE::get,
                        v -> {
                            AetherConfig.PEST_HUNTING_MAX_DISTANCE.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1))
                .add(new SliderSetting("Max Lasso Throws", 1, 15,
                        () -> (float) AetherConfig.PEST_HUNTING_MAX_THROWS.get(),
                        v -> {
                            AetherConfig.PEST_HUNTING_MAX_THROWS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(new SliderSetting("Catch Timeout", 10000, 120000,
                        () -> (float) AetherConfig.PEST_HUNTING_TIMEOUT_MS.get(),
                        v -> {
                            AetherConfig.PEST_HUNTING_TIMEOUT_MS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")));

        groups.add(SettingGroup.of(
                        "Ballsack Shredder",
                        "Uses a dedicated AOTV sequence before pest cleaning",
                        AetherConfig.BALLSACK_SHREDDER::get,
                        v -> {
                            AetherConfig.BALLSACK_SHREDDER.set(v);
                            AetherConfig.save();
                        })
                .add(new ListSetting("Ballsack Shredder Plots", "Add plot number",
                        () -> AetherConfig.BALLSACK_SHREDDER_PLOTS.get(),
                        v -> {
                            AetherConfig.BALLSACK_SHREDDER_PLOTS.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(AetherConfig.BALLSACK_SHREDDER::get))
                .add(new SliderSetting("AOTV Warps", 1, 5,
                        () -> (float) AetherConfig.BALLSACK_WARPS.get(),
                        v -> {
                            AetherConfig.BALLSACK_WARPS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0))
                .add(FarmingSettingsFactory.ballsackShredderTriggerDelaySetting())
                .add(new SliderSetting("Look Down Time", 0, 3000,
                        () -> (float) AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.get(),
                        v -> {
                            AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("ms")));

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
                .add(FarmingSettingsFactory.aotvToRoofPitchSetting())
                .add(new ToggleSetting("Break Blocks Before AOTV",
                        () -> AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.get(),
                        v -> {
                            AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.set(v);
                            AetherConfig.save();
                        })));
        groups.add(SettingGroup.of(
                "On-The-Track Pest",
                "Pauses farming briefly to vacuum pests already within reach",
                () -> AetherConfig.PEST_ON_TRACK_ENABLED.get(),
                v -> {
                    AetherConfig.PEST_ON_TRACK_ENABLED.set(v);
                    AetherConfig.save();
                })
        .add(new SliderSetting("Pest Detection FOV", 1, 360,
                () -> (float) AetherConfig.PEST_ON_THE_TRACK_FOV.get(),
                v -> {
                    AetherConfig.PEST_ON_THE_TRACK_FOV.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("\u00B0"))
        .add(new SliderSetting("Pest Detection Delay Time", 0, 3500,
                () -> (float) AetherConfig.PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS.get(),
                v -> {
                    AetherConfig.PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("ms"))
        .add(new SliderSetting("Stuck Timeout", 4000, 25000,
                () -> (float) AetherConfig.PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS.get(),
                v -> {
                    AetherConfig.PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("ms"))
        .add(new ToggleSetting("Skip during Jacob's Contests",
                () -> AetherConfig.PEST_ON_THE_TRACK_SKIP_JACOB.get(),
                v -> {
                    AetherConfig.PEST_ON_THE_TRACK_SKIP_JACOB.set(v);
                    AetherConfig.save();
                })));
        groups.add(SettingGroup.of(
                        "Manual Pest Killing",
                        "Tabs you in and pauses when pests spawn so you can kill them by hand, then warps to garden and restarts (overrides Pest Destroyer)",
                        () -> AetherConfig.MANUAL_PEST_MODE.get(),
                        v -> {
                            AetherConfig.MANUAL_PEST_MODE.set(v);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Switch to Vacuum When Start",
                        () -> AetherConfig.VACCUM_WHEN_START.get(),
                        v -> {
                            AetherConfig.VACCUM_WHEN_START.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.MANUAL_PEST_MODE.get()))
                .add(new DropdownSetting("Manual Pest Sound", manualPestSoundOptions,
                        () -> getSoundIndex(manualPestSoundOptions, AetherConfig.MANUAL_PEST_SOUND_FILE.get()),
                        i -> {
                            if (i < 0 || i >= manualPestSoundOptions.size()) {
                                return;
                            }
                            AetherConfig.MANUAL_PEST_SOUND_FILE.set(manualPestSoundOptions.get(i));
                            AetherConfig.save();
                        })
                        .addIconAction("/assets/aether/icons/folder.svg", FailsafeSoundManager::openSoundFolder)
                        .addIconAction("/assets/aether/icons/refresh.svg", () -> refreshSoundOptions(manualPestSoundOptions)))
                .add(new KeybindSetting("Manual Pest Early Finish",
                        AetherKeybindRegistry.getManualPestEarlyFinishKey())));

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

    private static List<String> getSoundOptions() {
        List<String> sounds = new ArrayList<>(FailsafeSoundManager.getAvailableSounds());
        if (sounds.isEmpty()) {
            sounds.add(FailsafeSoundManager.getDefaultSoundFileName());
        }
        return sounds;
    }

    private static void refreshSoundOptions(List<String> options) {
        options.clear();
        options.addAll(getSoundOptions());
    }

    private static int getSoundIndex(List<String> options, String selected) {
        int selectedIndex = options.indexOf(selected);
        if (selectedIndex >= 0) {
            return selectedIndex;
        }

        int defaultIndex = options.indexOf(FailsafeSoundManager.getDefaultSoundFileName());
        return defaultIndex >= 0 ? defaultIndex : 0;
    }

}
