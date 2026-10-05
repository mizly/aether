package dev.aether.modules.profit;

public enum ProfitPriceSource {
    BAZAAR("Bazaar"),
    NPC("NPC");

    private final String label;

    ProfitPriceSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ProfitPriceSource fromConfig(String value) {
        try {
            return ProfitPriceSource.valueOf(value);
        } catch (Exception ignored) {
            return BAZAAR;
        }
    }
}
