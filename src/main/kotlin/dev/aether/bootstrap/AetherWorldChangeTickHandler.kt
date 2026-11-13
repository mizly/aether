package dev.aether.bootstrap

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.ReconnectScheduler
import dev.aether.modules.failsafe.FailsafeAction
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.session.RecoveryManager
import dev.aether.modules.session.RestartManager
import dev.aether.notification.NotificationManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3

class AetherWorldChangeTickHandler private constructor() {
    companion object {
        private var lastObservedLevel: ClientLevel? = null
        private var lastStablePlayerPosition: Vec3? = null

        @JvmStatic
        fun register() {
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                val currentLevel = client.level
                if (currentLevel == null) {
                    lastObservedLevel = null
                    return@register
                }

                if (lastObservedLevel == null) {
                    lastObservedLevel = currentLevel
                    if (client.player != null) lastStablePlayerPosition = client.player!!.position()
                    return@register
                }

                if (currentLevel !== lastObservedLevel) {
                    val savedPosition = lastStablePlayerPosition
                    lastObservedLevel = currentLevel
                    if (shouldHandleWorldChange()) handleWorldChangeFailsafe(client, savedPosition)
                }

                if (client.player != null) lastStablePlayerPosition = client.player!!.position()
            }
        }

        private fun shouldHandleWorldChange(): Boolean {
            if (!AetherConfig.FAILSAFE_WORLD_CHANGE.get()) return false
            if (!MacroStateManager.isMacroRunning()) return false
            if (MacroStateManager.isIntentionalDisconnect()) return false
            if (MacroStateManager.getCurrentState() == MacroState.State.RECOVERING) return false
            if (RestartManager.isRestartPending()) return false
            return !ReconnectScheduler.isPending() && !ReconnectScheduler.shouldResume()
        }

        private fun handleWorldChangeFailsafe(client: Minecraft, savedPosition: Vec3?) {
            val action = FailsafeManager.getWorldChangeAction()
            val details = "World changed unexpectedly."
            val debugReason = "World change detected"
            NotificationManager.error(FailsafeManager.getNotificationTitle(action), details)

            if (FailsafeManager.shouldStopMacroOnTrigger(action)) {
                FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.WORLD_CHANGE, details, "World changed; stopping macro")
                return
            }

            FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.WORLD_CHANGE, details, debugReason, "Macro stopped and world change recovery started.")
            if (action == FailsafeAction.CUSTOM) return
            if (savedPosition == null) {
                MacroStateManager.stopMacro(client, "World change recovery skipped: no saved position", false)
                return
            }
            MacroStateManager.stopMacro(client, "World change detected; starting recovery path", false)
            RecoveryManager.beginWorldChangeRecovery(savedPosition)
        }
    }
}
