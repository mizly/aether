package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import dev.aether.modules.gear.GearManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pest.PestManager
import java.util.concurrent.atomic.AtomicBoolean

class PestAotvManager private constructor() {
    companion object {
        @Volatile private var isSneakingForAotv = false
        @Volatile private var preparationAotvActive = false
        @Volatile private var preparationPlotRecovery = false
        @Volatile private var preparationAotvStartY = Double.NaN
        @Volatile private var preparationAotvRequestedAt = 0L
        @Volatile private var preparationAotvStartedAt = 0L

        @JvmStatic fun isSneakingForAotv(): Boolean = isSneakingForAotv
        @JvmStatic fun setSneakingForAotv(sneaking: Boolean) { isSneakingForAotv = sneaking }
        @JvmStatic fun resetState() {
            isSneakingForAotv = false; preparationAotvActive = false; preparationPlotRecovery = false
            preparationAotvStartY = Double.NaN; preparationAotvRequestedAt = 0L; preparationAotvStartedAt = 0L
        }
        @JvmStatic @Throws(InterruptedException::class) fun startPreparationAotv(client: Minecraft) {
            preparationAotvActive = true; preparationPlotRecovery = false; preparationAotvStartY = Double.NaN
            preparationAotvRequestedAt = System.currentTimeMillis(); preparationAotvStartedAt = 0L; isSneakingForAotv = true
            performAotvToRoof(client, true)
        }
        @JvmStatic fun isPreparationAotvActive(): Boolean = preparationAotvActive
        @JvmStatic fun consumePreparationPlotRecovery(): Boolean {
            val recovery = preparationPlotRecovery; preparationPlotRecovery = false; return recovery
        }
        @JvmStatic fun cancelPreparationAotv(client: Minecraft) { finishPreparationAotv(client, false) }

        @JvmStatic fun updatePreparationAotv(client: Minecraft?) {
            if (!preparationAotvActive || client == null || client.player == null || client.level == null) return
            val options = client.options
            if (!java.lang.Double.isNaN(preparationAotvStartY)) {
                val aotvSlot = GearManager.findAspectOfTheVoidSlot(client)
                if (aotvSlot >= 0 && aotvSlot < 9) ClientUtils.setKeyMappingState(options.keyUse, true)
                if (!hasRoofAbove(client)) {
                    ClientUtils.sendDebugMessage("Pest PRE stage: AOTV reached open air above the roof.")
                    finishPreparationAotv(client, false); return
                }
            } else if (!isSneakingForAotv) { finishPreparationAotv(client, false); return }
            val now = System.currentTimeMillis()
            val startupTimedOut = java.lang.Double.isNaN(preparationAotvStartY) && now - preparationAotvRequestedAt > 10_000L
            val teleportTimedOut = !java.lang.Double.isNaN(preparationAotvStartY) && now - preparationAotvStartedAt > 2000L
            if (startupTimedOut || teleportTimedOut) {
                ClientUtils.sendDebugMessage("Pest PRE stage: roof AOTV timed out.")
                finishPreparationAotv(client, true)
            }
        }

        @JvmStatic fun shouldDoAotvOnCurrentPlot(client: Minecraft, currentInfestedPlot: String?, isSamePlot: Boolean): Boolean {
            if (currentInfestedPlot == null) return false
            if (PestManager.isBallsackShredderActiveForCurrentCycle() || !AetherConfig.AOTV_TO_ROOF.get()) return false
            if (AetherConfig.AOTV_ROOF_PLOTS.get().isEmpty()) return true
            val inAllowedList = AetherConfig.AOTV_ROOF_PLOTS.get().contains(currentInfestedPlot)
            ClientUtils.sendDebugMessage(if (inAllowedList) "plot in list, performing aotv" else "plot not in list, skipping aotv")
            return inAllowedList
        }

        @JvmStatic fun hasRoofAbove(client: Minecraft?): Boolean {
            if (client == null) return false
            if (!client.isSameThread) return PestClientThread.call(client, java.util.function.Supplier { hasRoofAbove(client) }, false)!!
            val level = client.level ?: return false
            if (client.player == null) return false
            val base = getRoofScanBase(client)
            for (i in 2..20) if (!level.getBlockState(base.above(i)).isAir) return true
            return false
        }

        @JvmStatic fun getRoofScanBase(client: Minecraft?): BlockPos {
            if (client == null) return BlockPos.ZERO
            if (!client.isSameThread) return PestClientThread.call(client, java.util.function.Supplier { getRoofScanBase(client) }, BlockPos.ZERO)!!
            val player = client.player ?: return BlockPos.ZERO
            return BlockPos.containing(player.x, player.y - 0.3, player.z)
        }

        @JvmStatic @Throws(InterruptedException::class) fun performAotvToRoof(client: Minecraft) { performAotvToRoof(client, false) }

        @Throws(InterruptedException::class)
        private fun performAotvToRoof(client: Minecraft, preparation: Boolean) {
            if (MacroWorkerThread.getInstance().isCancelled()) return
            val ready = PestClientThread.call(client, java.util.function.Supplier { client.player != null && client.gameMode != null }, false)!!
            if (!ready) return
            if (client.level != null && !hasRoofAbove(client)) {
                ClientUtils.sendDebugMessage("PestAotv: no roof detected above player (2..20). Aborting AOTV.")
                isSneakingForAotv = false
                MacroWorkerThread.runOnClient(client, Runnable {
                    val options = client.options
                    ClientUtils.setKeyMappingState(options.keyShift, false)
                    if (preparation) finishPreparationAotv(client, false) else PestDestroyer.completeRoofAotv()
                })
                return
            }
            if (AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.get()) {
                val breakTarget = PestClientThread.call(client, java.util.function.Supplier { val p = client.player ?: return@Supplier Vec3.ZERO; Vec3.atCenterOf(p.blockPosition().above(2)) }, Vec3.ZERO)!!
                MacroWorkerThread.runOnClient(client, Runnable { val p = client.player ?: return@Runnable; ClientUtils.lookAt(p, breakTarget) })
                PestClientThread.run(client, Runnable { ClientUtils.performAttackClick() })
                Thread.sleep(100)
            }
            if (MacroWorkerThread.getInstance().isCancelled()) return
            PestClientThread.run(client, Runnable { isSneakingForAotv = true })
            val aimOrigin = PestClientThread.call(client, java.util.function.Supplier { val p = client.player ?: return@Supplier null; AimOrigin(p.eyePosition, p.yRot) }, null)
            if (aimOrigin == null) { isSneakingForAotv = false; return }
            val eyePos = aimOrigin.eyePosition()
            val yawRad = Math.toRadians(aimOrigin.yaw().toDouble()).toFloat()
            val baseUpPitch = Math.max(0, Math.min(90, AetherConfig.AOTV_ROOF_PITCH.get()))
            val humanization = Math.max(0, Math.min(15, AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION.get()))
            var randomizedUpPitch = baseUpPitch + ((Math.random() * 2.0) - 1.0) * humanization
            randomizedUpPitch = Math.max(0.0, Math.min(90.0, randomizedUpPitch))
            val targetMcPitch = -randomizedUpPitch.toFloat()
            val pitchRad = Math.toRadians(targetMcPitch.toDouble())
            val dirX = -Math.sin(yawRad.toDouble()) * Math.cos(pitchRad)
            val dirY = -Math.sin(pitchRad)
            val dirZ = Math.cos(yawRad.toDouble()) * Math.cos(pitchRad)
            val targetPos = eyePos.add(dirX * 100.0, dirY * 100.0, dirZ * 100.0)
            val rotTime = (AetherConfig.ROTATION_TIME.get() * (0.92 + Math.random() * 0.16)).toInt()
            PestClientThread.run(client, Runnable { RotationManager.initiateRotation(client, targetPos, rotTime.toLong()) })
            ClientUtils.waitForRotationToComplete(targetMcPitch, rotTime)
            if (MacroWorkerThread.getInstance().isCancelled()) return
            val aotvSlot = GearManager.findAspectOfTheVoidSlot(client)
            if (aotvSlot != -1 && aotvSlot < 9) {
                GearManager.swapToAOTVSync(client)
                if (MacroWorkerThread.getInstance().isCancelled()) return
                MacroWorkerThread.runOnClient(client, Runnable {
                    ClientUtils.performUseClick(Runnable {
                        val startY = client.player?.y ?: 0.0
                        if (preparation) { preparationAotvStartY = startY; preparationAotvStartedAt = System.currentTimeMillis() }
                        else PestDestroyer.setAotvStartY(startY)
                        ClientUtils.sendDebugMessage("[PestAotv] Firing AOTV (slot=$aotvSlot, startY=" + String.format("%.2f", startY) + ")")
                    })
                })
            } else {
                ClientUtils.sendDebugMessage("[PestAotv] No AOTV found in hotbar. Aborting AOTV sequence.")
                isSneakingForAotv = false
                MacroWorkerThread.runOnClient(client, Runnable {
                    ClientUtils.setKeyMappingState(client.options.keyShift, false)
                    if (preparation) finishPreparationAotv(client, false)
                    else if (PestDestroyer.getVacuumSlot() < 0) PestDestroyer.setState(PestDestroyer.State.EQUIP_VACUUM)
                    else PestDestroyer.setState(PestDestroyer.State.CHECK_NEXT)
                })
            }
        }

        @JvmStatic @Throws(InterruptedException::class) fun rotateDownAfterAotv(client: Minecraft) = rotateDownAfterAotv(client, false)

        @JvmStatic @Throws(InterruptedException::class)
        fun rotateDownAfterAotv(client: Minecraft, ballsackOnPlot: Boolean) {
            if (client.player == null || MacroWorkerThread.getInstance().isCancelled()) return
            val targetYaw = PestClientThread.call(client, java.util.function.Supplier { val p = client.player ?: return@Supplier 0.0f; p.yRot + randomYawOffset() }, 0.0f)!!
            val targetPitch = if (ballsackOnPlot) 90.0f else (-30.0 + Math.random() * 60.0).toFloat()
            val rotTime = (AetherConfig.ROTATION_TIME.get() * (0.92 + Math.random() * 0.16)).toInt()
            val started = AtomicBoolean(false)
            MacroWorkerThread.runOnClient(client, Runnable { if (client.player != null) { RotationManager.rotateToYawPitch(client, targetYaw, targetPitch, rotTime.toLong(), true); started.set(true) } })
            val deadline = System.currentTimeMillis() + Math.max(1_500L, rotTime.toLong() + 1_000L)
            while (!MacroWorkerThread.getInstance().isCancelled() && !started.get() && System.currentTimeMillis() < deadline) MacroWorkerThread.sleep(10L)
            while (!MacroWorkerThread.getInstance().isCancelled() && started.get() && RotationManager.isRotating() && System.currentTimeMillis() < deadline) MacroWorkerThread.sleep(20L)
            if (ballsackOnPlot) {
                val vacuumSlot = PestLoadoutHelper.findVacuumHotbarSlot(client)
                if (vacuumSlot >= 0 && vacuumSlot < 9) PestClientThread.run(client, Runnable { FailsafeManager.selectHotbarSlot(client, vacuumSlot); ClientUtils.setKeyMappingState(client.options.keyUse, true) })
            }
        }

        private fun randomYawOffset(): Float = (-10.0 + Math.random() * 20.0).toFloat()
        private class AimOrigin(private val eyePositionValue: Vec3, private val yawValue: Float) {
            fun eyePosition(): Vec3 = eyePositionValue
            fun yaw(): Float = yawValue
        }
        private fun finishPreparationAotv(client: Minecraft?, recoverWithPlotTp: Boolean) {
            if (!preparationAotvActive) return
            preparationPlotRecovery = recoverWithPlotTp; preparationAotvActive = false; preparationAotvStartY = Double.NaN
            preparationAotvRequestedAt = 0L; preparationAotvStartedAt = 0L; isSneakingForAotv = false
            if (client != null) { val options = client.options; ClientUtils.setKeyMappingState(options.keyShift, false); ClientUtils.setKeyMappingState(options.keyUse, false) }
        }
    }
}
