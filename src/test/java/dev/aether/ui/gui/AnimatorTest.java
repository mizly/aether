package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnimatorTest {
    private static final long MS = 1_000_000L;
    private static final float MIN = 50f;

    private final Animator anim = new Animator();
    private long now = 5_000 * MS;

    @Test
    void aNewKeyStartsAtItsTarget() {
        frame(250f);
        assertEquals(40f, anim.spring("pill", 40f));
        assertEquals(1f, anim.ease("fade", 1f, 200f));
    }

    @Test
    void springsAreFrameRateIndependent() {
        float at30 = springAfter(250, 1000 / 30f);
        float at60 = springAfter(250, 1000 / 60f);
        float at144 = springAfter(250, 1000 / 144f);
        assertEquals(at60, at30, 1e-3f);
        assertEquals(at60, at144, 1e-3f);
        assertTrue(at60 > 95f && at60 < 100f, "settles most of the way in one animation time: " + at60);
    }

    @Test
    void springsSettleExactly() {
        frame(250f);
        anim.spring("pill", 0f);
        for (int i = 0; i < 120; i++) {
            advance(16);
            anim.spring("pill", 100f);
        }
        assertEquals(100f, anim.spring("pill", 100f));
    }

    @Test
    void easeRunsFromTheOldValueOverItsScaledDuration() {
        frame(250f);
        anim.ease("x", 0f, 200f);
        anim.ease("x", 10f, 200f);
        advance(100);
        float half = anim.ease("x", 10f, 200f);
        assertTrue(half > 5f && half < 10f, "ease-out is past halfway at half time: " + half);
        advance(100);
        assertEquals(10f, anim.ease("x", 10f, 200f));

        Animator slow = new Animator();
        slow.begin(now, 500f, MIN);
        slow.ease("x", 0f, 200f);
        slow.ease("x", 10f, 200f);
        now += 200 * MS;
        slow.begin(now, 500f, MIN);
        float slowed = slow.ease("x", 10f, 200f);
        assertTrue(slowed < 10f, "twice the animation time doubles the duration: " + slowed);
    }

    @Test
    void retargetingMidFlightStartsFromTheCurrentValue() {
        frame(250f);
        anim.ease("x", 0f, 200f);
        anim.ease("x", 100f, 200f);
        advance(100);
        float mid = anim.ease("x", 100f, 200f);
        float turned = anim.ease("x", 0f, 200f);
        assertEquals(mid, turned, 1e-4f);
        advance(200);
        assertEquals(0f, anim.ease("x", 0f, 200f));
    }

    @Test
    void hoverTakesAboutAHundredAndTwentyMilliseconds() {
        frame(250f);
        anim.hover("card", false);
        anim.hover("card", true);
        advance(60);
        float partway = anim.hover("card", true);
        assertTrue(partway > 0f && partway < 1f);
        advance(60);
        assertEquals(1f, anim.hover("card", true));
    }

    @Test
    void staggeredRowsStartTwentyMillisecondsApartCappedAtEight() {
        frame(250f);
        assertEquals(0f, anim.stagger("page", 0));
        advance(60);
        assertTrue(anim.stagger("page", 0) > 0f);
        assertEquals(0f, anim.stagger("page", 3));
        assertEquals(anim.stagger("page", 8), anim.stagger("page", 40));
        advance(400);
        assertEquals(1f, anim.stagger("page", 0));
        assertEquals(1f, anim.stagger("page", 40));
    }

    @Test
    void theMinimumAnimationTimeSnapsEverything() {
        frame(MIN);
        anim.spring("pill", 0f);
        assertEquals(100f, anim.spring("pill", 100f));
        anim.ease("x", 0f, 200f);
        assertEquals(1f, anim.ease("x", 1f, 200f));
        assertEquals(1f, anim.stagger("page", 5));
    }

    @Test
    void idleKeysAreForgotten() {
        frame(250f);
        anim.ease("x", 0f, 200f);
        advance(1_500);
        anim.ease("x", 0f, 200f);
        advance(1_500);
        // still remembered, so a new target eases in from the old value
        assertEquals(0f, anim.ease("x", 7f, 200f));
        advance(2_500);
        // forgotten after two idle seconds, so it starts at its target like a new key
        assertEquals(3f, anim.ease("x", 3f, 200f));
    }

    @Test
    void theInertViewAlwaysAnswersTheTarget() {
        frame(250f);
        assertEquals(3f, anim.inert().spring("pill", 3f));
        assertEquals(1f, anim.inert().stagger("page", 4));
        anim.inert().ease("x", 0f, 200f);
        assertEquals(9f, anim.ease("x", 9f, 200f));
        assertSame(anim.inert(), anim.inert().inert());
    }

    private float springAfter(int totalMs, float frameMs) {
        Animator spring = new Animator();
        long t = 0L;
        spring.begin(t, 250f, MIN);
        spring.spring("pill", 0f);
        double elapsed = 0.0;
        float value = 0f;
        while (elapsed + frameMs <= totalMs + 1e-6) {
            elapsed += frameMs;
            t = Math.round(elapsed * MS);
            spring.begin(t, 250f, MIN);
            value = spring.spring("pill", 100f);
        }
        if (elapsed < totalMs) {
            spring.begin(totalMs * MS, 250f, MIN);
            value = spring.spring("pill", 100f);
        }
        return value;
    }

    private void frame(float animTimeMs) {
        anim.begin(now, animTimeMs, MIN);
    }

    private void advance(long millis) {
        now += millis * MS;
        anim.begin(now, 250f, MIN);
    }
}
