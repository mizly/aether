package dev.aether.telemetry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public final class AetherApiClient {
    public static final String BASE_URL = "https://wheat.aether.cat";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "aether-mod";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AetherApiClient() {
    }

    public record LoginStart(String loginId, String loginUrl, long expiresInSeconds) {
    }

    public enum LoginStatusKind {
        PENDING,
        COMPLETE,
        CLAIMED,
        EXPIRED,
        INVALID,
        UNKNOWN
    }

    public record LoginStatus(LoginStatusKind kind, String token, String discordId) {
    }

    public record AccountInfo(String discordId, long totalSeconds, boolean active) {
    }

    public record ModState(boolean ok, boolean active, long totalSeconds, String sessionToken) {
    }

    public static LoginStart startLogin() throws AetherApiException {
        JsonObject body = send(request("/auth/start")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), "/auth/start");

        String loginId = string(body, "login_id");
        String loginUrl = string(body, "login_url");
        if (loginId.isEmpty() || loginUrl.isEmpty()) {
            throw new AetherApiException("/auth/start returned an incomplete response", 0, "");
        }
        return new LoginStart(loginId, loginUrl, longValue(body, "expires_in", 600L));
    }

    public static LoginStatus getLoginStatus(String loginId) throws AetherApiException {
        String query = "/auth/status?login_id=" + URLEncoder.encode(loginId, StandardCharsets.UTF_8);
        return parseLoginStatus(send(request(query).GET().build(), "/auth/status"));
    }

    static LoginStatus parseLoginStatus(JsonObject body) {
        String token = string(body, "token");
        LoginStatusKind kind = switch (string(body, "status").toLowerCase(Locale.ROOT)) {
            case "pending" -> LoginStatusKind.PENDING;
            case "complete", "authenticated" -> LoginStatusKind.COMPLETE;
            case "claimed" -> LoginStatusKind.CLAIMED;
            case "expired" -> LoginStatusKind.EXPIRED;
            case "invalid" -> LoginStatusKind.INVALID;
            default -> LoginStatusKind.UNKNOWN;
        };

        // the token is handed out once, so hiding it behind an unknown status would burn the login.
        if (kind == LoginStatusKind.UNKNOWN && !token.isEmpty()) {
            kind = LoginStatusKind.COMPLETE;
        }
        return new LoginStatus(kind, token, string(body, "discord_id"));
    }

    public static AccountInfo getCurrentUser(String token) throws AetherApiException {
        JsonObject body = send(authorized(request("/me").GET(), token).build(), "/me");
        return new AccountInfo(
                string(body, "discord_id"),
                longValue(body, "total_seconds", 0L),
                body.has("is_active") && !body.get("is_active").isJsonNull() && body.get("is_active").getAsBoolean());
    }

    public static ModState modOn(String token) throws AetherApiException {
        return modState("/mod/on", token, REQUEST_TIMEOUT, modOnPayload());
    }

    public static ModState heartbeat(String token) throws AetherApiException {
        return heartbeat(token, "");
    }

    public static ModState heartbeat(String token, String sessionToken) throws AetherApiException {
        return modState("/mod/heartbeat", token, REQUEST_TIMEOUT, sessionPayload(sessionToken));
    }

    public static ModState modOff(String token) throws AetherApiException {
        return modOff(token, "", REQUEST_TIMEOUT);
    }

    /** shutdown paths pass a short timeout so telemetry never stalls the client exit. */
    public static ModState modOff(String token, Duration timeout) throws AetherApiException {
        return modOff(token, "", timeout);
    }

    public static ModState modOff(String token, String sessionToken) throws AetherApiException {
        return modOff(token, sessionToken, REQUEST_TIMEOUT);
    }

    public static ModState modOff(String token, String sessionToken, Duration timeout) throws AetherApiException {
        return modState("/mod/off", token, timeout, sessionPayload(sessionToken));
    }

    public static void reportBan(String token, JsonObject payload) throws AetherApiException {
        reportBan(token, payload, "");
    }

    public static void reportBan(String token, JsonObject payload, String sessionToken) throws AetherApiException {
        JsonObject body = payload == null ? new JsonObject() : payload;
        if (sessionToken != null && !sessionToken.isBlank()) {
            body.addProperty("session_token", sessionToken);
        }
        HttpRequest req = authorized(request("/ban")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)), token)
                .build();
        execute(req, "/ban");
    }

    private static ModState modState(String path, String token, Duration timeout, JsonObject payload) throws AetherApiException {
        HttpRequest req = authorized(request(path, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)), token)
                .build();
        JsonObject body = send(req, path);
        return new ModState(
                !body.has("ok") || body.get("ok").getAsBoolean(),
                body.has("active") && !body.get("active").isJsonNull() && body.get("active").getAsBoolean(),
                longValue(body, "total_seconds", 0L),
                string(body, "session_token"));
    }

    private static JsonObject modOnPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("use_session_token", true);
        return payload;
    }

    private static JsonObject sessionPayload(String sessionToken) {
        JsonObject payload = new JsonObject();
        if (sessionToken != null && !sessionToken.isBlank()) {
            payload.addProperty("session_token", sessionToken);
        }
        return payload;
    }

    private static HttpRequest.Builder request(String path) {
        return request(path, REQUEST_TIMEOUT);
    }

    private static HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
    }

    private static HttpRequest.Builder authorized(HttpRequest.Builder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static HttpResponse<String> execute(HttpRequest request, String endpoint) throws AetherApiException {
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AetherApiException(endpoint + " was interrupted", 0, "", e);
        } catch (Exception e) {
            throw new AetherApiException(endpoint + " could not be reached: " + e.getClass().getSimpleName(), 0, "", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            JsonObject body = parse(response.body());
            String errorCode = body == null ? "" : string(body, "error");
            throw new AetherApiException(endpoint + " returned HTTP " + status
                    + (errorCode.isEmpty() ? "" : " (" + errorCode + ")"), status, errorCode);
        }
        return response;
    }

    private static JsonObject send(HttpRequest request, String endpoint) throws AetherApiException {
        HttpResponse<String> response = execute(request, endpoint);
        JsonObject body = parse(response.body());
        if (body == null) {
            throw new AetherApiException(endpoint + " returned a non-JSON body", response.statusCode(), "");
        }
        return body;
    }

    private static JsonObject parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(raw);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** total_seconds arrives as a JSON string on some endpoints, so parse via text. */
    private static long longValue(JsonObject obj, String key, long fallback) {
        String raw = string(obj, key);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
