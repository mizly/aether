package dev.aether.config;

import dev.aether.config.entries.ConfigEntry;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigKeysTest {
    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-config-keys-test"));
    }

    @Test
    void everyConfigEntryHasItsOwnKey() throws Exception {
        Map<String, String> owners = new HashMap<>();
        Set<ConfigEntry<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> duplicates = new ArrayList<>();
        for (Field field : AetherConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !ConfigEntry.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            ConfigEntry<?> entry = (ConfigEntry<?>) field.get(null);
            assertNotNull(entry, field.getName());
            // two fields may alias one entry; only distinct entries sharing a key collide in the json
            if (!seen.add(entry)) continue;
            String owner = owners.putIfAbsent(entry.getKey(), field.getName());
            if (owner != null) duplicates.add(entry.getKey() + " (" + owner + ", " + field.getName() + ")");
        }
        assertFalse(owners.isEmpty());
        assertEquals(List.of(), duplicates);
    }
}
