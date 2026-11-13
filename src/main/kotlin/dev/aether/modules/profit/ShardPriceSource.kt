package dev.aether.modules.profit

// Market side used when valuing pest shards from the SkyCofl snapshot.
enum class ShardPriceSource(val label: String) {
    INSTA_SELL("Insta Sell"),
    BUY_OFFER("Buy Offer");

    companion object {
        @JvmStatic
        fun fromConfig(value: String?): ShardPriceSource =
            try {
                valueOf(value ?: "")
            } catch (_: IllegalArgumentException) {
                INSTA_SELL
            }
    }
}
