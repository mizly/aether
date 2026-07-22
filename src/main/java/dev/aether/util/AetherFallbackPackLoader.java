package dev.aether.util;

import dev.aether.Aether;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

public final class AetherFallbackPackLoader {
    private static final String DEFAULT_PACK_URL =
            "https://resourcepacks.hypixel.net/SkyBlock/b9f0ff91-1baa-48d1-b122-29ca9f810f14/84.zip";
    private static final String FALLBACK_RESOURCE = "/pack_fallback.zip";
    private static final Path PACK_DIR = FabricLoader.getInstance().getConfigDir().resolve("aether");
    private static final Path PACK_FILE = PACK_DIR.resolve("pack.zip");

    private static Pack pack;

    private AetherFallbackPackLoader() {
    }

    private static synchronized Pack getPack() {
        if (pack == null) {
            pack = loadPack();
        }
        return pack;
    }

    private static Pack loadPack() {
        try {
            Files.createDirectories(PACK_DIR);
            try (HttpClient client = HttpClient.newHttpClient()) {
                if (!downloadPack(client) && !Files.exists(PACK_FILE)) {
                    loadBundledFallbackPack();
                }
            }
            return buildPack();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to prepare Aether fallback resource pack", exception);
        }
    }

    private static boolean downloadPack(HttpClient client) {
        Aether.LOGGER.info("Downloading Hypixel SkyBlock resource pack fallback");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_PACK_URL))
                .header("Accept-Encoding", "gzip")
                .timeout(Duration.ofSeconds(10))
                .build();

        Path tmp = null;
        try {
            tmp = Files.createTempFile(PACK_DIR, "pack", ".tmp");
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                Aether.LOGGER.warn("Hypixel pack download returned HTTP {}, using cached or bundled fallback",
                        response.statusCode());
                Files.deleteIfExists(tmp);
                return false;
            }

            Files.move(tmp, PACK_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Aether.LOGGER.info("Hypixel SkyBlock resource pack fallback downloaded");
            return true;
        } catch (Exception exception) {
            Aether.LOGGER.warn("Failed to download Hypixel SkyBlock resource pack fallback", exception);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    private static void loadBundledFallbackPack() throws Exception {
        Aether.LOGGER.info("Extracting bundled Hypixel SkyBlock fallback resource pack");
        try (InputStream input = AetherFallbackPackLoader.class.getResourceAsStream(FALLBACK_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Bundled fallback pack missing at " + FALLBACK_RESOURCE);
            }
            Files.copy(input, PACK_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Pack buildPack() {
        PackLocationInfo locationInfo = new PackLocationInfo(
                "hypixel_skyblock",
                Component.literal("Aether: SkyBlock fallback resources"),
                PackSource.BUILT_IN,
                Optional.empty());
        PackSelectionConfig selectionConfig = new PackSelectionConfig(true, Pack.Position.BOTTOM, true);
        FilePackResources.FileResourcesSupplier resourcesSupplier =
                new FilePackResources.FileResourcesSupplier(PACK_FILE.toFile());

        Pack builtPack = Pack.readMetaAndCreate(
                locationInfo,
                resourcesSupplier,
                PackType.CLIENT_RESOURCES,
                selectionConfig);
        if (builtPack == null) {
            throw new IllegalStateException("Failed to read fallback pack metadata for " + PACK_FILE);
        }
        return builtPack;
    }

    public static final class FallbackPackRepositorySource implements RepositorySource {
        @Override
        public void loadPacks(Consumer<Pack> onLoad) {
            onLoad.accept(getPack());
        }
    }
}
