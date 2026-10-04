package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScrollStateTest {
    private static final long MS = 1_000_000L;

    private final ScrollState scroll = new ScrollState();

    @Test
    void scrollingIsClampedToTheContent() {
        scroll.setExtent(1000f, 400f);
        assertEquals(600f, scroll.max());
        scroll.scrollBy(-50f);
        assertEquals(0f, scroll.target());
        scroll.scrollBy(10_000f);
        assertEquals(600f, scroll.target());
        scroll.setExtent(300f, 400f);
        assertEquals(0f, scroll.max());
        assertEquals(0f, scroll.target());
        assertEquals(0f, scroll.offset());
    }

    @Test
    void shrinkingContentPullsTheOffsetBack() {
        scroll.setExtent(1000f, 400f);
        scroll.jumpTo(600f);
        scroll.setExtent(700f, 400f);
        assertEquals(300f, scroll.offset());
        assertEquals(300f, scroll.target());
    }

    @Test
    void theOffsetEasesTowardsTheTargetAndSettles() {
        scroll.setExtent(1000f, 400f);
        long now = 0L;
        scroll.tick(now, 250f, false);
        scroll.scrollTo(300f);
        now += 16 * MS;
        scroll.tick(now, 250f, false);
        float first = scroll.offset();
        assertTrue(first > 0f && first < 300f);
        for (int i = 0; i < 100; i++) {
            now += 16 * MS;
            scroll.tick(now, 250f, false);
        }
        assertEquals(300f, scroll.offset());
    }

    @Test
    void aFrameHitchMovesNoFurtherThanATenthOfASecond() {
        scroll.setExtent(10_000f, 400f);
        scroll.tick(0L, 250f, false);
        scroll.scrollTo(5000f);
        scroll.tick(5_000 * MS, 250f, false);
        ScrollState capped = new ScrollState();
        capped.setExtent(10_000f, 400f);
        capped.tick(0L, 250f, false);
        capped.scrollTo(5000f);
        capped.tick(100 * MS, 250f, false);
        assertEquals(capped.offset(), scroll.offset(), 1e-3f);
    }

    @Test
    void reducedMotionSnaps() {
        scroll.setExtent(1000f, 400f);
        scroll.scrollTo(250f);
        scroll.tick(0L, 50f, true);
        assertEquals(250f, scroll.offset());
    }

    @Test
    void ensureVisibleScrollsTheLeastNeeded() {
        scroll.setExtent(2000f, 400f);
        scroll.jumpTo(500f);
        scroll.ensureVisible(600f, 650f, 8f);
        assertEquals(500f, scroll.target());
        scroll.ensureVisible(950f, 1000f, 8f);
        assertEquals(608f, scroll.target());
        scroll.ensureVisible(100f, 130f, 8f);
        assertEquals(92f, scroll.target());
        scroll.ensureVisible(1000f, 1600f, 8f);
        assertEquals(992f, scroll.target());
        scroll.ensureVisible(0f, 10f, 8f);
        assertEquals(0f, scroll.target());
    }

    @Test
    void thumbSizeAndPositionFollowTheOffset() {
        Rect track = new Rect(100f, 50f, 4f, 400f);
        assertTrue(scroll.thumb(track, 30f).isEmpty());
        scroll.setExtent(1600f, 400f);
        assertEquals(new Rect(100f, 50f, 4f, 100f), scroll.thumb(track, 30f));
        scroll.jumpTo(1200f);
        assertEquals(new Rect(100f, 350f, 4f, 100f), scroll.thumb(track, 30f));
        scroll.setExtent(40_000f, 400f);
        assertEquals(30f, scroll.thumb(track, 30f).h());
    }

    @Test
    void draggingTheThumbMapsThePointerOntoTheContent() {
        Rect track = new Rect(0f, 100f, 4f, 400f);
        scroll.setExtent(1600f, 400f);
        float grab = 20f;
        scroll.dragThumb(100f + grab + 150f, grab, track, 30f);
        assertEquals(600f, scroll.offset());
        scroll.dragThumb(0f, grab, track, 30f);
        assertEquals(0f, scroll.offset());
        scroll.dragThumb(10_000f, grab, track, 30f);
        assertEquals(1200f, scroll.offset());
    }
}
