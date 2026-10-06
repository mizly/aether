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

// the orbit panels' navigation and editing state: which category and page each panel shows, the open text
// editor and the picker overlays; the parts draw
public final class PanelStyle {

    record Location(String categoryId, String pageId) {
        static final Location HOME = new Location(null, null);
    }

    final PanelNav nav = new PanelNav();
    final PanelRows rows = new PanelRows(this);
    final PanelModulePage modulePage = new PanelModulePage(this);
    final PanelSearch search = new PanelSearch(this);
    final PanelOverlays overlays = new PanelOverlays(this);
    final PanelOrbit orbit = new PanelOrbit(this);
    PlotHooks plotHooks = PlotHooks.NONE;
    PanelView.Hover hover;
    // set when a press already made its own sound, so the panel skips the default click
    boolean pressSounded;

    private final Deque<Location> history = new ArrayDeque<>();
    private final Map<String, ScrollState> scrolls = new HashMap<>();
    private Location location = Location.HOME;
    private int enterSerial;
    private PanelFrame lastFrame;

    private PanelRows.Key editingKey;
    private TextEditor editor;
    private Consumer<String> editorCommit;
    private boolean editorMultiline;
    private Rect editorRect = Rect.EMPTY;

    public void close() {
        commitEditor();
        overlays.close();
    }

    public Location location() {
        return location;
    }

    // -- navigation -----------------------------------------------------------------

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

    // module -> its category; false on a category, where the orbit screen takes escape to the overview
    boolean up() {
        if (location.pageId() != null) {
            navigate(new Location(location.categoryId(), null));
            return true;
        }
        return false;
    }

    ScrollState scroll(String key) {
        return scrolls.computeIfAbsent(key, k -> new ScrollState());
    }

    String enterKey() {
        return "aurora.enter." + enterSerial;
    }

    // the frame the front panel last drew with, for the clipboard while editing
    void frame(PanelFrame f) {
        lastFrame = f;
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

    // -- keys --------------------------------------------------------------------------------

    public boolean keyPressed(KeyInput k) {
        PanelHost host = lastFrame == null ? null : lastFrame.host();
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
        if (overlays.isOpen()) {
            return overlays.chars(chars);
        }
        if (editor != null) {
            return editor.chars(chars);
        }
        return false;
    }

    public boolean pointerOutsideEditor(float rootX, float rootY) {
        return editor != null && !editorRect.contains(rootX, rootY);
    }

    public boolean editingText() {
        return editor != null;
    }
}
