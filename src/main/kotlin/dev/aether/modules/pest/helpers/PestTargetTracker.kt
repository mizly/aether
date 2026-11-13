package dev.aether.modules.pest.helpers

import com.mojang.authlib.properties.Property
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.Vec3
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Comparator
import java.util.Deque
import java.util.LinkedHashMap
import java.util.function.Predicate
import java.util.function.ToDoubleFunction

class PestTargetTracker private constructor() {
    companion object {
        private val PEST_TEXTURE_FRAGMENTS = listOf("70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c", "a24c69f96ce5562221e195c8ef2bfad71ebf7f95f5ae914a484a8d0ec21672674", "6403ba4027a333d8d2fd32ab59d1cfdbaa7d908d80d2381db2a69cbe65450ad8", "9d90e777826a52461368e26d1b2e19bfa1ba582d60248e545f4124d0f731842", "4b24a482a32db1ea78fb98060b0c2fa4a373cbd18a68edddeb7419455a59cda9", "be6baf6431a9daa2ca604d5a3c26e9a761d5952f0817174a4fe0b764616e21ff", "52a9fe05bc663efcd12e56a3ccc5ec035bf577b78708548b6f4ffcf1d30eccfe", "6545c4b34e5b5470be94de100e61f7816f81bc5a11dfdf0eccf890172da5d0a", "a8abb471db0ab78703011997dc8b40798a941f3a4dec3ec61cbeec2af8cffe8", "7a79d0fd677b54530961117ef84adc206e2cc5045c1344d61d776bf8ac2fe1ba", "1e04bb6367caa4e88f5fd0ee80f0745d137a604223dbbc42a16471fdf64bb83", "4ce69e90adf34718f313ec24d6c6135b69b3788c61849844666ccc83ca640c0b16", "254aff4c0b2dce3a672349cc0e99e6f3a9deebe4b3556e84611eca250a7821bf")
        private var cachedTick = Int.MIN_VALUE
        private var cachedLevelId = 0
        private var cachedSnapshot = PestSnapshot.EMPTY

        @JvmStatic fun peekNextQueuedPest(client: Minecraft?, pestTargetQueue: java.util.Deque<Entity>, killedEntities: Collection<Entity>, eligible: Predicate<Entity>): Entity? {
            if (client == null || client.player == null) return null
            return nearestQueuedTarget(pestTargetQueue, Predicate { target -> !isUnavailable(client, target, killedEntities) && eligible.test(target) }, ToDoubleFunction { client.player!!.distanceToSqr(it) })
        }

        @JvmStatic fun getNextQueuedPest(client: Minecraft?, pestTargetQueue: java.util.Deque<Entity>, killedEntities: Collection<Entity>, eligible: Predicate<Entity>): Entity? {
            val next = peekNextQueuedPest(client, pestTargetQueue, killedEntities, eligible)
            if (next != null) pestTargetQueue.remove(next)
            return next
        }

        @JvmStatic fun buildNearestRoute(client: Minecraft?, killedEntities: Collection<Entity>, reservedEntityId: Int, eligible: Predicate<Entity>, currentTarget: Entity?): List<Entity> {
            val player = client?.player ?: return emptyList()
            val remaining = availableTargets(client, killedEntities, eligible)
            remaining.removeIf { it.id == reservedEntityId }
            val route = ArrayList<Entity>(remaining.size)
            var cursor = player.position()
            if (currentTarget != null) {
                val active = remaining.firstOrNull { it.id == currentTarget.id }
                if (active != null) { route.add(active); remaining.remove(active); cursor = active.position() }
            }
            while (remaining.isNotEmpty()) {
                val origin = cursor
                val next = remaining.minWithOrNull(Comparator.comparingDouble<Entity> { origin.distanceToSqr(it.position()) }) ?: break
                route.add(next); remaining.remove(next); cursor = next.position()
            }
            return java.util.List.copyOf(route)
        }

        @JvmStatic fun <T> nearestQueuedTarget(queue: Deque<T>, eligible: Predicate<T>, distanceSquared: ToDoubleFunction<T>): T? {
            var closest: T? = null
            var closestDistance = Double.POSITIVE_INFINITY
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (!eligible.test(candidate)) { iterator.remove(); continue }
                val distance = distanceSquared.applyAsDouble(candidate)
                if (distance < closestDistance) { closest = candidate; closestDistance = distance }
            }
            return closest
        }

