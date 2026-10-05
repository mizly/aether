package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.ui.gui.TextEditor;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

// aurora: a centred glass window with a two-level sidebar, a dashboard home, card-grid categories and
// module pages of rounded setting groups. this class owns navigation and editing state; the parts draw
public final class PanelStyle {
    public static final String ID = "aurora";

    static final float WINDOW_MAX_W = 1120f;
    static final float WINDOW_MAX_H = 700f;
    static final float WINDOW_MARGIN = 24f;
    static final float WINDOW_RADIUS = 16f;
    static final float SIDEBAR_W = 232f;
    static final float RAIL_W = 68f;
    static final float HEADER_H = 60f;

    record Location(String categoryId, String pageId) {
        static final Location HOME = new Location(null, null);
    }

    final PanelNav nav = new PanelNav();
    final PanelRows rows = new PanelRows(this);
    final PanelSidebar sidebar = new PanelSidebar(this);
    final PanelHeader header = new PanelHeader(this);
    final PanelHome home = new PanelHome(this);
    final PanelModulePage modulePage = new PanelModulePage(this);
    final PanelSearch search = new PanelSearch(this);
    final PanelOverlays overlays = new PanelOverlays(this);
    final PanelOrbit orbit = new PanelOrbit(this);
    PlotHooks plotHooks = PlotHooks.NONE;

    private static Location remembered = Location.HOME;

    private final Deque<Location> history = new ArrayDeque<>();
    private final Map<String, ScrollState> scrolls = new HashMap<>();
    private Location location = Location.HOME;
    private boolean railPinned;
    private float dragX;
    private float dragY;
    private float lastViewW = -1f;
    private float lastViewH = -1f;
    private int enterSerial;
    private int openSerial;
    private Rect window = Rect.EMPTY;
    private boolean rail;
    private PanelFrame lastFrame;

    private PanelRows.Key editingKey;
    private TextEditor editor;
    private Consumer<String> editorCommit;
    private boolean editorMultiline;
    private Rect editorRect = Rect.EMPTY;

    public String id() {
        return ID;
    }

    public String displayName() {
        return "Aurora";
    }

    // -- lifecycle ----------------------------------------------------------------

    public void open(String pageId) {
        openSerial++;
        enterSerial++;
        history.clear();
        if (pageId != null && nav.page(pageId) != null) {
            PanelNav.Page page = nav.page(pageId);
            location = new Location(page.categoryId(), page.id());
        } else if (remembered != null && (remembered.pageId() == null || nav.page(remembered.pageId()) != null)) {
            location = remembered;
        } else {
            location = Location.HOME;
        }
    }

    public void close() {
        commitEditor();
        overlays.close();
        search.close();
        remembered = location;
    }

    public Location location() {
        return location;
    }

    // -- navigation -----------------------------------------------------------------

    void goHome() {
        navigate(Location.HOME);
    }

    void openCategory(String categoryId) {
        navigate(new Location(categoryId, null));
    }

    void openPage(String pageId) {
        PanelNav.Page page = nav.page(pageId);
        if (page != null) {
            navigate(new Location(page.categoryId(), page.id()));
        }
    }

    void openPageAt(String pageId, String anchorKey) {
        openPage(pageId);
        modulePage.revealAnchor(anchorKey);
    }

    private void navigate(Location next) {
        if (next.equals(location)) {
            return;
        }
        commitEditor();
        overlays.close();
        history.push(location);
        while (history.size() > 32) {
            history.removeLast();
        }
        location = next;
        enterSerial++;
    }

    boolean back() {
        if (history.isEmpty()) {
            return false;
        }
        commitEditor();
        overlays.close();
        location = history.pop();
        enterSerial++;
        return true;
    }

    // module -> category -> home; false at home so escape closes the screen
    boolean up() {
        if (location.pageId() != null) {
            navigate(new Location(location.categoryId(), null));
            return true;
        }
        if (location.categoryId() != null) {
            navigate(Location.HOME);
            return true;
        }
        return false;
    }

    void openAppearance() {
        for (String id : new String[]{"theme-options", "menu-colors"}) {
            if (nav.page(id) != null) {
                openPage(id);
                return;
            }
        }
    }

    // the failsafe's own page when one matches its name, otherwise the safety category
    void openFailsafe(String name) {
        PanelNav.Category safety = nav.category("safety");
        if (safety == null) {
            return;
        }
        for (PanelNav.Page page : safety.pages()) {
            if (page.tab().rawName().equalsIgnoreCase(name) || page.name().equalsIgnoreCase(name)) {
                openPage(page.id());
                return;
            }
        }
        openCategory(safety.id());
    }

    ScrollState scroll(String key) {
        return scrolls.computeIfAbsent(key, k -> new ScrollState());
    }

    String enterKey() {
        return "aurora.enter." + enterSerial;
    }

    boolean rail() {
        return rail;
    }

