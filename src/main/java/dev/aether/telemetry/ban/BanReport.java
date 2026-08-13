package dev.aether.telemetry.ban;

import com.google.gson.JsonObject;

public record BanReport(
        String server,
        boolean temporary,
        String reason,
        String duration,
        boolean macroActiveAtDisconnect,
        boolean failsafeTriggeredWithinLast5Minutes
) {
    public BanReport {
        server = server == null ? "" : server;
        reason = reason == null ? "" : reason;
        duration = duration == null ? "" : duration;
    }

    public static BanReport of(String server, DetectedBan ban, BanDisconnectContext context) {
        return new BanReport(
                server,
                ban.temporary(),
                ban.reason(),
                ban.duration(),
                context.macroActiveAtDisconnect(),
                context.failsafeTriggeredWithinLast5Minutes());
    }

    public JsonObject toJson() {
        JsonObject payload = new JsonObject();
        payload.addProperty("server", server);
        payload.addProperty("reason", reason);
        payload.addProperty("temporary", temporary);
        payload.addProperty("duration", duration);
        payload.addProperty("macro_active", macroActiveAtDisconnect);
        payload.addProperty("failsafe_recent", failsafeTriggeredWithinLast5Minutes);
        return payload;
    }

    public String dedupKey() {
        return server + '|' + temporary + '|' + reason + '|' + duration;
    }
}