        @JvmStatic fun rebuildPestTargetQueue(client: Minecraft?, pestTargetQueue: java.util.Deque<Entity>, killedEntities: Collection<Entity>, reservedEntityId: Int, eligible: Predicate<Entity>) {
            val pests = availableTargets(client, killedEntities, eligible)
            if (reservedEntityId != -1) pests.removeIf { it.id == reservedEntityId }
            if (client != null) { val player = client.player; if (player != null) pests.sortWith(Comparator.comparingDouble { player.distanceToSqr(it) }) }
            pestTargetQueue.clear(); pestTargetQueue.addAll(pests)
            if (pests.isNotEmpty()) ClientUtils.sendDebugMessage("[PestDestroyer] Rebuilt target queue with " + pests.size + " pest(s). Next: " + formatPos(pests.first().position()))
        }

        @JvmStatic fun findClosestPest(client: Minecraft?, killedEntities: Collection<Entity>, reservedEntityId: Int, eligible: Predicate<Entity>): Entity? {
            if (client == null || client.player == null) return null
            val targets = availableTargets(client, killedEntities, eligible)
            val closest = targets.filter { it.id != reservedEntityId }.minByOrNull { client.player!!.distanceToSqr(it) }
            val snapshot = snapshot(client)
            ClientUtils.sendDebugMessage("[PestDestroyer] Scan: " + snapshot.rawEntityCount() + " entities, " + snapshot.markers().size + " pest markers, " + targets.size + " available pests")
            return closest
        }

        @JvmStatic fun findMostIsolatedPest(client: Minecraft?, killedEntities: Collection<Entity>, eligible: Predicate<Entity>): Entity? {
            val pests = availableTargets(client, killedEntities, eligible)
            if (pests.size < 2) return null
            return pests[mostIsolatedIndex(pests.map(Entity::position))]
        }

        @JvmStatic fun isAvailablePest(client: Minecraft?, killedEntities: Collection<Entity>, entityId: Int, eligible: Predicate<Entity>): Boolean = availableTargets(client, killedEntities, eligible).any { it.id == entityId }

        @JvmStatic fun mostIsolatedIndex(positions: List<Vec3>): Int {
            var mostIsolated = -1
            var bestDistance = -1.0
            for (i in positions.indices) {
                val distance = nearestNeighborDistanceSqr(i, positions)
                if (distance > bestDistance) { bestDistance = distance; mostIsolated = i }
            }
            return mostIsolated
        }

        private fun nearestNeighborDistanceSqr(index: Int, positions: List<Vec3>): Double {
            var nearest = Double.MAX_VALUE
            for (i in positions.indices) if (i != index) nearest = Math.min(nearest, positions[index].distanceToSqr(positions[i]))
            return nearest
        }

        @JvmStatic fun hasPestArmorStandNearby(client: Minecraft?, targetEntity: Entity?): Boolean {
            if (targetEntity == null) return false
            return snapshot(client).markers().any { !it.isRemoved && it.distanceToSqr(targetEntity) <= 4.0 }
        }

        @JvmStatic fun hasPestSkullMarkerForTarget(client: Minecraft?, target: Entity?): Boolean {
            if (isUnavailable(client, target, emptyList<Entity>())) return false
            if (target is Bat || target is Silverfish) return true
            if (target is ArmorStand) return snapshot(client).markers().contains(target)
            return false
        }

        @JvmStatic fun selectClosestPestWithin(client: Minecraft?, maxDistFromEye: Double, condition: Predicate<Entity>?): Entity? {
            if (client == null || client.player == null || maxDistFromEye < 0.0) return null
            val eyePosition = client.player!!.eyePosition
            val maxDistanceSq = maxDistFromEye * maxDistFromEye
            var closest: Entity? = null
            var closestDistanceSq = Double.MAX_VALUE
            for (target in snapshot(client).targets()) {
                if (condition != null && !condition.test(target)) continue
                val targetEye = target.position().add(0.0, target.getEyeHeight(target.pose).toDouble(), 0.0)
                val distanceSq = eyePosition.distanceToSqr(targetEye)
                if (distanceSq <= maxDistanceSq && distanceSq < closestDistanceSq) { closest = target; closestDistanceSq = distanceSq }
            }
            return closest
        }

        @JvmStatic fun getLoadedPests(client: Minecraft?): List<Entity> = snapshot(client).targets()
        @JvmStatic fun getLoadedPestMarkers(client: Minecraft?): List<ArmorStand> = snapshot(client).markers()
        @JvmStatic fun getLoadedPestMobs(client: Minecraft?): List<Entity> = snapshot(client).targets().filter { (it is Bat || it is Silverfish) && !isUnavailable(client, it, emptyList<Entity>()) }

