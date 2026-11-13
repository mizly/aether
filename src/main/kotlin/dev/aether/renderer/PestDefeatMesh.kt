package dev.aether.renderer

import dev.aether.modules.visuals.DefeatEffectPool
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

class PestDefeatMesh private constructor() {
    companion object {
        private val COLORS = intArrayOf(0xFFB179FF.toInt(), 0xFF63E8E3.toInt(), 0xFFFF9B39.toInt())
        @JvmStatic fun append(mesh: CosmeticMesh, burst: DefeatEffectPool.Burst, camera: Vec3, right: Vector3f, up: Vector3f, now: Long) {
            val age = burst.age(now)
            val distanceSquared = burst.position().distanceToSqr(camera)
            if (age >= 1f || distanceSquared > 48 * 48 || distanceSquared < 0.25) return
            val distance = Math.sqrt(distanceSquared).toFloat()
            val nearFade = Math.clamp((distance - 0.5f) / 1.5f, 0f, 1f)
            val x = (burst.position().x - camera.x).toFloat()
            val y = (burst.position().y - camera.y).toFloat()
            val z = (burst.position().z - camera.z).toFloat()
            val color = COLORS[burst.style()]
            mesh.billboard(x, y, z, right, up, 1.35f * Math.min(burst.scale(), distance * 0.3f), color, 0.9f * nearFade, burst.style() + 2, age, burst.seed())
            val count = if (distanceSquared > 24 * 24) 8 else burst.particles()
            val travel = (1.0 - Math.exp(-age * 3.2)).toFloat() * burst.scale()
            val fade = (1f - age) * (1f - age) * nearFade
            for (i in 0 until count) {
                val angle = i * 2.399963f + burst.seed()
                val vertical = 1f - 2f * (i + 0.5f) / count
                val radial = Math.sqrt((1f - vertical * vertical).toDouble()).toFloat()
                val radius = travel * (0.75f + (i % 5) * 0.19f)
                val px = x + Math.cos(angle.toDouble()).toFloat() * radial * radius
                val py = y + vertical * radius + (if (burst.style() == 2) -0.45f else 0.3f) * age * age
                val pz = z + Math.sin(angle.toDouble()).toFloat() * radial * radius
                mesh.billboard(px, py, pz, right, up, (0.035f + (i % 3) * 0.013f) * burst.scale(), color, fade, 5, age, angle)
            }
        }
    }
}
