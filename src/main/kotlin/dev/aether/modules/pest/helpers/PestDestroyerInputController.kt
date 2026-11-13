package dev.aether.modules.pest.helpers

import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

internal class PestDestroyerInputController private constructor() {
    companion object {
        private const val KILL_USE_RETRY_HOLD_MS = 2_000L
        private const val KILL_USE_RETRY_RELEASE_MS = 150L
        private const val KILL_USE_RETRY_CLICK_HOLD_MS = 100L

        @JvmStatic fun update(client: Minecraft, runtime: PestDestroyerRuntime, vacuumTemporarilyReleased: Boolean) {
            if (!runtime.active || client.player == null) return
            if (runtime.state == PestDestroyer.State.AOTV_BETWEEN_PESTS || runtime.state == PestDestroyer.State.HUNT_PEST || runtime.pestEtherwarpActive) return
            if (runtime.vacuumSlot < 0) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
                return
            }
            val player = client.player ?: return
            val selected = (player.inventory as AccessorInventory).getSelected()
            if (runtime.state == PestDestroyer.State.KILL_PEST) {
                val shouldUse = !vacuumTemporarilyReleased
                if (shouldUse && selected != runtime.vacuumSlot) {
                    client.execute { FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot) }
                }
                ClientUtils.setKeyMappingState(client.options.keyUse, shouldUse)
                return
            }
            val shouldUse = selected == runtime.vacuumSlot && runtime.state != PestDestroyer.State.GET_LOCATION && !vacuumTemporarilyReleased
            ClientUtils.setKeyMappingState(client.options.keyUse, shouldUse)
        }

        @JvmStatic fun shouldTemporarilyReleaseKillVacuum(runtime: PestDestroyerRuntime, vacuumReady: Boolean, targetInRange: Boolean): Boolean {
            val now = System.currentTimeMillis()
            if (runtime.state != PestDestroyer.State.KILL_PEST || !vacuumReady || !targetInRange) {
                runtime.resetKillVacuumRetry()
                return false
            }
            if (runtime.killVacuumRetryPressAt != 0L) return true
            if (runtime.killVacuumHoldStartedAt == 0L) {
                runtime.killVacuumHoldStartedAt = now
                return false
            }
            if (now - runtime.killVacuumHoldStartedAt < KILL_USE_RETRY_HOLD_MS) return false
            runtime.killVacuumHoldStartedAt = 0L
            runtime.killVacuumRetryPressAt = now + KILL_USE_RETRY_RELEASE_MS
            runtime.killVacuumReleaseUntil = runtime.killVacuumRetryPressAt + KILL_USE_RETRY_CLICK_HOLD_MS
            ClientUtils.sendDebugMessage("PestDestroyer: retrying vacuum use")
            return true
        }

        @JvmStatic fun isVacuumTemporarilyReleased(runtime: PestDestroyerRuntime): Boolean = runtime.killVacuumRetryPressAt != 0L

        @JvmStatic fun updateVacuumRetryPulse(client: Minecraft, runtime: PestDestroyerRuntime) {
            if (runtime.killVacuumRetryPressAt == 0L || client.options == null) return
            val now = System.currentTimeMillis()
            if (now < runtime.killVacuumRetryPressAt) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
                return
            }
            if (now < runtime.killVacuumReleaseUntil) {
                ClientUtils.setKeyMappingState(client.options.keyUse, true)
                return
            }
            ClientUtils.setKeyMappingState(client.options.keyUse, false)
            runtime.killVacuumRetryPressAt = 0L
            runtime.killVacuumReleaseUntil = 0L
        }
    }
}
