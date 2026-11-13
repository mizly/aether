package dev.aether.modules.pathfinding.execution

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

interface FlightView {
    fun position(): Vec3
    fun eyePosition(): Vec3
    fun velocity(): Vec3
    fun yaw(): Float
    fun pitch(): Float
    fun flyingSpeed(): Double
    fun sprinting(): Boolean
    fun nowMillis(): Long
    fun bodyAt(feet: Vec3): AABB
    fun collisions(search: AABB): Iterable<AABB>
}
