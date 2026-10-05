package dev.aether.ui.orbit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the copied Barn as the plot map's little voxel model: every block face that touches air, coloured like the block's
// own texture on that side. built once per copy; null wherever the game or a copy isn't there
final class BarnVoxels {
    // a face of the block at (x, y, z), dir 0 up, 1 north, 2 south, 3 west, 4 east
    record Face(int x, int y, int z, int dir, int argb) {
    }

    record Model(int width, int height, int depth, List<Face> faces) {
    }

    private static BarnCopy.Copy built;
    private static Model model;
    private static boolean missing;
    private static Model preview;

    private BarnVoxels() {
    }

    // the preview harness has no game to copy from, so it hands in a model of its own
    static void preview(Model model) {
        preview = model;
    }

    static Model current() {
        if (preview != null) return preview;
        if (missing) return null;
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                missing = true;
                return null;
            }
            BarnCopy.Copy copy = BarnCopy.current(client);
            if (copy == null) return null;
            if (copy != built) {
                model = build(client, copy);
                built = copy;
            }
            return model;
        } catch (LinkageError e) {
            missing = true;
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Model build(Minecraft client, BarnCopy.Copy copy) {
        Map<BlockState, int[]> colors = new HashMap<>();
        List<Face> faces = new ArrayList<>();
        int[][] steps = {{0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (int y = 0; y < copy.height(); y++) {
            for (int z = 0; z < copy.depth(); z++) {
                for (int x = 0; x < copy.width(); x++) {
                    BlockState state = copy.at(x, y, z);
                    if (empty(state)) continue;
                    int[] sides = colors.computeIfAbsent(state, s -> colors(client, s));
                    for (int dir = 0; dir < steps.length; dir++) {
                        int nx = x + steps[dir][0], ny = y + steps[dir][1], nz = z + steps[dir][2];
                        boolean open = nx < 0 || ny < 0 || nz < 0 || nx >= copy.width() || ny >= copy.height()
                                || nz >= copy.depth() || empty(copy.at(nx, ny, nz));
                        // the floor's own sides would only show as a wall round the tile's edge
                        if (!open || y == 0 && dir > 0) continue;
                        if (sides[dir] != 0) faces.add(new Face(x, y, z, dir, sides[dir]));
                    }
                }
            }
        }
        return new Model(copy.width(), copy.height(), copy.depth(), faces);
    }

    private static boolean empty(BlockState state) {
        return state.isAir() || WorldColumns.seeThrough(state) && !state.getBlock().getDescriptionId().contains("glass");
    }

    // the average colour of the texture the model puts on each side, tinted the way it looks in plains
    private static int[] colors(Minecraft client, BlockState state) {
        int[] out = new int[5];
        if (state.is(Blocks.WATER)) {
            java.util.Arrays.fill(out, 0xFF3F76E4);
            return out;
        }
        if (state.is(Blocks.LAVA)) {
            java.util.Arrays.fill(out, 0xFFE0661C);
            return out;
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        client.getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(42L), parts);
        Direction[] dirs = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int fallback = 0;
        for (int i = 0; i < dirs.length; i++) {
            BakedQuad quad = null;
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(dirs[i]);
                if (!quads.isEmpty()) {
                    quad = quads.get(quads.size() - 1);
                    break;
                }
            }
            if (quad == null) {
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad q : part.getQuads(null)) {
                        if (q.direction() == dirs[i]) quad = q;
                    }
                }
            }
            if (quad == null) continue;
            int c = PlotMiniatures.spriteColor(client, quad.materialInfo().sprite().contents().name());
            if (c == 0) continue;
            if (quad.materialInfo().isTinted()) {
                BlockTintSource tint = client.getBlockColors().getTintSource(state, quad.materialInfo().tintIndex());
                if (tint != null) c = PlotMiniatures.multiply(c, tint.color(state) == -1 ? 0xFF91BD59 : tint.color(state));
            }
            out[i] = c;
            if (fallback == 0) fallback = c;
        }
        if (fallback == 0) {
            try {
                fallback = 0xFF000000 | state.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                        net.minecraft.core.BlockPos.ZERO).col;
            } catch (RuntimeException e) {
                fallback = 0xFF808080;
            }
        }
        for (int i = 0; i < out.length; i++) if (out[i] == 0) out[i] = fallback;
        return out;
    }
}
