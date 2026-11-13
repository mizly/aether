package dev.aether.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.Aether
import dev.aether.notification.NotificationManager
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class UpdateChecker private constructor() {
    companion object {
        private const val MOD_ID = "aether"
        private const val API_URL = "https://api.github.com/repos/mizly/aether/releases/latest"
        private const val RELEASES_URL = "https://github.com/mizly/aether/releases/latest"
        private const val TIMEOUT_MS = 8_000

        @Volatile
        private var cachedLatestVersion: String? = null

        @Volatile
        private var fetchStarted = false

        @JvmStatic
        fun checkAndNotify() {
            val client = Minecraft.getInstance()
            if (!fetchStarted) {
                fetchStarted = true
                CompletableFuture.runAsync {
                    val latest = fetchLatestTag()
                    cachedLatestVersion = latest ?: ""
                    if (latest != null) {
                        notifyIfOutdated(latest)
                    }
                }
                return
            }

            val cached = cachedLatestVersion
            if (cached != null && cached.isNotEmpty()) {
                notifyIfOutdated(cached)
            }
        }

        @JvmStatic
        fun getCachedLatestVersion(): String? = cachedLatestVersion

        private fun fetchLatestTag(): String? {
            var connection: HttpURLConnection? = null
            try {
                val url = URI.create(API_URL).toURL()
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "aether-mod")
                connection.setRequestProperty("Accept", "application/vnd.github+json")

                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    Aether.LOGGER.warn("[aether] UpdateChecker: GitHub API returned HTTP {}", status)
                    return null
                }

                connection.getInputStream().use { input ->
                    InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                        val obj = JsonParser.parseReader(reader).asJsonObject
                        if (obj.has("tag_name")) {
                            return obj.get("tag_name").asString.trim()
                        }
                    }
                }
            } catch (e: IOException) {
                Aether.LOGGER.warn("[aether] UpdateChecker: failed to reach GitHub: {}", e.message)
            } catch (e: IllegalStateException) {
                Aether.LOGGER.warn("[aether] UpdateChecker: failed to reach GitHub: {}", e.message)
            } finally {
                connection?.disconnect()
            }
            return null
        }

        private fun notifyIfOutdated(latestTag: String) {
            val client = Minecraft.getInstance()
            val currentVersion = getCurrentVersion()
            val normalizedLatest = normalizeTag(latestTag)
            val normalizedCurrent = normalizeTag(currentVersion)

            if (!isNewer(normalizedLatest, normalizedCurrent)) {
                Aether.LOGGER.info("[aether] UpdateChecker: up to date ({})", currentVersion)
                return
            }

            Aether.LOGGER.info(
                "[aether] UpdateChecker: update available: current={} latest={}",
                currentVersion,
                latestTag
            )

            client.execute {
                if (client.player == null) {
                    return@execute
                }

                NotificationManager.warning(
                    "Aether Update Available",
                    "Current: " + currentVersion + " Latest: " + latestTag,
                    8000
                )
                client.player!!.sendSystemMessage(createUpdateMessage(currentVersion, latestTag))
            }
        }

        private fun createUpdateMessage(currentVersion: String, latestTag: String): MutableComponent {
            val prefix = Component.literal("[Aether] ").withStyle(ChatFormatting.DARK_GRAY)
            val message = Component.literal("Update available! Current: ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(currentVersion).withStyle(ChatFormatting.RED))
                .append(Component.literal(" -> Latest: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(latestTag).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
            val link = Component.literal("[Download]")
                .withStyle(
                    Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(ClickEvent.OpenUrl(URI.create(RELEASES_URL)))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Open GitHub releases page")))
                )

            return prefix.append(message).append(link)
        }

        private fun getCurrentVersion(): String = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map { container -> container.metadata.version.friendlyString }
            .orElse("unknown")

        private fun normalizeTag(tag: String?): String {
            if (tag == null) {
                return ""
            }
            return if (tag.startsWith("v") || tag.startsWith("V")) tag.substring(1) else tag
        }

        @JvmStatic
        fun isNewer(candidate: String, current: String): Boolean {
            val candidateParts = parseVersion(candidate)
            val currentParts = parseVersion(current)

            for (i in 0 until maxOf(candidateParts.numbers.size, currentParts.numbers.size)) {
                val candidateNumber = if (i < candidateParts.numbers.size) candidateParts.numbers[i] else 0
                val currentNumber = if (i < currentParts.numbers.size) currentParts.numbers[i] else 0
                if (candidateNumber > currentNumber) {
                    return true
                }
                if (candidateNumber < currentNumber) {
                    return false
                }
            }

            return candidateParts.revision > currentParts.revision
        }

        private fun parseVersion(version: String?): VersionParts {
            if (version == null || version.isEmpty()) {
                return VersionParts(intArrayOf(0), 0)
            }

            var clean = version.split("+".toRegex(), limit = 0)[0]
            var revision = 0

            val revisionMarker = clean.lastIndexOf("-r")
            if (revisionMarker >= 0) {
                val revisionPart = clean.substring(revisionMarker + 2)
                try {
                    revision = Integer.parseInt(revisionPart)
                    clean = clean.substring(0, revisionMarker)
                } catch (ignored: NumberFormatException) {
                    // Leave revision at 0 if the suffix is malformed.
                }
            } else {
                val suffixMarker = clean.indexOf('-')
                if (suffixMarker >= 0) {
                    clean = clean.substring(0, suffixMarker)
                }
            }

            val parts = clean.split("\\.".toRegex(), limit = 0)
            val numbers = IntArray(parts.size)
            for (i in parts.indices) {
                try {
                    numbers[i] = Integer.parseInt(parts[i])
                } catch (e: NumberFormatException) {
                    numbers[i] = 0
                }
            }
            return VersionParts(numbers, revision)
        }
    }

    private class VersionParts(val numbers: IntArray, val revision: Int)
}
