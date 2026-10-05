package dev.aether.ui.orbit.panel;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Animator;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.FocusManager;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.GuiClock;
import dev.aether.ui.gui.HitRegions;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PersistenceBatch;
import dev.aether.ui.gui.PointerInput;
import dev.aether.ui.gui.Rect;

// drives aurora frame by frame and routes input to it, until the shared gui view takes over this job
public final class PanelView {
    private final PanelHost host;
    private final GuiClock clock;
    private final GuiCanvas canvas = new GuiCanvas();
    private final HitRegions hits = new HitRegions(canvas);
    private final FocusManager focus = new FocusManager(canvas);
    private final Animator anim = new Animator();
    private final PanelStyle style = new PanelStyle();
    private final PersistenceBatch batch = new PersistenceBatch();
    private final GuiCanvas passiveCanvas = new GuiCanvas();
    private final HitRegions passiveHits = new HitRegions(passiveCanvas);
    private final FocusManager passiveFocus = new FocusManager(passiveCanvas);
    private final Animator passiveAnim = new Animator();
    private float animTimeMs = Animator.BASE_MS;
    private float minAnimTimeMs = 50f;
    private Palette palette;

    public PanelView(PanelHost host, GuiClock clock) {
        this.host = host;
        this.clock = clock;
    }

    public PanelStyle style() {
        return style;
    }

    HitRegions hits() {
        return hits;
    }

    public void animation(float timeMs, float minTimeMs) {
        animTimeMs = timeMs;
        minAnimTimeMs = minTimeMs;
    }

    public void open(String pageId) {
        style.open(pageId);
    }

    public void render(NVGRenderer nvg, float width, float height, float mouseX, float mouseY) {
        long now = clock.nanos();
        palette = Palette.fromTheme();
        canvas.begin(nvg, width, height);
        hits.begin(mouseX, mouseY);
        focus.begin();
        anim.begin(now, animTimeMs, minAnimTimeMs);
        PanelFrame frame = new PanelFrame(canvas, hits, focus, anim, palette, new Rect(0f, 0f, width, height),
                mouseX, mouseY, now, host, false);
        try {
            style.render(frame);
        } finally {
            while (canvas.depth() > 0) {
                canvas.restore();
            }
            hits.end();
            focus.end();
            canvas.end();
        }
    }

    // the front orbit panel: live, with input recorded in panel-local units
    public void renderOrbitActive(NVGRenderer nvg, float width, float height, float mouseX, float mouseY,
                                  String categoryId) {
        long now = clock.nanos();
        palette = Palette.fromTheme();
        canvas.begin(nvg, width, height);
        hits.begin(mouseX, mouseY);
        focus.begin();
        anim.begin(now, animTimeMs, minAnimTimeMs);
        Rect area = new Rect(0f, 0f, width, height);
        PanelFrame frame = new PanelFrame(canvas, hits, focus, anim, palette, area, mouseX, mouseY, now, host, false);
        try {
            style.orbit.draw(frame, area, categoryId, true, 0f);
            style.overlays.render(frame);
        } finally {
            while (canvas.depth() > 0) canvas.restore();
            hits.end();
            focus.end();
            canvas.end();
        }
    }

    // a side or overview panel: drawn without input
    public void renderOrbitPassive(NVGRenderer nvg, float width, float height, String categoryId, float overview) {
        long now = clock.nanos();
        Palette p = palette == null ? Palette.fromTheme() : palette;
        passiveCanvas.begin(nvg, width, height);
        passiveHits.begin(-1f, -1f);
        passiveFocus.begin();
        passiveAnim.begin(now, animTimeMs, minAnimTimeMs);
        Rect area = new Rect(0f, 0f, width, height);
        PanelFrame frame = new PanelFrame(passiveCanvas, passiveHits, passiveFocus, passiveAnim, p, area, -1f, -1f, now,
                host, false).inert();
        try {
            style.orbit.draw(frame, area, categoryId, false, overview);
        } finally {
            while (passiveCanvas.depth() > 0) passiveCanvas.restore();
            passiveHits.end();
            passiveFocus.end();
            passiveCanvas.end();
        }
    }

    public java.util.List<String> orbitCategoryIds() {
        return style.nav.categories().stream().map(PanelNav.Category::id).toList();
    }

    public String orbitCategoryName(String id) {
        PanelNav.Category category = style.nav.category(id);
        return category == null ? id : category.name();
    }

    public dev.aether.ui.gui.Icon orbitCategoryIcon(String id) {
        PanelNav.Category category = style.nav.category(id);
        return category == null ? null : category.icon();
    }

    public String orbitOpenPageName() {
        PanelNav.Page page = style.nav.page(style.location().pageId());
        return page == null ? null : page.name();
    }

    public void orbitFocus(String categoryId) {
        PanelStyle.Location at = style.location();
        if (!categoryId.equals(at.categoryId()) || at.pageId() != null) style.openCategory(categoryId);
    }

    public void orbitOpenPage(String pageId) {
        style.openPage(pageId);
    }

    public boolean orbitModuleOpen() {
        return style.location().pageId() != null;
    }

    public boolean orbitBack() {
        return style.up();
    }

    public boolean orbitOverlayOpen() {
        return style.overlays.isOpen();
    }

    // a fixed-scale thumbnail of the style for pickers, drawn without input or animation
    public void renderPreview(NVGRenderer nvg, Rect area) {
        PanelFrame frame = new PanelFrame(canvas, hits, focus, anim, palette == null ? Palette.fromTheme() : palette,
                area, -1f, -1f, clock.nanos(), host, false).inert();
        PanelPreview.draw(frame, area);
    }

    public boolean pointerPressed(PointerInput in) {
        focus.pointerUsed();
        if (style.pointerOutsideEditor(in.x(), in.y())) {
            style.commitEditor();
        }
        boolean handled = hits.press(in);
        if (hits.captured()) {
            batch.begin();
        }
        return handled;
    }

    public boolean pointerReleased(PointerInput in) {
        boolean handled = hits.release(in);
        if (!hits.captured()) {
            batch.end();
        }
        return handled;
    }

    public boolean pointerDragged(PointerInput in) {
        return hits.drag(in);
    }

    public boolean scrolled(float x, float y, double dx, double dy) {
        return hits.scroll(x, y, dy);
    }

    public boolean keyPressed(KeyInput k) {
        return style.keyPressed(k);
    }

    public boolean charTyped(String chars) {
        return style.charTyped(chars);
    }

    public void close() {
        hits.cancelCapture();
        style.close();
        batch.end();
    }

    public boolean pointerCaptured() {
        return hits.captured();
    }

    public Cursor cursor() {
        return hits.cursor();
    }

    public int scrimArgb() {
        Palette p = palette == null ? Palette.fromTheme() : palette;
        return p.light() ? p.scrim() : 0x66000000;
    }
}
