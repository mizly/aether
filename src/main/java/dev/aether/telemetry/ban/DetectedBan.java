package dev.aether.telemetry.ban;

// parsed punishment text only; never carries a ban id.
public record DetectedBan(boolean temporary, String reason, String duration, boolean simulated) {
    public DetectedBan {
        reason = reason == null ? "" : reason;
        duration = duration == null ? "" : duration;
    }
}
