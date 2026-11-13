package dev.aether.util

import java.io.InputStream

class AetherResources private constructor() {
    companion object {
        @JvmStatic
        fun open(resourcePath: String?): InputStream? {
            if (resourcePath == null || resourcePath.isBlank()) return null
            val normalized = if (resourcePath.startsWith("/")) resourcePath.substring(1) else resourcePath
            val classLoader = AetherResources::class.java.classLoader
            val input = classLoader.getResourceAsStream(normalized)
            if (input != null) return input
            return AetherResources::class.java.getResourceAsStream(if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath")
        }
    }
}
