package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FocusManagerTest {
    private final GuiCanvas canvas = new GuiCanvas(new MonospaceTextMetrics());
    private final FocusManager focus = new FocusManager(canvas);
    private final List<String> activated = new ArrayList<>();
    private final List<Object> revealed = new ArrayList<>();

    @Test
    void nextAndPrevFollowDrawOrderAndWrap() {
        frame("a", "b", "c");
        assertTrue(focus.next());
        assertTrue(focus.focused("a"));
        focus.next();
        assertTrue(focus.focused("b"));
        focus.prev();
        focus.prev();
        assertTrue(focus.focused("c"));
        focus.next();
        assertTrue(focus.focused("a"));
    }

    @Test
    void prevStartsFromTheEnd() {
        frame("a", "b", "c");
        focus.prev();
        assertTrue(focus.focused("c"));
    }

    @Test
    void movingFocusAsksItsContainerToRevealItOnce() {
        frame("a", "b", "c");
        focus.next();
        focus.next();
        frame("a", "b", "c");
        assertEquals(List.of("b"), revealed);
        frame("a", "b", "c");
        assertEquals(List.of("b"), revealed);
    }

    @Test
    void theRingOnlyShowsInKeyboardModality() {
        frame("a", "b");
        focus.moveTo("b");
        assertTrue(focus.focused("b"));
        assertFalse(focus.ringVisible("b"));
        focus.next();
        assertTrue(focus.ringVisible("a"));
        focus.pointerUsed();
        assertTrue(focus.focused("a"));
        assertFalse(focus.ringVisible("a"));
    }

    @Test
    void handlersAndRectsComeFromTheLastFrame() {
        canvas.begin(null, 800f, 480f);
        focus.begin();
        canvas.translate(10f, 20f);
        focus.add("row", new Rect(0f, 0f, 100f, 30f), () -> activated.add("row"));
        focus.end();
        canvas.end();
        focus.next();
        focus.focusedHandler().activate();
        assertEquals(List.of("row"), activated);
        assertEquals(new Rect(10f, 20f, 100f, 30f), focus.focusedRect());
        focus.clear();
        assertNull(focus.focusedId());
        assertNull(focus.focusedHandler());
    }

    @Test
    void nothingToFocusMeansNoMove() {
        frame();
        assertFalse(focus.next());
        assertNull(focus.focusedId());
    }

    @Test
    void theInertViewNeverRegistersOrMoves() {
        canvas.begin(null, 800f, 480f);
        focus.begin();
        assertFalse(focus.inert().add("thumb", new Rect(0f, 0f, 10f, 10f), () -> { }));
        focus.end();
        canvas.end();
        assertFalse(focus.next());
        assertFalse(focus.inert().next());
    }

    private void frame(String... ids) {
        canvas.begin(null, 800f, 480f);
        focus.begin();
        float y = 0f;
        for (String id : ids) {
            if (focus.add(id, new Rect(0f, y, 100f, 30f), () -> activated.add(id))) {
                revealed.add(id);
            }
            y += 30f;
        }
        focus.end();
        canvas.end();
    }
}
