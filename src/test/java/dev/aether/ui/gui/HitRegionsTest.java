package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static dev.aether.ui.gui.Inputs.button;
import static dev.aether.ui.gui.Inputs.left;
import static org.junit.jupiter.api.Assertions.*;

class HitRegionsTest {
    private final GuiCanvas canvas = new GuiCanvas(new MonospaceTextMetrics());
    private final HitRegions hits = new HitRegions(canvas);

    @Test
    void translatedRegionsReceiveLocalCoordinates() {
        Recorder button = new Recorder(true);
        frame(0f, 0f, () -> {
            canvas.translate(100f, 50f);
            hits.add("button", new Rect(10f, 10f, 40f, 20f), button);
        });
        assertTrue(hits.press(left(115f, 65f)));
        PointerEvent press = button.presses.getFirst();
        assertEquals(15f, press.localX());
        assertEquals(15f, press.localY());
        assertEquals(115f, press.rootX());
        assertEquals(new Rect(10f, 10f, 40f, 20f), press.pressRect());
        assertFalse(new HitRegions(canvas).press(left(115f, 65f)));
    }

    @Test
    void scaledRegionsMapThroughTheScale() {
        Recorder icon = new Recorder(true);
        frame(0f, 0f, () -> {
            canvas.translate(100f, 100f);
            canvas.scale(2f);
            hits.add("icon", new Rect(0f, 0f, 10f, 10f), icon);
        });
        assertNull(hits.idAt(121f, 110f));
        assertEquals("icon", hits.idAt(119f, 119f));
        assertTrue(hits.press(left(110f, 118f)));
        assertEquals(5f, icon.presses.getFirst().localX());
        assertEquals(9f, icon.presses.getFirst().localY());
    }

    @Test
    void nestedClipsCutRegionsInRootSpace() {
        frame(0f, 0f, () -> {
            canvas.clip(new Rect(0f, 0f, 200f, 100f));
            canvas.translate(150f, 0f);
            canvas.clip(new Rect(0f, 0f, 100f, 200f));
            hits.add("clipped", new Rect(0f, 0f, 100f, 200f), new Recorder(true));
            canvas.translate(0f, 150f);
            hits.add("hidden", new Rect(0f, 0f, 10f, 10f), new Recorder(true));
        });
        assertEquals("clipped", hits.idAt(175f, 50f));
        assertNull(hits.idAt(210f, 50f));
        assertNull(hits.idAt(175f, 150f));
        assertNull(hits.idAt(155f, 155f));
    }

    @Test
    void theLastAddedRegionIsOnTop() {
        Recorder below = new Recorder(true);
        Recorder above = new Recorder(true);
        Runnable draw = () -> {
            hits.add("below", new Rect(0f, 0f, 100f, 100f), below);
            hits.add("above", new Rect(50f, 50f, 100f, 100f), above);
        };
        frame(0f, 0f, draw);
        frame(75f, 75f, draw);
        assertTrue(hits.hovered("above"));
        assertFalse(hits.hovered("below"));
        assertTrue(hits.press(left(75f, 75f)));
        assertEquals(1, above.presses.size());
        assertEquals(0, below.presses.size());
    }

    @Test
    void anUnconsumedPressFallsThroughToTheRegionBelow() {
        Recorder row = new Recorder(true);
        Recorder label = new Recorder(false);
        frame(0f, 0f, () -> {
            hits.add("row", new Rect(0f, 0f, 300f, 30f), row);
            hits.add("label", new Rect(0f, 0f, 100f, 30f), label);
        });
        assertTrue(hits.press(left(50f, 10f)));
        assertEquals(1, label.presses.size());
        assertEquals(1, row.presses.size());
        assertTrue(hits.active("row"));
    }

    @Test
    void blockersSwallowPressScrollAndHover() {
        Recorder page = new Recorder(true);
        frame(0f, 0f, () -> {
            hits.add("page", new Rect(0f, 0f, 400f, 400f), page);
            hits.block(new Rect(100f, 100f, 100f, 100f));
        });
        frame(150f, 150f, () -> {
            hits.add("page", new Rect(0f, 0f, 400f, 400f), page);
            hits.block(new Rect(100f, 100f, 100f, 100f));
        });
        assertFalse(hits.hovered("page"));
        assertNull(hits.idAt(150f, 150f));
        assertTrue(hits.press(left(150f, 150f)));
        assertTrue(hits.scroll(150f, 150f, 1.0));
        assertTrue(page.presses.isEmpty());
        assertTrue(page.scrolls.isEmpty());
        assertFalse(hits.captured());
        assertTrue(hits.press(left(50f, 50f)));
        assertEquals(1, page.presses.size());
    }

