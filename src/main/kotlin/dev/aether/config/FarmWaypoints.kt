package dev.aether.config

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator

class FarmWaypoints private constructor() {
    companion object {
        @Volatile
        private var cachedLastWaypoint: Int? = null

        @JvmStatic
        fun get(): List<FarmWaypoint> = AetherConfig.MACRO_FARM_WAYPOINTS.get().mapNotNull(FarmWaypoint::parse)

        @JvmStatic
        fun set(waypoints: List<FarmWaypoint>) {
            AetherConfig.MACRO_FARM_WAYPOINTS.set(java.util.ArrayList(waypoints.map(FarmWaypoint::encode)))
        }

        @JvmStatic
        fun get(index: Int): FarmWaypoint {
            val waypoints = get()
            if (waypoints.isEmpty()) return FarmWaypoint.emptyAt(0.0, 0.0, 0.0)
            return waypoints[index.coerceIn(0, waypoints.lastIndex)]
        }

        @JvmStatic
        fun update(index: Int, updater: UnaryOperator<FarmWaypoint>) {
            val waypoints = get().toMutableList()
            while (waypoints.size <= index) waypoints.add(FarmWaypoint.emptyAt(0.0, 0.0, 0.0))
            waypoints[index] = updater.apply(waypoints[index])
            set(waypoints)
            AetherConfig.save()
        }

        @JvmStatic
        fun add(waypoint: FarmWaypoint) {
            set(get() + waypoint)
            AetherConfig.save()
        }

        @JvmStatic
        fun insertAfter(index: Int, waypoint: FarmWaypoint) {
            val waypoints = get().toMutableList()
            waypoints.add((index + 1).coerceIn(0, waypoints.size), waypoint)
            set(waypoints)
            AetherConfig.save()
        }

        @JvmStatic
        fun remove(index: Int) {
            val waypoints = get().toMutableList()
            if (waypoints.size <= 1 || index !in waypoints.indices) return
            waypoints.removeAt(index)
            set(waypoints)
            AetherConfig.save()
        }

        @JvmStatic
        fun getLastWaypoint(): Int {
            if (cachedLastWaypoint == null) loadLastWaypoint()
            return cachedLastWaypoint ?: -1
        }

        @JvmStatic
        fun saveLastWaypoint(index: Int) {
            cachedLastWaypoint = index
            try {
                Files.writeString(lastWaypointPath(), index.toString())
            } catch (_: Exception) {
            }
        }

        private fun loadLastWaypoint() {
            try {
                val path = lastWaypointPath()
                if (Files.exists(path)) cachedLastWaypoint = Files.readString(path).trim().toInt()
            } catch (_: Exception) {
                cachedLastWaypoint = -1
            }
        }

        private fun lastWaypointPath(): Path = FabricLoader.getInstance().configDir.resolve("aether_last_waypoint.txt")
    }
}
