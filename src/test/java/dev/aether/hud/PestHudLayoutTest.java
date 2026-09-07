package dev.aether.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PestHudLayoutTest {
    @Test
    void everyPestFitsInTheViewportEvenInSmallWindows() {
        for (int count : new int[]{1, 8, 15, 30}) {
            for (float width : new float[]{320, 427, 854}) {
                for (float height : new float[]{84, 184, 300}) {
                    var layout = PestHudLayout.fit(count, width, height, 1f);
                    assertTrue(layout.rows() * layout.columns() >= count);
                    assertTrue(layout.width() * layout.scale() <= width + 0.001f);
                    assertTrue(layout.height() * layout.scale() <= height + 0.001f);
                    assertTrue(layout.scale() > 0 && layout.scale() <= 1f);
                }
            }
        }
    }

    @Test
    void keepsTheRequestedScaleAndVerticalLayoutWhenThereIsRoom() {
        assertEquals(new PestHudLayout(8, 1, 1f), PestHudLayout.fit(8, 854, 400, 1f));
        assertEquals(0.75f, PestHudLayout.fit(1, 854, 400, 0.75f).scale());
        assertEquals(1, PestHudLayout.fit(0, 854, 400, 1f).rows());
    }
}
