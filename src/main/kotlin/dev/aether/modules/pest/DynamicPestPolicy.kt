package dev.aether.modules.pest

import java.util.ArrayList

internal class DynamicPestPolicy private constructor() {
    companion object {
        @JvmStatic
        fun resolve(mode: Int, feastActive: Boolean, feastCrops: List<String>?, feastPriority: List<String>?, contestCrops: List<String>?, contestPriority: List<String>?): String? {
            val contestActive = contestCrops != null && contestCrops.isNotEmpty()
            return when (mode) {
                0 -> if (feastActive) highestPriority(feastCrops, feastPriority) else null
                1 -> if (contestActive) highestPriority(contestCrops, contestPriority) else null
                2 -> {
                    val contestCrop = if (contestActive) highestPriority(contestCrops, contestPriority) else null
                    contestCrop ?: if (feastActive) highestPriority(feastCrops, feastPriority) else null
                }
                else -> null
            }
        }

        @JvmStatic
        fun parseContestCrops(tabLines: List<String>): List<String> {
            val crops = ArrayList<String>()
            for (raw in tabLines) {
                val line = raw.trim()
                if (!line.startsWith("\u25CB ") && !line.startsWith("\u2618 ")) continue
                val crop = normalizeCrop(line.substring(2).trim())
                if (PestCropData.BY_CROP.containsKey(crop)) crops.add(crop)
            }
            return java.util.List.copyOf(crops)
        }

        @JvmStatic
        fun normalizeCrop(crop: String): String = when (crop) {
            "Melon" -> "Melon Slice"
            "Mushroom" -> "Mushrooms"
            else -> crop
        }

        private fun highestPriority(activeCrops: List<String>?, priority: List<String>?): String? {
            if (activeCrops == null || activeCrops.isEmpty()) return null
            if (priority != null) for (crop in priority) if (activeCrops.contains(crop)) return crop
            return activeCrops.first()
        }
    }
}
