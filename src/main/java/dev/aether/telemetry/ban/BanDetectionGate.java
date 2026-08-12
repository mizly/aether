package dev.aether.telemetry.ban;

import java.util.Locale;

public final class BanDetectionGate {
    public static final String SERVER_ID = "hypixel";

    private static final String HYPIXEL_ROOT = "hypixel.net";

    private BanDetectionGate() {
    }

    // Login-stage rejections never reach a play listener, so playStageDisconnect is false there
    // and no amount of ban-shaped text can produce an event.
    public static boolean allowsDetection(
            boolean playStageDisconnect,
            boolean playSessionEstablished,
            boolean intentionalDisconnect,
            String serverAddress
    ) {
        return playStageDisconnect
                && playSessionEstablished
                && !intentionalDisconnect
                && isHypixelAddress(serverAddress);
    }

    public static boolean isHypixelAddress(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return false;
        }

        String host = serverAddress.trim().toLowerCase(Locale.ROOT);
        int portSeparator = host.lastIndexOf(':');
        if (portSeparator > 0 && host.indexOf(':') == portSeparator) {
            host = host.substring(0, portSeparator);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.equals(HYPIXEL_ROOT) || host.endsWith("." + HYPIXEL_ROOT);
    }
}
