package dev.aether.ui.gui;

import dev.aether.ui.gui.preview.PreviewGuiHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static dev.aether.ui.gui.Inputs.key;
import static org.junit.jupiter.api.Assertions.*;

class OverlayStackTest {
    private final OverlayStack stack = new OverlayStack();
    private final GuiCanvas canvas = new GuiCanvas(new MonospaceTextMetrics());
    private final HitRegions hits = new HitRegions(canvas);

    @Test
    void closingALayerCommitsItsEditorThenTellsTheOverlay() {
        List<String> events = new ArrayList<>();
        Layer overlay = new Layer(new Rect(0f, 0f, 10f, 10f), true, events);
        stack.push(overlay);
        stack.topEditors().focus(new Field("hex", events));
        stack.pop();
        assertEquals(List.of("hex commit", "close"), events);
        assertTrue(stack.isEmpty());
        assertNull(stack.top());
        assertNull(stack.topEditors());
    }

    @Test
    void deadOverlaysCloseBeforeTheFrameDrawsThem() {
        List<String> events = new ArrayList<>();
        Layer alive = new Layer(new Rect(0f, 0f, 10f, 10f), true, events);
        Layer dead = new Layer(new Rect(0f, 0f, 10f, 10f), true, events);
        dead.alive = false;
        stack.push(alive);
        stack.push(dead);
        render();
        assertEquals(1, stack.size());
        assertSame(alive, stack.top());
        assertEquals(1, alive.renders);
        assertEquals(0, dead.renders);
        assertEquals(List.of("close"), events);
    }

    @Test
    void modalLayersBlockTheirBoundsForEverythingBelow() {
        stack.push(new Layer(new Rect(100f, 100f, 50f, 50f), true, new ArrayList<>()));
        stack.push(new Layer(new Rect(300f, 300f, 50f, 50f), false, new ArrayList<>()));
        canvas.begin(null, 800f, 480f);
        hits.begin(0f, 0f);
        hits.add("page", new Rect(0f, 0f, 800f, 480f), HitHandler.click(() -> { }));
        stack.render(frame());
        hits.end();
        canvas.end();
        assertNull(hits.idAt(120f, 120f));
        assertEquals("page", hits.idAt(320f, 320f));
    }

    @Test
    void pressRoutingOnlyLooksAtTheTopLayer() {
        Layer menu = new Layer(new Rect(100f, 100f, 50f, 50f), false, new ArrayList<>());
        stack.push(menu);
        assertEquals(OverlayStack.PressRoute.INSIDE, stack.routePress(120f, 120f));
        assertEquals(OverlayStack.PressRoute.PASS, stack.routePress(10f, 10f));
        assertSame(menu, stack.top(), "a non-modal overlay stays open on an outside press");
        stack.remove(menu);
        assertEquals(OverlayStack.PressRoute.PASS, stack.routePress(10f, 10f));
    }

    @Test
    void keysReachTheTopEditorThenItsOverlayAndStopAtAModalLayer() {
        List<String> events = new ArrayList<>();
        Layer modal = new Layer(new Rect(0f, 0f, 10f, 10f), true, events);
        Layer hint = new Layer(new Rect(0f, 0f, 10f, 10f), false, events);
        stack.push(modal);
        stack.push(hint);
        assertTrue(stack.key(key(GLFW.GLFW_KEY_A)));
        assertEquals(List.of("non-modal key", "modal key"), events);

        events.clear();
        Field field = new Field("search", events);
        field.result = FocusedEditor.Result.DONE;
        stack.topEditors().focus(field);
        assertTrue(stack.key(key(GLFW.GLFW_KEY_ENTER)));
        assertNull(stack.topEditors().focused());
        assertEquals(List.of("search key"), events);
        assertTrue(stack.chars("x"));
    }

    @Test
    void focusingAnotherEditorCommitsTheFirst() {
        List<String> events = new ArrayList<>();
        EditorSlot slot = new EditorSlot();
        Field first = new Field("first", events);
        slot.focus(first);
        slot.focus(first);
        assertTrue(events.isEmpty());
        slot.focus(new Field("second", events));
        assertEquals(List.of("first commit"), events);
        assertTrue(slot.isFocused("second"));
        slot.release();
        assertNull(slot.focused());
        slot.commit();
        slot.escape();
        assertEquals(List.of("first commit"), events);
    }

    private void render() {
        canvas.begin(null, 800f, 480f);
        hits.begin(0f, 0f);
        stack.render(frame());
        hits.end();
        canvas.end();
    }

    private GuiFrame frame() {
        return new GuiFrame(canvas, null, hits, new FocusManager(canvas), new Animator(), stack, new EditorSlot(),
                new InputCapture(), new PreviewGuiHost(), canvas.bounds(), 0f, 0f, 0L, 1f);
    }

    private static final class Layer implements Overlay {
        private final Rect bounds;
        private final boolean modal;
        private final List<String> events;
        private boolean alive = true;
        private int renders;

        private Layer(Rect bounds, boolean modal, List<String> events) {
            this.bounds = bounds;
            this.modal = modal;
            this.events = events;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        @Override
        public boolean modal() {
            return modal;
        }

        @Override
        public boolean alive(GuiFrame frame) {
            return alive;
        }

        @Override
        public void render(GuiFrame frame) {
            renders++;
        }

        @Override
        public boolean key(KeyInput key) {
            events.add((modal ? "modal" : "non-modal") + " key");
            return false;
        }

        @Override
        public void onClose() {
            events.add("close");
        }
    }

    private static final class Field implements FocusedEditor {
        private final String id;
        private final List<String> events;
        private Result result = Result.HANDLED;

        private Field(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public Result key(KeyInput key) {
            events.add(id + " key");
            return result;
        }

        @Override
        public boolean chars(String chars) {
            return true;
        }

        @Override
        public void commit() {
            events.add(id + " commit");
        }
    }
}
