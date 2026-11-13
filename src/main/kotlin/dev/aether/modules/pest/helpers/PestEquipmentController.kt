package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

object PestEquipmentController {
    interface Context {
        fun setState(state: PestDestroyer.State)
        fun finish(client: Minecraft)
    }

    fun equipVacuum(client: Minecraft, runtime: PestDestroyerRuntime, context: Context) {
        val hunting = PestHuntingController.isEnabled()
        var hasLasso = false
        if (hunting) {
            runtime.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client)
            hasLasso = runtime.lassoSlot != -1
            if (!hasLasso) {
                ClientUtils.sendMessage("\u00A7cNo lasso found in hotbar. Lasso-routed pests will be left untouched.", false)
            }
        }

        val vacuumSlots = PestLoadoutHelper.findAutomaticVacuumSlots(client)
        runtime.stunVacuumSlot = vacuumSlots[0]
        val slot = vacuumSlots[1]
        if (slot == -1) {
            if (hunting && hasLasso) {
                ClientUtils.sendDebugMessage("[PestHunting] No vacuum in hotbar. Skipping the stun tap.")
                runtime.vacuumSlot = -1
                enterFlightOrFinish(client, context)
                return
            }
            ClientUtils.sendMessage("\u00A7cNo vacuum found in hotbar. Aborting pest destroyer.", false)
            context.finish(client)
            return
        }

        runtime.vacuumSlot = slot
        runtime.killVacuumSlot = slot
        runtime.vacuumRange = PestLoadoutHelper.detectVacuumRange(client, slot)
        client.execute { FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot) }
        ClientUtils.sendDebugMessage("[PestDestroyer] Equipped vacuum (slot ${runtime.vacuumSlot}, range ${runtime.vacuumRange})")
        enterFlightOrFinish(client, context)
    }

    private fun enterFlightOrFinish(client: Minecraft, context: Context) {
        if (AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
            context.setState(PestDestroyer.State.CHECK_NEXT)
            return
        }
        val player = client.player ?: return
        if (!player.getAbilities().flying && player.getAbilities().mayfly) {
            context.setState(PestDestroyer.State.FLY_UP)
        } else if (player.getAbilities().flying) {
            context.setState(PestDestroyer.State.CHECK_NEXT)
        } else {
            ClientUtils.sendMessage("\u00A7cCannot fly. Aborting pest destroyer.", false)
            context.finish(client)
        }
    }

    fun enterFlight(client: Minecraft, runtime: PestDestroyerRuntime, context: Context) {
        if (AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
            PestFlightTapper.release(client)
            runtime.flyTapTicks = 0
            context.setState(PestDestroyer.State.CHECK_NEXT)
            return
        }
        val player = client.player ?: return
        if (player.getAbilities().flying) {
            PestFlightTapper.release(client)
            runtime.flyTapTicks = 0
            context.setState(PestDestroyer.State.CHECK_NEXT)
            return
        }
        if (runtime.flyTapTicks >= PestFlightTapper.TIMEOUT_TICKS) {
            PestFlightTapper.release(client)
            context.setState(PestDestroyer.State.CHECK_NEXT)
            return
        }
        PestFlightTapper.tick(client, runtime.flyTapTicks++)
    }
}
