package dev.aether.modules.pest.helpers

internal object PestPlotId {
    @JvmStatic fun normalize(plot: String?): String {
        if (plot == null) return ""
        val normalized = plot.trim().lowercase()
        val digits = normalized.replace(Regex("\\D"), "")
        return if (digits.isEmpty()) normalized else digits
    }

    @JvmStatic fun equals(first: String?, second: String?): Boolean = normalize(first) == normalize(second)

    @JvmStatic fun isUsable(plot: String?): Boolean {
        val normalized = normalize(plot)
        return normalized.isNotEmpty() && normalized != "0" && normalized != "unknown"
    }
}
