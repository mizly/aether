package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.List;

public final class NickHiderVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public NickHiderVisualsRegistryProvider() {
        super(1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.alwaysOn(
                        "Username",
                        "Spoofs your username in chat and overlays")
                .add(new ToggleSetting("Enable Username Spoof",
                        () -> AetherConfig.NICK_HIDER_ENABLED.get(),
                        v -> {
                            AetherConfig.NICK_HIDER_ENABLED.set(v);
                            AetherConfig.save();
                        }))
                .add(new TextSetting("Custom Username", "AetherUser",
                        () -> AetherConfig.CUSTOM_USERNAME.get(),
                        v -> {
                            AetherConfig.CUSTOM_USERNAME.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.NICK_HIDER_ENABLED.get()))
                .add(new ToggleSetting("Hide Skin",
                        () -> AetherConfig.HIDE_SKIN.get(),
                        v -> {
                            AetherConfig.HIDE_SKIN.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.NICK_HIDER_ENABLED.get())));

        groups.add(SettingGroup.of(
                        "Hide Server ID",
                        "Replaces the server identifier shown in tablist and scoreboard",
                        () -> AetherConfig.HIDE_SERVER_ID.get(),
                        v -> {
                            AetherConfig.HIDE_SERVER_ID.set(v);
                            AetherConfig.save();
                        })
                .add(new TextSetting("Custom Server ID", "aether.cat",
                        () -> AetherConfig.CUSTOM_SERVER_ID.get(),
                        v -> {
                            AetherConfig.CUSTOM_SERVER_ID.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.of(
                        "Coop Name Hider",
                        "Obfuscates configured coop names",
                        () -> AetherConfig.COOP_HIDER_ENABLED.get(),
                        v -> {
                            AetherConfig.COOP_HIDER_ENABLED.set(v);
                            AetherConfig.save();
                        })
                .add(new ListSetting("Coop Names", "Add coop name",
                        () -> AetherConfig.COOP_NAMES.get(),
                        v -> {
                            AetherConfig.COOP_NAMES.set(v);
                            AetherConfig.save();
                        })));

        SettingGroup spoofValues = SettingGroup.of(
                "Spoof Values",
                "Customise SkyBlock level and identifiable values",
                () -> AetherConfig.SPOOF_VALUES_ENABLED.get(),
                v -> {
                    AetherConfig.SPOOF_VALUES_ENABLED.set(v);
                    AetherConfig.save();
                });
        spoofValues.add(new ToggleSetting("Custom Skyblock Level",
                () -> AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get(),
                v -> {
                    AetherConfig.CUSTOM_SB_LEVEL_ENABLED.set(v);
                    AetherConfig.save();
                }).visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get()));
        spoofValues.add(new TextSetting("SkyBlock Level Override", "0",
                () -> Integer.toString(AetherConfig.CUSTOM_SB_LEVEL.get()),
                v -> saveIntValue(v, AetherConfig.CUSTOM_SB_LEVEL::set))
                .visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get() && AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get()));
        spoofValues.add(new TextSetting("Purse Offset", "0",
                () -> formatDecimal(AetherConfig.PURSE_OFFSET.get()),
                v -> saveDoubleValue(v, AetherConfig.PURSE_OFFSET::set))
                .visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get()));
        spoofValues.add(new TextSetting("Bits Offset", "0",
                () -> formatDecimal(AetherConfig.BITS_OFFSET.get()),
                v -> saveDoubleValue(v, AetherConfig.BITS_OFFSET::set))
                .visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get()));
        spoofValues.add(new TextSetting("Copper Offset", "0",
                () -> formatDecimal(AetherConfig.COPPER_OFFSET.get()),
                v -> saveDoubleValue(v, AetherConfig.COPPER_OFFSET::set))
                .visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get()));
        spoofValues.add(new TextSetting("Sawdust Offset", "0",
                () -> formatDecimal(AetherConfig.SAWDUST_OFFSET.get()),
                v -> saveDoubleValue(v, AetherConfig.SAWDUST_OFFSET::set))
                .visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get()));
        spoofValues.add(new TextSetting("Farming XP Offset", "0",
                () -> formatDecimal(AetherConfig.FARMING_EXP_OFFSET.get()),
                v -> saveDoubleValue(v, AetherConfig.FARMING_EXP_OFFSET::set))
                .visibleWhen(() -> AetherConfig.SPOOF_VALUES_ENABLED.get()));
        groups.add(spoofValues);

        return MainGUIRegistry.toggleSubTab(
                "Nick Hider",
                "Spoofs names, server IDs, coop names, and identifiable values",
                () -> AetherConfig.NICK_HIDER_MASTER_ENABLED.get(),
                v -> {
                    AetherConfig.NICK_HIDER_MASTER_ENABLED.set(v);
                    AetherConfig.save();
                },
                groups);
    }

    private static void saveIntValue(String value, java.util.function.IntConsumer setter) {
        try {
            setter.accept(Integer.parseInt(value.trim()));
            AetherConfig.save();
        } catch (NumberFormatException ignored) {
        }
    }

    private static void saveDoubleValue(String value, java.util.function.Consumer<Double> setter) {
        try {
            setter.accept(Double.parseDouble(value.trim()));
            AetherConfig.save();
        } catch (NumberFormatException ignored) {
        }
    }

    private static String formatDecimal(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }
}
