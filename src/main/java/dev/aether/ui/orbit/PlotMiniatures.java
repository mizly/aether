package dev.aether.ui.orbit;

import com.mojang.blaze3d.platform.NativeImage;
import dev.aether.renderer.NVGRenderer;
import dev.aether.util.GardenPlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

// a top-down picture of each garden plot: the top block of every column in its texture's colour and biome tint,
// shaded by height the way maps are. recorded whenever a plot is loaded during play and kept on disk, so the picker
// shows every plot, not only the ones near you. reads blocks, sends nothing
final class PlotMiniatures {
    static final int SIZE = 48;
    private static final int STEP = GardenPlots.PLOT_SIZE / SIZE;
    private static final int PLOTS = 25;

    private static final int[][] pixels = new int[PLOTS][];
    private static final long[] recorded = new long[PLOTS];
    private static final int[] versions = new int[PLOTS];
    private static final int[] uploaded = new int[PLOTS];
    private static final int[] handles = new int[PLOTS];
    private static final Map<Identifier, Integer> textureColors = new HashMap<>();
    private static java.util.UUID owner;

    private PlotMiniatures() {
    }

    // the plot's picture as a nanovg image: the recorded one when the plot was ever loaded, else one drawn from what
    // the plot menu says grows there (item), so every plot has a picture
    static int image(NVGRenderer nvg, int plot, String item) {
        if (plot < 0 || plot >= PLOTS) return -1;
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.isSameThread()) loadFor(client, nvg);
        int[] argb = pixels[plot];
        if (argb == null) return drawn(nvg, plot, item);
        if (handles[plot] <= 0 || uploaded[plot] != versions[plot]) {
            ByteBuffer rgba = MemoryUtil.memAlloc(SIZE * SIZE * 4);
            try {
                for (int i = 0; i < SIZE * SIZE; i++) {
                    int c = argb[i];
                    rgba.put(i * 4, (byte) (c >> 16)).put(i * 4 + 1, (byte) (c >> 8)).put(i * 4 + 2, (byte) c)
                            .put(i * 4 + 3, (byte) (c >>> 24));
                }
                if (handles[plot] > 0) nvg.deleteImage(handles[plot]);
                handles[plot] = nvg.createImageRGBA(SIZE, SIZE, NanoVG.NVG_IMAGE_NEAREST, rgba);
                uploaded[plot] = versions[plot];
            } finally {
                MemoryUtil.memFree(rgba);
            }
        }
        return handles[plot] > 0 ? handles[plot] : -1;
    }

    private static final int[] drawnHandles = new int[PLOTS];
    private static final String[] drawnItems = new String[PLOTS];

    private static int drawn(NVGRenderer nvg, int plot, String item) {
        String key = item == null ? "" : item;
        if (drawnHandles[plot] > 0 && key.equals(drawnItems[plot])) return drawnHandles[plot];
        int[] argb = PlotSketch.draw(plot, item, SIZE);
        ByteBuffer rgba = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            for (int i = 0; i < SIZE * SIZE; i++) {
                int c = argb[i];
                rgba.put(i * 4, (byte) (c >> 16)).put(i * 4 + 1, (byte) (c >> 8)).put(i * 4 + 2, (byte) c)
                        .put(i * 4 + 3, (byte) (c >>> 24));
            }
            if (drawnHandles[plot] > 0) nvg.deleteImage(drawnHandles[plot]);
            drawnHandles[plot] = nvg.createImageRGBA(SIZE, SIZE, NanoVG.NVG_IMAGE_NEAREST, rgba);
            drawnItems[plot] = key;
        } finally {
            MemoryUtil.memFree(rgba);
        }
        return drawnHandles[plot] > 0 ? drawnHandles[plot] : -1;
    }

    // milliseconds since the plot was last recorded, or Long.MAX_VALUE when never
    static long age(int plot) {
        return recorded[plot] == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - recorded[plot];
    }

    // samples a loaded plot from the world and keeps it, in memory and on disk; false when it isn't loaded
    static boolean record(Minecraft client, int plot) {
        if (client.level == null || client.player == null) return false;
        loadFor(client, null);
        int[] argb = sample(client, plot);
        if (argb == null) return false;
        pixels[plot] = argb;
        recorded[plot] = System.currentTimeMillis();
        versions[plot]++;
        save(client, plot, argb);
        return true;
    }

    // pictures belong to whoever is playing, so switching accounts swaps the set
    private static void loadFor(Minecraft client, NVGRenderer nvg) {
        if (client.player == null) return;
        java.util.UUID id = client.player.getUUID();
        if (id.equals(owner)) return;
        owner = id;
        for (int i = 0; i < PLOTS; i++) {
            if (handles[i] > 0 && nvg != null) nvg.deleteImage(handles[i]);
            handles[i] = 0;
            pixels[i] = null;
            recorded[i] = 0L;
            versions[i]++;
            java.nio.file.Path file = GardenMemory.dir(id).resolve("plot_" + i + ".png");
            if (!java.nio.file.Files.isRegularFile(file)) continue;
            try (InputStream in = java.nio.file.Files.newInputStream(file); NativeImage image = NativeImage.read(in)) {
                if (image.getWidth() != SIZE || image.getHeight() != SIZE) continue;
                int[] argb = new int[SIZE * SIZE];
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) argb[y * SIZE + x] = image.getPixel(x, y);
                pixels[i] = argb;
                recorded[i] = java.nio.file.Files.getLastModifiedTime(file).toMillis();
            } catch (Exception ignored) {
            }
        }
    }

    private static void save(Minecraft client, int plot, int[] argb) {
        try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) image.setPixel(x, y, argb[y * SIZE + x]);
            java.nio.file.Path dir = GardenMemory.dir(client.player.getUUID());
            java.nio.file.Files.createDirectories(dir);
            image.writeToFile(dir.resolve("plot_" + plot + ".png"));
        } catch (Exception e) {
            System.err.println("[Aether] could not save the plot " + plot + " picture: " + e.getMessage());
        }
    }

    private static int[] sample(Minecraft client, int plot) {
        ClientLevel world = client.level;
        GardenPlots.Bounds bounds = GardenPlots.boundsForPlot(plot);
        if (bounds == null || !world.hasChunksAt(bounds.minX(), bounds.minZ(), bounds.maxX() - 1, bounds.maxZ() - 1)) {
            return null;
        }
        int[] heights = new int[SIZE * SIZE];
        int[] colors = new int[SIZE * SIZE];
        WorldColumns columns = new WorldColumns(world);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                int x = bounds.minX() + col * STEP + STEP / 2;
                int z = bounds.minZ() + row * STEP + STEP / 2;
                int y = columns.top(x, z);
                if (y == Integer.MIN_VALUE) {
                    heights[row * SIZE + col] = world.getMinY();
                    colors[row * SIZE + col] = 0xFF2A2F36;
                    continue;
                }
                BlockState state = columns.state(x, y, z);
                heights[row * SIZE + col] = y;
                colors[row * SIZE + col] = color(client, world, state, pos.set(x, y, z));
            }
        }
        int[] out = new int[SIZE * SIZE];
        for (int i = 0; i < SIZE * SIZE; i++) {
            int north = i >= SIZE ? heights[i - SIZE] : heights[i];
            float shade = heights[i] > north ? 1.12f : heights[i] < north ? 0.8f : 1f;
            int c = colors[i];
            int r = Math.min(255, Math.round(((c >> 16) & 255) * shade));
            int g = Math.min(255, Math.round(((c >> 8) & 255) * shade));
            int b = Math.min(255, Math.round((c & 255) * shade));
            out[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        return out;
    }

    // the block's own texture colour times its tint, the way it reads from above in game
    private static int color(Minecraft client, ClientLevel world, BlockState state, BlockPos pos) {
        if (state.is(Blocks.WATER)) return 0xFF3F76E4;
        if (state.is(Blocks.LAVA)) return 0xFFE0661C;
        int base;
        try {
            Identifier sprite = client.getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite()
                    .contents().name();
            base = textureColor(client, sprite);
        } catch (RuntimeException e) {
            base = 0;
        }
        if (base == 0) return 0xFF000000 | state.getMapColor(world, pos).col;
        BlockTintSource tint = client.getBlockColors().getTintSource(state, 0);
        if (tint != null) {
            try {
                base = multiply(base, tint.colorInWorld(state, world, pos));
            } catch (RuntimeException ignored) {
            }
        }
        return base;
    }

    // the average of a block texture's opaque pixels, read once from the resource packs; 0 when it cannot be read
    private static int textureColor(Minecraft client, Identifier sprite) {
        Integer cached = textureColors.get(sprite);
        if (cached != null) return cached;
        int color = 0;
        Identifier file = Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        var resource = client.getResourceManager().getResource(file);
        if (resource.isPresent()) {
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                long r = 0, g = 0, b = 0, n = 0;
                int frame = Math.min(image.getWidth(), image.getHeight());
                for (int y = 0; y < frame; y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getPixel(x, y);
                        if ((argb >>> 24) < 128) continue;
                        r += (argb >> 16) & 255;
                        g += (argb >> 8) & 255;
                        b += argb & 255;
                        n++;
                    }
                }
                if (n > 0) color = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            } catch (Exception ignored) {
            }
        }
        textureColors.put(sprite, color);
        return color;
    }

    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255;
        int g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255;
        int bl = (a & 255) * (b & 255) / 255;
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
