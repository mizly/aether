package dev.aether.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class RewarpPointPairs {
    private RewarpPointPairs() {
    }

    public static List<RewarpPointPair> get() {
        List<String> entries = AetherConfig.REWARP_POINT_PAIRS.get();
        if (entries.isEmpty() || shouldUseLegacyEntry(entries)) {
            return List.of(RewarpPointPair.fromLegacyConfig());
        }

        List<RewarpPointPair> pairs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            pairs.add(new RewarpPointPair(entries.get(i), i));
        }
        return pairs;
    }

    private static boolean shouldUseLegacyEntry(List<String> entries) {
        if (entries.size() != 1 || !RewarpPointPair.defaultConfig(0).equals(entries.get(0))) {
            return false;
        }

        return hasNonZeroLegacyCoordinate(AetherConfig.REWARP_START_X.get(), AetherConfig.REWARP_START_Y.get(), AetherConfig.REWARP_START_Z.get())
                || hasNonZeroLegacyCoordinate(AetherConfig.REWARP_END_X.get(), AetherConfig.REWARP_END_Y.get(), AetherConfig.REWARP_END_Z.get());
    }

    private static boolean hasNonZeroLegacyCoordinate(double x, double y, double z) {
        return Math.abs(x) > 0.0001 || Math.abs(y) > 0.0001 || Math.abs(z) > 0.0001;
    }

    public static RewarpPointPair get(int index) {
        List<RewarpPointPair> pairs = get();
        if (pairs.isEmpty()) {
            return RewarpPointPair.defaultPair(index);
        }
        int safeIndex = Math.max(0, Math.min(index, pairs.size() - 1));
        return pairs.get(safeIndex);
    }

    public static void update(int index, Consumer<RewarpPointPair> updater) {
        List<String> entries = new ArrayList<>(AetherConfig.REWARP_POINT_PAIRS.get());
        if (shouldUseLegacyEntry(entries)) {
            entries.set(0, RewarpPointPair.fromLegacyConfig().toString());
        }
        while (entries.size() <= index) {
            entries.add(RewarpPointPair.defaultConfig(entries.size()));
        }

        RewarpPointPair pair = new RewarpPointPair(entries.get(index), index);
        updater.accept(pair);
        entries.set(index, pair.toString());
        AetherConfig.REWARP_POINT_PAIRS.set(entries);
        AetherConfig.save();
    }

    public static void add() {
        List<String> entries = new ArrayList<>(AetherConfig.REWARP_POINT_PAIRS.get());
        if (shouldUseLegacyEntry(entries)) {
            entries.set(0, RewarpPointPair.fromLegacyConfig().toString());
        }
        entries.add(RewarpPointPair.defaultConfig(entries.size()));
        AetherConfig.REWARP_POINT_PAIRS.set(entries);
        AetherConfig.save();
    }

    public static void remove(int index) {
        List<String> entries = new ArrayList<>(AetherConfig.REWARP_POINT_PAIRS.get());
        if (entries.size() <= 1 || index < 0 || index >= entries.size()) {
            return;
        }
        entries.remove(index);
        AetherConfig.REWARP_POINT_PAIRS.set(entries);
        AetherConfig.save();
    }
}
