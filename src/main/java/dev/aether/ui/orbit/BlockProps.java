package dev.aether.ui.orbit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.function.Function;
import java.util.function.Supplier;

// the skits' block props drawn as real blocks, so a repeater, a wire or a crop looks and joins up the way it does in
// game; the preview harness has no block models, so there each prop falls back to its hand-built stand-in
final class BlockProps {
    private static boolean missing;

    private BlockProps() {
    }

    // m maps the unit block onto the farm; false when the caller should draw its stand-in instead
    static boolean draw(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f m, Supplier<BlockState> state) {
        if (missing) return false;
        try {
            if (Minecraft.getInstance() == null) {
                missing = true;
                return false;
            }
            SceneClone.model(state.get(), m, buffer.apply(TextureAtlas.LOCATION_BLOCKS));
            return true;
        } catch (LinkageError e) {
            missing = true;
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // a block at a farm cell: x, z on the grid, y its layer above the ground, popped in by size around its foot
    static Matrix4f at(Matrix4f local, int x, int y, int z, float size) {
        return new Matrix4f(local).translate(x, y, z).scale(Math.max(0f, size)).translate(-0.5f, 0f, -0.5f);
    }
}
