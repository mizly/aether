package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import java.util.Locale
import java.util.regex.Pattern

internal object PestHuntingPolicy {
    @JvmField
    val PEST_TYPES: List<String> = listOf(
            "Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm",
            "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis"
        )
        private const val NAME_MARKER_SEARCH_SIZE = 5.0

        @JvmStatic
        fun shouldLasso(client: Minecraft?, target: Entity?): Boolean {
            if (!AetherConfig.PEST_HUNTING.get()) return false
            val typeIndex = findPestTypeIndex(client, target)
            return typeIndex < 0 || (AetherConfig.PEST_HUNTING_VACUUM_PEST_MASK.get() and (1 shl typeIndex)) == 0
        }

        @JvmStatic
        fun findPestTypeIndex(client: Minecraft?, target: Entity?): Int {
            if (target == null) return -1
            val direct = findPestTypeIndex(target.displayName.string)
            if (direct >= 0 || client?.level == null) return direct
            val searchBox = AABB.ofSize(target.position().add(0.0, 1.5, 0.0),
                NAME_MARKER_SEARCH_SIZE, NAME_MARKER_SEARCH_SIZE, NAME_MARKER_SEARCH_SIZE)
            var nearestType = -1
            var nearestDistance = Double.MAX_VALUE
            val level = client.level ?: return direct
            for (marker in level.getEntitiesOfClass(ArmorStand::class.java, searchBox)) {
                val name: Component? = marker.customName
                if (name == null || !isNearTarget(marker, target)) continue
                val markerType = findPestTypeIndex(name.string)
                val distance = marker.distanceToSqr(target)
                if (markerType >= 0 && distance < nearestDistance) {
                    nearestType = markerType
                    nearestDistance = distance
                }
            }
            return nearestType
        }

        @JvmStatic
        fun findPestTypeIndex(text: String?): Int {
            if (text.isNullOrBlank()) return -1
            val plain = PestHuntingController.stripFormatting(text).lowercase(Locale.ROOT)
            for (i in PEST_TYPES.indices) {
                val type = PEST_TYPES[i].lowercase(Locale.ROOT)
                if (Pattern.compile("(?<![a-z])" + Pattern.quote(type) + "(?![a-z])").matcher(plain).find()) return i
            }
            return -1
        }

        private fun isNearTarget(marker: ArmorStand, target: Entity): Boolean {
            val dx = marker.x - target.x
            val dz = marker.z - target.z
            val dy = marker.y - target.y
            return dx * dx + dz * dz <= 1.5625 && dy >= -1.0 && dy <= 5.0
        }
}
