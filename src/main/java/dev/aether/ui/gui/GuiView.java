package dev.aether.ui.gui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.nav.GuiActions;
import dev.aether.ui.theme.Theme;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

// the gui root: owns the frame loop and routes input in a fixed order. it never touches minecraft; the
// screen shell feeds it layout-unit coordinates and does everything game-specific through GuiHost
public final class GuiView {
    public static final float MIN_LAYOUT_WIDTH = 800f;
    public static final float MIN_LAYOUT_HEIGHT = 480f;

    private final GuiHost host;
    private final GuiClock clock;
    private final StyleRegistry styles;
    private final Supplier<MainGUIRegistry.Snapshot> registry;
    private final GuiCanvas canvas;
    private final HitRegions hits;
    private final FocusManager focus;
    private final Animator anim = new Animator();
    private final OverlayStack overlays = new OverlayStack();
    private final EditorSlot pageEditors = new EditorSlot();
    private final InputCapture capture = new InputCapture();
    private final PersistenceBatch persistence = new PersistenceBatch();
    private GuiNavigator navigator;
    private GuiActions actions;
    private GuiStyle style;
    private Palette palette;
    private GuiFrame lastFrame;
    private long generation = -1L;
    private long openedNanos;
    private float uiScale = 1f;
    private float textScale = 1f;

    public GuiView(GuiHost host, GuiClock clock) {
        this(host, clock, StyleRegistry.defaults(), MainGUIRegistry::snapshot, null);
    }

    // headlessMetrics measures frames rendered without a renderer, for tests; null in game
    public GuiView(GuiHost host, GuiClock clock, StyleRegistry styles, Supplier<MainGUIRegistry.Snapshot> registry,
                   TextMetrics headlessMetrics) {
        this.host = host;
        this.clock = clock;
        this.styles = styles;
        this.registry = registry;
        this.canvas = new GuiCanvas(headlessMetrics);
        this.hits = new HitRegions(canvas);
        this.focus = new FocusManager(canvas);
        this.actions = new GuiActions(host, clock, styles, registry);
        this.navigator = actions;
    }

    // replaces the built-in navigation; frames carry actions only while it is a GuiActions
    public void setNavigator(GuiNavigator navigator) {
        this.navigator = navigator;
        this.actions = navigator instanceof GuiActions guiActions ? guiActions : null;
    }

    public GuiActions actions() {
        return actions;
    }

    // null restores the last location of this session
    public void open(LaunchRequest launch) {
        openedNanos = clock.nanos();
        navigator.open(launch);
    }

    // commits what is being edited and flushes held writes; the shell calls this from Screen.removed()
    public void close() {
        capture.cancel();
        overlays.closeAll();
        pageEditors.commit();
        endPointerCapture();
        navigator.close();
    }

    // -- scale -----------------------------------------------------------------

    // the shell passes Theme's scales every frame; they only apply while no drag holds the pointer, so
    // dragging the ui scale slider never rescales the slider under the cursor
    public void applyScales(float requestedUiScale, float requestedTextScale, float physicalWidth, float physicalHeight) {
        if (!pointerCaptured()) {
            uiScale = clampUiScale(requestedUiScale, physicalWidth, physicalHeight);
            textScale = requestedTextScale;
        }
    }

    // never lets the layout canvas drop below 800 x 480 units; Theme.UI_SCALE itself is left alone
    public static float clampUiScale(float requested, float physicalWidth, float physicalHeight) {
        return Math.min(requested, Math.min(physicalWidth / MIN_LAYOUT_WIDTH, physicalHeight / MIN_LAYOUT_HEIGHT));
    }

    public float uiScale() {
        return uiScale;
    }

    public float textScale() {
        return textScale;
    }

    public boolean pointerCaptured() {
        return hits.captured();
    }

    // -- frame -----------------------------------------------------------------

    // inside a nanovg frame already scaled to layout units; a null renderer measures and routes without drawing
    public void render(NVGRenderer nvg, float viewWidth, float viewHeight, float mouseX, float mouseY) {
        long now = clock.nanos();
        syncStyle();
        syncRegistry();
        palette = Palette.fromTheme();
        canvas.begin(nvg, viewWidth, viewHeight);
        hits.begin(mouseX, mouseY);
        focus.begin();
        anim.begin(now, Theme.ANIM_TIME_MS, Theme.ANIM_TIME_MIN_MS);
        GuiFrame frame = new GuiFrame(canvas, palette, hits, focus, anim, overlays, pageEditors, capture, host,
                canvas.bounds(), mouseX, mouseY, now, textScale, actions);
        lastFrame = frame;
        try {
            style.render(frame);
            overlays.render(frame);
        } finally {
            hits.end();
            focus.end();
        }
        canvas.end();
    }

    public GuiStyle style() {
        syncStyle();
        return style;
    }

    public Backdrop backdrop() {
        return style().backdrop();
    }

    // the vanilla fill drawn over the blurred world, fading in as the gui opens
    public int scrimArgb() {
        Palette current = palette != null ? palette : Palette.fromTheme();
        float fadeMs = Theme.ANIM_TIME_MS <= Theme.ANIM_TIME_MIN_MS ? 0f : Theme.ANIM_TIME_MS * 0.6f;
        float shown = fadeMs <= 0f ? 1f : Math.min(1f, (clock.nanos() - openedNanos) / 1_000_000f / fadeMs);
        return Argb.multiplyAlpha(current.scrim(), shown);
    }

    public Cursor cursor() {
        return hits.cursor();
    }

    // -- input -----------------------------------------------------------------

