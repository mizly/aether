package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StyleRegistryTest {
    @Test
    void unknownIdsFallBackToAuroraThenToTheDebugScene() {
        GuiStyle aurora = new Named("aurora");
        GuiStyle terminal = new Named("terminal");
        StyleRegistry styles = new StyleRegistry(List.of(aurora, terminal));
        assertSame(terminal, styles.resolve("terminal"));
        assertSame(aurora, styles.resolve("inventory"));
        assertSame(aurora, styles.resolve(null));
        assertEquals(List.of(aurora, terminal), styles.all());

        StyleRegistry none = new StyleRegistry(List.of());
        assertEquals("debug", none.resolve("aurora").id());
        assertSame(none.resolve("aurora"), none.resolve("terminal"));
        assertTrue(none.all().isEmpty());
    }

    @Test
    void everyRegistryHasItsOwnStyleInstances() {
        assertNotSame(StyleRegistry.defaults().resolve("aurora"), StyleRegistry.defaults().resolve("aurora"));
    }

    private record Named(String id) implements GuiStyle {
        @Override
        public String displayName() {
            return id;
        }

        @Override
        public void render(GuiFrame frame) {
        }
    }
}
