package dev.aether.telemetry.ban;

// Parsed punishment text only. Never carries a Ban ID.
public record DetectedBan(boolean temporary, String reason, String duration, boolean simulated) {
    public DetectedBan {
        reason = reason == null ? "" : reason;
        duration = duration == null ? "" : duration;
    }
}
