package dev.aether.config

import java.util.Locale

data class FarmWaypoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val forward: Boolean,
    val left: Boolean,
    val back: Boolean,
    val right: Boolean,
    val highlighted: Boolean
) {
    fun encode(): String = String.format(Locale.ROOT, "%.3f,%.3f,%.3f:%s:%s", x, y, z, movementLabel(), highlighted)

    fun movementLabel(): String {
        val builder = StringBuilder()
        appendKey(builder, forward, 'W')
        appendKey(builder, left, 'A')
        appendKey(builder, back, 'S')
        appendKey(builder, right, 'D')
        return if (builder.isEmpty()) "None" else builder.toString()
    }

    fun withPosition(x: Double, y: Double, z: Double) = copy(x = x, y = y, z = z)

    fun withHighlighted(highlighted: Boolean) = copy(highlighted = highlighted)

    fun movementMask(): Int = (if (forward) 1 else 0) or (if (left) 2 else 0) or
        (if (back) 4 else 0) or (if (right) 8 else 0)

    fun withMovementMask(mask: Int) = copy(
        forward = mask and 1 != 0,
        left = mask and 2 != 0,
        back = mask and 4 != 0,
        right = mask and 8 != 0
    )

    fun toggleKey(key: Char): FarmWaypoint = when (key.uppercaseChar()) {
        'W' -> copy(forward = !forward)
        'A' -> copy(left = !left)
        'S' -> copy(back = !back)
        'D' -> copy(right = !right)
        else -> this
    }

    fun isKeySelected(key: Char): Boolean = when (key.uppercaseChar()) {
        'W' -> forward
        'A' -> left
        'S' -> back
        'D' -> right
        else -> false
    }

    private fun appendKey(builder: StringBuilder, selected: Boolean, key: Char) {
        if (!selected) return
        if (builder.isNotEmpty()) builder.append('/')
        builder.append(key)
    }

    companion object {
        @JvmStatic
        fun emptyAt(x: Double, y: Double, z: Double) = FarmWaypoint(x, y, z, false, false, false, false, true)

        @JvmStatic
        fun parse(value: String?): FarmWaypoint? {
            if (value.isNullOrBlank()) return null
            val parts = value.trim().split(":", limit = -1)
            val coords = parts[0].split(",")
            if (coords.size < 3) return null
            return try {
                val keys = parts.getOrElse(1) { "" }.uppercase(Locale.ROOT)
                FarmWaypoint(
                    coords[0].trim().toDouble(), coords[1].trim().toDouble(), coords[2].trim().toDouble(),
                    'W' in keys, 'A' in keys, 'S' in keys, 'D' in keys,
                    parts.size < 3 || parts[2].toBoolean()
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
