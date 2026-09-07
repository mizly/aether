package dev.aether.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionAnimationTest {
    @Test
    void startsAtTheInitialSelectionWithoutSlidingIn() {
        assertEquals(72f, new SelectionAnimation().update(72f, 250f, 0L));
    }

    @Test
    void travelsSmoothlyAtDifferentFrameRates() {
        float at30Fps = animate(30);
        assertEquals(at30Fps, animate(60), 0.0001f);
        assertEquals(at30Fps, animate(144), 0.0001f);
        assertTrue(at30Fps > 95f && at30Fps < 100f);
    }

    @Test
    void retargetingPreservesPositionAndMotion() {
        SelectionAnimation animation = new SelectionAnimation();
        animation.update(0f, 250f, 0L);
        float before = animation.update(100f, 250f, 50_000_000L);
        assertEquals(before, animation.update(200f, 250f, 50_000_000L));
        float after = animation.update(200f, 250f, 51_000_000L);
        assertTrue(after > before + 0.5f && after < before + 1.5f);
        assertEquals(after, animation.update(0f, 250f, 51_000_000L));
        assertEquals(0f, animation.update(0f, 250f, 2_000_000_000L));
    }

    @Test
    void finishesAfterALongFrameGap() {
        SelectionAnimation animation = new SelectionAnimation();
        animation.update(0f, 250f, 0L);
        assertEquals(100f, animation.update(100f, 250f, 5_000_000_000L));
    }

    private static float animate(int fps) {
        SelectionAnimation animation = new SelectionAnimation();
        animation.update(0f, 250f, 0L);
        float previous = 0f;
        for (int frame = 1; 1_000_000_000L * frame / fps < 250_000_000L; frame++) {
            float value = animation.update(100f, 250f, 1_000_000_000L * frame / fps);
            assertTrue(value >= previous && value < 100f);
            previous = value;
        }
        return animation.update(100f, 250f, 250_000_000L);
    }
}
