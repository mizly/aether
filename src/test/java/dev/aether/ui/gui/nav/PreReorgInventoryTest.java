package dev.aether.ui.gui.nav;

import dev.aether.config.AetherConfig;
import dev.aether.config.FarmType;
import dev.aether.config.RewarpPointPairs;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

// the no-loss proof for the reorganisation: every subtab, group and setting the registry had before it,
// keyed by (raw name, type) with a count, under the default config and under a config that grows the
// dynamic groups (custom farm type with one waypoint, two rewarp pairs, two pet trackers)
class PreReorgInventoryTest {
    // (raw name, type) the reorganisation removes on purpose, with the count it removes
    private static final Map<String, Integer> REMOVED = Map.of();
    // old (raw name, type) to new raw name, for renames
    private static final Map<String, String> RENAMED = Map.of();

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @Test
    void nothingRegisteredBeforeTheReorganisationIsLostWithTheDefaultConfig() throws IOException {
        compare("pre-reorg-default.txt", inventory());
    }

    @Test
    void nothingIsLostWithTheDynamicGroupsGrown() throws IOException {
        String farmType = AetherConfig.FARM_TYPE.get();
        List<String> pairs = AetherConfig.REWARP_POINT_PAIRS.get();
        List<String> pets = AetherConfig.PET_TRACKER_LIST.get();
        try {
            AetherConfig.FARM_TYPE.set(FarmType.CUSTOM.name());
            AetherConfig.REWARP_POINT_PAIRS.set(List.of());
            RewarpPointPairs.add();
            RewarpPointPairs.add();
            AetherConfig.PET_TRACKER_LIST.set(List.of("Rose Dragon:200:650000000:1250000000:LEGENDARY",
                    "Elephant:100:1000000:5000000:EPIC"));
            compare("pre-reorg-grown.txt", inventory());
        } finally {
            AetherConfig.FARM_TYPE.set(farmType);
            AetherConfig.REWARP_POINT_PAIRS.set(pairs);
            AetherConfig.PET_TRACKER_LIST.set(pets);
            MainGUIRegistry.invalidate();
            MainGUIRegistry.refresh();
        }
    }

    // free-text plot fields became plot pickers; same settings, new type
    private static final Set<String> PLOT_PICKERS = Set.of("AOTV Roof Plots", "Ballsack Shredder Plots",
            "Drop at Plot TP", "Leave One Pest Plots", "Pest Traps Plot", "Plot Number", "Plots");

    private static Map<String, Integer> inventory() {
        MainGUIRegistry.invalidate();
        MainGUIRegistry.refresh();
        assertEquals(List.of(), MainGUIRegistry.lastFailures().stream().map(f -> f.provider()).toList());
        MainGUIRegistry.Snapshot snapshot = MainGUIRegistry.snapshot();
        List<ModulesTab.SubTab> tabs = new ArrayList<>(snapshot.modules());
        tabs.addAll(snapshot.colors());
        tabs.addAll(snapshot.keybinds());
        tabs.addAll(snapshot.settings());
        Map<String, Integer> counts = new TreeMap<>();
        for (ModulesTab.SubTab tab : tabs) {
            counts.merge(tab.rawName() + "\tSUBTAB", 1, Integer::sum);
            for (SettingGroup group : tab.groups()) {
                counts.merge(group.getRawName() + "\tGROUP", 1, Integer::sum);
                for (Setting setting : group.getSettings()) {
                    counts.merge(setting.getRawName() + "\t" + setting.getType(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static void compare(String fixture, Map<String, Integer> now) throws IOException {
        Map<String, Integer> before = new TreeMap<>();
        try (InputStream in = PreReorgInventoryTest.class.getResourceAsStream(fixture)) {
            if (in == null) {
                Path out = Path.of("build", fixture);
                Files.createDirectories(out.getParent());
                StringBuilder text = new StringBuilder();
                now.forEach((key, count) -> text.append(key).append('\t').append(count).append('\n'));
                Files.writeString(out, text.toString());
                fail("no fixture " + fixture + "; wrote the current inventory to " + out.toAbsolutePath());
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.lastIndexOf('\t');
                before.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
            }
        }
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            String key = entry.getKey();
            String type = key.substring(key.lastIndexOf('\t'));
            String current = RENAMED.containsKey(key) ? RENAMED.get(key) + type : key;
            if (PLOT_PICKERS.contains(key.substring(0, key.lastIndexOf('\t')))) {
                current = key.substring(0, key.lastIndexOf('\t')) + "\tPLOT";
            }
            int expected = entry.getValue() - REMOVED.getOrDefault(key, 0);
            int found = now.getOrDefault(current, 0);
            if (found < expected) {
                lost.add(key.replace('\t', ' ') + ": " + expected + " before, " + found + " now");
            }
        }
        assertEquals(List.of(), lost);
        assertTrue(Set.copyOf(before.keySet()).containsAll(REMOVED.keySet()), "removals name real entries");
    }
}
