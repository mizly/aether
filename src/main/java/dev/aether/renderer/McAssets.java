package dev.aether.renderer;

import dev.aether.util.AetherResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// resource packs apply through the client's resource manager; without a client (tests, previews) the classpath
// serves the same paths, since the minecraft jar is on it
final class McAssets {

    private McAssets() {}

    // null when nothing provides the asset
    static InputStream open(Identifier location) throws IOException {
        Optional<Resource> resource = packResource(location);
        if (resource.isPresent()) return resource.get().open();
        return AetherResources.open(classpathPath(location));
    }

    // every pack's copy, highest priority first, for files vanilla merges across packs (font definitions)
    static List<IoSupplier<InputStream>> openAll(Identifier location) {
        ResourceManager manager = manager();
        List<IoSupplier<InputStream>> copies = new ArrayList<>();
        if (manager != null) {
            for (Resource resource : manager.getResourceStack(location).reversed()) copies.add(resource::open);
            return copies;
        }
        String path = classpathPath(location);
        copies.add(() -> AetherResources.open(path));
        return copies;
    }

    static Optional<AnimationMetadataSection> animation(Identifier location) throws IOException {
        return metadata(location).getSection(AnimationMetadataSection.TYPE);
    }

    static ResourceMetadata metadata(Identifier location) throws IOException {
        Optional<Resource> resource = packResource(location);
        if (resource.isPresent()) return resource.get().metadata();
        try (InputStream in = AetherResources.open(classpathPath(location) + ".mcmeta")) {
            return in == null ? ResourceMetadata.EMPTY : ResourceMetadata.fromJsonStream(in);
        }
    }

    private static Optional<Resource> packResource(Identifier location) {
        ResourceManager manager = manager();
        return manager == null ? Optional.empty() : manager.getResource(location);
    }

    private static ResourceManager manager() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : client.getResourceManager();
    }

    private static String classpathPath(Identifier location) {
        return "/assets/" + location.getNamespace() + "/" + location.getPath();
    }
}
