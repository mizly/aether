package dev.aether.renderer;

import java.util.List;
import java.util.Locale;

// a minecraft item icon drawn inside nanovg with NVGRenderer.mcIcon; textures are full ids such as
// "minecraft:textures/item/wheat.png", and every variant is a value, so icons work as constants and map keys
public sealed interface McIcon {

    int UNTINTED = 0xFFFFFFFF;

    // resolved through McIcons.of when drawn, so resource packs and reloads apply
    record Item(String id) implements McIcon {
        public Item {
            id = id.trim().toLowerCase(Locale.ROOT);
            if (id.indexOf(':') < 0) id = "minecraft:" + id;
        }
    }

    record Sprite(String texture, int tint) implements McIcon {
        public Sprite(String texture) {
            this(texture, UNTINTED);
        }
    }

    // flat sprites drawn in order, e.g. a map under its tinted markings
    record Layered(List<Sprite> layers) implements McIcon {
        public Layered {
            layers = List.copyOf(layers);
        }
    }

    // the vanilla inventory cube: left is the east face, right the north face, shaded 1 / 0.6505 / 0.4
    // sideInset pulls both side faces in by that fraction of the block, 1/16 for cactus
    record Block(String top, String left, String right, float sideInset) implements McIcon {
        public Block {
            if (sideInset < 0f || sideInset >= 0.5f) throw new IllegalArgumentException("sideInset " + sideInset);
        }
    }

    // a block model id such as "minecraft:block/composter", drawn from its elements with the model's own gui transform
    // tints are argb colours by tint index
    record Model(String model, List<Integer> tints) implements McIcon {
        public Model {
            tints = List.copyOf(tints);
        }
    }

    // player head built from a 64x64 skin texture
    record Head(String skin) implements McIcon {}

    // block entities vanilla draws with special models; texture is the entity texture
    record Special(Kind kind, String texture) implements McIcon {
        public enum Kind { CHEST, BED }
    }
}
