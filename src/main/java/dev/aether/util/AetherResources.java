package dev.aether.util;

import java.io.InputStream;

public final class AetherResources {
    private AetherResources() {
    }

    public static InputStream open(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        ClassLoader classLoader = AetherResources.class.getClassLoader();
        InputStream input = classLoader.getResourceAsStream(normalized);
        if (input != null) {
            return input;
        }
        return AetherResources.class.getResourceAsStream(resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath);
    }
}
