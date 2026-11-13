package dev.aether.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.Aether
import dev.aether.config.AetherConfig
import dev.aether.notification.NotificationManager
import dev.aether.ui.MainGUIRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.ArrayList
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AetherLanguageManager private constructor() {
    companion object {
        private val REPO_CONTENTS_URI = URI.create("https://api.github.com/repos/mizly/aether/contents/translations?ref=main")
        private val HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).version(HttpClient.Version.HTTP_1_1).build()
        private val LANGUAGE_DIR: Path = FabricLoader.getInstance().configDir.resolve("aether").resolve("lang")
        private val INITIALIZED = AtomicBoolean()
        private val REFRESH_IN_PROGRESS = AtomicBoolean()

        @JvmStatic fun init() {
            ensureLanguageDir()
            reloadSelectedLanguage()
            if (INITIALIZED.compareAndSet(false, true)) refreshFromRemoteAsync(false)
        }

        @JvmStatic fun onConfigLoaded() {
            ensureLanguageDir()
            reloadSelectedLanguage()
            refreshLocalizedUi()
        }

        @JvmStatic fun getAvailableLanguageCodes(): List<String> {
            ensureLanguageDir()
            val codes = ArrayList<String>()
            addLanguageCode(codes, "en_us")
            addLanguageCode(codes, getSelectedLanguageCode())
            try {
                Files.list(LANGUAGE_DIR).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.filter { it.endsWith(".json") }
                        .map { normalizeLanguageCode(it.substring(0, it.length - 5)) }.sorted(languageComparator()).forEach { addLanguageCode(codes, it) }
                }
            } catch (e: IOException) {
                Aether.LOGGER.debug("Failed to list cached language packs: {}", e.message)
            }
            codes.sortWith(languageComparator())
            return codes
        }

        @JvmStatic fun getSelectedLanguageCode(): String = normalizeLanguageCode(AetherConfig.LANGUAGE_CODE.get())

        @JvmStatic fun getSelectedLanguageIndex(options: java.util.List<String>?): Int {
            if (options == null || options.isEmpty()) return 0
            val index = options.indexOf(getSelectedLanguageCode())
            return if (index >= 0) index else 0
        }

        @JvmStatic fun selectLanguage(languageCode: String?) {
            val normalized = normalizeLanguageCode(languageCode)
            if (normalized != getSelectedLanguageCode()) {
                AetherConfig.LANGUAGE_CODE.set(normalized)
                AetherConfig.save()
            }
            reloadSelectedLanguage()
            refreshLocalizedUi()
            NotificationManager.success("Language Changed", normalized)
        }

        @JvmStatic fun refreshFromRemoteAsync(notifyUser: Boolean) {
            if (!REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
                if (notifyUser) runOnClientThread { NotificationManager.info("Language Pack Refresh", "Refresh already in progress") }
                return
            }
            val thread = Thread({
                try {
                    val downloaded = refreshFromRemote()
                    reloadSelectedLanguage()
                    refreshLocalizedUi()
                    if (notifyUser) {
                        val message = if (downloaded == 1) AetherLang.localize("Downloaded 1 language pack")
                        else String.format(Locale.US, AetherLang.localize("Downloaded %d language packs"), downloaded)
                        runOnClientThread { NotificationManager.success("Language Pack Refresh", message) }
                    }
                } catch (e: Exception) {
                    Aether.LOGGER.warn("Failed to refresh language packs: {}", e.message)
                    if (notifyUser) runOnClientThread {
                        NotificationManager.error("Language Pack Refresh Failed", if (e.message == null || e.message!!.isBlank()) "Unable to fetch language packs" else e.message!!)
                    }
                } finally {
                    REFRESH_IN_PROGRESS.set(false)
                }
            }, "aether-language-refresh")
            thread.isDaemon = true
            thread.start()
        }

        @JvmStatic fun reloadSelectedLanguage() {
            val selected = getSelectedLanguageCode()
            AetherLang.installExternalTranslations(selected, loadTranslations(selected))
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun refreshFromRemote(): Int {
            ensureLanguageDir()
            val contents = fetchRepositoryContents()
            var downloaded = 0
            for (element in contents) {
                if (!element.isJsonObject) continue
                val entry = element.asJsonObject
                val type = getAsString(entry, "type")
                val name = getAsString(entry, "name")
                val downloadUrl = getAsString(entry, "download_url")
                if (type != "file" || name.isBlank() || downloadUrl.isBlank() || !name.endsWith(".json")) continue
                val body = fetchString(URI.create(downloadUrl))
                parseTranslationObject(body)
                writeLanguageFile(name, body)
                downloaded++
            }
            return downloaded
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun fetchRepositoryContents(): JsonArray {
            val root = JsonParser.parseString(fetchString(REPO_CONTENTS_URI))
            if (!root.isJsonArray) throw IOException("GitHub contents response was not an array")
            return root.asJsonArray
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun fetchString(uri: URI): String {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").header("User-Agent", "AetherClient").GET().build()
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw IOException("HTTP ${response.statusCode()} for $uri")
            return response.body()
        }

        @Throws(IOException::class)
        private fun writeLanguageFile(fileName: String, body: String) {
            val target = LANGUAGE_DIR.resolve(fileName)
            val temp = target.resolveSibling("$fileName.tmp")
            Files.writeString(temp, body, StandardCharsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun loadTranslations(languageCode: String): Map<String, String> {
            val path = LANGUAGE_DIR.resolve(normalizeLanguageCode(languageCode) + ".json")
            if (!Files.exists(path)) return java.util.Collections.emptyMap<String, String>()
            try {
                return parseTranslationObject(Files.readString(path, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                Aether.LOGGER.warn("Failed to load language pack '{}': {}", languageCode, e.message)
                return java.util.Collections.emptyMap<String, String>()
            }
        }

        private fun parseTranslationObject(json: String): Map<String, String> {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) throw IllegalArgumentException("Language pack root must be a JSON object")
            val translations = LinkedHashMap<String, String>()
            val objectValue: JsonObject = root.asJsonObject
            for ((key, value) in objectValue.entrySet()) {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) continue
                translations[key] = value.asString
            }
            return translations
        }

        private fun refreshLocalizedUi() {
            MainGUIRegistry.invalidate()
            MainGUIRegistry.refresh()
            runOnClientThread {
                val client = Minecraft.getInstance()
                if (client.gui.screen() != null) client.gui.screen()!!.resize(client.window.guiScaledWidth, client.window.guiScaledHeight)
            }
        }

        private fun runOnClientThread(action: Runnable) {
            val client = Minecraft.getInstance() ?: return
            client.execute(action)
        }

        private fun ensureLanguageDir() {
            try { Files.createDirectories(LANGUAGE_DIR) }
            catch (e: IOException) { throw IllegalStateException("Failed to create language directory", e) }
        }

        private fun addLanguageCode(codes: MutableList<String>, code: String) {
            val normalized = normalizeLanguageCode(code)
            if (!codes.contains(normalized)) codes.add(normalized)
        }

        private fun languageComparator(): Comparator<String> = Comparator.comparing<String, Boolean> { it != "en_us" }.thenComparing(Comparator.naturalOrder<String>().let { String.CASE_INSENSITIVE_ORDER })

        private fun normalizeLanguageCode(value: String?): String {
            if (value == null || value.isBlank()) return "en_us"
            return value.trim().lowercase(Locale.ROOT).replace('-', '_')
        }

        private fun getAsString(objectValue: JsonObject?, key: String?): String {
            if (objectValue == null || key == null || !objectValue.has(key) || !objectValue.get(key).isJsonPrimitive) return ""
            return try { objectValue.get(key).asString } catch (_: Exception) { "" }
        }
    }
}
