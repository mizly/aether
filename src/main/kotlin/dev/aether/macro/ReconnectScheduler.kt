package dev.aether.macro

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ReconnectScheduler {
    enum class ReconnectMode { STANDARD, PROXY_RESTART }

    companion object {
        private var scheduler: ScheduledExecutorService? = null
        private var pendingReconnect: ScheduledFuture<*>? = null
        @Volatile private var reconnectGeneration = 0L
        private var stateLoaded = false
        private var cachedReconnectAt = 0L
        private var cachedShouldResume = false
        private var cachedReconnectMode = ReconnectMode.STANDARD
        private val STATE_FILE: Path = FabricLoader.getInstance().gameDir.resolve("macro_rest_state.json")

        @Synchronized private fun cacheState(reconnectAt: Long, shouldResume: Boolean, reconnectMode: ReconnectMode?) {
            stateLoaded = true
            cachedReconnectAt = reconnectAt
            cachedShouldResume = shouldResume
            cachedReconnectMode = reconnectMode ?: ReconnectMode.STANDARD
        }
        @Synchronized private fun ensureStateLoaded() {
            if (stateLoaded) return
            if (!Files.exists(STATE_FILE)) { cacheState(0, false, ReconnectMode.STANDARD); return }
            try {
                val obj = JsonParser.parseString(Files.readString(STATE_FILE)).asJsonObject
                cacheState(if (obj.has("reconnectAt")) obj.get("reconnectAt").asLong else 0L, obj.has("shouldResume") && obj.get("shouldResume").asBoolean, parseReconnectMode(obj))
            } catch (_: Exception) { cacheState(0, false, ReconnectMode.STANDARD) }
        }
        private fun parseReconnectMode(obj: JsonObject?): ReconnectMode {
            if (obj == null || !obj.has("reconnectMode")) return ReconnectMode.STANDARD
            return try { ReconnectMode.valueOf(obj.get("reconnectMode").asString) } catch (_: Exception) { ReconnectMode.STANDARD }
        }
        private fun saveReconnectTime(epochSeconds: Long, shouldResume: Boolean, reconnectMode: ReconnectMode) {
            try {
                val obj = JsonObject()
                obj.addProperty("reconnectAt", epochSeconds)
                obj.addProperty("shouldResume", shouldResume)
                obj.addProperty("reconnectMode", reconnectMode.name)
                Files.writeString(STATE_FILE, Gson().toJson(obj))
                cacheState(epochSeconds, shouldResume, reconnectMode)
            } catch (e: IOException) { e.printStackTrace() }
        }
        @JvmStatic fun loadReconnectTime(): Long { ensureStateLoaded(); return cachedReconnectAt }
        @JvmStatic fun shouldResume(): Boolean { ensureStateLoaded(); return cachedShouldResume }
        @JvmStatic fun getReconnectMode(): ReconnectMode { ensureStateLoaded(); return cachedReconnectMode }
        @JvmStatic fun clearState() {
            try { Files.deleteIfExists(STATE_FILE); cacheState(0, false, ReconnectMode.STANDARD) } catch (e: IOException) { e.printStackTrace() }
        }
        @JvmStatic fun scheduleReconnect(delaySeconds: Long, shouldResume: Boolean) = scheduleReconnect(delaySeconds, shouldResume, ReconnectMode.STANDARD)
        @JvmStatic fun scheduleReconnect(delaySeconds: Long, shouldResume: Boolean, reconnectMode: ReconnectMode) {
            if (scheduler == null || scheduler!!.isShutdown) scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aether-reconnect").apply { isDaemon = true } }
            pendingReconnect?.cancel(false)
            val reconnectAt = Instant.now().epochSecond + delaySeconds
            saveReconnectTime(reconnectAt, shouldResume, reconnectMode)
            val generation = ++reconnectGeneration
            pendingReconnect = scheduler!!.schedule({ doReconnect(generation) }, delaySeconds, TimeUnit.SECONDS)
        }
        private fun doReconnect(generation: Long) {
            val mc = Minecraft.getInstance()
            mc.execute {
                if (generation != reconnectGeneration || mc.player != null || mc.connection != null) return@execute
                val serverData = ServerData("Hypixel", "mc.hypixel.net", ServerData.Type.OTHER)
                ConnectScreen.startConnecting(TitleScreen(), mc, ServerAddress.parseString("mc.hypixel.net"), serverData, false, null)
            }
        }
        @JvmStatic fun cancel() {
            reconnectGeneration++
            pendingReconnect?.cancel(false)
            clearState()
        }
        @JvmStatic fun isPending(): Boolean = pendingReconnect != null && !pendingReconnect!!.isDone
    }
}