        private fun availableTargets(client: Minecraft?, killedEntities: Collection<Entity>, eligible: Predicate<Entity>): ArrayList<Entity> {
            val available = ArrayList<Entity>()
            for (target in snapshot(client).targets()) if (!isUnavailable(client, target, killedEntities) && eligible.test(target)) available.add(target)
            return available
        }

        private fun isUnavailable(client: Minecraft?, entity: Entity?, killedEntities: Collection<Entity>): Boolean = entity == null || entity.isRemoved || entity.y < 50 || entity is LivingEntity && entity.isDeadOrDying || killedEntities.contains(entity) || client?.player != null && entity === client.player

        private fun snapshot(client: Minecraft?): PestSnapshot {
            if (client == null || client.level == null || client.player == null) return PestSnapshot.EMPTY
            if (!client.isSameThread) return PestClientThread.call(client, java.util.function.Supplier { snapshot(client) }, PestSnapshot.EMPTY)!!
            val tick = client.player!!.tickCount
            val levelId = System.identityHashCode(client.level)
            if (cachedTick == tick && cachedLevelId == levelId) return cachedSnapshot
            val rawEntities = ArrayList<Entity>()
            for (entity in client.level!!.entitiesForRendering()) rawEntities.add(entity)
            val markers = rawEntities.filterIsInstance<ArmorStand>().filter { !it.isRemoved && it.y >= 50 && isPestArmorStand(it, rawEntities) }
            val targetsById = LinkedHashMap<Int, Entity>()
            for (entity in rawEntities) {
                if (entity === client.player || entity.isRemoved || entity.y < 50) continue
                var target: Entity? = null
                if (entity is Bat || entity is Silverfish) target = entity
                else if (entity is ArmorStand && markers.contains(entity)) target = findRealEntityNear(rawEntities, entity) ?: entity
                if (target != null && !isUnavailable(client, target, emptyList<Entity>())) targetsById.putIfAbsent(target.id, target)
            }
            cachedTick = tick
            cachedLevelId = levelId
            cachedSnapshot = PestSnapshot(java.util.List.copyOf(targetsById.values), java.util.List.copyOf(markers), rawEntities.size)
            return cachedSnapshot
        }

        private fun findRealEntityNear(entities: List<Entity>, armorStand: ArmorStand): Entity? {
            var closest: Entity? = null
            var closestDistance = Double.MAX_VALUE
            for (entity in entities) {
                if (!(entity is Bat || entity is Silverfish) || entity.isRemoved) continue
                val distance = entity.distanceToSqr(armorStand)
                if (distance <= 4.0 && distance < closestDistance) { closest = entity; closestDistance = distance }
            }
            return closest
        }

        private fun isPestArmorStand(armorStand: ArmorStand, entities: List<Entity>): Boolean {
            val headItem: ItemStack = armorStand.getItemBySlot(EquipmentSlot.HEAD)
            if (headItem.isEmpty) return false
            val profile: ResolvableProfile? = headItem.get(DataComponents.PROFILE)
            if (profile == null || profile.partialProfile().properties() == null) return false
            val textures = profile.partialProfile().properties().get("textures")
            if (textures == null || textures.isEmpty()) return false
            if (!headItem.has(DataComponents.CUSTOM_NAME) && hasKnownPestTexture(textures)) return true
            return findRealEntityNear(entities, armorStand) != null
        }

        private fun hasKnownPestTexture(textures: Collection<Property>): Boolean {
            for (property in textures) {
                try {
                    val decoded = String(java.util.Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8)
                    if (PEST_TEXTURE_FRAGMENTS.any { decoded.contains(it) }) return true
                } catch (_: IllegalArgumentException) {
                    ClientUtils.sendDebugMessage("Pest target marker contained an invalid base64 texture.")
                }
            }
            return false
        }

        private fun formatPos(pos: Vec3): String = String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z)

        private class PestSnapshot(private val targets: List<Entity>, private val markers: List<ArmorStand>, private val rawEntityCount: Int) {
            fun targets(): List<Entity> = targets
            fun markers(): List<ArmorStand> = markers
            fun rawEntityCount(): Int = rawEntityCount
            companion object { val EMPTY = PestSnapshot(emptyList(), emptyList(), 0) }
        }
    }
}
