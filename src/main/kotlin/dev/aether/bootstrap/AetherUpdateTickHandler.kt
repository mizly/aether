package dev.aether.bootstrap

import dev.aether.config.AetherConfig
import dev.aether.update.AutoUpdateInstaller
import dev.aether.update.UpdateChecker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

class AetherUpdateTickHandler private constructor() {
    companion object {
        private var checkedForCurrentJoin = false

        @JvmStatic
        fun register() {
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                if (client.player == null) {
                    checkedForCurrentJoin = false
                    return@register
                }

                if (checkedForCurrentJoin) {
                    return@register
                }

                checkedForCurrentJoin = true
                when (selectUpdateAction(AetherConfig.AUTO_UPDATE.get(), AetherConfig.CHECK_FOR_UPDATES.get())) {
                    UpdateAction.INSTALL -> AutoUpdateInstaller.checkAndInstallLatest()
                    UpdateAction.NOTIFY -> UpdateChecker.checkAndNotify()
                    UpdateAction.NONE -> {
                        // Both update options are disabled.
                    }
                }
            }
        }

        @JvmStatic
        internal fun selectUpdateAction(automaticInstallationEnabled: Boolean, notificationsEnabled: Boolean): UpdateAction {
            if (automaticInstallationEnabled) {
                return UpdateAction.INSTALL
            }
            return if (notificationsEnabled) UpdateAction.NOTIFY else UpdateAction.NONE
        }
    }

    internal enum class UpdateAction {
        NONE,
        NOTIFY,
        INSTALL
    }
}
