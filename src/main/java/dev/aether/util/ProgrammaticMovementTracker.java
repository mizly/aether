package dev.aether.util;

import net.minecraft.client.KeyMapping;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ProgrammaticMovementTracker {
    private static final Map<KeyMapping, Boolean> KEY_STATES =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private ProgrammaticMovementTracker() {
    }

    public static void set(KeyMapping mapping, boolean down) {
        if (mapping == null) {
            return;
        }
        if (down) {
            KEY_STATES.put(mapping, true);
            return;
        }
        KEY_STATES.remove(mapping);
    }

    public static boolean isDown(KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }
        return Boolean.TRUE.equals(KEY_STATES.get(mapping));
    }

    public static void clear(KeyMapping mapping) {
        if (mapping == null) {
            return;
        }
        KEY_STATES.remove(mapping);
    }
}
