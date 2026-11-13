package dev.aether.modules.profit

import java.util.Locale

object ProfitTrackingRules {
    @JvmStatic
    fun parsePurseLine(line: String?): Long {
        if (line == null) return -1L
        var labelEnd = labelEnd(line, "Purse:")
        if (labelEnd < 0) labelEnd = labelEnd(line, "Piggy:")
        if (labelEnd < 0) return -1L

        var value = line.substring(labelEnd).trim()
        val space = value.indexOf(' ')
        if (space >= 0) value = value.substring(0, space)
        value = value.replace(",", "")
        val dot = value.indexOf('.')
        if (dot >= 0) value = value.substring(0, dot)
        if (value.isEmpty() || value.any { !it.isDigit() }) return -1L
        return value.toLongOrNull() ?: -1L
    }

    private fun labelEnd(line: String, label: String): Int {
        val index = line.indexOf(label)
        return if (index < 0) -1 else index + label.length
    }

    @JvmStatic
    fun cropForToolId(skyblockId: String?): String? {
        if (skyblockId.isNullOrBlank()) return null
        val id = skyblockId.uppercase(Locale.ROOT)
        return when {
            id.startsWith("THEORETICAL_HOE_WHEAT") -> "Wheat"
            id.startsWith("THEORETICAL_HOE_CARROT") -> "Carrot"
            id.startsWith("THEORETICAL_HOE_POTATO") -> "Potato"
            id.startsWith("THEORETICAL_HOE_WARTS") -> "Nether Wart"
            id.startsWith("THEORETICAL_HOE_CANE") -> "Sugar Cane"
            id.startsWith("PUMPKIN_DICER") -> "Pumpkin"
            id.startsWith("MELON_DICER") -> "Melon Slice"
            id.startsWith("CACTUS_KNIFE") -> "Cactus"
            id.startsWith("COCO_CHOPPER") -> "Cocoa Beans"
            else -> null
        }
    }

    @JvmStatic
    fun isMushroomTool(skyblockId: String?): Boolean =
        skyblockId != null && skyblockId.uppercase(Locale.ROOT).startsWith("FUNGI_CUTTER")

    @JvmStatic
    fun isSystemMatch(text: String?, matchStart: Int): Boolean {
        if (text == null || matchStart < 0) return false
        if (matchStart == 0) return true
        return text.lastIndexOf(':', matchStart - 1) < 0
    }
}
