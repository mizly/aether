package dev.aether.modules.failsafe;

public enum FailsafeAction {
    STOP,
    IGNORE,
    CUSTOM;

    public static FailsafeAction fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return STOP;
        }

        try {
            return FailsafeAction.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STOP;
        }
    }
}
