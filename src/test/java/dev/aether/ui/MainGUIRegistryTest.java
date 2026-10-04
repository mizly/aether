package dev.aether.ui;

import dev.aether.config.ConfigProfileManager;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.settings.ModulesTab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MainGUIRegistryTest {
    // unregistered since 3b7739f; the reorganisation deletes it, then this list goes
    private static final Set<String> KNOWN_UNREGISTERED =
            Set.of("dev.aether.ui.providers.settings.RemoteControlSettingsRegistryProvider");

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @Test
    void buildsWithoutAMinecraftClientAndEveryProviderRegisters() {
        MainGUIRegistry.invalidate();
        MainGUIRegistry.refresh();
        assertEquals(List.of(), MainGUIRegistry.lastFailures().stream()
                .map(failure -> failure.provider() + ": " + failure.error()).toList());
        MainGUIRegistry.Snapshot snapshot = MainGUIRegistry.snapshot();
        assertEquals(List.of("farming", "fishing", "other", "failsafes", "failsafes_farming", "failsafes_fishing", "visuals"),
                snapshot.sections().stream().map(MainGUIRegistry.ModuleSection::id).toList());
        assertEquals(2, snapshot.colors().size());
        assertEquals(1, snapshot.keybinds().size());
        assertEquals(4, snapshot.settings().size());
        assertTrue(snapshot.modules().stream().map(ModulesTab.SubTab::rawName).toList().contains("Pest Manager"));
        assertTrue(snapshot.keybinds().getFirst().groups().getFirst().getSettings().size() >= 11);
    }

    @Test
    void everyRebuildPublishesANewGenerationAndTheOldListsFollow() {
        MainGUIRegistry.refresh();
        MainGUIRegistry.Snapshot before = MainGUIRegistry.snapshot();
        MainGUIRegistry.refresh();
        assertSame(before, MainGUIRegistry.snapshot(), "refresh without invalidate keeps the build");
        MainGUIRegistry.invalidate();
        MainGUIRegistry.refresh();
        MainGUIRegistry.Snapshot after = MainGUIRegistry.snapshot();
        assertEquals(before.generation() + 1, after.generation());
        assertEquals(after.generation(), MainGUIRegistry.generation());
        assertNotSame(before.modules().getFirst(), after.modules().getFirst());
        assertEquals(after.modules(), MainGUIRegistry.MODULE_SUBTABS);
        assertEquals(after.sections(), MainGUIRegistry.MODULE_SECTIONS);
        assertThrows(UnsupportedOperationException.class, () -> after.sections().clear());
    }

    @Test
    void loadingAConfigProfilePublishesANewGeneration() {
        MainGUIRegistry.refresh();
        ConfigProfileManager.save("registry-generation-test");
        long before = MainGUIRegistry.generation();
        try {
            assertTrue(ConfigProfileManager.load("registry-generation-test"));
            assertTrue(MainGUIRegistry.generation() > before, "rewarp pairs, farm type groups and pets come from config");
        } finally {
            ConfigProfileManager.delete("registry-generation-test");
        }
    }

    @Test
    void everyConcreteProviderIsListedInTheServicesFile() throws IOException, URISyntaxException {
        Set<String> listed;
        try (InputStream in = MainGUIRegistry.class.getResourceAsStream("/META-INF/services/" + MainGUIRegistryProvider.class.getName())) {
            assertNotNull(in);
            listed = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        URL providers = MainGUIRegistry.class.getResource("/dev/aether/ui/providers");
        assertNotNull(providers);
        Path root = Path.of(providers.toURI());
        Set<String> concrete = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$")).toList()) {
                String name = "dev.aether.ui.providers." + root.relativize(file).toString()
                        .replace(file.getFileSystem().getSeparator(), ".").replaceAll("\\.class$", "");
                try {
                    Class<?> type = Class.forName(name, false, MainGUIRegistry.class.getClassLoader());
                    if (MainGUIRegistryProvider.class.isAssignableFrom(type) && !type.isInterface()
                            && !Modifier.isAbstract(type.getModifiers())) {
                        concrete.add(name);
                    }
                } catch (ClassNotFoundException e) {
                    fail(e);
                }
            }
        }
        concrete.removeAll(KNOWN_UNREGISTERED);
        assertTrue(concrete.size() > 40, "found " + concrete.size() + " providers");
        assertEquals(concrete, listed);
    }
}