    void toggleRail() {
        railPinned = !railPinned;
    }

    Rect window() {
        return window;
    }

    void dragWindow(float x, float y) {
        dragX = x;
        dragY = y;
    }

    float dragX() {
        return dragX;
    }

    float dragY() {
        return dragY;
    }

    // -- frame ---------------------------------------------------------------------------

    public void render(PanelFrame f) {
        lastFrame = f;
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Rect view = f.viewport();
        if (view.w() != lastViewW || view.h() != lastViewH) {
            lastViewW = view.w();
            lastViewH = view.h();
            dragX = 0f;
            dragY = 0f;
        }
        float w = Math.min(WINDOW_MAX_W, view.w() - WINDOW_MARGIN * 2f);
        float h = Math.min(WINDOW_MAX_H, view.h() - WINDOW_MARGIN * 2f);
        float maxDx = (view.w() - w) / 2f;
        float maxDy = (view.h() - h) / 2f;
        dragX = Math.max(-maxDx, Math.min(maxDx, dragX));
        dragY = Math.max(-maxDy, Math.min(maxDy, dragY));
        window = new Rect(Math.round((view.w() - w) / 2f + dragX), Math.round((view.h() - h) / 2f + dragY), w, h);
        rail = railPinned || w < 860f;

        float enter = f.anim().stagger("aurora.window." + openSerial, 0);
        drawBackdrop(c, p, view, window, enter);

        c.save();
        c.alpha(enter);
        c.translate(0f, (1f - enter) * 12f);
        drawWindow(c, p, window);
        f.hits().block(window);
        c.save();
        c.clip(window);
        float sidebarW = rail ? RAIL_W : SIDEBAR_W;
        Rect side = new Rect(window.x(), window.y(), sidebarW, window.h());
        Rect main = new Rect(window.x() + sidebarW, window.y(), window.w() - sidebarW, window.h());
        drawBody(f, main);
        sidebar.draw(f, side);
        c.restore();
        c.restore();

        overlays.render(f);
        search.render(f);
    }

    private void drawBody(PanelFrame f, Rect main) {
        Rect headerRect = new Rect(main.x(), main.y(), main.w(), HEADER_H);
        Rect body = new Rect(main.x(), main.y() + HEADER_H, main.w(), main.h() - HEADER_H);
        PanelNav.Page page = nav.page(location.pageId());
        PanelNav.Category category = location.categoryId() == null ? null : nav.category(location.categoryId());
        if (location.pageId() != null && page == null) {
            location = category != null ? new Location(category.id(), null) : Location.HOME;
        }
        if (location.categoryId() != null && category == null) {
            location = Location.HOME;
        }
        if (page != null) {
            modulePage.draw(f, body, page);
        } else if (category != null) {
            home.drawCategory(f, body, category);
        } else {
            home.drawHome(f, body);
        }
        header.draw(f, headerRect, category, page);
    }

    private static void drawBackdrop(GuiCanvas c, Palette p, Rect view, Rect window, float enter) {
        c.save();
        c.alpha(enter);
        float radius = Math.max(window.w(), window.h()) * 0.75f;
        c.legacy(nvg -> {
            nvg.radialGradient(window.x() + window.w() * 0.18f, window.y() + window.h() * 0.12f, 0f, radius,
                    Argb.withAlpha(p.accent(), p.light() ? 0.16f : 0.20f), Argb.withAlpha(p.accent(), 0f));
            nvg.radialGradient(window.right() - window.w() * 0.1f, window.bottom(), 0f, radius * 0.8f,
                    Argb.withAlpha(p.accent2(), p.light() ? 0.10f : 0.12f), Argb.withAlpha(p.accent2(), 0f));
        });
        c.restore();
    }

    private static void drawWindow(GuiCanvas c, Palette p, Rect window) {
        c.shadow(window.offset(0f, 10f), WINDOW_RADIUS, 48f, PanelPaint.shadow(p, 0.9f));
        c.roundedRect(window, WINDOW_RADIUS, PanelPaint.windowFill(p));
        c.strokeRect(window, WINDOW_RADIUS, 1f, Argb.withAlpha(p.border(), p.light() ? 0.35f : 0.28f));
        c.line(window.x() + WINDOW_RADIUS, window.y() + 1f, window.right() - WINDOW_RADIUS, window.y() + 1f, 1f,
                PanelPaint.highlight(p));
    }

    // -- editing -----------------------------------------------------------------------

    boolean editing(PanelRows.Key key) {
        return editor != null && key.equals(editingKey);
    }

    void beginEdit(PanelRows.Key key, String value, boolean multiline, Consumer<String> commit) {
        if (key.equals(editingKey)) {
            return;
        }
        commitEditor();
        editingKey = key;
        editor = new TextEditor(value == null ? "" : value);
        if (multiline) {
            editor.multiline();
        }
        editorMultiline = multiline;
        editorCommit = commit;
    }

