package dev.aether.telemetry;

import java.io.IOException;

// statusCode is 0 when no HTTP response happened at all (offline, DNS, timeout).
// Messages must never carry request headers or the API token.
public final class AetherApiException extends IOException {
    private final int statusCode;
    private final String errorCode;

    AetherApiException(String message, int statusCode, String errorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    AetherApiException(String message, int statusCode, String errorCode) {
        this(message, statusCode, errorCode, null);
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    public boolean isNotActive() {
        return statusCode == 409 && "not_active".equals(errorCode);
    }
}
