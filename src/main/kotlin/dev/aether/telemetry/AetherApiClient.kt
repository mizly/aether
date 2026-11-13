package dev.aether.telemetry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale

class AetherApiClient private constructor() {
    data class LoginStart(val loginId: String, val claimSecret: String, val loginUrl: String, val expiresInSeconds: Long)

    enum class LoginStatusKind { PENDING, COMPLETE, CLAIMED, EXPIRED, INVALID, OUTDATED_CLIENT, UNKNOWN }

    class LoginStatus(
        private val kindValue: LoginStatusKind,
        private val tokenValue: String,
        private val discordIdValue: String,
        private val expiresInSecondsValue: Long
    ) {
        fun kind(): LoginStatusKind = kindValue
        fun token(): String = tokenValue
        fun discordId(): String = discordIdValue
        fun expiresInSeconds(): Long = expiresInSecondsValue
    }
    data class TokenRefresh(val token: String, val expiresInSeconds: Long)
    data class AccountInfo(val discordId: String, val totalSeconds: Long, val active: Boolean)
    data class ModState(val ok: Boolean, val active: Boolean, val totalSeconds: Long, val sessionToken: String)

    data class IrcMessage(val id: String, val source: String, val author: String, val replyTo: String, val content: String)

    companion object {
        const val BASE_URL = "https://wheat.aether.cat"
        @JvmField val IRC_SOCKET_URL = BASE_URL.replaceFirst("^http".toRegex(), "ws") + "/irc/ws"
        private val CONNECT_TIMEOUT = Duration.ofSeconds(8)
        private val REQUEST_TIMEOUT = Duration.ofSeconds(10)
        private const val USER_AGENT = "aether-mod"
        private val HTTP: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()

        @JvmStatic fun startLogin(): LoginStart {
            val body = send(request("/auth/start").POST(HttpRequest.BodyPublishers.noBody()).build(), "/auth/start")
            val loginId = string(body, "login_id")
            val claimSecret = string(body, "claim_secret")
            val loginUrl = string(body, "login_url")
            if (loginId.isEmpty() || claimSecret.isEmpty() || loginUrl.isEmpty()) throw AetherApiException("/auth/start returned an incomplete response", 0, "")
            return LoginStart(loginId, claimSecret, loginUrl, longValue(body, "expires_in", 600L))
        }

        @JvmStatic fun getLoginStatus(loginId: String, claimSecret: String): LoginStatus {
            val payload = JsonObject().apply { addProperty("login_id", loginId); addProperty("claim_secret", claimSecret) }
            return parseLoginStatus(send(request("/auth/status").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build(), "/auth/status"))
        }

        @JvmStatic fun refreshToken(token: String): TokenRefresh {
            val body = send(authorized(request("/auth/refresh").POST(HttpRequest.BodyPublishers.noBody()), token).build(), "/auth/refresh")
            val refreshed = string(body, "token")
            if (refreshed.isEmpty()) throw AetherApiException("/auth/refresh returned no token", 0, "")
            return TokenRefresh(refreshed, longValue(body, "expires_in", 0L))
        }

        @JvmStatic fun parseLoginStatus(body: JsonObject): LoginStatus {
            val token = string(body, "token")
            var kind = when (string(body, "status").lowercase(Locale.ROOT)) {
                "pending" -> LoginStatusKind.PENDING
                "complete", "authenticated" -> LoginStatusKind.COMPLETE
                "claimed" -> LoginStatusKind.CLAIMED
                "expired" -> LoginStatusKind.EXPIRED
                "invalid" -> LoginStatusKind.INVALID
                else -> LoginStatusKind.UNKNOWN
            }
            // the token is handed out once, so hiding it behind an unknown status would burn the login.
            if (kind == LoginStatusKind.UNKNOWN && token.isNotEmpty()) kind = LoginStatusKind.COMPLETE
            return LoginStatus(kind, token, string(body, "discord_id"), longValue(body, "expires_in", 0L))
        }

        @JvmStatic fun getCurrentUser(token: String): AccountInfo {
            val body = send(authorized(request("/me").GET(), token).build(), "/me")
            return AccountInfo(string(body, "discord_id"), longValue(body, "total_seconds", 0L), body.has("is_active") && !body.get("is_active").isJsonNull && body.get("is_active").asBoolean)
        }

        @JvmStatic fun modOn(token: String): ModState = modState("/mod/on", token, REQUEST_TIMEOUT, modOnPayload())
        @JvmStatic fun heartbeat(token: String): ModState = heartbeat(token, "")
        @JvmStatic fun heartbeat(token: String, sessionToken: String): ModState = modState("/mod/heartbeat", token, REQUEST_TIMEOUT, sessionPayload(sessionToken))
        @JvmStatic fun modOff(token: String): ModState = modOff(token, "", REQUEST_TIMEOUT)
        // shutdown paths pass a short timeout so telemetry never stalls the client exit
        @JvmStatic fun modOff(token: String, timeout: Duration): ModState = modOff(token, "", timeout)
        @JvmStatic fun modOff(token: String, sessionToken: String): ModState = modOff(token, sessionToken, REQUEST_TIMEOUT)
        @JvmStatic fun modOff(token: String, sessionToken: String, timeout: Duration): ModState = modState("/mod/off", token, timeout, sessionPayload(sessionToken))

        @JvmStatic fun reportBan(token: String, payload: JsonObject?) = reportBan(token, payload, "")
        @JvmStatic fun reportBan(token: String, payload: JsonObject?, sessionToken: String) {
            val body = payload ?: JsonObject()
            if (sessionToken.isNotBlank()) body.addProperty("session_token", sessionToken)
            val req = authorized(request("/ban").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)), token).build()
            execute(req, "/ban")
        }

        private fun modState(path: String, token: String, timeout: Duration, payload: JsonObject): ModState {
            val req = authorized(request(path, timeout).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)), token).build()
            val body = send(req, path)
            return ModState(!body.has("ok") || body.get("ok").asBoolean, body.has("active") && !body.get("active").isJsonNull && body.get("active").asBoolean, longValue(body, "total_seconds", 0L), string(body, "session_token"))
        }
        private fun modOnPayload() = JsonObject().apply { addProperty("use_session_token", true) }
        private fun sessionPayload(sessionToken: String) = JsonObject().apply { if (sessionToken.isNotBlank()) addProperty("session_token", sessionToken) }
        private fun request(path: String) = request(path, REQUEST_TIMEOUT)
        private fun request(path: String, timeout: Duration) = HttpRequest.newBuilder(URI.create(BASE_URL + path)).timeout(timeout).header("Accept", "application/json").header("User-Agent", USER_AGENT)
        private fun authorized(builder: HttpRequest.Builder, token: String) = builder.header("Authorization", "Bearer $token")

        private fun execute(request: HttpRequest, endpoint: String): HttpResponse<String> {
            val response: HttpResponse<String>
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AetherApiException("$endpoint was interrupted", 0, "", e)
            } catch (e: Exception) {
                throw AetherApiException("$endpoint could not be reached: ${e.javaClass.simpleName}", 0, "", e)
            }
            val status = response.statusCode()
            if (status < 200 || status >= 300) {
                val body = parse(response.body())
                val errorCode = if (body == null) "" else string(body, "error")
                throw AetherApiException("$endpoint returned HTTP $status" + if (errorCode.isEmpty()) "" else " ($errorCode)", status, errorCode)
            }
            return response
        }
        private fun send(request: HttpRequest, endpoint: String): JsonObject {
            val response = execute(request, endpoint)
            val body = parse(response.body()) ?: throw AetherApiException("$endpoint returned a non-JSON body", response.statusCode(), "")
            return body
        }
        private fun parse(raw: String?): JsonObject? {
            if (raw == null || raw.isBlank()) return null
            return try { val element: JsonElement = JsonParser.parseString(raw); if (element != null && element.isJsonObject) element.asJsonObject else null } catch (_: RuntimeException) { null }
        }
        private fun string(obj: JsonObject?, key: String): String {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull) return ""
            return try { obj.get(key).asString.trim() } catch (_: RuntimeException) { "" }
        }
        // total_seconds arrives as a json string on some endpoints, so parse via text
        private fun longValue(obj: JsonObject, key: String, fallback: Long): Long {
            val raw = string(obj, key)
            if (raw.isEmpty()) return fallback
            return try { java.lang.Long.parseLong(raw) } catch (_: NumberFormatException) { fallback }
        }
    }
}
