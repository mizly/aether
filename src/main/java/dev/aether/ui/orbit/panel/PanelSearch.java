package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.ui.gui.TextEditor;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SettingType;
import dev.aether.util.AetherLang;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// the spotlight palette: one input over ranked pages, groups, settings and actions, driven by arrows and enter
final class PanelSearch {
    private static final float INPUT_H = 56f;
    private static final float ROW_H = 46f;
    private static final int MAX_ROWS = 8;
    private static final int MAX_RESULTS = 60;

    enum Kind { ACTION, PAGE, GROUP, SETTING }

    record Result(Kind kind, String title, String path, Icon icon, Runnable run, int rank, Setting setting) {
        Result(Kind kind, String title, String path, Icon icon, Runnable run, int rank) {
            this(kind, title, path, icon, run, rank, null);
        }
    }

    private final PanelStyle style;
    private final ScrollState scroll = new ScrollState();
    private TextEditor query;
    private int selected;
    private int serial;
    private String lastQuery;
    private List<Result> results = List.of();
    private long lastGeneration = Long.MIN_VALUE;

    PanelSearch(PanelStyle style) {
        this.style = style;
    }

    boolean isOpen() {
        return query != null;
    }

    void open(String initial) {
        query = new TextEditor(initial == null ? "" : initial).maxLength(80);
        selected = 0;
        serial++;
        lastQuery = null;
        scroll.jumpTo(0f);
    }

    void close() {
        query = null;
    }

    String query() {
        return query == null ? "" : query.text();
    }

    // -- render ---------------------------------------------------------------------

