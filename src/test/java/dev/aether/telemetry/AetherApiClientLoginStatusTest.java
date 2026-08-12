package dev.aether.telemetry;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AetherApiClientLoginStatusTest {
    private static JsonObject body(String status, String token) {
        JsonObject json = new JsonObject();
        json.addProperty("status", status);
        if (token != null) {
            json.addProperty("token", token);
            json.addProperty("discord_id", "123");
        }
        return json;
    }

    @Test
    void authenticatedIsTheCompletionStatusTheBackendActuallySends() {
        AetherApiClient.LoginStatus status = AetherApiClient.parseLoginStatus(body("authenticated", "tok"));

        assertEquals(AetherApiClient.LoginStatusKind.COMPLETE, status.kind());
        assertEquals("tok", status.token());
        assertEquals("123", status.discordId());
    }

    @Test
    void completeIsStillAccepted() {
        assertEquals(AetherApiClient.LoginStatusKind.COMPLETE,
                AetherApiClient.parseLoginStatus(body("complete", "tok")).kind());
    }

    @Test
    void statusIsCaseInsensitive() {
        assertEquals(AetherApiClient.LoginStatusKind.COMPLETE,
                AetherApiClient.parseLoginStatus(body("AUTHENTICATED", "tok")).kind());
    }

    // A missed completion burns the login: the token is served once and the next poll reads claimed.
    @Test
    void anUnknownStatusCarryingATokenIsTreatedAsComplete() {
        AetherApiClient.LoginStatus status = AetherApiClient.parseLoginStatus(body("all_good_now", "tok"));

        assertEquals(AetherApiClient.LoginStatusKind.COMPLETE, status.kind());
        assertEquals("tok", status.token());
    }

    @Test
    void anUnknownStatusWithoutATokenStaysUnknown() {
        assertEquals(AetherApiClient.LoginStatusKind.UNKNOWN,
                AetherApiClient.parseLoginStatus(body("something_else", null)).kind());
    }

    @Test
    void terminalAndPendingStatusesKeepTheirMeaning() {
        assertEquals(AetherApiClient.LoginStatusKind.PENDING,
                AetherApiClient.parseLoginStatus(body("pending", null)).kind());
        assertEquals(AetherApiClient.LoginStatusKind.CLAIMED,
                AetherApiClient.parseLoginStatus(body("claimed", null)).kind());
        assertEquals(AetherApiClient.LoginStatusKind.EXPIRED,
                AetherApiClient.parseLoginStatus(body("expired", null)).kind());
        assertEquals(AetherApiClient.LoginStatusKind.INVALID,
                AetherApiClient.parseLoginStatus(body("invalid", null)).kind());
        assertTrue(AetherApiClient.parseLoginStatus(body("pending", null)).token().isEmpty());
    }
}
