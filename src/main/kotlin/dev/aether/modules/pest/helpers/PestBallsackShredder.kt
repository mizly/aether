package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

// dedicated pre-cleaning aotv route for ballsack shredder
class PestBallsackShredder private constructor() {
    companion object {
        private const val AOTV_TIMEOUT_MS = 10_000L
        private const val RESULT_CONFIRM_TIMEOUT_MS = 2_000L
        private const val POSITION_CHANGE_DISTANCE_SQR = 1.0

        @JvmStatic
        @Throws(InterruptedException::class)
        fun run(client: Minecraft, sessionId: Int, startingPests: Int): Result {
            if (client.player == null || client.options == null) return Result.unmeasured(false, startingPests)

            val aotvSlot = GearManager.findAspectOfTheVoidSlot(client)
            if (aotvSlot < 0 || aotvSlot >= 9) {
                ClientUtils.sendDebugMessage("Ballsack Shredder: no AOTV found; continuing without the route.")
                return Result.unmeasured(true, startingPests)
            }

            GearManager.swapToAOTVSync(client)
            PestClientThread.run(client, Runnable {
                ClientUtils.setKeyMappingState(client.options.keyShift, true)
                ClientUtils.setKeyMappingState(client.options.keyUse, true)
            })

            val requiredWarps = AetherConfig.BALLSACK_WARPS.get()
            val positionChanges = waitForPositionChanges(client, sessionId, requiredWarps)
            releaseAotvKeys(client)
            if (shouldAbort(client, sessionId)) return Result.unmeasured(false, startingPests)
            if (positionChanges < requiredWarps) {
                ClientUtils.sendDebugMessage("Ballsack Shredder: AOTV timed out after " + positionChanges + " of " + requiredWarps + " position change(s); continuing pest cleaning.")
                return Result.unmeasured(true, startingPests)
            }

            if (!AetherConfig.BALLSACK_LOOK_DOWN.get()) {
                ClientUtils.sendDebugMessage("Ballsack Shredder: look-down disabled; skipping vacuum hold and entering pest cleaning immediately.")
                return Result.unmeasured(!shouldAbort(client, sessionId), startingPests)
            }

            val trackedPests: List<Entity> = PestClientThread.call(client, { java.util.List.copyOf(PestTargetTracker.getLoadedPests(client)) }, java.util.List.of<Entity>())!!
            if (!lookDownAndHoldVacuum(client, sessionId)) return Result.unmeasured(!shouldAbort(client, sessionId), startingPests)
            if (shouldAbort(client, sessionId)) return Result.unmeasured(false, startingPests)

            releaseVacuumUse(client)
            val result = awaitResultEvidence(client, sessionId, startingPests, trackedPests)
            val trackedKills = countTrackedKills(trackedPests)
            val tabRemaining = PestClientThread.call(client, { PestManager.getTabAliveCountNow(client) }, -1)!!
            ClientUtils.sendDebugMessage("Ballsack Shredder: estimated killed=" + result.estimatedKilled() + ", remaining=" + result.estimatedRemaining() + " (start=" + Math.max(0, startingPests) + ", tracked removals=" + trackedKills + ", tab remaining=" + tabRemaining + ").")
            return result
        }

        @JvmStatic
        fun shouldRunOnPlot(plot: String): Boolean = AetherConfig.BALLSACK_SHREDDER.get() && isConfiguredForPlot(plot, AetherConfig.BALLSACK_SHREDDER_PLOTS.get())

        @JvmStatic
        fun isConfiguredForPlot(plot: String, configuredPlots: List<String>?): Boolean {
            if (configuredPlots == null || configuredPlots.isEmpty()) return true
            return configuredPlots.stream().anyMatch { configuredPlot -> PestPlotId.equals(configuredPlot, plot) }
        }

        @Throws(InterruptedException::class)
        private fun awaitResultEvidence(client: Minecraft, sessionId: Int, startingPests: Int, trackedPests: List<Entity>): Result {
            var latest = estimateResult(startingPests, countTrackedKills(trackedPests), PestClientThread.call(client, { PestManager.getTabAliveCountNow(client) }, -1)!!)
            if (latest.estimatedRemaining() <= 1) return latest
            val deadline = System.currentTimeMillis() + RESULT_CONFIRM_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !shouldAbort(client, sessionId)) {
                val trackedKills = countTrackedKills(trackedPests)
                val tabRemaining = PestClientThread.call(client, { PestManager.getTabAliveCountNow(client) }, -1)!!
                latest = estimateResult(startingPests, trackedKills, tabRemaining)
                if (latest.estimatedRemaining() <= 1) return latest
                MacroWorkerThread.sleep(50)
            }
            return latest
        }

        private fun countTrackedKills(trackedPests: List<Entity>): Int = trackedPests.stream().filter { isDead(it) }.count().toInt()

