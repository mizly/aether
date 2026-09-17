package dev.aether.ui.providers.modules;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.config.UnflyMode;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MiscellaneousRegistryProvider extends AbstractModulesRegistryProvider {
    public MiscellaneousRegistryProvider() {
        super(11);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.alwaysOn(
                        "Miscellaneous",
                        "Miscellaneous settings")
                .add(new DropdownSetting("Unfly Mode",
                        List.of("Sneak", "2x Tap Space"),
                        () -> AetherConfig.UNFLY_MODE.get().length() > 0
                                ? Arrays.asList(UnflyMode.values()).indexOf(ConfigHelpers.getUnflyMode())
                                : 0,
                        i -> {
                            AetherConfig.UNFLY_MODE.set(UnflyMode.values()[i].name());
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Persist Session Timer",
                        () -> AetherConfig.PERSIST_SESSION_TIMER.get(),
                        v -> {
                            AetherConfig.PERSIST_SESSION_TIMER.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.alwaysOn(
                        "Pathfinder",
                        "Tune obstacle jumps, camera tracking, sprinting and stuck recovery")
                .add(new SliderSetting("Pathfinder Max Jump Height", 1, 6,
                        () -> (float) AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get(),
                        v -> {
                            AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix(" blocks"))
                .add(new ToggleSetting("Raycast Jump Detection",
                        AetherConfig.PATHFINDER_RAYCAST_JUMP::get,
                        v -> {
                            AetherConfig.PATHFINDER_RAYCAST_JUMP.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Jump Lookahead", 0, 5,
                        AetherConfig.PATHFINDER_JUMP_LOOKAHEAD_TICKS::get,
                        v -> {
                            AetherConfig.PATHFINDER_JUMP_LOOKAHEAD_TICKS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1).withSuffix(" ticks")
                        .visibleWhen(AetherConfig.PATHFINDER_RAYCAST_JUMP::get))
                .add(new SliderSetting("Aim Lookahead", 1, 8,
                        AetherConfig.PATHFINDER_AIM_LOOKAHEAD::get,
                        v -> {
                            AetherConfig.PATHFINDER_AIM_LOOKAHEAD.set(v);
                            AetherConfig.save();
                        }).withDecimals(1).withSuffix(" blocks"))
                .add(new SliderSetting("Camera Turn Speed", 60, 720,
                        AetherConfig.PATHFINDER_TURN_SPEED::get,
                        v -> {
                            AetherConfig.PATHFINDER_TURN_SPEED.set(v);
                            AetherConfig.save();
                        }).withDecimals(0).withSuffix(" °/s"))
                .add(new ToggleSetting("Pathfinder Sprint",
                        AetherConfig.PATHFINDER_SPRINT::get,
                        v -> {
                            AetherConfig.PATHFINDER_SPRINT.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Stuck Recovery Delay", 750, 5000,
                        () -> (float) AetherConfig.PATHFINDER_STUCK_TIMEOUT_MS.get(),
                        v -> {
                            AetherConfig.PATHFINDER_STUCK_TIMEOUT_MS.set(Math.round(v));
                            AetherConfig.save();
                        }).withDecimals(0).withSuffix(" ms"))
                .add(new SliderSetting("Flight Braking Lookahead", 0, 6,
                        AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS::get,
                        v -> {
                            AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.set(v);
                            AetherConfig.save();
                        }).withDecimals(1).withSuffix(" ticks")));

        groups.add(SettingGroup.alwaysOn(
                        "Macro Settings",
                        "Macro-specific client behavior")
                .add(new ToggleSetting("Ungrab Mouse",
                        () -> AetherConfig.MACRO_UNGRAB_MOUSE.get(),
                        v -> {
                            AetherConfig.MACRO_UNGRAB_MOUSE.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Auto Reconnect",
                        () -> AetherConfig.AUTO_RECONNECT.get(),
                        v -> {
                            AetherConfig.AUTO_RECONNECT.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Mute Game",
                        () -> AetherConfig.MUTE_GAME.get(),
                        v -> {
                            AetherConfig.MUTE_GAME.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Game Volume", 0, 100,
                        () -> AetherConfig.MUTE_GAME_VOLUME.get() * 100.0f,
                        v -> {
                            AetherConfig.MUTE_GAME_VOLUME.set(clamp01(v / 100.0f));
                            AetherConfig.save();
                        })
                        .withDecimals(0).withSuffix("%")
                        .visibleWhen(() -> AetherConfig.MUTE_GAME.get()))
                .add(new ToggleSetting("Keep Focus",
                        () -> AetherConfig.KEEP_FOCUS.get(),
                        v -> {
                            AetherConfig.KEEP_FOCUS.set(v);
                            AetherConfig.save();
                        })));

        SettingGroup performanceMode = SettingGroup.of(
                "Performance Mode",
                "Lowers render distance and limits FPS during macro execution",
                () -> AetherConfig.PERFORMANCE_MODE.get(),
                v -> {
                    AetherConfig.PERFORMANCE_MODE.set(v);
                    AetherConfig.save();
                });
        performanceMode.add(new ToggleSetting("Limit FPS",
                () -> AetherConfig.PERFORMANCE_LIMIT_FPS.get(),
                v -> {
                    AetherConfig.PERFORMANCE_LIMIT_FPS.set(v);
                    AetherConfig.save();
                }));
        performanceMode.add(new SliderSetting("Max FPS", 20, 60,
                () -> (float) AetherConfig.PERFORMANCE_MODE_MAX_FPS.get(),
                v -> {
                    AetherConfig.PERFORMANCE_MODE_MAX_FPS.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix(" fps")
                .visibleWhen(() -> AetherConfig.PERFORMANCE_LIMIT_FPS.get()));
        performanceMode.add(new ToggleSetting("Limit Chunk Distance",
                () -> AetherConfig.PERFORMANCE_LIMIT_CHUNK_DISTANCE.get(),
                v -> {
                    AetherConfig.PERFORMANCE_LIMIT_CHUNK_DISTANCE.set(v);
                    AetherConfig.save();
                }));
        performanceMode.add(new SliderSetting("Chunk Distance", 2, 8,
                () -> (float) AetherConfig.PERFORMANCE_CHUNK_DISTANCE.get(),
                v -> {
                    AetherConfig.PERFORMANCE_CHUNK_DISTANCE.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix(" chunks")
                .visibleWhen(() -> AetherConfig.PERFORMANCE_LIMIT_CHUNK_DISTANCE.get()));
        performanceMode.add(new ToggleSetting("Disable Block Breaking Particles",
                () -> AetherConfig.PERFORMANCE_DISABLE_PARTICLES.get(),
                v -> {
                    AetherConfig.PERFORMANCE_DISABLE_PARTICLES.set(v);
                    AetherConfig.save();
                }));
        groups.add(performanceMode);

        return MainGUIRegistry.subTab(
                "Miscellaneous",
                "Miscellaneous settings",
                groups);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
