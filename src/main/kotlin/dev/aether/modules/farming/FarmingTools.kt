package dev.aether.modules.farming

import net.minecraft.world.item.ItemStack
import java.util.Locale

class FarmingTools private constructor() {
    companion object {
        private val NAME_KEYWORDS: List<String> = listOf(
            "hoe", "dicer", "knife", "chopper", "cutter", "sickle", "shovel", "axe"
        )

        @JvmStatic fun nameKeywords(): List<String> = NAME_KEYWORDS

        @JvmStatic fun isFarmingTool(stack: ItemStack?): Boolean {
            if (stack == null || stack.isEmpty) return false
            return isFarmingToolName(stack.hoverName.string)
        }

        @JvmStatic fun isFarmingToolName(name: String?): Boolean {
            if (name == null || name.isBlank()) return false
            val normalizedName = name.lowercase(Locale.ROOT)
            for (keyword in NAME_KEYWORDS) {
                if (normalizedName.contains(keyword)) return true
            }
            return false
        }
    }
}
