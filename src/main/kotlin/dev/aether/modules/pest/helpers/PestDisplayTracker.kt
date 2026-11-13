package dev.aether.modules.pest.helpers

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap

object PestDisplayTracker {
    private var level: ClientLevel? = null
    private var tick = Int.MIN_VALUE
    private var cached: List<PestDisplay> = emptyList()
    private val peakHealth = HashMap<Int, Double>()
    private val skullOwners = HashMap<Int, Int>()

    @JvmStatic
    fun getPests(client: Minecraft): List<PestDisplay> {
        if (client.level !== level || client.player == null) {
            level = client.level
            tick = Int.MIN_VALUE
            cached = emptyList()
            peakHealth.clear()
            skullOwners.clear()
        }
        val currentLevel = level ?: return emptyList()
        val player = client.player ?: return emptyList()
        if (tick == player.tickCount) return livePests()
        tick = player.tickCount
        val mobs = PestTargetTracker.getLoadedPestMobs(client)
        val skulls = PestTargetTracker.getLoadedPestMarkers(client)
        val preferredMobs = mobs.filter { !isAttached(client, it) }
        val visibleSkulls = LinkedHashMap<Entity, ArmorStand>()
        for (skull in skulls) {
            val previousOwner = skullOwners[skull.id]
            val owner = findSkullOwner(skull, previousOwner, mobs, preferredMobs)
            if (owner != null) {
                visibleSkulls.putIfAbsent(owner, skull)
                skullOwners[skull.id] = owner.id
            }
        }
        skullOwners.keys.removeIf { id -> skulls.none { it.id == id } }
        val displayed = ArrayList(visibleSkulls.keys)
        for (mob in mobs) {
            if (!visibleSkulls.containsKey(mob) && !(isAttached(client, mob) && displayed.any { it.distanceToSqr(mob) < 9 })) {
                displayed.add(mob)
            }
        }
        val markers = HashMap<Entity, MutableList<ArmorStand>>()
        for (entity in currentLevel.entitiesForRendering()) {
            if (entity !is ArmorStand || entity.isRemoved) continue
            val owner = nearest(entity, displayed) ?: continue
            markers.computeIfAbsent(owner) { ArrayList() }.add(entity)
        }
        val result = ArrayList<PestDisplay>()
        for (mob in displayed) {
            val nearby = markers[mob] ?: emptyList()
            val skull = visibleSkulls[mob]
            var name = "Pest"
            var health: PestDisplayValues.Health? = null
            var progress = -1f
            val attached = mobs.any { helper ->
                helper is Leashable && helper.leashHolder === player && helper.distanceToSqr(mob) <= 9.0
                    && displayed.minByOrNull { it.distanceToSqr(helper) } === mob
            }
            val labels = ArrayList<Component>()
            mob.customName?.let { labels.add(mob.displayName) }
            nearby.forEach { labels.add(it.displayName) }
            for (label in labels) {
                val type = PestHuntingPolicy.findPestTypeIndex(label.string)
                if (type >= 0) name = PestHuntingPolicy.PEST_TYPES[type]
                val parsed = PestDisplayValues.health(label.string)
                if (parsed != null && type >= 0) health = parsed
                val parsedProgress = PestDisplayValues.huntingProgress(label)
                if (parsedProgress >= 0) progress = maxOf(progress, parsedProgress)
            }
            var fraction = -1f
            var current = -1.0
            if (health != null) {
                current = health.current
                val maximum = if (health.maximum > 0) health.maximum else peakHealth.merge(mob.id, current, ::maxOf)!!
                fraction = if (maximum > 0) (current / maximum).toFloat().coerceIn(0f, 1f) else 0f
            }
            val hunting = attached || PestHuntingController.shouldLassoTarget(client, mob)
            if (hunting && !attached && progress < 0) progress = 0f
            val icon = if (skull == null) ItemStack(Items.PLAYER_HEAD) else skull.getItemBySlot(EquipmentSlot.HEAD).copy()
            result.add(PestDisplay(mob, skull, name, icon, current, fraction, hunting, progress, attached))
        }
        result.sortWith(Comparator.comparingInt { it.entity().id })
        peakHealth.keys.removeIf { id -> mobs.none { it.id == id } }
        cached = result.toList()
        return livePests()
    }

    private fun livePests(): List<PestDisplay> {
        if (cached.any { it.entity().isRemoved || !it.entity().isAlive }) {
            cached = cached.filter { !it.entity().isRemoved && it.entity().isAlive }
        }
        return cached
    }

    private fun isAttached(client: Minecraft, entity: Entity): Boolean =
        entity is Leashable && entity.leashHolder === client.player

    @JvmStatic
    fun findSkullOwner(skull: Entity, previousOwner: Int?, mobs: List<Entity>, preferredMobs: List<Entity>): Entity? {
        if (previousOwner != null) return mobs.firstOrNull { it.id == previousOwner }
        var vehicle = skull.vehicle
        while (vehicle != null) {
            if (mobs.contains(vehicle)) return vehicle
            vehicle = vehicle.vehicle
        }
        return nearest(skull, preferredMobs) ?: nearest(skull, mobs)
    }

    private fun nearest(marker: Entity, mobs: List<Entity>): Entity? = mobs.asSequence()
        .filter { isMarkerNear(marker, it) }
        .minWithOrNull(compareBy<Entity> { marker.distanceToSqr(it) }.thenBy { it.id })

    private fun isMarkerNear(marker: Entity, mob: Entity): Boolean {
        val dx = marker.x - mob.x
        val dz = marker.z - mob.z
        val dy = marker.y - mob.y
        return dx * dx + dz * dz <= 2.25 && dy >= -2 && dy <= 5
    }

    class PestDisplay(
        private val entityValue: Entity,
        private val skullValue: ArmorStand?,
        private val nameValue: String,
        private val iconValue: ItemStack,
        private val healthValue: Double,
        private val healthFractionValue: Float,
        private val huntingValue: Boolean,
        private val progressValue: Float,
        private val attachedValue: Boolean
    ) {
        fun entity(): Entity = entityValue
        fun skull(): ArmorStand? = skullValue
        fun name(): String = nameValue
        fun icon(): ItemStack = iconValue
        fun health(): Double = healthValue
        fun healthFraction(): Float = healthFractionValue
        fun hunting(): Boolean = huntingValue
        fun progress(): Float = progressValue
        fun attached(): Boolean = attachedValue

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PestDisplay) return false
            return entityValue == other.entityValue && skullValue == other.skullValue && nameValue == other.nameValue &&
                iconValue == other.iconValue && healthValue == other.healthValue && healthFractionValue == other.healthFractionValue &&
                huntingValue == other.huntingValue && progressValue == other.progressValue && attachedValue == other.attachedValue
        }

        override fun hashCode(): Int {
            var result = entityValue.hashCode()
            result = 31 * result + (skullValue?.hashCode() ?: 0)
            result = 31 * result + nameValue.hashCode()
            result = 31 * result + iconValue.hashCode()
            result = 31 * result + healthValue.hashCode()
            result = 31 * result + healthFractionValue.hashCode()
            result = 31 * result + huntingValue.hashCode()
            result = 31 * result + progressValue.hashCode()
            result = 31 * result + attachedValue.hashCode()
            return result
        }

        override fun toString(): String =
            "PestDisplay[entity=$entityValue, skull=$skullValue, name=$nameValue, icon=$iconValue, health=$healthValue, healthFraction=$healthFractionValue, hunting=$huntingValue, progress=$progressValue, attached=$attachedValue]"
    }
}
