package dev.aether.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;

// the skins skyblock puts on its garden pests, fetched once from mojang's texture server (where the game loads every
// skull skin from, never the game server) and kept on disk; until one arrives the orbit menu draws a silverfish
public final class PestHeads {
    private static final String[] HASHES = {
            "70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c",
            "6403ba4027a333d8d2fd32ab59d1cfdbaa7d908d80d2381db2a69cbe65450ad8",
            "4b24a482a32db1ea78fb98060b0c2fa4a373cbd18a68edddeb7419455a59cda9",
            "be6baf6431a9daa2ca604d5a3c26e9a761d5952f0817174a4fe0b764616e21ff",
            "52a9fe05bc663efcd12e56a3ccc5ec035bf577b78708548b6f4ffcf1d30eccfe",
            "7a79d0fd677b54530961117ef84adc206e2cc5045c1344d61d776bf8ac2fe1ba"};

    private record Ready(int index, byte[] png) {
    }

    private static final Identifier[] textures = new Identifier[HASHES.length];
    private static final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    private static volatile boolean started;

    private PestHeads() {
    }

    public static int count() {
        return HASHES.length;
    }

    // the head's texture once it has loaded, else null; the first call starts loading them all
    public static Identifier texture(int index) {
        if (!started) start();
        for (Ready r; (r = ready.poll()) != null; ) register(r);
        return textures[Math.floorMod(index, HASHES.length)];
    }

    private static void register(Ready r) {
        try {
            NativeImage image = NativeImage.read(r.png());
            Identifier id = Identifier.fromNamespaceAndPath("aether", "orbit/pest_head_" + r.index());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "aether pest head", image));
            textures[r.index()] = id;
        } catch (Exception e) {
            System.err.println("[Aether] could not use pest head " + r.index() + ": " + e.getMessage());
        }
    }

    // one daemon thread reads the cache or downloads what is missing; the game's own threads are left alone
    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(PestHeads::load, "aether-pest-heads");
        thread.setDaemon(true);
        thread.start();
    }

    private static void load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("cache").resolve("pest_heads");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        for (int i = 0; i < HASHES.length; i++) {
            Path file = dir.resolve(HASHES[i] + ".png");
            try {
                if (!Files.isRegularFile(file)) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + HASHES[i]))
                            .timeout(Duration.ofSeconds(10)).GET().build();
                    HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200) continue;
                    Files.createDirectories(dir);
                    Files.write(file, response.body());
                }
                try (InputStream in = Files.newInputStream(file)) {
                    ready.add(new Ready(i, in.readAllBytes()));
                }
            } catch (Exception e) {
                System.err.println("[Aether] could not fetch pest head " + i + ": " + e.getMessage());
            }
        }
    }
}
