package dev.aether.modules.pest.helpers

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale
import java.util.regex.Pattern

internal class SprayonatorItem private constructor() {
    companion object {
        private val IDS = setOf("SPRAYONATOR", "JUICY_SPRAYONATOR", "SALTY_SPRAYONATOR")
        private val NAMES = setOf("sprayonator", "juicy sprayonator", "salty sprayonator")
        private val FORMATTING = Pattern.compile("§.")
        private val MATERIAL = Pattern.compile("(?i)^selected\\s+material\\s*:\\s*(.+)$")

        @JvmStatic fun matches(stack: ItemStack): Boolean {
            if (stack.isEmpty) return false
            val custom = stack.get(DataComponents.CUSTOM_DATA)
            var id = ""
            if (custom != null) {
                val tag = custom.copyTag()
                id = tag.getString("id").orElseGet {
                    tag.getCompound("ExtraAttributes").flatMap { attributes -> attributes.getString("id") }.orElse("")
                }
            }
            return matches(id, stack.hoverName.string)
        }

        @JvmStatic fun matches(id: String?, name: String): Boolean {
            if (id != null && id.isNotBlank()) return IDS.contains(id.uppercase(Locale.ROOT))
            return NAMES.contains(clean(name).lowercase(Locale.ROOT))
        }

        @JvmStatic fun selectedMaterial(stack: ItemStack): String? {
            if (!matches(stack)) return null
            val lore = stack.get(DataComponents.LORE)
            return selectedMaterial(lore?.lines()?.map(Component::getString) ?: emptyList())
        }

        @JvmStatic fun selectedMaterial(lore: List<String>): String? {
            for (line in lore) {
                val matcher = MATERIAL.matcher(clean(line))
                if (matcher.matches()) {
                    val material = matcher.group(1).trim()
                    return if (material.equals("none", ignoreCase = true)) null else material
                }
            }
            return null
        }

        private fun clean(text: String?): String = FORMATTING.matcher(text ?: "").replaceAll("").replace('\u00a0', ' ').trim()
    }
}
