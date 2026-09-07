package dev.aether.renderer;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PestOutlineStateTest {
    @Test
    void onlyTheHeadSubmissionReceivesThePestOutline() {
        var state = new TestRenderState();
        state.isInvisible = true;
        PestOutlineState.extract(state, 0xFF80C090);

        assertTrue(state.appearsGlowing());
        PestOutlineState.submitBody(state, () -> {
            assertEquals(0, state.outlineColor);
            assertFalse(state.appearsGlowing());
            PestOutlineState.submitHead(state, () -> assertEquals(0xFF80C090, state.outlineColor));
            assertEquals(0, state.outlineColor);
        });
        assertEquals(0xFF80C090, state.outlineColor);
        assertTrue(state.isInvisible);
    }

    @Test
    void preservesVanillaGlowAndRestoresStateAfterSubmissionFailure() {
        var state = new TestRenderState();
        state.outlineColor = 0xFFABCDEF;
        PestOutlineState.extract(state, 0xFF80C090);

        assertThrows(IllegalStateException.class, () -> PestOutlineState.submitBody(state, () -> {
            assertEquals(0xFFABCDEF, state.outlineColor);
            assertThrows(IllegalArgumentException.class, () -> PestOutlineState.submitHead(state, () -> {
                assertEquals(0xFF80C090, state.outlineColor);
                throw new IllegalArgumentException();
            }));
            assertEquals(0xFFABCDEF, state.outlineColor);
            throw new IllegalStateException();
        }));
        assertEquals(0xFF80C090, state.outlineColor);
    }

    @Test
    void reusedStateStopsHighlightingWhenPestGlowIsDisabled() {
        var state = new TestRenderState();
        PestOutlineState.extract(state, 0xFF80C090);
        state.outlineColor = 0;
        PestOutlineState.extract(state, 0);

        assertFalse(state.appearsGlowing());
        PestOutlineState.submitBody(state, () -> {
            assertEquals(0, state.outlineColor);
            PestOutlineState.submitHead(state, () -> assertEquals(0, state.outlineColor));
        });
    }

    private static final class TestRenderState extends LivingEntityRenderState {
        private final Map<RenderStateDataKey<?>, Object> data = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getData(RenderStateDataKey<T> key) {
            return (T) data.get(key);
        }

        @Override
        public <T> void setData(RenderStateDataKey<T> key, T value) {
            data.put(key, value);
        }
    }
}
