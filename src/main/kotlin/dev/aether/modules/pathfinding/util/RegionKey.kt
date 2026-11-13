package dev.aether.modules.pathfinding.util

import dev.aether.modules.pathfinding.wrapper.PathPosition

class RegionKey private constructor() {
    companion object {
        private const val MASK_Y = 0xFFFL // 12 bit
        private const val MASK_XZ = 0x3FFFFFFL // 26 bit
        private const val SHIFT_Z = 12
        private const val SHIFT_X = 38 // 12 + 26

        @JvmStatic
        fun pack(pos: PathPosition): Long = pack(pos.flooredX(), pos.flooredY(), pos.flooredZ())

        @JvmStatic
        fun pack(x: Int, y: Int, z: Int): Long =
            (x.toLong() and MASK_XZ) shl SHIFT_X or
                (z.toLong() and MASK_XZ) shl SHIFT_Z or
                (y.toLong() and MASK_Y)
    }
}
