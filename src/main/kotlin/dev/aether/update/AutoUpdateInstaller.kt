package dev.aether.update

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.Aether
import dev.aether.notification.NotificationManager
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.CodeSource
import java.time.Duration
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.CompletableFuture

class AutoUpdateInstaller private constructor() {
    companion object {
        private const val MOD_ID = "aether"
        private const val API_URL = "https://api.github.com/repos/mizly/aether/releases/latest"
        private const val POPUP_TITLE = "Aether"

        private val HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        @Volatile
        private var installStarted = false

        @Volatile
        private var status = "Idle"

        @JvmStatic
        fun checkAndInstallLatest() {
            if (installStarted) {
                return
            }

            installStarted = true
            status = "Checking for updates"
            CompletableFuture.runAsync {
                try {
                    val release = fetchLatestRelease()
                    val currentVersion = currentVersion()
                    val latestVersion = stripTagPrefix(release.tagName())
                    if (!UpdateChecker.isNewer(latestVersion, stripTagPrefix(currentVersion))) {
                        status = "Current (" + currentVersion + ")"
                        Aether.LOGGER.info("[aether] AutoUpdate: already current ({})", currentVersion)
                        return@runAsync
                    }

                    status = "Downloading " + release.tagName()
                    val jarBytes = downloadReleaseJar(release)
                    val install = installUpdate(release, jarBytes)
                    val helperScript = schedulePostExitPrompt(install, currentVersion, release.tagName())
                    status = "Installed: " + currentVersion + " -> " + release.tagName()
                    Aether.LOGGER.info(
                        "[aether] AutoUpdate: installed update {} -> {}; newModPath={}, oldModDeleted={}, helper={}",
                        currentVersion,
                        release.tagName(),
                        install.newModPath(),
                        install.oldModDeleted(),
                        helperScript
                    )

                    val client = Minecraft.getInstance()
                    client.execute {
                        NotificationManager.success(
                            "Aether Updated",
                            "The mod has been updated from version " + currentVersion + " to version "
                                + release.tagName() + "!",
                            10000
                        )
                    }
                } catch (e: Exception) {
                    installStarted = false
                    status = "Failed: " + e.message
                    Aether.LOGGER.warn("[aether] AutoUpdate: failed to install latest release", e)
                    val client = Minecraft.getInstance()
                    client.execute {
                        NotificationManager.error("Aether Auto Update Failed", e.message, 8000)
                    }
                }
            }
        }

        @JvmStatic
        fun getStatus(): String = status

        private fun fetchLatestRelease(): Release {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "aether-mod")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build()
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw IOException("GitHub returned HTTP " + response.statusCode())
            }

            val root = JsonParser.parseString(response.body()).asJsonObject
            val tagName = if (root.has("tag_name")) root.get("tag_name").asString.trim() else ""
            if (tagName.isEmpty()) {
                throw IOException("Latest GitHub release did not include a tag.")
            }

            val assets = if (root.has("assets") && root.get("assets").isJsonArray) {
                root.getAsJsonArray("assets")
            } else {
                JsonArray()
            }
            for (assetElement: JsonElement in assets) {
                if (!assetElement.isJsonObject) {
                    continue
                }
                val asset = assetElement.asJsonObject
                val name = if (asset.has("name")) asset.get("name").asString.trim() else ""
                val downloadUrl = if (asset.has("browser_download_url")) {
                    asset.get("browser_download_url").asString.trim()
                } else {
                    ""
                }
                if (name.lowercase(Locale.ROOT).endsWith(".jar") && downloadUrl.isNotEmpty()) {
                    return Release(tagName, name, URI.create(downloadUrl))
                }
            }

