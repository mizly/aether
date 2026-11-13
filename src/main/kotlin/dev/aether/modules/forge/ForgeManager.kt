package dev.aether.modules.forge

import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

class ForgeManager {
    companion object {
        @JvmField @Volatile var isRunning = false
        private const val FORGE_NPC_X = -22
        private const val FORGE_NPC_Y = 151
        private const val FORGE_NPC_Z = -79
        private const val ROTATION_MS = 200L
        private const val WARP_TIMEOUT_MS = 10_000L
        private const val PATHFIND_TIMEOUT_MS = 30_000L

        @JvmStatic fun start(client: Minecraft) {
            if (isRunning) {
                ClientUtils.sendMessage("§c[Aether] Forge: already running.", false)
                return
            }
            isRunning = true
            MacroWorkerThread.getInstance().submit("ForgeManager") {
                try { run(client) } catch (e: Exception) {
                    e.printStackTrace()
                    ClientUtils.sendMessage("§c[Aether] Forge error: " + e.message, false)
                } finally { isRunning = false }
            }
        }

        @JvmStatic fun stop() { isRunning = false; PathfindingManager.stop() }

        private fun run(client: Minecraft) {
            if (client.player == null) return
            ClientUtils.sendMessage("§e[Aether] Warping to forge...", false)
            if (!warpToForge(client)) { ClientUtils.sendMessage("§c[Aether] Forge warp timed out. Aborting.", false); return }
            MacroWorkerThread.sleepRandom(680L, 240L)
            if (!isRunning) return
            ClientUtils.sendMessage("§e[Aether] Pathfinding to Forger NPC...", false)
            var walkTarget = findBestWalkingTarget(client, FORGE_NPC_X, FORGE_NPC_Y, FORGE_NPC_Z)
            if (walkTarget == null) walkTarget = BlockPos(FORGE_NPC_X, FORGE_NPC_Y, FORGE_NPC_Z)
            if (!walkToCoords(client, walkTarget.x, walkTarget.y, walkTarget.z)) { ClientUtils.sendMessage("§c[Aether] Failed to reach Forger NPC. Aborting.", false); return }
            if (!isRunning) return
            val forger = findForgerNpc(client)
            if (forger == null) { ClientUtils.sendMessage("§c[Aether] Could not find Forger NPC. Aborting.", false); return }
            ClientUtils.sendDebugMessage("[ForgeManager] Found Forger at " + forger.position())
            faceEntity(client, forger)
            if (!isRunning) return
            ClientUtils.sendMessage("§e[Aether] Opening forge menu...", false)
            if (!interactUntilGui(client, forger, 5000)) { ClientUtils.sendMessage("§c[Aether] Forge GUI did not open. Aborting.", false); return }
            ClientUtils.sendMessage("§a[Aether] Forge menu opened successfully.", false)
            MacroWorkerThread.sleepRandom(255L, 90L)
            MacroWorkerThread.runOnClient(client) { client.player?.closeContainer() }
        }

        private fun warpToForge(client: Minecraft): Boolean {
            val player = client.player ?: return false
            val startPos = player.position()
            MacroWorkerThread.runOnClient(client) { ClientUtils.sendCommand("/warp forge") }
            val deadline = System.currentTimeMillis() + WARP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && isRunning) {
                Thread.sleep(150)
                val p = client.player ?: continue
                if (p.position().distanceTo(startPos) > 10) {
                    val areaDeadline = System.currentTimeMillis() + 5_000
                    while (System.currentTimeMillis() < areaDeadline && isRunning) {
                        val area = TablistUtils.findLine(client, "Area:")
                        if (area != null && area.contains("Dwarven")) return true
                        Thread.sleep(150)
                    }
                    return false
                }
            }
            return false
        }

        private fun findBestWalkingTarget(client: Minecraft, tx: Int, ty: Int, tz: Int): BlockPos? {
            val level = client.level ?: return null
            val player = client.player ?: return null
            val base = BlockPos(tx, ty, tz)
            var best: BlockPos? = null
            var bestDistSq = Double.MAX_VALUE
            for (x in -3..3) for (z in -3..3) for (y in -2..2) {
                val pos = base.offset(x, y, z)
                if (pos.distToCenterSqr(tx.toDouble(), ty.toDouble(), tz.toDouble()) > 9.0) continue
                if (!isWalkable(client, pos)) continue
                val dP = pos.distToCenterSqr(player.position())
                if (dP < bestDistSq) { bestDistSq = dP; best = pos }
            }
            return best
        }

        private fun isWalkable(client: Minecraft, pos: BlockPos): Boolean {
            val level = client.level ?: return false
            return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty &&
                level.getBlockState(pos).getCollisionShape(level, pos).isEmpty &&
                level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty
        }

        private fun walkToCoords(client: Minecraft, x: Int, y: Int, z: Int): Boolean {
            MacroWorkerThread.runOnClient(client) { PathfindingManager.startPathfind(client, x, y, z, false) }
            Thread.sleep(340 + (Math.random() * 120).toLong())
            val deadline = System.currentTimeMillis() + PATHFIND_TIMEOUT_MS
            while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline && isRunning) Thread.sleep(200)
            if (PathfindingManager.isNavigating()) { PathfindingManager.stop(); return false }
            return true
        }

        private fun findForgerNpc(client: Minecraft): Entity? {
            val level = client.level ?: return null
            val player = client.player ?: return null
            var closest: Entity? = null
            var closestDist = Double.MAX_VALUE
            for (entity in level.entitiesForRendering()) {
                if (entity == player || entity is Player) continue
                val name = entity.name.string.replace(Regex("(?i)§."), "").trim().lowercase()
                if (!name.contains("forger")) continue
                val dist = entity.distanceToSqr(player)
                if (dist < closestDist) { closestDist = dist; closest = entity }
            }
            return closest
        }

        private fun faceEntity(client: Minecraft, entity: Entity) {
            MacroWorkerThread.runOnClient(client) { RotationManager.initiateRotation(client, Vec3(entity.x, entity.eyeY, entity.z), ROTATION_MS, 0f) }
            Thread.sleep(ROTATION_MS + 40 + (Math.random() * 30).toLong())
        }

        private fun interactUntilGui(client: Minecraft, entity: Entity, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && isRunning) {
                ClientUtils.performUseClick()
                for (i in 0 until 5) {
                    Thread.sleep(100)
                    if (client.gui.screen() is AbstractContainerScreen<*>) return true
                    if (!isRunning) return false
                }
            }
            return client.gui.screen() is AbstractContainerScreen<*>
        }
    }
}
