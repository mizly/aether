package dev.aether.util

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.ArrayList
import java.util.Comparator

class EntityUtils private constructor() {
    companion object {
        @JvmStatic
        fun findEntity(client: Minecraft, nameSubstring: String): Entity? {
            if (client.level == null || client.player == null) return null
            val target = TablistUtils.stripColors(nameSubstring).lowercase().trim()
            if (target.isEmpty()) return null
            var closest: Entity? = null
            var closestDist = Double.MAX_VALUE
            var bestMatchScore = -1
            val player = client.player ?: return null
            for (entity in client.level!!.entitiesForRendering()) {
                if (entity === player) continue
                val matchScore = getEntityMatchScore(entity, target)
                if (matchScore < 0) continue
                val dist = entity.distanceToSqr(player)
                if (matchScore > bestMatchScore) {
                    bestMatchScore = matchScore
                    closestDist = dist
                    closest = entity
                    continue
                }
                if (matchScore != bestMatchScore) continue
                if (entity !is ArmorStand && closest is ArmorStand) {
                    closestDist = dist
                    closest = entity
                    continue
                }
                if (dist < closestDist) {
                    closestDist = dist
                    closest = entity
                }
            }
            if (closest is ArmorStand) {
                val character = findCharacterNearArmorStand(client, closest)
                if (character != null) return character
            }
            return closest
        }

        @JvmStatic
        fun describeNearbyEntities(client: Minecraft, radius: Double, maxResults: Int): List<String> {
            if (client.level == null || client.player == null) return java.util.List.of()
            val radiusSq = radius * radius
            val player = client.player ?: return emptyList()
            val nearby = ArrayList<Entity>()
            for (entity in client.level!!.entitiesForRendering()) {
                if (entity === player) continue
                if (entity.distanceToSqr(player) <= radiusSq) nearby.add(entity)
            }
            nearby.sortWith(Comparator.comparingDouble { entity -> entity.distanceToSqr(player) })
            val lines = ArrayList<String>()
            val count = Math.min(maxResults, nearby.size)
            for (i in 0 until count) {
                val entity = nearby[i]
                val distance = Math.sqrt(entity.distanceToSqr(player))
                val name = cleanComponent(entity.name)
                val displayName = cleanComponent(entity.displayName)
                val customName = cleanComponent(entity.customName)
                val type = entity.type.toShortString()
                lines.add(String.format("%s d=%.1f type=%s name=\"%s\" display=\"%s\" custom=\"%s\"", entity.javaClass.simpleName, distance, type, name, displayName, customName))
            }
            return lines
        }

        private fun getEntityMatchScore(entity: Entity, target: String): Int {
            var score = scoreName(entity.name, target)
            score = Math.max(score, scoreName(entity.displayName, target))
            score = Math.max(score, scoreName(entity.customName, target))
            return score
        }

        private fun scoreName(component: Component?, target: String): Int {
            if (component == null) return -1
            val clean = TablistUtils.stripColors(component.string).lowercase().trim()
            if (clean.isEmpty()) return -1
            if (clean == target) return 2
            if (clean.contains(target) || target.contains(clean)) return 1
            return -1
        }

        private fun cleanComponent(component: Component?): String {
            if (component == null) return ""
            return TablistUtils.stripColors(component.string).replace('\u00A0', ' ').trim()
        }

        private fun findCharacterNearArmorStand(client: Minecraft, armorStand: Entity): Entity? {
            for (entity in client.level!!.entitiesForRendering()) {
                if (entity === client.player || entity is ArmorStand) continue
                if (entity.distanceToSqr(armorStand) < 4.0) return entity
            }
            return null
        }
    }
}
