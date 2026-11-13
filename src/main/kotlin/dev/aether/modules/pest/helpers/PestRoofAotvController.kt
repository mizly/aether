package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.minecraft.client.Minecraft

internal class PestRoofAotvController private constructor() {
    companion object {
        private const val ROOF_AOTV_TIMEOUT_MS = 2_000L

        @JvmStatic fun tick(client: Minecraft, runtime: PestDestroyerRuntime): Boolean {
            if (runtime.state == PestDestroyer.State.AOTV_TO_ROOF_RETURN) {
                releaseAotvKeys(client)
                if (!RotationManager.isRotating()) { PestAotvManager.setSneakingForAotv(false); PestDestroyer.completeRoofAotv() }
                return true
            }
            if (runtime.state != PestDestroyer.State.AOTV_TO_ROOF) return false
            if (java.lang.Double.isNaN(runtime.aotvStartY)) {
                if (!PestAotvManager.isSneakingForAotv()) PestDestroyer.completeRoofAotv()
                return true
            }
            PestAotvManager.setSneakingForAotv(true)
            val aotvSlot = GearManager.findAspectOfTheVoidSlot(client)
            if (aotvSlot >= 0 && (client.player!!.inventory as AccessorInventory).getSelected() == aotvSlot) ClientUtils.setKeyMappingState(client.options.keyUse, true)
            if (!PestAotvManager.hasRoofAbove(client)) finishSuccessfulClimb(client, runtime)
            else if (System.currentTimeMillis() - runtime.stateEnteredAt > ROOF_AOTV_TIMEOUT_MS) recoverTimedOutClimb(client, runtime)
            return true
        }

        private fun finishSuccessfulClimb(client: Minecraft, runtime: PestDestroyerRuntime) {
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV Success: open air detected above.")
            runtime.aotvStartY = Double.NaN
            releaseAotvKeys(client)
            val targetYaw = client.player!!.yRot + (-10.0 + Math.random() * 20.0).toFloat()
            val ballsackOnPlot = PestManager.isBallsackShredderActiveForCurrentCycle()
            val targetPitch = if (ballsackOnPlot) 90.0f else (-30.0 + Math.random() * 60.0).toFloat()
            RotationManager.rotateToYawPitch(client, targetYaw, targetPitch, AetherConfig.ROTATION_TIME.get().toLong(), true)
            if (ballsackOnPlot) { PestAotvManager.setSneakingForAotv(false); PestDestroyer.setState(PestDestroyer.State.AOTV_POST_LOOKDOWN) }
            else PestDestroyer.setState(PestDestroyer.State.AOTV_TO_ROOF_RETURN)
        }

        private fun recoverTimedOutClimb(client: Minecraft, runtime: PestDestroyerRuntime) {
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV timed out. Issuing /plottp to recover.")
            runtime.aotvStartY = Double.NaN
            runtime.roofAotvReturnState = null
            PestAotvManager.setSneakingForAotv(false)
            releaseAotvKeys(client)
            val currentPlot = ClientUtils.getCurrentPlot()
            if (currentPlot == null || currentPlot.isEmpty()) { PestDestroyer.completeRoofAotv(); return }
            runtime.navigation.plotTpWindow = CommandUtils.beginChatWindow()
            CommandUtils.initiatePlotTp(currentPlot)
            runtime.navigation.lastTargetPlot = currentPlot
            runtime.navigation.plotTpSent = true
            PestDestroyer.setState(PestDestroyer.State.TELEPORT_TO_PLOT)
        }

        private fun releaseAotvKeys(client: Minecraft) {
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
            ClientUtils.setKeyMappingState(client.options.keyUse, false)
        }
    }
}
