package dev.aether.ui.orbit;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.Map;

// finds the top of each block column in the client's loaded world by scanning down from the highest section that
// holds anything; the client's heightmaps can't be trusted on servers that send them empty
final class WorldColumns {
    private final ClientLevel level;
    private final Map<Long, Integer> sectionTops = new HashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    WorldColumns(ClientLevel level) {
        this.level = level;
    }

    // y of the highest block worth drawing, looking past glass roofs and markers; Integer.MIN_VALUE when empty
    int top(int x, int z) {
        int from = sectionTop(x >> 4, z >> 4);
        int floor = level.getMinY();
        for (int y = from; y >= floor; y--) {
            if (!seeThrough(level.getBlockState(pos.set(x, y, z)))) return y;
        }
        return Integer.MIN_VALUE;
    }

    BlockState state(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    private int sectionTop(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Integer cached = sectionTops.get(key);
        if (cached != null) return cached;
        int top = level.getMinY() - 1;
        ChunkAccess chunk = level.getChunk(cx, cz);
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = sections.length - 1; i >= 0; i--) {
            if (sections[i] != null && !sections[i].hasOnlyAir()) {
                top = level.getSectionYFromSectionIndex(i) * 16 + 15;
                break;
            }
        }
        sectionTops.put(key, top);
        return top;
    }

    // blocks that don't hide what is under them in game: air, glass, barriers and the like
    static boolean seeThrough(BlockState state) {
        if (state.isAir()) return true;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("glass") || path.endsWith("glass_pane") || path.equals("barrier") || path.equals("light")
                || path.equals("structure_void") || path.equals("tripwire") || path.equals("string");
    }
}
