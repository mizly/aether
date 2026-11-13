package dev.aether.util

import dev.aether.Aether
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.FilePackResources
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Optional
import java.util.function.Consumer

class AetherFallbackPackLoader private constructor() {
    companion object {
        private const val DEFAULT_PACK_URL = "https://resourcepacks.hypixel.net/SkyBlock/00aa4b88-fba6-4b04-9382-fe473d453b6f/84.zip"
        private const val FALLBACK_RESOURCE = "/pack_fallback.zip"
        private val PACK_DIR: Path = FabricLoader.getInstance().configDir.resolve("aether")
        private val PACK_FILE: Path = PACK_DIR.resolve("pack.zip")
        private var pack: Pack? = null

        @Synchronized
        private fun getPack(): Pack {
            if (pack == null) pack = loadPack()
            return pack ?: throw IllegalStateException("Fallback pack was not loaded")
        }

        private fun loadPack(): Pack {
            try {
                Files.createDirectories(PACK_DIR)
                HttpClient.newHttpClient().use { client ->
                    if (shouldDownload() && !downloadPack(client) && shouldDownload()) loadBundledFallbackPack()
                }
                return buildPack()
            } catch (exception: Exception) {
                throw IllegalStateException("Failed to prepare Aether fallback resource pack", exception)
            }
        }

        @JvmStatic
        fun shouldDownload(): Boolean = !Files.isRegularFile(PACK_FILE)

        private fun downloadPack(client: HttpClient): Boolean {
            Aether.LOGGER.info("Downloading Hypixel SkyBlock resource pack fallback")
            val request = HttpRequest.newBuilder().uri(URI.create(DEFAULT_PACK_URL)).header("Accept-Encoding", "gzip").timeout(Duration.ofSeconds(10)).build()
            var tmp: Path? = null
            try {
                tmp = Files.createTempFile(PACK_DIR, "pack", ".tmp")
                val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    Aether.LOGGER.warn("Hypixel pack download returned HTTP {}, using cached or bundled fallback", response.statusCode())
                    Files.deleteIfExists(tmp)
                    return false
                }
                Files.move(tmp, PACK_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                Aether.LOGGER.info("Hypixel SkyBlock resource pack fallback downloaded")
                return true
            } catch (exception: Exception) {
                Aether.LOGGER.warn("Failed to download Hypixel SkyBlock resource pack fallback", exception)
                if (tmp != null) try { Files.deleteIfExists(tmp) } catch (_: Exception) { }
                return false
            }
        }

        private fun loadBundledFallbackPack() {
            Aether.LOGGER.info("Extracting bundled Hypixel SkyBlock fallback resource pack")
            AetherFallbackPackLoader::class.java.getResourceAsStream(FALLBACK_RESOURCE).use { input: InputStream? ->
                if (input == null) throw IllegalStateException("Bundled fallback pack missing at $FALLBACK_RESOURCE")
                Files.copy(input, PACK_FILE, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun buildPack(): Pack {
            val locationInfo = PackLocationInfo("hypixel_skyblock", Component.literal("Aether: SkyBlock fallback resources"), PackSource.BUILT_IN, Optional.empty())
            val selectionConfig = PackSelectionConfig(true, Pack.Position.BOTTOM, true)
            val resourcesSupplier = FilePackResources.FileResourcesSupplier(PACK_FILE.toFile())
            return Pack.readMetaAndCreate(locationInfo, resourcesSupplier, PackType.CLIENT_RESOURCES, selectionConfig)
                ?: throw IllegalStateException("Failed to read fallback pack metadata for $PACK_FILE")
        }
    }

    class FallbackPackRepositorySource : RepositorySource {
        override fun loadPacks(onLoad: Consumer<Pack>) { onLoad.accept(getPack()) }
    }
}