            throw IOException("Latest GitHub release did not include a jar asset.")
        }

        private fun downloadReleaseJar(release: Release): ByteArray {
            val request = HttpRequest.newBuilder()
                .uri(release.downloadUri())
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "aether-mod")
                .GET()
                .build()
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null) {
                throw IOException("Jar download returned HTTP " + response.statusCode())
            }
            return response.body()
        }

        private fun installUpdate(release: Release, jarBytes: ByteArray): Install {
            val gameDir = FabricLoader.getInstance().gameDir.toAbsolutePath().normalize()
            val modsDir = gameDir.resolve("mods").toAbsolutePath().normalize()
            Files.createDirectories(modsDir)

            val oldModPath = currentModPath()
            val newModPath = resolveInstallPath(modsDir, release, oldModPath)
            if (!newModPath.startsWith(modsDir)) {
                throw IOException("Resolved update target escaped the mods directory.")
            }

            val tempPath = modsDir.resolve(newModPath.fileName.toString() + ".part").toAbsolutePath().normalize()
            Aether.LOGGER.info("[aether] AutoUpdate: writing update temp file {}", tempPath)
            Files.write(tempPath, jarBytes)
            try {
                Files.move(tempPath, newModPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                Aether.LOGGER.info("[aether] AutoUpdate: moved update jar into place with atomic move.")
            } catch (atomicMoveFailure: IOException) {
                Aether.LOGGER.warn(
                    "[aether] AutoUpdate: atomic move failed; retrying normal move: {}",
                    atomicMoveFailure.message
                )
                Files.move(tempPath, newModPath, StandardCopyOption.REPLACE_EXISTING)
            }

            val oldModDeleted = deleteIfManagedOldMod(oldModPath, modsDir, newModPath)
            val staleTargets = deleteStaleAetherJars(modsDir, newModPath, oldModPath)
            return Install(gameDir, modsDir, newModPath, oldModPath, oldModDeleted, staleTargets)
        }

        private fun resolveInstallPath(modsDir: Path, release: Release, oldModPath: Path?): Path {
            val fileName = sanitizeFileName(release.assetName(), release.tagName())
            val candidate = modsDir.resolve(fileName).toAbsolutePath().normalize()
            if (oldModPath == null || candidate != oldModPath.toAbsolutePath().normalize()) {
                return candidate
            }

            val version = stripTagPrefix(release.tagName()).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val suffix = Integer.toHexString(kotlin.math.abs(release.downloadUri().toString().hashCode()))
            return modsDir.resolve("aether-" + version + "-" + suffix + ".jar").toAbsolutePath().normalize()
        }

        private fun deleteIfManagedOldMod(oldModPath: Path?, modsDir: Path, newModPath: Path): Boolean {
            if (oldModPath == null) {
                return false
            }

            val normalizedOld = oldModPath.toAbsolutePath().normalize()
            if (normalizedOld == newModPath || !normalizedOld.startsWith(modsDir)
                || !Files.isRegularFile(normalizedOld) || !isManagedAetherJar(normalizedOld)
            ) {
                Aether.LOGGER.info("[aether] AutoUpdate: old mod delete skipped; current mod is not a managed mods jar.")
                return false
            }

            try {
                val deleted = Files.deleteIfExists(normalizedOld)
                Aether.LOGGER.info("[aether] AutoUpdate: old mod delete attempted; deleted={}", deleted)
                return deleted
            } catch (e: IOException) {
                Aether.LOGGER.warn("[aether] AutoUpdate: old mod could not be deleted immediately: {}", e.message)
                return false
            }
        }

        private fun deleteStaleAetherJars(modsDir: Path, newModPath: Path, oldModPath: Path?): List<Path> {
            val undeleted: MutableList<Path> = ArrayList()
            val normalizedOld = oldModPath?.toAbsolutePath()?.normalize()
            Files.list(modsDir).use { stream ->
                for (path in stream
                    .filter { Files.isRegularFile(it) }
                    .map { candidate -> candidate.toAbsolutePath().normalize() }
                    .filter { candidate -> candidate != newModPath }
                    .filter { candidate -> normalizedOld == null || candidate != normalizedOld }
                    .filter { candidate -> isManagedAetherJar(candidate) }
                    .toList()
                ) {
                    try {
                        Files.deleteIfExists(path)
                    } catch (e: IOException) {
                        undeleted.add(path)
                        Aether.LOGGER.warn("[aether] AutoUpdate: stale mod could not be deleted immediately: {}", path)
                    }
                }
            }
            return undeleted
        }

        private fun schedulePostExitPrompt(
            install: Install,
            fromVersion: String,
            toVersion: String
        ): Path {
            val updateStateDir = FabricLoader.getInstance()
                .configDir
                .resolve("aether")
                .resolve("update")
                .toAbsolutePath()
                .normalize()
            Files.createDirectories(updateStateDir)

            val pid = ProcessHandle.current().pid()
            val script = if (isWindows()) {
                writeWindowsPostExitScript(updateStateDir, pid, install, fromVersion, toVersion)
            } else {
                writeUnixPostExitScript(updateStateDir, pid, install, fromVersion, toVersion)
            }

            val builder = if (isWindows()) {
                ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
            } else {
                ProcessBuilder("sh", script.toString())
            }
            builder.directory(install.gameDir().toFile())
            builder.start()
            return script
        }

        private fun writeWindowsPostExitScript(
            updateStateDir: Path,
            pid: Long,
            install: Install,
            fromVersion: String,
            toVersion: String
        ): Path {
            val script = updateStateDir.resolve("aether-update-" + System.currentTimeMillis() + ".ps1")
            val content = StringBuilder(1024)
            content.append("\$ErrorActionPreference = 'Continue'\n")
            content.append("try { Wait-Process -Id ").append(pid).append(" -ErrorAction Stop } catch {}\n")
            content.append("Start-Sleep -Milliseconds 500\n")
            if (install.oldModPath() != null && !install.oldModDeleted()) {
                content.append("try { Remove-Item -LiteralPath ")
                    .append(powerShellString(install.oldModPath()!!.toString()))
                    .append(" -Force -ErrorAction Stop } catch {}\n")
            }
            for (staleTarget in install.staleTargets()) {
                content.append("try { Remove-Item -LiteralPath ")
                    .append(powerShellString(staleTarget.toString()))
                    .append(" -Force -ErrorAction Stop } catch {}\n")
            }
            content.append("try { Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show(")
                .append(powerShellString(popupMessage(fromVersion, toVersion)))
                .append(", ")
                .append(powerShellString(POPUP_TITLE))
                .append(") | Out-Null } catch {}\n")
            content.append("Remove-Item -LiteralPath \$PSCommandPath -Force\n")
            Files.writeString(script, content.toString(), StandardCharsets.UTF_8)
            return script
        }

        private fun writeUnixPostExitScript(
            updateStateDir: Path,
            pid: Long,
            install: Install,
            fromVersion: String,
            toVersion: String
        ): Path {
            val script = updateStateDir.resolve("aether-update-" + System.currentTimeMillis() + ".sh")
            val message = popupMessage(fromVersion, toVersion)
            val content = StringBuilder(1024)
            content.append("#!/bin/sh\n")
            content.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n")
            content.append("sleep 1\n")
            if (install.oldModPath() != null && !install.oldModDeleted()) {
                content.append("rm -f -- ").append(shellQuote(install.oldModPath()!!.toString())).append(" >/dev/null 2>&1 || true\n")
            }
            for (staleTarget in install.staleTargets()) {
                content.append("rm -f -- ").append(shellQuote(staleTarget.toString())).append(" >/dev/null 2>&1 || true\n")
            }
            content.append("if command -v osascript >/dev/null 2>&1; then\n")
            content.append(" nohup osascript -e ")
                .append(shellQuote("display dialog \"" + message + "\" with title \"" + POPUP_TITLE
                    + "\" buttons {\"OK\"} default button 1"))
                .append(" >/dev/null 2>&1 &\n")
            content.append("elif command -v zenity >/dev/null 2>&1; then\n")
            content.append(" nohup zenity --info --title=").append(shellQuote(POPUP_TITLE))
                .append(" --text=").append(shellQuote(message)).append(" >/dev/null 2>&1 &\n")
            content.append("elif command -v kdialog >/dev/null 2>&1; then\n")
            content.append(" nohup kdialog --title ").append(shellQuote(POPUP_TITLE))
                .append(" --msgbox ").append(shellQuote(message)).append(" >/dev/null 2>&1 &\n")
            content.append("elif command -v xmessage >/dev/null 2>&1; then\n")
            content.append(" nohup xmessage -center ").append(shellQuote(message)).append(" >/dev/null 2>&1 &\n")
            content.append("fi\n")
            content.append("rm -f -- ").append(shellQuote(script.toString())).append(" >/dev/null 2>&1 || true\n")
            content.append("exit 0\n")
            Files.writeString(script, content.toString(), StandardCharsets.UTF_8)
            return script
        }

        private fun isManagedAetherJar(path: Path): Boolean {
            val name = path.fileName.toString().lowercase(Locale.ROOT)
            return name.endsWith(".jar") && (name == "aether.jar" || name.startsWith("aether-") || name.startsWith("aether_"))
        }

        private fun sanitizeFileName(fileName: String?, tagName: String): String {
            var normalized = fileName?.trim() ?: ""
            if (!normalized.lowercase(Locale.ROOT).endsWith(".jar")) {
                normalized = "aether-" + stripTagPrefix(tagName) + ".jar"
            }
            normalized = normalized.replace('\\', '/')
            val slash = normalized.lastIndexOf('/')
            if (slash >= 0) {
                normalized = normalized.substring(slash + 1)
            }
            normalized = normalized.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (normalized.isBlank() || normalized == ".jar") "aether-" + stripTagPrefix(tagName) + ".jar" else normalized
        }

        private fun currentModPath(): Path? {
            try {
                val fabricPath = FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .flatMap { container ->
                        container.rootPaths.stream()
                            .map { path -> physicalPath(path) }
                            .filter { path -> path != null && Files.isRegularFile(path) }
                            .findFirst()
                    }
                    .orElse(null)
                if (fabricPath != null) {
                    return fabricPath.toAbsolutePath().normalize()
                }
            } catch (ignored: Exception) {
            }

            try {
                val codeSource: CodeSource? = AutoUpdateInstaller::class.java.protectionDomain.codeSource
                if (codeSource == null || codeSource.location == null) {
                    return null
                }
                val path = Path.of(codeSource.location.toURI()).toAbsolutePath().normalize()
                return if (Files.isRegularFile(path)) path else null
            } catch (ignored: Exception) {
                return null
            }
        }

        private fun physicalPath(path: Path?): Path? {
            if (path == null) {
                return null
            }
            try {
                if (Files.isRegularFile(path)) {
                    return path
                }
                val uri = path.toUri()
                if (uri == null || !"jar".equals(uri.scheme, ignoreCase = true)) {
                    return path
                }
                var value = uri.schemeSpecificPart
                val separator = value.indexOf("!/")
                if (separator >= 0) {
                    value = value.substring(0, separator)
                }
                return if (value.isBlank()) null else Path.of(URI.create(value))
            } catch (ignored: Exception) {
                return null
            }
        }

        private fun currentVersion(): String = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map { container -> container.metadata.version.friendlyString }
            .orElse("unknown")

        private fun stripTagPrefix(tag: String?): String {
            if (tag == null) {
                return ""
            }
            return if (tag.startsWith("v") || tag.startsWith("V")) tag.substring(1) else tag
        }

        private fun popupMessage(fromVersion: String, toVersion: String): String {
            return "Aether has been updated from version " + fromVersion + " to version " + toVersion + "!"
        }

        private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

        private fun powerShellString(value: String?): String {
            val normalized = value ?: ""
            return "'" + normalized.replace("'", "''") + "'"
        }
    }

    private class Release(val tag: String, val asset: String, val uri: URI) {
        fun tagName(): String = tag
        fun assetName(): String = asset
        fun downloadUri(): URI = uri
    }

    private class Install(
        val game: Path,
        val mods: Path,
        val newPath: Path,
        val oldPath: Path?,
        val deleted: Boolean,
        val stale: List<Path>
    ) {
        fun gameDir(): Path = game
        fun modsDir(): Path = mods
        fun newModPath(): Path = newPath
        fun oldModPath(): Path? = oldPath
        fun oldModDeleted(): Boolean = deleted
        fun staleTargets(): List<Path> = stale
    }
}
