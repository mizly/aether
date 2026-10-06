package dev.aether.ui.gui;

import dev.aether.config.Config;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.preview.PreviewGuiHost;
import dev.aether.ui.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static dev.aether.ui.gui.Inputs.alt;
import static dev.aether.ui.gui.Inputs.button;
import static dev.aether.ui.gui.Inputs.key;
import static dev.aether.ui.gui.Inputs.left;
import static dev.aether.ui.gui.Inputs.shift;
import static dev.aether.ui.gui.Inputs.shortcut;
import static org.junit.jupiter.api.Assertions.*;

class GuiViewTest {
    private static final Rect PAGE = new Rect(0f, 0f, 800f, 480f);

    private final PreviewGuiHost host = new PreviewGuiHost();
    private final ManualClock clock = new ManualClock();
    private final TestStyle aurora = new TestStyle("aurora");
    private final TestStyle terminal = new TestStyle("terminal");
    private final Navigator navigator = new Navigator();
    private final Region page = new Region();
    private MainGUIRegistry.Snapshot snapshot = snapshot(1L);
    private GuiView view;
    private String savedStyle;

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @BeforeEach
    void openView() {
        savedStyle = Theme.GUI_STYLE;
        Theme.GUI_STYLE = "aurora";
        view = new GuiView(host, clock, new StyleRegistry(List.of(aurora, terminal)), () -> snapshot,
                new MonospaceTextMetrics());
        view.setNavigator(navigator);
        aurora.draw = f -> f.hits().add("page", PAGE, page);
        view.open(null);
        frame(0f, 0f);
    }

    @AfterEach
    void restoreStyle() {
        view.close();
        Theme.GUI_STYLE = savedStyle;
    }

