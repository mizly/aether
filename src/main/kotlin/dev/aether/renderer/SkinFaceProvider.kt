package dev.aether.renderer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

class SkinFaceProvider private constructor() {
    companion object {
        private const val SRC = 8
        private const val CELL = 8
        private const val TEX = SRC * CELL
        private const val RETRY_COOLDOWN_MS = 10_000L
        @Volatile private var loadedUuid: UUID? = null
        @Volatile private var pendingPixels: ByteBuffer? = null
        @Volatile private var fetching = false
        @Volatile private var nextAttemptMs: Long = 0
        private var imageHandle = -1

        @JvmStatic fun render(nvg: NVGRenderer, x: Float, y: Float, size: Float, alpha: Float) {
            ensureLoaded()
            val pixels = pendingPixels
            if (pixels != null) {
                pendingPixels = null
                if (imageHandle != -1) nvg.deleteImage(imageHandle)
                imageHandle = nvg.createImageRGBA(TEX, TEX, pixels)
                MemoryUtil.memFree(pixels)
            }
            if (imageHandle != -1) nvg.image(imageHandle, x, y, size, size, 3f, alpha)
        }

        private fun ensureLoaded() {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            val connection = mc.connection ?: return
            val uuid = player.uuid
            if (uuid == loadedUuid || fetching) return
            if (System.currentTimeMillis() < nextAttemptMs) return
            val info: PlayerInfo = connection.getPlayerInfo(uuid) ?: return
            val url = skinUrl(info.profile) ?: return
            fetching = true
            val thread = Thread({ download(uuid, url) }, "aether-skin-face")
            thread.isDaemon = true
            thread.start()
        }

        private fun download(uuid: UUID, url: String) {
            try {
                java.net.URI.create(url).toURL().openStream().use { input ->
                    val bytes = input.readAllBytes()
                    val img = NativeImage.read(bytes)
                    try {
                        pendingPixels = buildPixels(img)
                        loadedUuid = uuid
                    } finally {
                        img.close()
                    }
                }
            } catch (e: Exception) {
                nextAttemptMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS
                System.err.println("[Aether] failed to load skin face: ${e.message}")
            } finally {
                fetching = false
            }
        }

        private fun buildPixels(img: NativeImage): ByteBuffer {
            val buf = MemoryUtil.memAlloc(TEX * TEX * 4)
            for (sy in 0 until SRC) for (sx in 0 until SRC) {
                val base = img.getPixel(8 + sx, 8 + sy)
                val hat = img.getPixel(40 + sx, 8 + sy)
                writeCell(buf, sx, sy, composite(base, hat))
            }
            return buf
        }

        private fun writeCell(buf: ByteBuffer, sx: Int, sy: Int, rgba: Int) {
            val r = (rgba ushr 24).toByte()
            val g = (rgba ushr 16).toByte()
            val b = (rgba ushr 8).toByte()
            val a = rgba.toByte()
            for (dy in 0 until CELL) {
                val row = (sy * CELL + dy) * TEX
                for (dx in 0 until CELL) {
                    val idx = (row + sx * CELL + dx) * 4
                    buf.put(idx, r)
                    buf.put(idx + 1, g)
                    buf.put(idx + 2, b)
                    buf.put(idx + 3, a)
                }
            }
        }

        private fun composite(baseArgb: Int, hatArgb: Int): Int {
            val ha = ((hatArgb ushr 24) and 0xFF) / 255f
            val br = (baseArgb ushr 16) and 0xFF
            val bg = (baseArgb ushr 8) and 0xFF
            val bb = baseArgb and 0xFF
            val hr = (hatArgb ushr 16) and 0xFF
            val hg = (hatArgb ushr 8) and 0xFF
            val hb = hatArgb and 0xFF
            val r = Math.round(hr * ha + br * (1f - ha))
            val g = Math.round(hg * ha + bg * (1f - ha))
            val b = Math.round(hb * ha + bb * (1f - ha))
            return (r shl 24) or (g shl 16) or (b shl 8) or 0xFF
        }

        private fun skinUrl(profile: GameProfile): String? {
            for (property: Property in profile.properties().get("textures")) {
                try {
                    val json = String(Base64.getDecoder().decode(property.value()), java.nio.charset.StandardCharsets.UTF_8)
                    val root = JsonParser.parseString(json).asJsonObject
                    val textures: JsonObject? = root.getAsJsonObject("textures")
                    if (textures == null || !textures.has("SKIN")) continue
                    return textures.getAsJsonObject("SKIN").get("url").asString
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