    // keybind capture first, then overlays, then commit-on-blur for the focused editor, then hit regions
    public boolean pointerPressed(PointerInput in) {
        focus.pointerUsed();
        if (capture.active() && capture.pointer(in)) {
            return true;
        }
        if (in.button() == GLFW.GLFW_MOUSE_BUTTON_4) {
            return back();
        }
        OverlayStack.PressRoute route = overlays.routePress(in.x(), in.y());
        if (route == OverlayStack.PressRoute.SWALLOW) {
            return true;
        }
        EditorSlot slot = route == OverlayStack.PressRoute.INSIDE ? overlays.topEditors() : pageEditors;
        if (slot.focused() != null && !slot.isFocused(hits.idAt(in.x(), in.y()))) {
            slot.commit();
        }
        boolean consumed = hits.press(in);
        if (hits.captured()) {
            persistence.begin();
        }
        return consumed || route == OverlayStack.PressRoute.INSIDE;
    }

    public boolean pointerDragged(PointerInput in) {
        return hits.drag(in);
    }

    public boolean pointerReleased(PointerInput in) {
        boolean handled = hits.release(in);
        if (!hits.captured()) {
            persistence.end();
        }
        return handled;
    }

    public boolean scrolled(float x, float y, double dx, double dy) {
        Overlay top = overlays.top();
        if (top != null && top.modal() && !top.bounds().contains(x, y)) {
            return true;
        }
        return hits.scroll(x, y, dy);
    }

    // capture, escape, overlays, the focused editor, hover shortcuts, style and global shortcuts, then
    // back and keyboard focus; once an editor has focus nothing after it sees the key
    public boolean keyPressed(KeyInput in) {
        if (capture.active()) {
            return capture.key(in);
        }
        if (in.is(GLFW.GLFW_KEY_ESCAPE)) {
            return escape();
        }
        if (overlays.key(in)) {
            return true;
        }
        FocusedEditor editor = pageEditors.focused();
        if (editor != null) {
            FocusedEditor.Result result = editor.key(in);
            if (result == FocusedEditor.Result.DONE) {
                pageEditors.release();
            }
            return result != FocusedEditor.Result.IGNORED;
        }
        if (in.shortcut() && (in.is(GLFW.GLFW_KEY_C) || in.is(GLFW.GLFW_KEY_V))
                && hits.hoveredHandler() instanceof ClipboardTarget target) {
            if (in.is(GLFW.GLFW_KEY_C)) {
                host.clipboard().write(target.copyText());
            } else {
                target.paste(host.clipboard().read());
            }
            return true;
        }
        if (lastFrame != null && (style.keyPressed(lastFrame, in) || navigator.shortcut(lastFrame, in))) {
            return true;
        }
        if (in.is(GLFW.GLFW_KEY_BACKSPACE) || (in.alt() && in.is(GLFW.GLFW_KEY_LEFT))) {
            return back();
        }
        return focusKey(in);
    }

    public boolean charTyped(String chars) {
        if (capture.active()) {
            return capture.chars(chars);
        }
        if (overlays.chars(chars)) {
            return true;
        }
        if (pageEditors.focused() != null) {
            return pageEditors.focused().chars(chars);
        }
        return lastFrame != null && navigator.typed(lastFrame, chars);
    }

    // -- internals -------------------------------------------------------------

    // capture is handled before this; then the top layer's editor, the top overlay, search, one level up, close
    private boolean escape() {
        EditorSlot slot = overlays.isEmpty() ? pageEditors : overlays.topEditors();
        if (slot.focused() != null) {
            slot.escape();
            return true;
        }
        if (!overlays.isEmpty()) {
            overlays.pop();
            return true;
        }
        if (navigator.clearSearch() || navigator.up()) {
            return true;
        }
        host.closeScreen();
        return true;
    }

    private boolean back() {
        if (!overlays.isEmpty()) {
            overlays.pop();
            return true;
        }
        pageEditors.commit();
        return navigator.back();
    }

    private boolean focusKey(KeyInput in) {
        if (in.is(GLFW.GLFW_KEY_TAB)) {
            return in.shift() ? focus.prev() : focus.next();
        }
        FocusHandler handler = focus.focusedHandler();
        if (handler == null) {
            return false;
        }
        switch (in.key()) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> handler.activate();
            case GLFW.GLFW_KEY_LEFT -> handler.adjust(-1, in.shift());
            case GLFW.GLFW_KEY_RIGHT -> handler.adjust(1, in.shift());
            case GLFW.GLFW_KEY_UP -> focus.prev();
            case GLFW.GLFW_KEY_DOWN -> focus.next();
            default -> {
                return false;
            }
        }
        return true;
    }

    // a style switch drops everything that belongs to the old style's controls
    private void syncStyle() {
        GuiStyle next = styles.resolve(Theme.GUI_STYLE);
        if (next == style) {
            return;
        }
        if (style != null) {
            discardTransientState();
            focus.clear();
        }
        style = next;
    }

    // a rebuilt registry replaced the objects editors and overlays point at, so they are committed to the old
    // objects and dropped before navigation finds its way back by stable id
    private void syncRegistry() {
        MainGUIRegistry.Snapshot snapshot = registry.get();
        if (snapshot.generation() == generation) {
            return;
        }
        if (generation >= 0L) {
            discardTransientState();
        }
        generation = snapshot.generation();
        navigator.snapshotChanged(snapshot);
    }

    private void discardTransientState() {
        pageEditors.commit();
        overlays.closeAll();
        capture.cancel();
        endPointerCapture();
    }

    private void endPointerCapture() {
        hits.cancelCapture();
        persistence.end();
    }
}
