package dev.aether.modules.pathfinding.util

// bit layout [X:26][Y:12][Z:26], so x/z hold +-33.5m and y holds +-2048
class BlockPosUtil private constructor() {
    companion object {
        private const val X_OFFSET = 33554432 // 2^25
        private const val Z_OFFSET = 33554432
        private const val Y_OFFSET = 2048 // 2^11

        private const val X_MASK = 0x3FFFFFFL // 26 bits
        private const val Y_MASK = 0xFFFL // 12 bits
        private const val Z_MASK = 0x3FFFFFFL

        private const val Y_SHIFT = 26
        private const val X_SHIFT = 38 // 26 + 12

        @JvmStatic
        fun pack(x: Int, y: Int, z: Int): Long {
            val lx = (x + X_OFFSET).toLong() and X_MASK
            val ly = (y + Y_OFFSET).toLong() and Y_MASK
            val lz = (z + Z_OFFSET).toLong() and Z_MASK
            return (lx shl X_SHIFT) or (ly shl Y_SHIFT) or lz
        }

        @JvmStatic
        fun unpackX(key: Long): Int = ((key shr X_SHIFT) and X_MASK).toInt() - X_OFFSET

        @JvmStatic
        fun unpackY(key: Long): Int = ((key shr Y_SHIFT) and Y_MASK).toInt() - Y_OFFSET

        @JvmStatic
        fun unpackZ(key: Long): Int = (key and Z_MASK).toInt() - Z_OFFSET
    }
}