    void editorPress(float localX, float localY) {
        if (editor != null) {
            editor.pointerPress(localX, localY, false);
            editor.pointerRelease();
        }
    }

    void commitEditor() {
        if (editor != null && editorCommit != null) {
            editorCommit.accept(editor.text());
        }
        editor = null;
        editingKey = null;
        editorCommit = null;
    }

    void drawEditor(PanelFrame f, Rect inner, boolean multiline) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        if (editor == null) {
            return;
        }
        float size = 12.5f;
        float top = multiline ? inner.y() + 7f : inner.centerY() - c.lineHeight(PanelPaint.REGULAR, size) / 2f;
        Rect area = new Rect(inner.x(), top, inner.w(), multiline ? inner.h() - 14f : c.lineHeight(PanelPaint.REGULAR, size));
        editor.layout(c.metrics(), PanelPaint.REGULAR, size, area.w(), area.h());
        editorRect = c.toRoot(area);
        c.save();
        c.clip(area.inset(-1f, -2f, -1f, -2f));
        for (Rect selection : editor.selectionRects()) {
            c.rect(selection.offset(area.x(), area.y()), Argb.withAlpha(p.accent(), 0.35f));
        }
        for (int line = 0; line < editor.lineCount(); line++) {
            c.text(PanelPaint.REGULAR, size, editor.lineText(line), area.x() - editor.scrollX(),
                    area.y() + editor.lineTop(line), p.text());
        }
        if ((f.nanos() / 530_000_000L) % 2L == 0L) {
            Rect caret = editor.caretRect();
            c.rect(new Rect(area.x() + caret.x(), area.y() + caret.y(), 1.2f, caret.h()), p.accent());
        }
        c.restore();
    }

    // -- overlays ------------------------------------------------------------------------

    boolean menuOpenFor(PanelRows.Key key) {
        return overlays.openFor(key);
    }

    void openDropdown(PanelRows.Key key, DropdownSetting setting, Rect rootAnchor) {
        commitEditor();
        overlays.openDropdown(key, setting, rootAnchor);
    }

    void openMulti(PanelRows.Key key, MultiDropdownSetting setting, Rect rootAnchor) {
        commitEditor();
        overlays.openMulti(key, setting, rootAnchor);
    }

    void openAddPicker(PanelRows.Key key, DropdownListSetting setting, Rect rootAnchor) {
        commitEditor();
        overlays.openAddPicker(key, setting, rootAnchor);
    }

    void openColor(PanelRows.Key key, ColorSetting setting, Rect rootAnchor) {
        commitEditor();
        overlays.openColor(key, setting, rootAnchor);
    }

    void openSearch(String query) {
        commitEditor();
        overlays.close();
        search.open(query);
    }

    // -- keys --------------------------------------------------------------------------------

    public boolean keyPressed(KeyInput k) {
        PanelHost host = lastFrame == null ? null : lastFrame.host();
        if (search.isOpen()) {
            return search.key(k, host == null ? null : host.clipboard());
        }
        if (overlays.isOpen()) {
            return overlays.key(k);
        }
        if (editor != null) {
            if (k.is(GLFW.GLFW_KEY_ESCAPE) || (k.is(GLFW.GLFW_KEY_ENTER) && !k.shift())
                    || k.is(GLFW.GLFW_KEY_KP_ENTER)) {
                if (k.is(GLFW.GLFW_KEY_ESCAPE) || !editorMultiline) {
                    commitEditor();
                    return true;
                }
            }
            editor.key(k, host == null ? null : host.clipboard());
            return true;
        }
        if (k.shortcut() && k.is(GLFW.GLFW_KEY_F)) {
            openSearch("");
            return true;
        }
        if (k.shortcut() && (k.is(GLFW.GLFW_KEY_ENTER) || k.is(GLFW.GLFW_KEY_KP_ENTER))) {
            if (host != null && host.session() != null && host.session().resumable()) {
                host.resume();
            }
            return true;
        }
        if (k.is(GLFW.GLFW_KEY_ESCAPE)) {
            return up();
        }
        if (k.is(GLFW.GLFW_KEY_BACKSPACE) || (k.alt() && k.is(GLFW.GLFW_KEY_LEFT))) {
            return back();
        }
        return false;
    }

    public boolean charTyped(String chars) {
        if (search.isOpen()) {
            return search.chars(chars);
        }
        if (overlays.isOpen()) {
            return overlays.chars(chars);
        }
        if (editor != null) {
            return editor.chars(chars);
        }
        if ("/".equals(chars)) {
            openSearch("");
            return true;
        }
        return false;
    }

    // a press that reaches no region drops text focus, committing what was typed
    public void pointerMissed() {
        commitEditor();
    }

    public boolean pointerOutsideEditor(float rootX, float rootY) {
        return editor != null && !editorRect.contains(rootX, rootY);
    }

    public boolean editingText() {
        return editor != null || search.isOpen();
    }
}
