package dev.aether.telemetry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.Aether
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.io.Reader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Set

// kept out of aether_config.json on purpose: config profiles are shared between users and would leak the token.
class AetherTokenStore private constructor() {
    companion object {
        private const val FILE_NAME = "aether_auth.json"
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val LOCK = Any()
        @Volatile private var token = ""
        @Volatile private var discordId = ""
        @Volatile private var expiresAtMillis = 0L
        @Volatile private var loaded = false

        @JvmStatic fun load() = synchronized(LOCK) {
            loaded = true
            token = ""
            discordId = ""
            expiresAtMillis = 0L
            val path = path()
            if (path == null || !Files.exists(path)) return@synchronized
            try {
                Files.newBufferedReader(path).use { reader: Reader ->
                    val element = JsonParser.parseReader(reader)
                    if (element == null || !element.isJsonObject) return@use
                    val obj = element.asJsonObject
                    token = readString(obj, "token")
                    discordId = readString(obj, "discord_id")
                    expiresAtMillis = readLong(obj, "expires_at_ms")
                }
            } catch (e: IOException) {
                Aether.LOGGER.warn("[aether] Could not read {}: {}", FILE_NAME, e.javaClass.simpleName)
            } catch (e: RuntimeException) {
                Aether.LOGGER.warn("[aether] Could not read {}: {}", FILE_NAME, e.javaClass.simpleName)
            }
        }
        @JvmStatic fun save(newToken: String?, newDiscordId: String?) = synchronized(LOCK) { save(newToken, newDiscordId, expiresAtMillis) }
        @JvmStatic fun save(newToken: String?, newDiscordId: String?, newExpiresAtMillis: Long) = synchronized(LOCK) {
            token = newToken?.trim() ?: ""
            discordId = newDiscordId?.trim() ?: ""
            expiresAtMillis = Math.max(0L, newExpiresAtMillis)
            loaded = true
            write()
        }
        @JvmStatic fun clear() = synchronized(LOCK) {
            token = ""
            discordId = ""
            expiresAtMillis = 0L
            loaded = true
            val path = path() ?: return@synchronized
            try { Files.deleteIfExists(path) } catch (e: IOException) { Aether.LOGGER.warn("[aether] Could not delete {}: {}", FILE_NAME, e.javaClass.simpleName) }
        }
        @JvmStatic fun getToken(): String { ensureLoaded(); return token }
        @JvmStatic fun getDiscordId(): String { ensureLoaded(); return discordId }
        @JvmStatic fun getExpiresAtMillis(): Long { ensureLoaded(); return expiresAtMillis }
        @JvmStatic fun hasToken() = getToken().isNotEmpty()
        // no expiry means it predates rotation, so treat it as due
        @JvmStatic fun needsRefresh(refreshBeforeMillis: Long): Boolean {
            if (!hasToken()) return false
            val expiry = getExpiresAtMillis()
            return expiry <= 0L || System.currentTimeMillis() >= expiry - refreshBeforeMillis
        }
        @JvmStatic fun isExpired(): Boolean { val expiry = getExpiresAtMillis(); return expiry > 0L && System.currentTimeMillis() >= expiry }
        private fun ensureLoaded() { if (!loaded) load() }
        private fun write() {
            val path = path() ?: return
            val obj = JsonObject().apply { addProperty("token", token); addProperty("discord_id", discordId); addProperty("expires_at_ms", expiresAtMillis) }
            try {
                val parent = path.parent
                if (parent != null) Files.createDirectories(parent)
                val temp = if (parent == null) Path.of(FILE_NAME + ".tmp") else parent.resolve(FILE_NAME + ".tmp")
                Files.writeString(temp, GSON.toJson(obj))
                restrictPermissions(temp)
                try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
            } catch (e: IOException) { Aether.LOGGER.warn("[aether] Could not write {}: {}", FILE_NAME, e.javaClass.simpleName) }
        }
        private fun restrictPermissions(path: Path) {
            try { Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
            catch (_: IOException) { }
            catch (_: UnsupportedOperationException) { }
            catch (_: SecurityException) { }
        }
        private fun path(): Path? = try { FabricLoader.getInstance().configDir.resolve(FILE_NAME) } catch (_: RuntimeException) { null } catch (_: LinkageError) { null }
        private fun readLong(obj: JsonObject, key: String): Long {
            if (!obj.has(key) || obj.get(key).isJsonNull) return 0L
            return try { obj.get(key).asLong } catch (_: RuntimeException) { 0L }
        }
        private fun readString(obj: JsonObject, key: String): String {
            if (!obj.has(key) || obj.get(key).isJsonNull) return ""
            return try { obj.get(key).asString.trim() } catch (_: RuntimeException) { "" }
        }
    }
}