    @Test
    void captureSendsDragAndReleaseToThePressedRegionWhereverThePointerGoes() {
        Recorder slider = new Recorder(true);
        Recorder other = new Recorder(true);
        frame(0f, 0f, () -> {
            hits.add("other", new Rect(200f, 0f, 100f, 100f), other);
            canvas.translate(10f, 0f);
            hits.add("slider", new Rect(0f, 0f, 100f, 20f), slider);
        });
        assertTrue(hits.press(left(20f, 10f)));
        assertTrue(hits.captured());
        assertTrue(hits.active("slider"));
        assertTrue(hits.drag(left(250f, 50f)));
        assertEquals(240f, slider.drags.getFirst().localX());
        frame(250f, 50f, () -> { });
        assertTrue(hits.hovered("slider"));
        assertFalse(hits.hovered("other"));
        assertTrue(hits.press(left(250f, 50f)));
        assertTrue(other.presses.isEmpty());
        assertFalse(hits.release(button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 250f, 50f)));
        assertTrue(hits.release(left(250f, 50f)));
        assertEquals(1, slider.releases.size());
        assertFalse(hits.captured());
        assertFalse(hits.drag(left(0f, 0f)));
    }

    @Test
    void dragsKeepThePressTimeLayout() {
        Recorder knob = new Recorder(true);
        frame(0f, 0f, () -> {
            canvas.translate(100f, 0f);
            hits.add("knob", new Rect(0f, 0f, 50f, 20f), knob);
        });
        assertTrue(hits.press(left(110f, 5f)));
        frame(110f, 5f, () -> {
            canvas.translate(300f, 0f);
            hits.add("knob", new Rect(0f, 0f, 80f, 20f), knob);
        });
        hits.drag(left(150f, 5f));
        assertEquals(50f, knob.drags.getFirst().localX());
        assertEquals(new Rect(0f, 0f, 50f, 20f), knob.drags.getFirst().pressRect());
    }

    @Test
    void scrollBubblesOutwardToTheFirstConsumer() {
        Recorder list = new Recorder(true);
        Recorder field = new Recorder(false);
        frame(0f, 0f, () -> {
            hits.add("list", new Rect(0f, 0f, 300f, 300f), list);
            hits.add("field", new Rect(10f, 10f, 100f, 30f), field);
        });
        assertTrue(hits.scroll(20f, 20f, -1.0));
        assertEquals(1, field.scrolls.size());
        assertEquals(1, list.scrolls.size());
        assertFalse(hits.scroll(500f, 500f, -1.0));
    }

    @Test
    void hoverFollowsStableIdsAcrossFrames() {
        frame(0f, 0f, () -> hits.add(new RowId("pests", "Pest Threshold", 0), new Rect(0f, 0f, 100f, 20f), new Recorder(true)));
        frame(10f, 10f, () -> hits.add(new RowId("pests", "Pest Threshold", 0), new Rect(0f, 0f, 100f, 20f), new Recorder(true)));
        assertTrue(hits.hovered(new RowId("pests", "Pest Threshold", 0)));
        assertFalse(hits.hovered(new RowId("pests", "Pest Threshold", 1)));
    }

    @Test
    void cursorComesFromTheHoveredRegion() {
        Runnable draw = () -> hits.add("field", new Rect(0f, 0f, 100f, 20f), new Recorder(true), Cursor.IBEAM);
        frame(0f, 0f, draw);
        frame(10f, 10f, draw);
        assertEquals(Cursor.IBEAM, hits.cursor());
        frame(500f, 10f, draw);
        assertEquals(Cursor.DEFAULT, hits.cursor());
    }

    @Test
    void theInertViewRegistersNothing() {
        Recorder thumb = new Recorder(true);
        frame(0f, 0f, () -> hits.inert().add("thumb", new Rect(0f, 0f, 100f, 100f), thumb));
        frame(10f, 10f, () -> hits.inert().add("thumb", new Rect(0f, 0f, 100f, 100f), thumb));
        assertFalse(hits.hovered("thumb"));
        assertFalse(hits.inert().hovered("thumb"));
        assertFalse(hits.press(left(10f, 10f)));
        assertTrue(thumb.presses.isEmpty());
        assertSame(hits.inert(), hits.inert().inert());
    }

    @Test
    void clickHelperReactsToTheLeftButtonOnly() {
        int[] clicks = {0};
        frame(0f, 0f, () -> hits.add("ok", new Rect(0f, 0f, 50f, 20f), HitHandler.click(() -> clicks[0]++)));
        assertFalse(hits.press(button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 5f, 5f)));
        assertTrue(hits.press(left(5f, 5f)));
        assertEquals(1, clicks[0]);
    }

    private void frame(float mouseX, float mouseY, Runnable draw) {
        canvas.begin(null, 800f, 480f);
        hits.begin(mouseX, mouseY);
        canvas.save();
        draw.run();
        canvas.restore();
        hits.end();
        canvas.end();
    }

    private record RowId(String page, String setting, int ordinal) {
    }

    private static final class Recorder implements HitHandler {
        private final boolean consumes;
        private final List<PointerEvent> presses = new ArrayList<>();
        private final List<PointerEvent> drags = new ArrayList<>();
        private final List<PointerEvent> releases = new ArrayList<>();
        private final List<PointerEvent> scrolls = new ArrayList<>();

        private Recorder(boolean consumes) {
            this.consumes = consumes;
        }

        @Override
        public boolean press(PointerEvent e) {
            presses.add(e);
            return consumes;
        }

        @Override
        public void drag(PointerEvent e) {
            drags.add(e);
        }

        @Override
        public void release(PointerEvent e) {
            releases.add(e);
        }

        @Override
        public boolean scroll(PointerEvent e, double dy) {
            scrolls.add(e);
            return consumes;
        }
    }
}
