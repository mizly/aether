package dev.aether.ui.orbit;

import com.mojang.blaze3d.platform.NativeImage;
import dev.aether.macro.MacroState;
import dev.aether.renderer.NVGRenderer;
import dev.aether.util.ClientUtils;
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

// a top-down picture of each garden plot as it stands in this client's loaded world: the top block of every
// column in its texture's colour and biome tint, shaded by height the way maps are. reads blocks, sends nothing
final class PlotMiniatures {
    static final int SIZE = 48;
    private static final int STEP = GardenPlots.PLOT_SIZE / SIZE;
    private static final long REFRESH_NANOS = 15_000_000_000L;
    private static final long GAP_NANOS = 5_000_000L;
    private static final int PLOTS = 25;

    private static final int[] handles = new int[PLOTS];
    private static final long[] sampled = new long[PLOTS];
    private static final Map<Identifier, Integer> textureColors = new HashMap<>();
    private static ClientLevel level;
    private static long lastSample;
    private static long gardenChecked;
    private static boolean garden;

    private PlotMiniatures() {
    }

    // the plot's picture as a nanovg image, or -1 while it is unknown; samples at most one plot every few ms
    static int image(NVGRenderer nvg, int plot) {
        if (plot < 0 || plot >= PLOTS) return -1;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || !client.isSameThread()) return -1;
        if (client.level != level) reset(nvg, client.level);
        if (!inGarden()) return handles[plot] > 0 ? handles[plot] : -1;
        long now = System.nanoTime();
        boolean stale = handles[plot] <= 0 || now - sampled[plot] > REFRESH_NANOS;
        if (stale && now - lastSample > GAP_NANOS) {
            lastSample = now;
            sampled[plot] = now;
            ByteBuffer pixels = sample(client, plot);
            if (pixels != null) {
                try {
                    if (handles[plot] > 0) nvg.deleteImage(handles[plot]);
                    handles[plot] = nvg.createImageRGBA(SIZE, SIZE, NanoVG.NVG_IMAGE_NEAREST, pixels);
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            }
        }
        return handles[plot] > 0 ? handles[plot] : -1;
    }

    private static void reset(NVGRenderer nvg, ClientLevel next) {
        for (int i = 0; i < PLOTS; i++) {
            if (handles[i] > 0) nvg.deleteImage(handles[i]);
            handles[i] = 0;
            sampled[i] = 0L;
        }
        level = next;
        gardenChecked = 0L;
    }

    private static boolean inGarden() {
        long now = System.nanoTime();
        if (now - gardenChecked > 2_000_000_000L) {
            gardenChecked = now;
            garden = ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;
        }
        return garden;
    }

    private static ByteBuffer sample(Minecraft client, int plot) {
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
        ByteBuffer out = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        for (int i = 0; i < SIZE * SIZE; i++) {
            int north = i >= SIZE ? heights[i - SIZE] : heights[i];
            float shade = heights[i] > north ? 1.12f : heights[i] < north ? 0.8f : 1f;
            int c = colors[i];
            out.put(i * 4, (byte) Math.min(255, Math.round(((c >> 16) & 255) * shade)));
            out.put(i * 4 + 1, (byte) Math.min(255, Math.round(((c >> 8) & 255) * shade)));
            out.put(i * 4 + 2, (byte) Math.min(255, Math.round((c & 255) * shade)));
            out.put(i * 4 + 3, (byte) 255);
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
