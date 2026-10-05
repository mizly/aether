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
    // the setting row under the cursor on the front panel, with the page and group it sits in
    public record Hover(dev.aether.ui.settings.Setting setting, String pageId, String group) {
    }

    // one search hit for the orbit's top bar; value is the setting's current state, or null
    public record SearchHit(String kind, String title, String path, dev.aether.ui.gui.Icon icon, String value,
                            Runnable run) {
    }

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
        style.hover = null;
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

    // brings a category to the front; an open page of that same category stays open
    public void orbitFocus(String categoryId) {
        if (!categoryId.equals(style.location().categoryId())) style.openCategory(categoryId);
    }

    // the category the panel ui is on, which moves when a search hit or link opens another one
    public String orbitCategory() {
        return style.location().categoryId();
    }

    public java.util.List<SearchHit> orbitSearch(String query) {
        java.util.List<SearchHit> out = new java.util.ArrayList<>();
        for (PanelSearch.Result r : style.search.search(host, query, true)) {
            String kind = switch (r.kind()) {
                case ACTION -> "Action";
                case PAGE -> "Page";
                case GROUP -> "Group";
                case SETTING -> "Setting";
            };
            out.add(new SearchHit(kind, r.title(), r.path(), r.icon(), valueText(r.setting()), r.run()));
        }
        return out;
    }

    private static String valueText(dev.aether.ui.settings.Setting setting) {
        try {
            return switch (setting) {
                case null -> null;
                case dev.aether.ui.settings.ToggleSetting t -> dev.aether.util.AetherLang.localize(t.getValue() ? "On" : "Off");
                case dev.aether.ui.settings.SliderSetting sl -> PanelRows.formatValue(sl.getValue(), sl.getDecimals(), sl.getSuffix());
                case dev.aether.ui.settings.RangeSliderSetting r -> PanelRows.formatValue(r.getLowerValue(), r.getDecimals(), "")
                        + "–" + PanelRows.formatValue(r.getUpperValue(), r.getDecimals(), r.getSuffix());
                case dev.aether.ui.settings.DropdownSetting d -> d.getSelectedOption();
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void orbitPlotHooks(PlotHooks hooks) {
        style.plotHooks = hooks == null ? PlotHooks.NONE : hooks;
    }

    public void orbitOpenPage(String pageId) {
        style.openPage(pageId);
    }

    // opens a page scrolled to a group or section, by its key or its label
    public void orbitOpenPage(String pageId, String anchor) {
        style.openPageAt(pageId, anchor);
    }

    // the failsafe tile under the cursor on the front panel, for the ring around the player
    public String orbitHoveredFailsafe() {
        return style.orbit.hoveredFailsafe();
    }

    // every failsafe as raw name, item and armed state, in panel order
    public java.util.List<String[]> orbitFailsafes() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (PanelNav.Category category : style.nav.categories()) {
            for (PanelNav.Page page : category.pages()) {
                if (PanelOrbit.isFailsafe(page)) {
                    out.add(new String[]{page.tab().rawName(), PanelOrbit.FAILSAFE_ITEMS.get(page.tab().rawName()),
                            page.enabled() ? "1" : "0", page.name()});
                }
            }
        }
        return out;
    }

    public Hover orbitHover() {
        return style.overlays.isOpen() ? null : style.hover;
    }

    public boolean orbitModuleOpen() {
        return style.location().pageId() != null;
    }

    public boolean orbitBack() {
        return style.up();
    }

    // a field or picker inside the panel has the keyboard
    public boolean orbitTyping() {
        return style.editingText() || style.overlays.isOpen();
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
