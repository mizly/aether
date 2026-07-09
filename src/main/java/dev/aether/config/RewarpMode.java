package dev.aether.config;

public enum RewarpMode {
    PLOT_TP("/plottp"),
    FLY("Fly"),
    WARP_GARDEN("/warp garden");

    private final String displayName;

    RewarpMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean usesCommand() {
        return this == PLOT_TP || this == WARP_GARDEN;
    }

    public static RewarpMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return FLY;
        }

        String normalized = value.trim();
        for (RewarpMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)
                    || mode.displayName.equalsIgnoreCase(normalized)) {
                return mode;
            }
        }

        return FLY;
    }
}