    @Test
    void keybindCaptureSeesEveryInputFirst() {
        Editor editor = focusPageEditor();
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        List<BoundKey> bound = new ArrayList<>();
        KeyTarget target = new KeyTarget() {
            @Override
            public void bind(BoundKey key) {
                bound.add(key);
            }

            @Override
            public void clear() {
            }
        };
        view.render(null, 800f, 480f, 0f, 0f);
        lastFrameCapture().begin("freelook", target);
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_SLASH)));
        lastFrameCapture().begin("freelook", target);
        assertTrue(view.charTyped("/"));
        assertTrue(view.pointerPressed(button(GLFW.GLFW_MOUSE_BUTTON_4, 500f, 400f)));
        assertEquals(List.of(BoundKey.keysym(GLFW.GLFW_KEY_SLASH), BoundKey.mouse(GLFW.GLFW_MOUSE_BUTTON_4)), bound);
        assertTrue(editor.keys.isEmpty());
        assertTrue(editor.chars.isEmpty());
        assertTrue(overlay.keys.isEmpty());
        assertEquals(1, overlays().size());
        assertEquals(0, navigator.backs);

        lastFrameCapture().begin("freelook", target);
        view.pointerPressed(left(150f, 150f));
        assertFalse(lastFrameCapture().active());
        assertEquals(1, overlay.presses, "a left click cancels the capture and still lands");
    }

    @Test
    void modalOverlaysTakeKeysBeforeThePageEditor() {
        Editor editor = focusPageEditor();
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_A)));
        assertTrue(view.charTyped("a"));
        assertEquals(1, overlay.keys.size());
        assertEquals(List.of("a"), overlay.chars);
        assertTrue(editor.keys.isEmpty());
        assertTrue(editor.chars.isEmpty());
    }

    @Test
    void aPressOutsideAModalOverlayClosesItAndIsSwallowed() {
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        frame(0f, 0f);
        assertTrue(view.pointerPressed(left(500f, 400f)));
        assertTrue(overlay.closed);
        assertEquals(0, page.presses);
        assertEquals(0, overlays().size());
    }

    @Test
    void theColourPickerLetsAnOutsidePressThrough() {
        Editor hex = new Editor("hex");
        TestOverlay picker = new TestOverlay(new Rect(100f, 100f, 200f, 100f));
        picker.passThrough = true;
        pushOverlay(picker);
        frame(0f, 0f);
        overlays().topEditors().focus(hex);
        assertTrue(view.pointerPressed(left(500f, 400f)));
        assertTrue(picker.closed);
        assertEquals(1, hex.commits, "closing the picker applies its pending value");
        assertEquals(1, page.presses);
    }

    @Test
    void pressesInsideAnOverlayReachItsRegionsNotThePageBelow() {
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        frame(0f, 0f);
        assertTrue(view.pointerPressed(left(150f, 150f)));
        assertEquals(1, overlay.presses);
        assertEquals(0, page.presses);
        assertTrue(view.scrolled(150f, 150f, 0.0, 1.0));
        assertTrue(view.scrolled(500f, 400f, 0.0, 1.0));
        assertEquals(0, page.scrolls, "a modal overlay keeps the wheel from the page");
    }

    @Test
    void aPressElsewhereCommitsTheFocusedEditorButItsOwnFieldKeepsIt() {
        Editor editor = focusPageEditor();
        aurora.draw = f -> {
            f.hits().add("page", PAGE, page);
            f.hits().add("field", new Rect(10f, 10f, 100f, 20f), new Region());
        };
        frame(0f, 0f);
        view.pointerPressed(left(20f, 15f));
        view.pointerReleased(left(20f, 15f));
        assertEquals(0, editor.commits);
        view.pointerPressed(left(400f, 300f));
        assertEquals(1, editor.commits);
        assertNull(pageEditors().focused());
    }

    @Test
    void theFocusedEditorTakesKeysBeforeAnyShortcut() {
        Editor editor = focusPageEditor();
        editor.result = FocusedEditor.Result.IGNORED;
        aurora.handlesKeys = true;
        assertFalse(view.keyPressed(shortcut(GLFW.GLFW_KEY_F)));
        assertFalse(view.keyPressed(key(GLFW.GLFW_KEY_BACKSPACE)));
        assertEquals(2, editor.keys.size());
        assertTrue(aurora.keys.isEmpty());
        assertEquals(0, navigator.shortcuts);
        assertEquals(0, navigator.backs);

        editor.result = FocusedEditor.Result.DONE;
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_ENTER)));
        assertNull(pageEditors().focused());
    }

    @Test
    void hoverShortcutsCopyAndPasteWhileNoEditorIsFocused() {
        Swatch swatch = new Swatch();
        aurora.draw = f -> f.hits().add("swatch", new Rect(10f, 10f, 24f, 24f), swatch);
        frame(15f, 15f);
        frame(15f, 15f);
        assertTrue(view.keyPressed(shortcut(GLFW.GLFW_KEY_C)));
        assertEquals("FF112233", host.clipboard().read());
        host.clipboard().write("#445566");
        assertTrue(view.keyPressed(shortcut(GLFW.GLFW_KEY_V)));
        assertEquals("#445566", swatch.pasted);

        Editor editor = focusPageEditor();
        host.clipboard().write("unchanged");
        view.keyPressed(shortcut(GLFW.GLFW_KEY_C));
        assertEquals("unchanged", host.clipboard().read());
        assertEquals(1, editor.keys.size());
    }

    @Test
    void styleShortcutsComeBeforeGlobalOnes() {
        aurora.handlesKeys = true;
        assertTrue(view.keyPressed(shortcut(GLFW.GLFW_KEY_F)));
        assertEquals(0, navigator.shortcuts);
        aurora.handlesKeys = false;
        navigator.handlesShortcuts = true;
        assertTrue(view.keyPressed(shortcut(GLFW.GLFW_KEY_F)));
        assertEquals(1, navigator.shortcuts);
    }

    @Test
    void backComesFromMouseFourAltLeftAndBackspace() {
        navigator.handlesBack = true;
        assertTrue(view.pointerPressed(button(GLFW.GLFW_MOUSE_BUTTON_4, 10f, 10f)));
        assertTrue(view.keyPressed(alt(GLFW.GLFW_KEY_LEFT)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_BACKSPACE)));
        assertEquals(3, navigator.backs);
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        frame(0f, 0f);
        view.pointerPressed(button(GLFW.GLFW_MOUSE_BUTTON_4, 10f, 10f));
        assertTrue(overlay.closed);
        assertEquals(3, navigator.backs);
    }

    @Test
    void escapeWalksCaptureEditorOverlaySearchAndUpBeforeClosing() {
        Editor editor = focusPageEditor();
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        Editor overlayField = new Editor("hex");
        overlays().topEditors().focus(overlayField);
        lastFrameCapture().begin("key", new KeyTarget() {
            @Override
            public void bind(BoundKey key) {
                fail("escape must cancel, not bind");
            }

            @Override
            public void clear() {
                fail("escape must cancel, not clear");
            }
        });

        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertFalse(lastFrameCapture().active());
        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(1, overlayField.escapes);
        assertFalse(overlay.closed);
        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertTrue(overlay.closed);
        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(1, editor.escapes);
        navigator.searchToClear = true;
        navigator.levelsUp = 1;
        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(1, navigator.searchClears);
        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(1, navigator.ups);
        assertEquals(0, host.closeRequests());
        view.keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(1, host.closeRequests());
    }

    @Test
    void typedTextGoesToTheEditorOrElseTheNavigator() {
        assertTrue(view.charTyped("/"));
        assertEquals(List.of("/"), navigator.typed);
        Editor editor = focusPageEditor();
        assertTrue(view.charTyped("x"));
        assertEquals(List.of("x"), editor.chars);
        assertEquals(List.of("/"), navigator.typed);
    }

    @Test
    void aRegistryRebuildCommitsEditorsClosesOverlaysAndCancelsCaptures() {
        assertEquals(List.of(1L), navigator.generations);
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        frame(0f, 0f);
        view.pointerPressed(left(150f, 150f));
        assertTrue(view.pointerCaptured());
        Editor editor = focusPageEditor();
        Editor overlayField = new Editor("hex");
        overlays().topEditors().focus(overlayField);
        lastFrameCapture().begin("key", new KeyTarget() {
            @Override
            public void bind(BoundKey key) {
            }

            @Override
            public void clear() {
            }
        });

        snapshot = snapshot(2L);
        frame(0f, 0f);
        assertEquals(1, editor.commits);
        assertEquals(1, overlayField.commits);
        assertTrue(overlay.closed);
        assertFalse(lastFrameCapture().active());
        assertFalse(view.pointerCaptured());
        assertFalse(Config.batching());
        assertEquals(List.of(1L, 2L), navigator.generations);
    }

    @Test
    void aStyleSwitchCommitsEditorsClosesOverlaysAndClearsFocus() {
        aurora.draw = f -> f.focus().add("row", new Rect(0f, 0f, 100f, 20f), () -> { });
        frame(0f, 0f);
        view.keyPressed(key(GLFW.GLFW_KEY_TAB));
        Editor editor = focusPageEditor();
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        Theme.GUI_STYLE = "terminal";
        frame(0f, 0f);
        assertSame(terminal, view.style());
        assertEquals(1, editor.commits);
        assertTrue(overlay.closed);
        assertNull(lastFrameFocus().focusedId());
        Theme.GUI_STYLE = "unknown";
        assertSame(aurora, view.style(), "unknown ids fall back to aurora");
    }

    @Test
    void scalesFreezeWhileADragHoldsThePointer() {
        view.applyScales(1.5f, 1f, 1920f, 1080f);
        assertEquals(1.5f, view.uiScale());
        assertTrue(view.pointerPressed(left(400f, 300f)));
        assertTrue(view.pointerCaptured());
        view.pointerDragged(left(420f, 300f));
        view.applyScales(2f, 1.25f, 1920f, 1080f);
        assertEquals(1.5f, view.uiScale(), "the slider being dragged must not rescale under the cursor");
        assertEquals(1f, view.textScale());
        frame(420f, 300f);
        assertEquals(1f, lastFrameTextScale());
        view.pointerReleased(left(420f, 300f));
        view.applyScales(2f, 1.25f, 1920f, 1080f);
        assertEquals(2f, view.uiScale());
        assertEquals(1.25f, view.textScale());
    }

    @Test
    void theEffectiveScaleKeepsTheCanvasAtLeastEightHundredByFourEighty() {
        assertEquals(2.25f, GuiView.clampUiScale(3f, 1920f, 1080f));
        assertEquals(0.75f, GuiView.clampUiScale(1f, 640f, 360f));
        assertEquals(1.5f, GuiView.clampUiScale(1.5f, 3840f, 2160f));
        assertTrue(1920f / GuiView.clampUiScale(3f, 1920f, 1080f) >= GuiView.MIN_LAYOUT_WIDTH);
        assertTrue(1080f / GuiView.clampUiScale(3f, 1920f, 1080f) >= GuiView.MIN_LAYOUT_HEIGHT);
    }

    @Test
    void aDragHoldsConfigAndThemeWritesUntilRelease() {
        view.pointerPressed(left(400f, 300f));
        assertTrue(Config.batching());
        view.pointerReleased(left(400f, 300f));
        assertFalse(Config.batching());
        view.pointerPressed(left(400f, 300f));
        view.close();
        assertFalse(Config.batching());
    }

    @Test
    void closeCommitsEditorsAndClosesEverything() {
        Editor editor = focusPageEditor();
        TestOverlay overlay = pushOverlay(new TestOverlay(new Rect(100f, 100f, 200f, 100f)));
        view.close();
        assertEquals(1, editor.commits);
        assertTrue(overlay.closed);
        assertEquals(1, navigator.closes);
    }

    @Test
    void tabArrowsEnterAndSpaceDriveKeyboardFocus() {
        List<String> events = new ArrayList<>();
        aurora.draw = f -> {
            f.focus().add("a", new Rect(0f, 0f, 100f, 20f), handler("a", events));
            f.focus().add("b", new Rect(0f, 20f, 100f, 20f), handler("b", events));
        };
        frame(0f, 0f);
        assertFalse(view.keyPressed(key(GLFW.GLFW_KEY_ENTER)), "nothing focused yet");
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_TAB)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_ENTER)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_RIGHT)));
        assertTrue(view.keyPressed(shift(GLFW.GLFW_KEY_LEFT)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_DOWN)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_SPACE)));
        assertTrue(view.keyPressed(shift(GLFW.GLFW_KEY_TAB)));
        assertTrue(view.keyPressed(key(GLFW.GLFW_KEY_SPACE)));
        assertEquals(List.of("a activate", "a adjust 1 false", "a adjust -1 true", "b activate", "a activate"), events);
    }

    @Test
    void cursorBackdropAndScrimFollowTheViewState() {
        aurora.draw = f -> f.hits().add("link", new Rect(0f, 0f, 50f, 20f), new Region(), Cursor.HAND);
        frame(10f, 10f);
        frame(10f, 10f);
        assertEquals(Cursor.HAND, view.cursor());
        frame(400f, 400f);
        assertEquals(Cursor.DEFAULT, view.cursor());
        assertEquals(Backdrop.WORLD_BLUR, view.backdrop());

        Theme.ANIM_TIME_MS = 250f;
        view.open(null);
        assertEquals(0, Argb.alpha(view.scrimArgb()));
        clock.advanceMillis(1_000L);
        assertEquals(Argb.alpha(Palette.fromTheme().scrim()), Argb.alpha(view.scrimArgb()));
    }

    private void frame(float mouseX, float mouseY) {
        view.render(null, 800f, 480f, mouseX, mouseY);
    }

    private Editor focusPageEditor() {
        Editor editor = new Editor("field");
        pageEditors().focus(editor);
        return editor;
    }

    private TestOverlay pushOverlay(TestOverlay overlay) {
        overlays().push(overlay);
        return overlay;
    }

    private OverlayStack overlays() {
        return aurora.lastFrame.overlays();
    }

    private EditorSlot pageEditors() {
        return aurora.lastFrame.editors();
    }

    private InputCapture lastFrameCapture() {
        return aurora.lastFrame.capture();
    }

    private FocusManager lastFrameFocus() {
        return aurora.lastFrame != null ? aurora.lastFrame.focus() : terminal.lastFrame.focus();
    }

    private float lastFrameTextScale() {
        return aurora.lastFrame.textScale();
    }

    private static FocusHandler handler(String id, List<String> events) {
        return new FocusHandler() {
            @Override
            public void activate() {
                events.add(id + " activate");
            }

            @Override
            public void adjust(int direction, boolean large) {
                events.add(id + " adjust " + direction + " " + large);
            }
        };
    }

    private static MainGUIRegistry.Snapshot snapshot(long generation) {
        return new MainGUIRegistry.Snapshot(generation, List.of(), List.of(), List.of(), List.of());
    }

    private static final class TestStyle implements GuiStyle {
        private final String id;
        private final List<KeyInput> keys = new ArrayList<>();
        private Consumer<GuiFrame> draw = f -> { };
        private boolean handlesKeys;
        private GuiFrame lastFrame;

        private TestStyle(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public void render(GuiFrame frame) {
            lastFrame = frame;
            draw.accept(frame);
        }

        @Override
        public Backdrop backdrop() {
            return Backdrop.WORLD_BLUR;
        }

        @Override
        public boolean keyPressed(GuiFrame frame, KeyInput key) {
            if (handlesKeys) {
                keys.add(key);
            }
            return handlesKeys;
        }
    }

    private static final class Navigator implements GuiNavigator {
        private final List<Long> generations = new ArrayList<>();
        private final List<String> typed = new ArrayList<>();
        private boolean searchToClear;
        private int levelsUp;
        private boolean handlesBack;
        private boolean handlesShortcuts;
        private int searchClears;
        private int ups;
        private int backs;
        private int shortcuts;
        private int closes;

        @Override
        public void snapshotChanged(MainGUIRegistry.Snapshot snapshot) {
            generations.add(snapshot.generation());
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public boolean clearSearch() {
            if (!searchToClear) {
                return false;
            }
            searchToClear = false;
            searchClears++;
            return true;
        }

        @Override
        public boolean up() {
            if (levelsUp == 0) {
                return false;
            }
            levelsUp--;
            ups++;
            return true;
        }

        @Override
        public boolean back() {
            backs++;
            return handlesBack;
        }

        @Override
        public boolean shortcut(GuiFrame frame, KeyInput key) {
            if (handlesShortcuts) {
                shortcuts++;
            }
            return handlesShortcuts;
        }

        @Override
        public boolean typed(GuiFrame frame, String chars) {
            typed.add(chars);
            return true;
        }
    }

    private static final class Editor implements FocusedEditor {
        private final Object id;
        private final List<KeyInput> keys = new ArrayList<>();
        private final List<String> chars = new ArrayList<>();
        private Result result = Result.HANDLED;
        private int commits;
        private int escapes;

        private Editor(Object id) {
            this.id = id;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public Result key(KeyInput key) {
            keys.add(key);
            return result;
        }

        @Override
        public boolean chars(String typed) {
            chars.add(typed);
            return true;
        }

        @Override
        public void commit() {
            commits++;
        }

        @Override
        public void escape() {
            escapes++;
        }
    }

    private static final class TestOverlay implements Overlay {
        private final Rect bounds;
        private final List<KeyInput> keys = new ArrayList<>();
        private final List<String> chars = new ArrayList<>();
        private boolean passThrough;
        private boolean closed;
        private int presses;

        private TestOverlay(Rect bounds) {
            this.bounds = bounds;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        @Override
        public void render(GuiFrame frame) {
            frame.hits().add("overlay", bounds.inset(10f), new HitHandler() {
                @Override
                public boolean press(PointerEvent e) {
                    presses++;
                    return true;
                }
            });
        }

        @Override
        public boolean key(KeyInput key) {
            keys.add(key);
            return true;
        }

        @Override
        public boolean chars(String typed) {
            chars.add(typed);
            return true;
        }

        @Override
        public void onClose() {
            closed = true;
        }

        @Override
        public boolean outsidePressPassesThrough() {
            return passThrough;
        }
    }

    private static final class Region implements HitHandler {
        private int presses;
        private int scrolls;

        @Override
        public boolean press(PointerEvent e) {
            presses++;
            return true;
        }

        @Override
        public boolean scroll(PointerEvent e, double dy) {
            scrolls++;
            return true;
        }
    }

    private static final class Swatch implements HitHandler, ClipboardTarget {
        private String pasted;

        @Override
        public String copyText() {
            return "FF112233";
        }

        @Override
        public boolean paste(String text) {
            pasted = text;
            return true;
        }
    }
}