        @JvmStatic
        fun estimateResult(startingPests: Int, trackedKills: Int, tabRemaining: Int): Result {
            val normalizedStart = Math.max(0, startingPests)
            val killsFromEntities = Math.min(normalizedStart, Math.max(0, trackedKills))
            val killsFromTab = if (tabRemaining < 0) normalizedStart else Math.max(0, normalizedStart - tabRemaining)
            val estimatedKilled = Math.max(killsFromEntities, killsFromTab)
            return Result(true, true, estimatedKilled, normalizedStart - estimatedKilled)
        }

        @Throws(InterruptedException::class)
        private fun waitForPositionChanges(client: Minecraft, sessionId: Int, requiredWarps: Int): Int {
            var lastPosition = PestClientThread.call(client, { client.player?.position() }, null) ?: return 0
            var changes = 0
            val deadline = System.currentTimeMillis() + AOTV_TIMEOUT_MS
            while (changes < requiredWarps && System.currentTimeMillis() < deadline && !shouldAbort(client, sessionId)) {
                MacroWorkerThread.sleep(25)
                val previousPosition = lastPosition
                val currentPosition = PestClientThread.call(client, { client.player?.position() ?: previousPosition }, previousPosition)!!
                if (currentPosition.distanceToSqr(previousPosition) >= POSITION_CHANGE_DISTANCE_SQR) {
                    changes++
                    lastPosition = currentPosition
                }
            }
            return changes
        }

        @Throws(InterruptedException::class)
        private fun lookDownAndHoldVacuum(client: Minecraft, sessionId: Int): Boolean {
            PestClientThread.run(client, Runnable { client.player?.let { RotationManager.rotateToYawPitch(client, it.yRot, 90.0f, AetherConfig.ROTATION_TIME.get().toLong(), true) } })
            val rotationDeadline = System.currentTimeMillis() + AetherConfig.ROTATION_TIME.get() + 1_000
            while (RotationManager.isRotating() && System.currentTimeMillis() < rotationDeadline && !shouldAbort(client, sessionId)) MacroWorkerThread.sleep(20)
            val vacuumSlot = PestLoadoutHelper.findVacuumHotbarSlot(client)
            if (vacuumSlot < 0 || vacuumSlot >= 9) {
                ClientUtils.sendDebugMessage("Ballsack Shredder: no vacuum found for lookdown; continuing pest cleaning.")
                return false
            }
            PestClientThread.run(client, Runnable {
                FailsafeManager.selectHotbarSlot(client, vacuumSlot)
                ClientUtils.setKeyMappingState(client.options.keyUse, true)
            })
            val holdUntil = System.currentTimeMillis() + AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.get()
            while (System.currentTimeMillis() < holdUntil && !shouldAbort(client, sessionId)) MacroWorkerThread.sleep(20)
            return true
        }

        private fun isDead(entity: Entity): Boolean = entity.isRemoved || (entity is LivingEntity && entity.isDeadOrDying)

        private fun releaseAotvKeys(client: Minecraft) {
            PestClientThread.run(client, Runnable {
                ClientUtils.setKeyMappingState(client.options.keyShift, false)
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
            })
        }

        private fun releaseVacuumUse(client: Minecraft) {
            PestClientThread.run(client, Runnable { ClientUtils.setKeyMappingState(client.options.keyUse, false) })
        }

        private fun shouldAbort(client: Minecraft, sessionId: Int): Boolean = MacroWorkerThread.shouldAbortTask(client) || sessionId != PestManager.getCurrentPestSessionId() || !PestManager.isCleaningInProgress()
    }

    class Result(private val successfulValue: Boolean, private val measuredValue: Boolean, private val estimatedKilledValue: Int, private val estimatedRemainingValue: Int) {
        fun successful(): Boolean = successfulValue
        fun measured(): Boolean = measuredValue
        fun estimatedKilled(): Int = estimatedKilledValue
        fun estimatedRemaining(): Int = estimatedRemainingValue

        override fun equals(other: Any?): Boolean = this === other || (other is Result && successfulValue == other.successfulValue && measuredValue == other.measuredValue && estimatedKilledValue == other.estimatedKilledValue && estimatedRemainingValue == other.estimatedRemainingValue)

        override fun hashCode(): Int {
            var result = java.lang.Boolean.hashCode(successfulValue)
            result = 31 * result + java.lang.Boolean.hashCode(measuredValue)
            result = 31 * result + estimatedKilledValue
            result = 31 * result + estimatedRemainingValue
            return result
        }

        override fun toString(): String = "Result[successful=$successfulValue, measured=$measuredValue, estimatedKilled=$estimatedKilledValue, estimatedRemaining=$estimatedRemainingValue]"

        companion object {
            @JvmStatic
            fun unmeasured(successful: Boolean, startingPests: Int): Result = Result(successful, false, 0, Math.max(0, startingPests))
        }
    }
}
