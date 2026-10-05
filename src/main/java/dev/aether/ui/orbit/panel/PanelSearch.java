package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SettingType;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// ranks every page, group, setting and action against a query for the orbit's top bar search
final class PanelSearch {
    private static final int MAX_RESULTS = 60;

    enum Kind { ACTION, PAGE, GROUP, SETTING }

    record Result(Kind kind, String title, String path, Icon icon, Runnable run, int rank, Setting setting) {
        Result(Kind kind, String title, String path, Icon icon, Runnable run, int rank) {
            this(kind, title, path, icon, run, rank, null);
        }
    }

    private final PanelStyle style;

    PanelSearch(PanelStyle style) {
        this.style = style;
    }

    // ranked pages, groups, settings and actions for q; an empty query lists the categories
    List<Result> search(PanelHost host, String q) {
        List<Result> out = new ArrayList<>();
        String needle = q.toLowerCase(Locale.ROOT);
        for (Result action : actions(host)) {
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

    private static List<Result> actions(PanelHost host) {
        List<Result> actions = new ArrayList<>();
        PanelHost.Session session = host.session();
        if (session != null && session.resumable()) {
            actions.add(new Result(Kind.ACTION, AetherLang.localize("Resume") + " " + AetherLang.localize(session.macroName()),
                    "Ctrl+Enter", PanelPaint.PLAY, host::resume, 0));
        }
        actions.add(new Result(Kind.ACTION, AetherLang.localize("Open macro menu"), null, PanelPaint.PLAY,
                host::openMacroMenu, 0));
        actions.add(new Result(Kind.ACTION, AetherLang.localize("Edit HUD layout"), null, PanelPaint.HUD,
                host::openHudEditor, 0));
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
}
