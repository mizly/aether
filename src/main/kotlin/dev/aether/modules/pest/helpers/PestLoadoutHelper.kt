package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorInventory
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale

internal object PestLoadoutHelper {
    @JvmStatic fun findVacuumHotbarSlot(client: Minecraft): Int {
        val player = client.player ?: return -1
        val current = player.mainHandItem
        if (!current.isEmpty && current.hoverName.string.lowercase(Locale.ROOT).contains("vacuum")) return (player.inventory as AccessorInventory).getSelected()
        for (i in 0 until 9) {
            val stack = player.inventory.getItem(i)
            if (!stack.isEmpty && stack.hoverName.string.lowercase(Locale.ROOT).contains("vacuum")) return i
        }
        return -1
    }

    @JvmStatic fun findAutomaticVacuumSlots(client: Minecraft): IntArray {
        val player = client.player ?: return intArrayOf(-1, -1)
        var lowestSlot = -1; var highestSlot = -1
        var lowestQuality = Int.MAX_VALUE; var highestQuality = Int.MIN_VALUE
        for (slot in 0 until 9) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !isVacuum(stack)) continue
            val quality = vacuumQuality(stack)
            if (quality < lowestQuality) { lowestQuality = quality; lowestSlot = slot }
            if (quality > highestQuality) { highestQuality = quality; highestSlot = slot }
        }
        if (lowestSlot < 0) return intArrayOf(-1, -1)
        return intArrayOf(lowestSlot, if (highestSlot < 0) lowestSlot else highestSlot)
    }

    @JvmStatic fun isVacuum(stack: ItemStack): Boolean = !stack.isEmpty && stack.hoverName.string.lowercase(Locale.ROOT).contains("vacuum")
    private fun vacuumQuality(stack: ItemStack): Int { val profile = vacuumProfile(stack); return (profile.range() * 100).toInt() + profile.rarity() }

    @JvmStatic fun findLassoHotbarSlot(client: Minecraft): Int {
        val player = client.player ?: return -1
        if (isLasso(player.mainHandItem)) return (player.inventory as AccessorInventory).getSelected()
        for (i in 0 until 9) if (isLasso(player.inventory.getItem(i))) return i
        return -1
    }

    private fun isLasso(stack: ItemStack): Boolean = !stack.isEmpty && stack.hoverName.string.lowercase(Locale.ROOT).contains("lasso")

    @JvmStatic fun findAotvHotbarSlot(client: Minecraft): Int {
        val player = client.player ?: return -1
        fun matches(stack: ItemStack): Boolean {
            if (stack.isEmpty) return false
            val name = stack.hoverName.string
            return name.contains("Aspect of the Void") || name.contains("Aspect of the End")
        }
        if (matches(player.mainHandItem)) return (player.inventory as AccessorInventory).getSelected()
        for (i in 0 until 9) if (matches(player.inventory.getItem(i))) return i
        return -1
    }

    @JvmStatic fun detectVacuumRange(client: Minecraft, slot: Int): Float {
        val player = client.player ?: return 4.5f
        if (slot < 0 || slot >= 9) return 4.5f
        val stack = player.inventory.getItem(slot)
        if (stack.isEmpty) return 4.5f
        return vacuumProfile(stack).effectiveRange(AetherConfig.RESPECT_VACUUM_TRUE_RANGE.get())
    }

    private fun vacuumProfile(stack: ItemStack): VacuumProfile {
        val lore = stack.get(DataComponents.LORE)
        val custom = stack.get(DataComponents.CUSTOM_DATA)
        val recombobulated = custom != null && custom.copyTag().getInt("rarity_upgrades").orElse(0) > 0
        return VacuumProfile.parse(stack.hoverName.string, lore?.lines()?.map(Component::getString) ?: emptyList(), recombobulated)
    }
}
