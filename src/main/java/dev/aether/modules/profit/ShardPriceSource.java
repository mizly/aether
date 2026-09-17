package dev.aether.modules.profit;

// market side used when valuing pest shards from the skycofl snapshot
public enum ShardPriceSource {
    INSTA_SELL("Insta Sell"),
    BUY_OFFER("Buy Offer");

    private final String label;

    ShardPriceSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ShardPriceSource fromConfig(String value) {
        try {
            return value == null ? INSTA_SELL : valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return INSTA_SELL;
        }
    }
}
