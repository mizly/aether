package dev.aether.modules.profit

enum class ProfitPriceSource(val label: String) {
    BAZAAR("Bazaar"),
    NPC("NPC");

    companion object {
        @JvmStatic
        fun fromConfig(value: String?): ProfitPriceSource =
            try {
                valueOf(value ?: "")
            } catch (_: Exception) {
                BAZAAR
            }
    }
}
