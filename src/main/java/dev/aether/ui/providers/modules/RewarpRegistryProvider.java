package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.config.RewarpPointPair;
import dev.aether.config.RewarpPointPairs;
import dev.aether.config.RewarpMode;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class RewarpRegistryProvider extends AbstractModulesRegistryProvider {
    private static final List<SettingGroup> REWARP_GROUPS = new ArrayList<>();
    private static final List<String> REWARP_MODE_OPTIONS = List.of(
            RewarpMode.PLOT_TP.displayName(),
            RewarpMode.FLY.displayName(),
            RewarpMode.WARP_GARDEN.displayName());

    public RewarpRegistryProvider() {
        super(1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        return MainGUIRegistry.toggleSubTab(
                "Rewarp",
                "Brings you back to the start of your farm after reaching the end",
                () -> AetherConfig.ENABLE_REWARP.get(),
                v -> {
                    AetherConfig.ENABLE_REWARP.set(v);
                    AetherConfig.save();
                },
                buildGroups());
    }

    private static List<SettingGroup> buildGroups() {
        REWARP_GROUPS.clear();
        REWARP_GROUPS.add(SettingGroup.alwaysOn(
                "Rewarp Delay",
                "Timing controls for rewarp")
                .add(FarmingSettingsFactory.rewarpDelaySetting()));
        int count = Math.max(1, RewarpPointPairs.get().size());
        for (int i = 0; i < count; i++) {
            REWARP_GROUPS.add(buildPointGroup(i));
        }
        return REWARP_GROUPS;
    }

    private static void addRewarpPair() {
        RewarpPointPairs.add();
        buildGroups();
    }

    private static void removeRewarpPair(int index) {
        RewarpPointPairs.remove(index);
        buildGroups();
    }

    private static SettingGroup buildPointGroup(int index) {
        RewarpPointPair pair = RewarpPointPairs.get(index);
        SettingGroup group = SettingGroup.alwaysOn(
                pair.displayName(),
                "Configure a named rewarp start and end pair");

        group.add(new TextSetting("Rewarp Name", "e.g. Wheat",
                () -> RewarpPointPairs.get(index).displayName(),
                v -> RewarpPointPairs.update(index, p -> p.name = sanitizeName(v, index))));
        group.add(new DropdownSetting("Rewarp Mode",
                REWARP_MODE_OPTIONS,
                () -> RewarpPointPairs.get(index).rewarpMode.ordinal(),
                v -> RewarpPointPairs.update(index, p -> p.rewarpMode = RewarpMode.values()[v])));
        group.add(new TextSetting("Plot Number", "e.g. 5",
                () -> RewarpPointPairs.get(index).plotTpNumber,
                v -> RewarpPointPairs.update(index, p -> p.plotTpNumber = sanitizePlotNumber(v)))
                .visibleWhen(() -> RewarpPointPairs.get(index).rewarpMode == RewarpMode.PLOT_TP));
        group.add(new ToggleSetting("Hold W Until Wall",
                () -> RewarpPointPairs.get(index).holdWUntilWall,
                v -> RewarpPointPairs.update(index, p -> p.holdWUntilWall = v)));
        group.add(new ToggleSetting("AOTV/AOTE Align",
                () -> RewarpPointPairs.get(index).aotvAlign,
                v -> RewarpPointPairs.update(index, p -> p.aotvAlign = v))
                .visibleWhen(() -> RewarpPointPairs.get(index).rewarpMode == RewarpMode.FLY));
        group.add(buildRewarpStartSetting(index));
        group.add(buildRewarpEndSetting(index));
        group.add(new ActionSetting("Add Rewarp", RewarpRegistryProvider::addRewarpPair));
        group.add(new ActionSetting("Remove Rewarp", () -> removeRewarpPair(index))
                .visibleWhen(() -> RewarpPointPairs.get().size() > 1));
        return group;
    }

    private static String sanitizeName(String value, int index) {
        if (value == null || value.trim().isEmpty()) {
            return "Rewarp " + (index + 1);
        }
        return value.trim();
    }

    private static String sanitizePlotNumber(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "0";
        }
        return value.trim().replace(":", "");
    }

    private static PositionSetting buildRewarpStartSetting(int index) {
        return new PositionSetting("Rewarp Start",
                () -> RewarpPointPairs.get(index).startX,
                v -> RewarpPointPairs.update(index, pair -> pair.startX = v),
                () -> RewarpPointPairs.get(index).startY,
                v -> RewarpPointPairs.update(index, pair -> pair.startY = v),
                () -> RewarpPointPairs.get(index).startZ,
                v -> RewarpPointPairs.update(index, pair -> pair.startZ = v),
                () -> RewarpPointPairs.get(index).highlightStart,
                v -> RewarpPointPairs.update(index, pair -> pair.highlightStart = v),
                () -> captureStart(index));
    }

    private static PositionSetting buildRewarpEndSetting(int index) {
        return new PositionSetting("Rewarp End",
                () -> RewarpPointPairs.get(index).endX,
                v -> RewarpPointPairs.update(index, pair -> pair.endX = v),
                () -> RewarpPointPairs.get(index).endY,
                v -> RewarpPointPairs.update(index, pair -> pair.endY = v),
                () -> RewarpPointPairs.get(index).endZ,
                v -> RewarpPointPairs.update(index, pair -> pair.endZ = v),
                () -> RewarpPointPairs.get(index).highlightEnd,
                v -> RewarpPointPairs.update(index, pair -> pair.highlightEnd = v),
                () -> captureEnd(index));
    }

    private static void captureStart(int index) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        RewarpPointPairs.update(index, pair -> {
            pair.startX = player.getX();
            pair.startY = player.getY();
            pair.startZ = player.getZ();
            pair.startSet = true;
        });
        RewarpPointPair pair = RewarpPointPairs.get(index);
        NotificationManager.success(AetherLang.localize("Rewarp Start Set"),
                String.format("%s - X: %.1f, Y: %.1f, Z: %.1f",
                        pair.displayName(), pair.startX, pair.startY, pair.startZ));
    }

    private static void captureEnd(int index) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        RewarpPointPairs.update(index, pair -> {
            pair.endX = player.getX();
            pair.endY = player.getY();
            pair.endZ = player.getZ();
            pair.endSet = true;
        });
        RewarpPointPair pair = RewarpPointPairs.get(index);
        NotificationManager.success(AetherLang.localize("Rewarp End Set"),
                String.format("%s - X: %.1f, Y: %.1f, Z: %.1f",
                        pair.displayName(), pair.endX, pair.endY, pair.endZ));
    }
}
