package dev.aether.renderer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.platform.NativeImage;
import dev.aether.macro.MacroWorkerThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

public final class SkinFaceProvider {

    private static final int SRC = 8;
    private static final int CELL = 8;
    private static final int TEX = SRC * CELL;

    private static volatile UUID requestedUuid;
    private static volatile ByteBuffer pendingPixels;
    private static volatile boolean fetching;
    private static int imageHandle = -1;

    private SkinFaceProvider() {
    }

    public static void render(NVGRenderer nvg, float x, float y, float size, float alpha) {
        ensureLoaded();

        ByteBuffer pixels = pendingPixels;
        if (pixels != null) {
            pendingPixels = null;
            if (imageHandle != -1) nvg.deleteImage(imageHandle);
            imageHandle = nvg.createImageRGBA(TEX, TEX, pixels);
            MemoryUtil.memFree(pixels);
        }

        if (imageHandle != -1) {
            nvg.image(imageHandle, x, y, size, size, 3f, alpha);
        }
    }

    private static void ensureLoaded() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        UUID uuid = mc.player.getUUID();
        if (uuid.equals(requestedUuid)) return;

        PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
        if (info == null) return;

        String url = skinUrl(info.getProfile());
        if (url == null) return;

        requestedUuid = uuid;
        fetching = true;
        MacroWorkerThread.getInstance().submit("skin-face", () -> download(url));
    }

    private static void download(String url) {
        try (var in = java.net.URI.create(url).toURL().openStream()) {
            byte[] bytes = in.readAllBytes();
            NativeImage img = NativeImage.read(bytes);
            try {
                pendingPixels = buildPixels(img);
            } finally {
                img.close();
            }
        } catch (Exception e) {
            System.err.println("[Aether] failed to load skin face: " + e.getMessage());
        } finally {
            fetching = false;
        }
    }

    private static ByteBuffer buildPixels(NativeImage img) {
        ByteBuffer buf = MemoryUtil.memAlloc(TEX * TEX * 4);
        for (int sy = 0; sy < SRC; sy++) {
            for (int sx = 0; sx < SRC; sx++) {
                int base = img.getPixel(8 + sx, 8 + sy);
                int hat = img.getPixel(40 + sx, 8 + sy);
                int rgba = composite(base, hat);
                writeCell(buf, sx, sy, rgba);
            }
        }
        return buf;
    }

    private static void writeCell(ByteBuffer buf, int sx, int sy, int rgba) {
        byte r = (byte) (rgba >>> 24);
        byte g = (byte) (rgba >>> 16);
        byte b = (byte) (rgba >>> 8);
        byte a = (byte) rgba;
        for (int dy = 0; dy < CELL; dy++) {
            int row = (sy * CELL + dy) * TEX;
            for (int dx = 0; dx < CELL; dx++) {
                int idx = (row + sx * CELL + dx) * 4;
                buf.put(idx, r);
                buf.put(idx + 1, g);
                buf.put(idx + 2, b);
                buf.put(idx + 3, a);
            }
        }
    }

    private static int composite(int baseArgb, int hatArgb) {
        float ha = ((hatArgb >>> 24) & 0xFF) / 255f;
        int br = (baseArgb >>> 16) & 0xFF, bg = (baseArgb >>> 8) & 0xFF, bb = baseArgb & 0xFF;
        int hr = (hatArgb >>> 16) & 0xFF, hg = (hatArgb >>> 8) & 0xFF, hb = hatArgb & 0xFF;
        int r = Math.round(hr * ha + br * (1f - ha));
        int g = Math.round(hg * ha + bg * (1f - ha));
        int b = Math.round(hb * ha + bb * (1f - ha));
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    private static String skinUrl(GameProfile profile) {
        for (Property property : profile.properties().get("textures")) {
            try {
                String json = new String(Base64.getDecoder().decode(property.value()), java.nio.charset.StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonObject textures = root.getAsJsonObject("textures");
                if (textures == null || !textures.has("SKIN")) continue;
                return textures.getAsJsonObject("SKIN").get("url").getAsString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
