package dev.aether.modules.failsafe;

import java.util.Locale;

public enum FailsafeAction {
    STOP,
    IGNORE,
    CUSTOM;

    public static FailsafeAction fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return STOP;
        }

        try {
            return FailsafeAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STOP;
        }
    }
}
