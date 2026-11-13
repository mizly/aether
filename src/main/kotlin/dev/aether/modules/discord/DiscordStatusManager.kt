package dev.aether.modules.discord

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.session.DynamicRestManager
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class DiscordStatusManager private constructor() {
    companion object {
        private const val SCREENSHOT_ATTACHMENT_PLACEHOLDER = "__AETHER_SCREENSHOT__"
        private var lastUpdateTime = System.currentTimeMillis()
        private var isTakingScreenshot = false
        private var screenshotRequestTime = 0L
        @Volatile private var forceStatusUpdatePending = false
        @Volatile private var pendingScreenshotPayloadJson: String? = null

        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            if (!shouldSendStatus() && !forceStatusUpdatePending) {
                isTakingScreenshot = false
                return
            }
            val now = System.currentTimeMillis()
            // convert minutes to milliseconds for interval
            if (!isTakingScreenshot && now - lastUpdateTime >= AetherConfig.DISCORD_STATUS_UPDATE_TIME.get() * 60 * 1000L) {
                lastUpdateTime = now
                takeAndSendScreenshot(client)
            }
        }

        @JvmStatic fun requestManualStatusUpdate(): Boolean {
            val client = Minecraft.getInstance()
            val webhookUrl = AetherConfig.DISCORD_WEBHOOK_URL.get()
            if (client == null || webhookUrl == null || webhookUrl.isBlank()) {
                return false
            }
            if (isTakingScreenshot) {
                return false
            }
            forceStatusUpdatePending = true
            pendingScreenshotPayloadJson = null
            requestScreenshot(client)
            return true
        }

        @JvmStatic fun sendFailsafeAlert(reason: String, actionDone: String) {
            val client = Minecraft.getInstance()
            if (client == null || !isWebhookConfigured() || isTakingScreenshot) {
                return
            }
            forceStatusUpdatePending = true
            pendingScreenshotPayloadJson = buildFailsafePayload(reason, actionDone)
            requestScreenshot(client)
        }

        private fun takeAndSendScreenshot(client: Minecraft) {
            if (!shouldSendStatus()) {
                return
            }
            pendingScreenshotPayloadJson = null
            requestScreenshot(client)
        }

        private fun requestScreenshot(client: Minecraft) {
            isTakingScreenshot = true
            screenshotRequestTime = System.currentTimeMillis()
            client.execute {
                Screenshot.grab(client.gameDirectory, client.gameRenderer.mainRenderTarget()) { _ ->
                    // Ignore the message
                }
            }
            Thread({
                try {
                    Thread.sleep(2000L)
                    sendScreenshotAsync(client)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    isTakingScreenshot = false
                    forceStatusUpdatePending = false
                    pendingScreenshotPayloadJson = null
                }
            }, "Aether-Discord-Screenshot").start()
        }

        private fun sendScreenshotAsync(client: Minecraft) {
            Thread({
                try {
                    val forced = forceStatusUpdatePending
                    val payloadOverride = pendingScreenshotPayloadJson
                    if (!forced && !shouldSendStatus()) {
                        return@Thread
                    }
                    val screenshotsDir = File(client.gameDirectory, "screenshots")
                    if (!screenshotsDir.exists()) {
                        return@Thread
                    }
                    val latestScreenshot = Files.list(screenshotsDir.toPath()).use { paths ->
                        paths.filter { Files.isRegularFile(it) }
                                .filter { it.toString().endsWith(".png") }
                                .filter { System.currentTimeMillis() - it.toFile().lastModified() < 15000L } // created in last
                                // 15s
                                .max(Comparator.comparingLong<Path> { it.toFile().lastModified() })
                                .map(Path::toFile)
                                .orElse(null)
                    }
                    if (latestScreenshot == null) {
                        return@Thread
                    }
                    if (!forced && !shouldSendStatus()) {
                        return@Thread
                    }
                    sendWebhook(AetherConfig.DISCORD_WEBHOOK_URL.get(), latestScreenshot, payloadOverride)
                    try {
                        Files.deleteIfExists(latestScreenshot.toPath())
                    } catch (_: Exception) {
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isTakingScreenshot = false
                    forceStatusUpdatePending = false
                    pendingScreenshotPayloadJson = null
                }
            }).start()
        }

        private fun shouldSendStatus(): Boolean {
            if (!isWebhookConfigured()) {
                return false
            }
            val state = MacroStateManager.getCurrentState()
            return state != MacroState.State.OFF && state != MacroState.State.RECOVERING
        }

        private fun isWebhookConfigured(): Boolean {
            val webhookUrl = AetherConfig.DISCORD_WEBHOOK_URL.get()
            if (!AetherConfig.SEND_DISCORD_STATUS.get() || webhookUrl == null || webhookUrl.isEmpty()) {
                return false
            }
            return true
        }

        private fun sendWebhook(webhookUrl: String, imageFile: File, payloadJsonOverride: String?) {
            val boundary = "===" + System.currentTimeMillis() + "==="
            val url = java.net.URI(webhookUrl).toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            connection.setRequestProperty("User-Agent", "Java-DiscordWebhook-Client")
            connection.outputStream.use { outputStream ->
                val writer = PrintWriter(OutputStreamWriter(outputStream, "UTF-8"), true)
                writer.append("--").append(boundary).append("\r\n")
                writer.append("Content-Disposition: form-data; name=\"payload_json\"\r\n")
                writer.append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                val jsonFileName = imageFile.name
                val json = if (payloadJsonOverride != null) {
                    payloadJsonOverride.replace(SCREENSHOT_ATTACHMENT_PLACEHOLDER, escapeJson(jsonFileName))
                } else {
                    buildStatusPayload(jsonFileName)
                }
                writer.append(json).append("\r\n")
                writer.append("--").append(boundary).append("\r\n")
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"" + imageFile.name + "\"\r\n")
                writer.append("Content-Type: image/png\r\n\r\n")
                writer.flush()
                Files.copy(imageFile.toPath(), outputStream)
                outputStream.flush()
                writer.append("\r\n")
                writer.append("--").append(boundary).append("--\r\n")
                writer.flush()
            }
            val responseCode = connection.responseCode
            if (responseCode >= 200 && responseCode < 300) {
                System.out.println("Discord webhook sent successfully.")
            } else {
                System.err.println("Discord webhook failed with response code: " + responseCode)
            }
        }

        private fun buildStatusPayload(imageFileName: String): String {
            val state = java.lang.String.valueOf(MacroStateManager.getCurrentState())
            val sessionTotalSecs = MacroStateManager.getSessionRunningTime() / 1000
            val sHours = sessionTotalSecs / 3600
            val sMins = (sessionTotalSecs % 3600) / 60
            val sSecs = sessionTotalSecs % 60
            val sessionStr = if (sHours > 0) {
                String.format("%02d:%02d:%02d", sHours, sMins, sSecs)
            } else {
                String.format("%02d:%02d", sMins, sSecs)
            }
            val nextRestTriggerMs = DynamicRestManager.getNextRestTriggerMs()
            val nextRestStr: String
            if (nextRestTriggerMs > 0 && !DynamicRestManager.isRestPending()) {
                val remaining = nextRestTriggerMs - System.currentTimeMillis()
                if (remaining > 0) {
                    val totalSecs = remaining / 1000
                    val rHours = totalSecs / 3600
                    val rMins = (totalSecs % 3600) / 60
                    val rSecs = totalSecs % 60
                    nextRestStr = if (rHours > 0) {
                        String.format("%02d:%02d:%02d", rHours, rMins, rSecs)
                    } else {
                        String.format("%02d:%02d", rMins, rSecs)
                    }
                } else {
                    nextRestStr = "Starting soon..."
                }
            } else if (DynamicRestManager.isRestPending()) {
                nextRestStr = "Resting now..."
            } else {
                nextRestStr = "Not scheduled"
            }
            return "{\n" +
                    "  \"embeds\": [{\n" +
                    "    \"title\": \"Status Update\",\n" +
                    "    \"description\": \"Here is your latest Aether status update! :rocket:\",\n" +
                    "    \"color\": 5814783,\n" +
                    "    \"fields\": [\n" +
                    "      {\n" +
                    "        \"name\": \"Current State\",\n" +
                    "        \"value\": \"`" + escapeJson(state) + "`\",\n" +
                    "        \"inline\": true\n" +
                    "      },\n" +
                    "      {\n" +
                    "        \"name\": \"Session Time\",\n" +
                    "        \"value\": \"`" + escapeJson(sessionStr) + "`\",\n" +
                    "        \"inline\": true\n" +
                    "      },\n" +
                    "      {\n" +
                    "        \"name\": \"Time Until Next Rest\",\n" +
                    "        \"value\": \"`" + escapeJson(nextRestStr) + "`\",\n" +
                    "        \"inline\": true\n" +
                    "      }\n" +
                    "    ],\n" +
                    "    \"image\": {\n" +
                    "      \"url\": \"attachment://" + escapeJson(imageFileName) + "\"\n" +
                    "    }\n" +
                    "  }]\n" +
                    "}"
        }

        private fun buildFailsafePayload(reason: String?, actionDone: String?): String {
            val state = java.lang.String.valueOf(MacroStateManager.getCurrentState())
            val sessionTotalSecs = MacroStateManager.getSessionRunningTime() / 1000
            val sHours = sessionTotalSecs / 3600
            val sMins = (sessionTotalSecs % 3600) / 60
            val sSecs = sessionTotalSecs % 60
            val sessionStr = if (sHours > 0) {
                String.format("%02d:%02d:%02d", sHours, sMins, sSecs)
            } else {
                String.format("%02d:%02d", sMins, sSecs)
            }
            return "{\n" +
                    "  \"content\": \"@everyone\",\n" +
                    "  \"allowed_mentions\": {\n" +
                    "    \"parse\": [\"everyone\"]\n" +
                    "  },\n" +
                    "  \"embeds\": [{\n" +
                    "    \"title\": \"Failsafe Alert\",\n" +
                    "    \"description\": \"Aether detected a failsafe and captured the current screen.\",\n" +
                    "    \"color\": 15158332,\n" +
                    "    \"fields\": [\n" +
                    "      {\n" +
                    "        \"name\": \"Current State\",\n" +
                    "        \"value\": \"`" + escapeJson(state) + "`\",\n" +
                    "        \"inline\": true\n" +
                    "      },\n" +
                    "      {\n" +
                    "        \"name\": \"Session Time\",\n" +
                    "        \"value\": \"`" + escapeJson(sessionStr) + "`\",\n" +
                    "        \"inline\": true\n" +
                    "      },\n" +
                    "      {\n" +
                    "        \"name\": \"Action Done\",\n" +
                    "        \"value\": \"`" + escapeJson(actionDone) + "`\",\n" +
                    "        \"inline\": true\n" +
                    "      },\n" +
                    "      {\n" +
                    "        \"name\": \"Reason\",\n" +
                    "        \"value\": \"" + escapeJson(reason) + "\",\n" +
                    "        \"inline\": false\n" +
                    "      }\n" +
                    "    ],\n" +
                    "    \"image\": {\n" +
                    "      \"url\": \"attachment://" + SCREENSHOT_ATTACHMENT_PLACEHOLDER + "\"\n" +
                    "    }\n" +
                    "  }]\n" +
                    "}"
        }

        private fun escapeJson(value: String?): String {
            if (value == null) {
                return ""
            }
            val escaped = StringBuilder(value.length + 16)
            for (i in 0 until value.length) {
                val c = value[i]
                when (c) {
                    '\\' -> escaped.append("\\\\")
                    '"' -> escaped.append("\\\"")
                    '\b' -> escaped.append("\\b")
                    '\u000c' -> escaped.append("\\f")
                    '\n' -> escaped.append("\\n")
                    '\r' -> escaped.append("\\r")
                    '\t' -> escaped.append("\\t")
                    else -> {
                        if (c.code < 0x20) {
                            escaped.append(java.lang.String.format("\\u%04x", c.code))
                        } else {
                            escaped.append(c)
                        }
                    }
                }
            }
            return escaped.toString()
        }
    }
}