    void render(PanelFrame f) {
        if (query == null) {
            return;
        }
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        refresh(f);
        Rect window = style.window();
        Rect view = f.viewport();
        float t = f.anim().stagger("aurora.search.in." + serial, 0);

        f.hits().add("aurora.search.backdrop", view, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                close();
                return true;
            }

            @Override
            public boolean scroll(PointerEvent e, double dy) {
                return true;
            }
        });
        c.save();
        c.alpha(t);
        c.roundedRect(window, PanelStyle.WINDOW_RADIUS, Argb.withAlpha(p.scrim(), p.light() ? 0.55f : 0.45f));
        c.restore();

        float w = Math.min(620f, window.w() - 64f);
        int rows = Math.min(MAX_ROWS, Math.max(1, results.size()));
        float listH = rows * ROW_H;
        float h = INPUT_H + 1f + 8f + listH + 8f + 34f;
        Rect panel = new Rect(window.centerX() - w / 2f, window.y() + Math.max(40f, window.h() * 0.12f), w, h);
        c.save();
        c.alpha(t);
        c.translate(0f, (1f - t) * -8f);
        PanelOverlays.glass(c, p, panel, 14f);
        f.hits().block(panel);

        Rect input = new Rect(panel.x(), panel.y(), panel.w(), INPUT_H);
        PanelPaint.icon(c, PanelSidebar.SEARCH, input.x() + 26f, input.centerY(), 17f, p.accent());
        Rect text = new Rect(input.x() + 48f, input.centerY() - 11f, input.w() - 48f - 70f, 22f);
        query.layout(c.metrics(), MEDIUM, 16f, text.w(), text.h());
        c.save();
        c.clip(text.inset(-1f, -3f, -1f, -3f));
        if (query.text().isEmpty()) {
            c.text(MEDIUM, 16f, AetherLang.localize("Search settings, pages and actions"), text.x(),
                    text.y() + (text.h() - c.lineHeight(MEDIUM, 16f)) / 2f, p.textMuted());
        }
        float lineTop = text.y() + (text.h() - query.lineHeight()) / 2f;
        for (Rect sel : query.selectionRects()) {
            c.rect(sel.offset(text.x(), lineTop), Argb.withAlpha(p.accent(), 0.35f));
        }
        c.text(MEDIUM, 16f, query.text(), text.x() - query.scrollX(), lineTop, p.text());
        if ((f.nanos() / 530_000_000L) % 2L == 0L) {
            Rect caret = query.caretRect();
            c.rect(new Rect(text.x() + caret.x(), lineTop + caret.y() + 1f, 1.5f, caret.h() - 2f), p.accent());
        }
        c.restore();
        PanelPaint.keycap(c, p, input.right() - 18f - c.textWidth(MEDIUM, 10.5f, "Esc") - 12f, input.centerY(), "Esc");
        c.line(panel.x(), input.bottom(), panel.right(), input.bottom(), 1f, PanelPaint.hairline(p));

        Rect list = new Rect(panel.x() + 8f, input.bottom() + 9f, panel.w() - 16f, listH);
        scroll.tick(f.nanos(), 200f, f.frozen());
        scroll.setExtent(results.size() * ROW_H, list.h());
        f.hits().add("aurora.search.list", list, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * ROW_H));
                return true;
            }
        });
        c.save();
        c.clip(list);
        if (results.isEmpty()) {
            String empty = AetherLang.localize("No results for") + " \"" + query.text() + "\"";
            PanelPaint.textCentered(c, MEDIUM, 13f, c.ellipsize(MEDIUM, 13f, empty, list.w() - 40f), list.centerX(),
                    list.centerY(), p.textMuted());
        }
        for (int i = 0; i < results.size(); i++) {
            Rect row = new Rect(list.x(), list.y() + i * ROW_H - scroll.offset(), list.w(), ROW_H);
            if (!c.isVisible(row)) {
                continue;
            }
            drawResult(f, results.get(i), row, i);
        }
        c.restore();

        Rect footer = new Rect(panel.x(), list.bottom() + 8f, panel.w(), 34f);
        c.line(panel.x(), footer.y(), panel.right(), footer.y(), 1f, PanelPaint.hairline(p));
        float fx = footer.x() + 18f;
        fx += PanelPaint.keycap(c, p, fx, footer.centerY(), "↑↓") + 6f;
        fx += drawHint(c, p, AetherLang.localize("move"), fx, footer.centerY()) + 14f;
        fx += PanelPaint.keycap(c, p, fx, footer.centerY(), "Enter") + 6f;
        drawHint(c, p, AetherLang.localize("open"), fx, footer.centerY());
        String count = results.size() + " " + AetherLang.localize(results.size() == 1 ? "result" : "results");
        PanelPaint.textRight(c, REGULAR, 11f, count, footer.right() - 18f, footer.centerY(), p.textMuted());
        c.restore();
    }

    private static float drawHint(GuiCanvas c, Palette p, String text, float x, float cy) {
        PanelPaint.text(c, REGULAR, 11f, text, x, cy, p.textMuted());
        return c.textWidth(REGULAR, 11f, text);
    }

    private void drawResult(PanelFrame f, Result result, Rect row, int index) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        String id = "aurora.search.result." + index;
        boolean active = index == selected;
        if (active) {
            c.roundedRect(row, 10f, PanelPaint.accentWash(p, 1.2f));
        } else if (f.hits().hovered(id)) {
            c.roundedRect(row, 10f, p.hover());
        }
        Rect tile = new Rect(row.x() + 8f, row.centerY() - 15f, 30f, 30f);
        c.roundedRect(tile, 8f, Argb.withAlpha(p.text(), 0.06f));
        if (result.icon() != null) {
            PanelPaint.icon(c, result.icon(), tile.centerX(), tile.centerY(), 18f, p.textSecondary());
        } else if (result.kind() == Kind.ACTION) {
            PanelPaint.play(c, tile.centerX(), tile.centerY(), 9f, p.accent());
        } else {
            c.circle(tile.centerX(), tile.centerY(), 3f, p.textMuted());
        }
        float tx = tile.right() + 12f;
        String kind = AetherLang.localize(switch (result.kind()) {
            case ACTION -> "Action";
            case PAGE -> "Page";
            case GROUP -> "Group";
            case SETTING -> "Setting";
        });
        float kindW = c.textWidth(MEDIUM, 10.5f, kind);
        float textW = row.right() - tx - kindW - (active ? 60f : 24f);
        boolean hasPath = result.path() != null && !result.path().isEmpty();
        PanelPaint.fitText(c, SEMIBOLD, 13f, result.title(), tx, row.centerY() - (hasPath ? 8f : 0f), textW, p.text());
        if (hasPath) {
            PanelPaint.fitText(c, REGULAR, 11f, result.path(), tx, row.centerY() + 9f, textW, p.textMuted());
        }
        float right = row.right() - 12f;
        if (active) {
            right -= PanelPaint.keycap(c, p, right - c.textWidth(MEDIUM, 10.5f, "↵") - 12f, row.centerY(), "↵") + 8f;
        }
        PanelPaint.textRight(c, MEDIUM, 10.5f, kind, right, row.centerY(), p.textMuted());
        f.hits().add(id, row, HitHandler.click(() -> run(result)), Cursor.HAND);
    }

    // -- results -------------------------------------------------------------------------

    private void refresh(PanelFrame f) {
        String q = query.text().trim();
        if (q.equals(lastQuery) && style.nav.generation() == lastGeneration) {
            return;
        }
        lastQuery = q;
        lastGeneration = style.nav.generation();
        results = search(f.host(), q, false);
        selected = 0;
        scroll.jumpTo(0f);
    }

    // ranked hits for q; the orbit menu has no home page, so it leaves that action out
    List<Result> search(PanelHost host, String q, boolean orbit) {
        List<Result> out = new ArrayList<>();
        String needle = q.toLowerCase(Locale.ROOT);
        for (Result action : actions(host, orbit)) {
            int rank = rank(action.title(), needle);
            if (rank >= 0) {
                out.add(new Result(action.kind(), action.title(), action.path(), action.icon(), action.run(), rank));
            }
        }
        if (needle.isEmpty()) {
            for (PanelNav.Category category : style.nav.categories()) {
                out.add(new Result(Kind.PAGE, category.name(), category.description(), category.icon(),
                        () -> style.openCategory(category.id()), 0));
            }
            return out;
        }
        for (PanelNav.Category category : style.nav.categories()) {
            int categoryRank = rank(category.name(), needle);
            if (categoryRank >= 0) {
                out.add(new Result(Kind.PAGE, category.name(), AetherLang.localize("Category"), category.icon(),
                        () -> style.openCategory(category.id()), categoryRank));
            }
            for (PanelNav.Page page : category.pages()) {
                int pageRank = rank(page.name(), needle);
                if (pageRank >= 0) {
                    out.add(new Result(Kind.PAGE, page.name(), category.name(), page.icon(),
                            () -> style.openPage(page.id()), pageRank));
                }
                int groupIndex = 0;
                for (SettingGroup group : page.tab().groups()) {
                    int gi = groupIndex++;
                    String groupKey = page.id() + "/" + group.getRawName() + "#" + gi;
                    String groupPath = category.name() + " › " + page.name();
                    int groupRank = rank(group.getName(), needle);
                    if (groupRank >= 0 && !group.getName().equals(page.name())) {
                        out.add(new Result(Kind.GROUP, group.getName(), groupPath, page.icon(),
                                () -> style.openPageAt(page.id(), groupKey), groupRank));
                    }
                    for (Setting setting : group.getSettings()) {
                        if (setting.getType() == SettingType.SECTION || !visible(setting)) {
                            continue;
                        }
                        int settingRank = rank(setting.getName(), needle);
                        if (settingRank >= 0) {
                            out.add(new Result(Kind.SETTING, setting.getName(), groupPath + " › " + group.getName(),
                                    page.icon(), () -> style.openPageAt(page.id(), groupKey), settingRank, setting));
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(Result::rank).thenComparing(r -> r.kind().ordinal()));
        return out.size() > MAX_RESULTS ? List.copyOf(out.subList(0, MAX_RESULTS)) : out;
    }

    private static boolean visible(Setting setting) {
        try {
            return setting.isVisible();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<Result> actions(PanelHost host, boolean orbit) {
        List<Result> actions = new ArrayList<>();
        PanelHost.Session session = host.session();
        if (session != null && session.resumable()) {
            actions.add(new Result(Kind.ACTION, AetherLang.localize("Resume") + " " + AetherLang.localize(session.macroName()),
                    "Ctrl+Enter", PanelSidebar.PLAY, host::resume, 0));
        }
        actions.add(new Result(Kind.ACTION, AetherLang.localize("Open macro menu"), null, PanelSidebar.PLAY,
                host::openMacroMenu, 0));
        actions.add(new Result(Kind.ACTION, AetherLang.localize("Edit HUD layout"), null, PanelSidebar.HUD,
                host::openHudEditor, 0));
        if (!orbit) actions.add(new Result(Kind.ACTION, AetherLang.localize("Go home"), null, null, style::goHome, 0));
        return actions;
    }

    // exact 0, prefix 1, word prefix 2, contains 3, subsequence 4, no match -1
    static int rank(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        String hay = text.toLowerCase(Locale.ROOT);
        if (hay.equals(needle)) {
            return 0;
        }
        if (hay.startsWith(needle)) {
            return 1;
        }
        if (hay.contains(" " + needle) || hay.contains("(" + needle)) {
            return 2;
        }
        if (hay.contains(needle)) {
            return 3;
        }
        int at = 0;
        for (int i = 0; i < hay.length() && at < needle.length(); i++) {
            if (hay.charAt(i) == needle.charAt(at)) {
                at++;
            }
        }
        return at == needle.length() && needle.length() >= 3 ? 4 : -1;
    }

    private void run(Result result) {
        close();
        result.run().run();
    }

    // -- keys -----------------------------------------------------------------------------

    boolean key(KeyInput k, Clipboard clipboard) {
        switch (k.key()) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (!query.text().isEmpty()) {
                    query.setText("");
                } else {
                    close();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> {
                move(k.shift() && k.is(GLFW.GLFW_KEY_TAB) ? -1 : 1);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                move(-1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (selected >= 0 && selected < results.size()) {
                    run(results.get(selected));
                }
                return true;
            }
            default -> {
                query.key(k, clipboard);
                return true;
            }
        }
    }

    boolean chars(String typed) {
        return query.chars(typed);
    }

    private void move(int direction) {
        if (results.isEmpty()) {
            return;
        }
        selected = Math.floorMod(selected + direction, results.size());
        scroll.ensureVisible(selected * ROW_H, selected * ROW_H + ROW_H, 0f);
    }
}
