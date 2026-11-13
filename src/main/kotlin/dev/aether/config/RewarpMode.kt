package dev.aether.config

enum class RewarpMode(private val displayName: String) {
    PLOT_TP("/plottp"),
    FLY("Fly"),
    WARP_GARDEN("/warp garden");

    fun displayName(): String = displayName

    fun usesCommand(): Boolean = this == PLOT_TP || this == WARP_GARDEN

    companion object {
        @JvmStatic
        fun fromConfig(value: String?): RewarpMode {
            if (value.isNullOrBlank()) return FLY
            val normalized = value.trim()
            return entries.firstOrNull {
                it.name.equals(normalized, ignoreCase = true) || it.displayName.equals(normalized, ignoreCase = true)
            } ?: FLY
        }
    }
}
