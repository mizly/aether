package dev.aether.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// minecraft textures ("minecraft:textures/item/wheat.png", "aether:...") as nearest-filtered nanovg images, cached by id
// render thread only: get() creates images, so it only loads inside a nanovg frame
public final class McTextures {

    public record Texture(int handle, int width, int height, boolean missing) {}

    record Pixels(ByteBuffer rgba, int width, int height) {}

    // a .png.mcmeta "gui" scaling; widths, heights and borders are gui pixels, whatever the texture's resolution
    public sealed interface Scaling {
        record Stretch() implements Scaling {}

        record Tile(int width, int height) implements Scaling {}

        record NineSlice(int width, int height, int left, int top, int right, int bottom, boolean stretchInner)
                implements Scaling {}
    }

    public record GuiSprite(Texture texture, Scaling scaling) {}

    private static final Texture MISSING = new Texture(0, 0, 0, true);

    private static final Scaling STRETCH = new Scaling.Stretch();

    private static final Map<String, Texture> cache = new HashMap<>();

    private static final Map<String, Scaling> scalings = new ConcurrentHashMap<>();

    private McTextures() {}

    // a gui sprite id ("minecraft:widget/button", under textures/gui/sprites) or a full texture id, with its scaling;
    // the texture only loads inside a nanovg frame
    public static GuiSprite sprite(String id) {
        String texture = spriteTexture(id);
        return new GuiSprite(get(texture), scaling(texture));
    }

    // any thread; stretch when the texture has no gui scaling
    public static Scaling scaling(String id) {
        return scalings.computeIfAbsent(spriteTexture(id), McTextures::readScaling);
    }

    static String spriteTexture(String id) {
        Identifier location = Identifier.tryParse(id);
        if (location == null || location.getPath().startsWith("textures/")) return id;
        return location.getNamespace() + ":textures/gui/sprites/" + location.getPath() + ".png";
    }

    // a texture that fails to load stays missing until the next invalidate()
    public static Texture get(String id) {
        return cached(id, id, false);
    }

    // white with the texture's alpha, for additive passes that should follow a sprite's shape but not its colours
    static Texture mask(String id) {
        return cached(id + "#mask", id, true);
    }

    private static Texture cached(String key, String id, boolean mask) {
        Texture texture = cache.get(key);
        if (texture != null) return texture;
        if (!NanoVGManager.isDrawing()) return MISSING;
        texture = load(id, mask);
        cache.put(key, texture);
        return texture;
    }

    // any thread; old images go at the start of the next frame, once nothing queued in this one still samples them
    public static void invalidate() {
        scalings.clear();
        NanoVGManager.runInNextFrame(McTextures::deleteAll);
    }

    public static void registerReloadListener() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("aether", "mc_textures"),
                (ResourceManagerReloadListener) manager -> {
                    invalidate();
                    McIcons.invalidate();
                    McBitmapFont.invalidate();
                });
    }

    static void destroy(long vg) {
        deleteAll(vg);
    }

    private static void deleteAll() {
        deleteAll(NanoVGManager.getVg());
    }

    private static void deleteAll(long vg) {
        for (Texture texture : cache.values()) {
            if (texture.handle() > 0) NanoVG.nvgDeleteImage(vg, texture.handle());
        }
        cache.clear();
    }

    private static Texture load(String id, boolean mask) {
        Pixels pixels = decode(id);
        if (pixels == null) return MISSING;
        try {
            if (mask) {
                for (int i = 0; i < pixels.rgba().limit(); i += 4) {
                    pixels.rgba().put(i, (byte) 0xFF).put(i + 1, (byte) 0xFF).put(i + 2, (byte) 0xFF);
                }
            }
            int handle = NanoVG.nvgCreateImageRGBA(NanoVGManager.getVg(), pixels.width(), pixels.height(),
                    NanoVG.NVG_IMAGE_NEAREST, pixels.rgba());
            if (handle <= 0) {
                System.err.println("[Aether] NanoVG could not create an image for " + id);
                return MISSING;
            }
            return new Texture(handle, pixels.width(), pixels.height(), false);
        } finally {
            MemoryUtil.memFree(pixels.rgba());
        }
    }

    private static Scaling readScaling(String texture) {
        Identifier location = Identifier.tryParse(texture);
        if (location == null) return STRETCH;
        try {
            return McAssets.metadata(location).getSection(GuiMetadataSection.TYPE)
                    .map(section -> scaling(section.scaling())).orElse(STRETCH);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Aether] Could not read gui scaling of " + texture + ": " + e.getMessage());
            return STRETCH;
        }
    }

    private static Scaling scaling(GuiSpriteScaling scaling) {
        return switch (scaling) {
            case GuiSpriteScaling.Tile tile -> new Scaling.Tile(tile.width(), tile.height());
            case GuiSpriteScaling.NineSlice nine -> new Scaling.NineSlice(nine.width(), nine.height(),
                    nine.border().left(), nine.border().top(), nine.border().right(), nine.border().bottom(),
                    nine.stretchInner());
            default -> STRETCH;
        };
    }

    // straight-alpha rgba of the first animation frame; null when unreadable. the caller frees the buffer
    static Pixels decode(String id) {
        Identifier location = Identifier.tryParse(id);
        if (location == null) {
            System.err.println("[Aether] Invalid texture id: " + id);
            return null;
        }
        try (InputStream in = McAssets.open(location)) {
            if (in == null) {
                System.err.println("[Aether] Texture not found: " + id);
                return null;
            }
            try (NativeImage image = NativeImage.read(in)) {
                return firstFrame(image, McAssets.animation(location));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Aether] Could not read texture " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static Pixels firstFrame(NativeImage image, Optional<AnimationMetadataSection> animation) {
        int width = image.getWidth();
        int height = image.getHeight();
        int frameW = width;
        int frameH = height;
        int frameX = 0;
        int frameY = 0;
        if (animation.isPresent()) {
            FrameSize size = animation.get().calculateFrameSize(width, height);
            frameW = Math.clamp(size.width(), 1, width);
            frameH = Math.clamp(size.height(), 1, height);
            // frames run left to right, then top to bottom
            int index = animation.get().frames().filter(frames -> !frames.isEmpty())
                    .map(List::getFirst).map(AnimationFrame::index).orElse(0);
            int columns = Math.max(1, width / frameW);
            frameX = (index % columns) * frameW;
            frameY = (index / columns) * frameH;
            if (frameY + frameH > height) {
                frameX = 0;
                frameY = 0;
            }
        }
        ByteBuffer rgba = MemoryUtil.memAlloc(frameW * frameH * 4);
        long source = image.getPointer();
        long target = MemoryUtil.memAddress(rgba);
        for (int row = 0; row < frameH; row++) {
            MemoryUtil.memCopy(source + ((long) (frameY + row) * width + frameX) * 4L,
                    target + (long) row * frameW * 4L, frameW * 4L);
        }
        return new Pixels(rgba, frameW, frameH);
    }
}
