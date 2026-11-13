package dev.aether.telemetry.ban

import java.util.Locale

class BanDetectionGate private constructor() {
    companion object {
        const val SERVER_ID = "hypixel"
        private const val HYPIXEL_ROOT = "hypixel.net"

        // login-stage rejections never reach a play listener, so playStageDisconnect is false and no ban text fires.
        @JvmStatic
        fun allowsDetection(
            playStageDisconnect: Boolean,
            playSessionEstablished: Boolean,
            intentionalDisconnect: Boolean,
            serverAddress: String?
        ): Boolean {
            return playStageDisconnect && playSessionEstablished && !intentionalDisconnect && isHypixelAddress(serverAddress)
        }

        @JvmStatic
        fun isHypixelAddress(serverAddress: String?): Boolean {
            if (serverAddress == null || serverAddress.isBlank()) return false
            var host = serverAddress.trim().lowercase(Locale.ROOT)
            val portSeparator = host.lastIndexOf(':')
            if (portSeparator > 0 && host.indexOf(':') == portSeparator) host = host.substring(0, portSeparator)
            while (host.endsWith(".")) host = host.substring(0, host.length - 1)
            return host == HYPIXEL_ROOT || host.endsWith(".$HYPIXEL_ROOT")
        }
    }
}
