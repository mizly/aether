package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RectTest {
    @Test
    void containsIsHalfOpenSoTouchingRectsNeverShareAPoint() {
        Rect left = new Rect(0f, 0f, 10f, 10f);
        Rect right = new Rect(10f, 0f, 10f, 10f);
        assertTrue(left.contains(0f, 0f));
        assertTrue(left.contains(9.99f, 9.99f));
        assertFalse(left.contains(10f, 5f));
        assertTrue(right.contains(10f, 5f));
        assertFalse(left.contains(-0.01f, 5f));
    }

    @Test
    void emptyRectsContainNothing() {
        assertTrue(new Rect(5f, 5f, 0f, 10f).isEmpty());
        assertTrue(new Rect(5f, 5f, 10f, -1f).isEmpty());
        assertFalse(new Rect(5f, 5f, 0f, 10f).contains(5f, 5f));
        assertFalse(new Rect(0f, 0f, 1f, 1f).isEmpty());
    }

    @Test
    void intersectKeepsTheOverlapOrIsEmpty() {
        Rect a = new Rect(0f, 0f, 100f, 50f);
        Rect b = new Rect(60f, 20f, 100f, 100f);
        assertEquals(new Rect(60f, 20f, 40f, 30f), a.intersect(b));
        assertEquals(a.intersect(b), b.intersect(a));
        assertTrue(a.intersect(new Rect(100f, 0f, 10f, 10f)).isEmpty());
        assertTrue(a.intersect(new Rect(200f, 200f, 10f, 10f)).isEmpty());
        assertTrue(Rect.EMPTY.intersect(a).isEmpty());
    }

    @Test
    void edgesOffsetAndCentres() {
        Rect r = Rect.ofEdges(10f, 20f, 50f, 80f);
        assertEquals(new Rect(10f, 20f, 40f, 60f), r);
        assertEquals(50f, r.right());
        assertEquals(80f, r.bottom());
        assertEquals(30f, r.centerX());
        assertEquals(50f, r.centerY());
        assertEquals(new Rect(15f, 15f, 40f, 60f), r.offset(5f, -5f));
    }

    @Test
    void insetShrinksButNeverGoesNegative() {
        Rect r = new Rect(0f, 0f, 20f, 10f);
        assertEquals(new Rect(2f, 2f, 16f, 6f), r.inset(2f));
        assertEquals(new Rect(1f, 2f, 16f, 4f), r.inset(1f, 2f, 3f, 4f));
        Rect collapsed = r.inset(8f);
        assertEquals(4f, collapsed.w());
        assertEquals(0f, collapsed.h());
        assertTrue(collapsed.isEmpty());
    }
}
