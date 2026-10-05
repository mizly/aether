package dev.aether.renderer;

import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// model json parsed with minecraft's own reader and resolved up the parent chain the way vanilla does it:
// the nearest elements and gui transform win, texture slots resolve child first
final class McModels {

    record Resolved(Identifier id, List<CuboidModelElement> elements, Identifier elementsFrom, TextureSlots textures,
                    ItemTransform gui, boolean frontLight, boolean generated) {

        Identifier sprite(String slot) {
            Material material = textures.getMaterial(slot);
            return material == null ? null : material.sprite();
        }
    }

    static final Identifier GENERATED = Identifier.withDefaultNamespace("builtin/generated");

    private static final int MAX_PARENTS = 32;

    private static final Map<String, Optional<Resolved>> cache = new ConcurrentHashMap<>();

    private McModels() {}

    // null when the model does not exist
    static Resolved resolve(String modelId) {
        Identifier id = Identifier.tryParse(modelId);
        if (id == null) return null;
        return cache.computeIfAbsent(id.toString(), key -> Optional.ofNullable(load(id))).orElse(null);
    }

    static void invalidate() {
        cache.clear();
    }

    private static Resolved load(Identifier id) {
        TextureSlots.Resolver textures = new TextureSlots.Resolver();
        List<CuboidModelElement> elements = null;
        Identifier elementsFrom = null;
        ItemTransform gui = null;
        UnbakedModel.GuiLight light = null;
        boolean generated = false;
        Identifier current = id;
        for (int depth = 0; current != null && depth < MAX_PARENTS; depth++) {
            if (current.equals(GENERATED)) {
                generated = true;
                break;
            }
            CuboidModel model = read(current);
            if (model == null) {
                if (depth == 0) return null;
                break;
            }
            textures.addLast(model.textureSlots());
            if (elements == null && model.geometry() instanceof UnbakedCuboidGeometry geometry) {
                elements = geometry.elements();
                elementsFrom = current;
            }
            if (gui == null && model.transforms() != null && !ItemTransform.NO_TRANSFORM.equals(model.transforms().gui())) {
                gui = model.transforms().gui();
            }
            if (light == null) light = model.guiLight();
            current = model.parent();
        }
        return new Resolved(id, elements == null ? List.of() : elements, elementsFrom, textures.resolve(id::toString),
                gui == null ? ItemTransform.NO_TRANSFORM : gui, light == UnbakedModel.GuiLight.FRONT, generated);
    }

    private static CuboidModel read(Identifier model) {
        Identifier location = model.withPath(path -> "models/" + path + ".json");
        try (InputStream in = McAssets.open(location)) {
            if (in == null) return null;
            return CuboidModel.fromStream(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            System.err.println("[Aether] Could not read model " + model + ": " + e.getMessage());
            return null;
        }
    }
}
