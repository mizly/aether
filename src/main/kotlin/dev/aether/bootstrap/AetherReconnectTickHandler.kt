package dev.aether.bootstrap

import dev.aether.macro.MacroStateManager
import dev.aether.macro.ReconnectScheduler
import dev.aether.modules.session.RecoveryManager
import dev.aether.ui.DynamicRestScreen
import dev.aether.util.ClientUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.TitleScreen
import java.time.Instant

class AetherReconnectTickHandler private constructor() {
    companion object {
        private var hasCheckedPersistenceOnJoin = false
        private const val PROXY_RESTART_SCREEN_TITLE = "Proxy Restart Recovery"
        private const val PROXY_RESTART_STATUS_LABEL = "reconnecting to Hypixel in"
        private const val PROXY_RESTART_CANCEL_LABEL = "Cancel Reconnect & Exit to Menu"

        @JvmStatic
        fun register() {
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                if (client.player == null || client.level == null || client.connection == null) {
                    hasCheckedPersistenceOnJoin = false
                    if (client.gui.screen() !is TitleScreen && client.gui.screen() !is DisconnectedScreen && client.gui.screen() !is DynamicRestScreen) {
                        return@register
                    }

                    val reconnectAt = ReconnectScheduler.loadReconnectTime()
                    val reconnectMode = ReconnectScheduler.getReconnectMode()
                    if (reconnectAt <= 0) return@register

                    val now = Instant.now().epochSecond
                    val remaining = reconnectAt - now
                    if (remaining <= 0) {
                        if (!ReconnectScheduler.isPending()) {
                            ReconnectScheduler.scheduleReconnect(10, ReconnectScheduler.shouldResume(), reconnectMode)
                            if (client.gui.screen() is DisconnectedScreen) {
                                client.execute {
                                    client.gui.setScreen(createReconnectScreen(reconnectMode, Instant.now().epochSecond * 1000 + 10000, 10000))
                                }
                            }
                        }
                    } else if (!ReconnectScheduler.isPending()) {
                        ReconnectScheduler.scheduleReconnect(remaining, ReconnectScheduler.shouldResume(), reconnectMode)
                        if (client.gui.screen() is DisconnectedScreen) {
                            client.execute { client.gui.setScreen(createReconnectScreen(reconnectMode, reconnectAt * 1000, remaining * 1000)) }
                        }
                    }
                    return@register
                }

                if (hasCheckedPersistenceOnJoin) return@register

                val reconnectAt = ReconnectScheduler.loadReconnectTime()
                if (reconnectAt != 0L) {
                    if (ReconnectScheduler.shouldResume()) {
                        RecoveryManager.beginRecovery()
                        ClientUtils.sendMessage("Session persistence detected! Initializing recovery...")
                    }
                    ReconnectScheduler.cancel()
                }
                hasCheckedPersistenceOnJoin = true
                MacroStateManager.setIntentionalDisconnect(false)
            }
        }

        private fun createReconnectScreen(reconnectMode: ReconnectScheduler.ReconnectMode, reconnectAtMs: Long, durationMs: Long): DynamicRestScreen {
            if (reconnectMode == ReconnectScheduler.ReconnectMode.PROXY_RESTART) {
                return DynamicRestScreen(PROXY_RESTART_SCREEN_TITLE, PROXY_RESTART_SCREEN_TITLE, PROXY_RESTART_STATUS_LABEL, PROXY_RESTART_CANCEL_LABEL, reconnectAtMs, durationMs)
            }
            return DynamicRestScreen(reconnectAtMs, durationMs)
        }
    }
}
