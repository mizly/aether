package dev.aether.ui.gui.nav;

import dev.aether.config.ConfigProfileManager;
import dev.aether.macro.MacroCatalog;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.GuiClock;
import dev.aether.ui.gui.GuiFrame;
import dev.aether.ui.gui.GuiHost;
import dev.aether.ui.gui.GuiNavigator;
import dev.aether.ui.gui.GuiStyle;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.LaunchRequest;
import dev.aether.ui.gui.StyleRegistry;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;
import dev.aether.util.AetherLang;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

// the gui's navigation state and every action a style or the command palette can trigger. locations,
// history, peeks and pins are keyed by stable ids, so a registry rebuild only needs them re-resolved
public final class GuiActions implements GuiNavigator {
    public static final long FLASH_NANOS = 1_400_000_000L;
    // a revealed row is scrolled to about a third of the way down the list
    public static final float REVEAL_VIEW_FRACTION = 1f / 3f;
    private static final int HISTORY_LIMIT = 50;

    // restored by open(null) for the rest of the session
    private static NavLocation lastLocation = NavLocation.HOME;

    // the style's command palette; installed by the overlay layer
    @FunctionalInterface
    public interface SearchOpener {
        boolean open(GuiFrame frame, SearchIndex index);
    }

    // a scroll-to and flash request for an anchor on a page
    public record Reveal(String pageId, String anchor, long startNanos) {
    }

    private final GuiHost host;
    private final GuiClock clock;
    private final StyleRegistry styles;
    private final Supplier<MainGUIRegistry.Snapshot> registry;
    private final Supplier<Placement> placement;
    private final PinStore pins;
    private final ChangeLog changes;
    private final SearchIndex search;
    private final Deque<NavLocation> history = new ArrayDeque<>();
    private final Set<GroupKey> peeks = new HashSet<>();
    private NavModel model;
    private NavLocation location = NavLocation.HOME;
    private Reveal reveal;
    private boolean scrollPending;
    private String query = "";
    private SearchOpener searchOpener = (frame, index) -> false;

    public GuiActions(GuiHost host, GuiClock clock, StyleRegistry styles,
                      Supplier<MainGUIRegistry.Snapshot> registry) {
        this(host, clock, styles, registry, PlacementTable::current, PinStore.shared(), ChangeLog.shared());
    }

    public GuiActions(GuiHost host, GuiClock clock, StyleRegistry styles, Supplier<MainGUIRegistry.Snapshot> registry,
                      Supplier<Placement> placement, PinStore pins, ChangeLog changes) {
        this.host = host;
        this.clock = clock;
        this.styles = styles;
        this.registry = registry;
        this.placement = placement;
        this.pins = pins;
        this.changes = changes;
        this.search = new SearchIndex(this::model)
                .add(SearchIndex.pages(page -> () -> openPage(page.id())))
                .add(actionSource())
                .add(SearchIndex.groups(key -> () -> revealGroup(key)))
                .add(SearchIndex.sections(key -> () -> revealSetting(key)))
                .add(SearchIndex.settings(key -> () -> revealSetting(key)));
    }

    // -- model and location ----------------------------------------------------

    public NavModel model() {
        MainGUIRegistry.Snapshot snapshot = registry.get();
        Placement current = placement.get();
        if (model == null || model.snapshot() != snapshot || model.placement() != current) {
            model = NavModel.build(snapshot, current);
        }
        return model;
    }

    public NavLocation location() {
        return location;
    }

    public Optional<NavCategory> currentCategory() {
        return model().category(location.categoryId());
    }

    public Optional<NavPage> currentPage() {
        return model().page(location.pageId());
    }

    // newest last
    public List<NavLocation> history() {
        return List.copyOf(history);
    }

    public void home() {
        go(NavLocation.HOME);
    }

    public boolean openCategory(String categoryId) {
        if (model().category(categoryId).isEmpty()) {
            return false;
        }
        go(NavLocation.category(categoryId));
        return true;
    }

    public boolean openPage(String pageId) {
        return openPage(pageId, null);
    }

    // anchor: a GroupKey or SettingKey anchor, or null for the top of the page
    public boolean openPage(String pageId, String anchor) {
        Optional<NavPage> page = model().page(pageId);
        if (page.isEmpty()) {
            return false;
        }
        go(NavLocation.page(page.get().categoryId(), pageId).withAnchor(anchor));
        if (anchor != null) {
            startReveal(pageId, anchor);
        }
        return true;
    }

    // opens the setting's page, peeks its group open when it is switched off, scrolls the row into view
    // and flashes it. resolved now, so a group rebuilt since the key was made is still found
    public boolean revealSetting(SettingKey key) {
        NavModel nav = model();
        Optional<NavPage> page = nav.page(key.pageId());
        Optional<SettingGroup> group = nav.group(key.group());
        if (page.isEmpty() || group.isEmpty() || nav.setting(key).isEmpty()) {
            return false;
        }
        if (page.get().groupMode(group.get()) == NavPage.GroupMode.TOGGLE && !group.get().isEnabled()) {
            peeks.add(key.group());
        }
        return openPage(key.pageId(), key.anchor());
    }

    public boolean revealSetting(String pageId, String groupRawName, String settingRawName) {
        return model().locate(pageId, null, groupRawName, settingRawName)
                .map(NavModel.Target::setting)
                .map(this::revealSetting)
                .orElse(false);
    }

    public boolean revealGroup(GroupKey key) {
        if (model().group(key).isEmpty()) {
            return false;
        }
        return openPage(key.pageId(), key.anchor());
    }

    @Override
    public boolean back() {
        while (!history.isEmpty()) {
            NavLocation previous = resolve(history.removeLast());
            if (!previous.equals(location)) {
                location = previous;
                return true;
            }
        }
        return false;
    }

    // escape: page to category to home; false at home, where the screen closes
    @Override
    public boolean up() {
        if (location.isHome()) {
            return false;
        }
        go(location.up());
        return true;
    }

    private void go(NavLocation next) {
        if (next.equals(location)) {
            return;
        }
        boolean samePlace = Objects.equals(next.categoryId(), location.categoryId())
                && Objects.equals(next.pageId(), location.pageId());
        if (!samePlace) {
            history.addLast(location.withAnchor(null));
            while (history.size() > HISTORY_LIMIT) {
                history.removeFirst();
            }
            reveal = null;
            scrollPending = false;
        }
        location = next;
    }

    // a page that moved category follows the page; a gone page falls back to its category, then home
    private NavLocation resolve(NavLocation wanted) {
        NavModel nav = model();
        if (wanted.isPage()) {
            Optional<NavPage> page = nav.page(wanted.pageId());
            if (page.isPresent()) {
                String anchor = wanted.anchor();
                if (anchor != null && nav.setting(SettingKey.fromAnchor(anchor)).isEmpty()
                        && nav.group(GroupKey.fromAnchor(anchor)).isEmpty()) {
                    anchor = null;
                }
                return NavLocation.page(page.get().categoryId(), page.get().id()).withAnchor(anchor);
            }
        }
        if (!wanted.isHome() && nav.category(wanted.categoryId()).isPresent()) {
            return NavLocation.category(wanted.categoryId());
        }
        return NavLocation.HOME;
    }

    // -- group peek ------------------------------------------------------------

    // whether a settings list draws this group's rows: always for always-on groups and groups whose switch
    // is the page switch, otherwise while enabled or peeked. enabling a group ends its peek
    public boolean showsChildren(NavPage page, SettingGroup group) {
        NavPage.GroupMode mode = page.groupMode(group);
        if (mode != NavPage.GroupMode.TOGGLE) {
            return true;
        }
        GroupKey key = model().keyOf(page, group);
        if (group.isEnabled()) {
            peeks.remove(key);
            return true;
        }
        return peeks.contains(key);
    }

    public boolean peeked(GroupKey key) {
        return peeks.contains(key);
    }

    public void setPeek(GroupKey key, boolean peek) {
        if (peek) {
            peeks.add(key);
        } else {
            peeks.remove(key);
        }
    }

    public void togglePeek(GroupKey key) {
        setPeek(key, !peeks.contains(key));
    }

    // -- reveal ----------------------------------------------------------------

    private void startReveal(String pageId, String anchor) {
        reveal = new Reveal(pageId, anchor, clock.nanos());
        scrollPending = true;
    }

    public Reveal reveal() {
        return reveal;
    }

    // the anchor the list on this page should scroll to (row top at REVEAL_VIEW_FRACTION), once
    public String takeScrollAnchor(String pageId) {
        if (!scrollPending || reveal == null || !reveal.pageId().equals(pageId)) {
            return null;
        }
        scrollPending = false;
        return reveal.anchor();
    }

    // 1 when the reveal starts, fading to 0 over FLASH_NANOS; 0 for any other anchor
    public float flash(String anchor) {
        if (reveal == null || anchor == null || !anchor.equals(reveal.anchor())) {
            return 0f;
        }
        long elapsed = clock.nanos() - reveal.startNanos();
        return elapsed >= FLASH_NANOS ? 0f : 1f - (float) elapsed / FLASH_NANOS;
    }

    // -- modules, session, style, theme ----------------------------------------

    public boolean toggleModule(String pageId) {
        Optional<NavPage> page = model().page(pageId);
        if (page.isEmpty() || !page.get().hasToggle()) {
            return false;
        }
        page.get().toggle().toggle();
        return true;
    }

    public boolean canResume() {
        GuiHost.SessionInfo session = host.session();
        return session != null && session.resume() != null;
    }

    // the macro that opening the gui stopped; MacroCatalog remembers the last one started
    public boolean resumeMacro() {
        GuiHost.SessionInfo session = host.session();
        if (session == null || session.resume() == null) {
            return false;
        }
        session.resume().run();
        return true;
    }

    public List<GuiStyle> styles() {
        return styles.all();
    }

    // the view picks the new style up on its next frame and drops the old style's editors and overlays
    public boolean setStyle(String styleId) {
        if (styles.all().stream().noneMatch(style -> style.id().equals(styleId))) {
            return false;
        }
        if (!styleId.equals(Theme.GUI_STYLE)) {
            Theme.GUI_STYLE = styleId;
            Theme.saveTheme();
        }
        return true;
    }

    public boolean applyTheme(String presetId) {
        for (ThemePreset preset : ThemePreset.values()) {
            if (preset.name().equalsIgnoreCase(presetId) || preset.label().equalsIgnoreCase(presetId)) {
                ProfileActions.applyPreset(preset);
                return true;
            }
        }
        return false;
    }

    public void defaultColours() {
        ProfileActions.defaultColours();
    }

    public void openHudEditor() {
        host.openHudEditor();
    }

    public void openMacroMenu() {
        host.openMacroMenu();
    }

    // the registry rebuilds after a config load; the next frame re-resolves the location by id
    public boolean loadProfile(String name) {
        return ProfileActions.load(ProfileActions.Kind.CONFIG, name);
    }

    // -- pins and changes ------------------------------------------------------

    public PinStore pins() {
        return pins;
    }

    public boolean isPinned(SettingKey key) {
        return pins.isPinned(key);
    }

    public void pin(SettingKey key) {
        pins.pin(key);
    }

    public void unpin(SettingKey key) {
        pins.unpin(key);
    }

    public ChangeLog changes() {
        return changes;
    }

    // controls call this after writing a setting, with the value SettingValues.read gave before the write
    public void recordChange(SettingKey key, Setting setting, String before) {
        changes.record(key, before, SettingValues.read(setting));
    }

    public boolean undo() {
        return announceUndo(changes.undoLast(model()));
    }

    public boolean undo(ChangeLog.Change change) {
        return announceUndo(changes.undo(change, model()));
    }

    private boolean announceUndo(Optional<ChangeLog.Change> undone) {
        if (undone.isEmpty()) {
            return false;
        }
        ChangeLog.Change change = undone.get();
        String value = model().setting(change.key())
                .map(setting -> setting.getName() + ": " + SettingValues.display(setting, change.before()))
                .orElse(AetherLang.localize(change.key().settingRawName()));
        NotificationManager.info(AetherLang.localize("Change Undone"), value);
        return true;
    }

    // -- search ----------------------------------------------------------------

    public SearchIndex search() {
        return search;
    }

    public String query() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
    }

    public void setSearchOpener(SearchOpener opener) {
        this.searchOpener = opener == null ? (frame, index) -> false : opener;
    }

    public boolean openSearch(GuiFrame frame) {
        return searchOpener.open(frame, search);
    }

    private SearchSource actionSource() {
        return (nav, out) -> {
            String actions = AetherLang.localize("Actions");
            if (canResume()) {
                String macro = MacroCatalog.lastStarted().map(MacroCatalog.Entry::displayName).orElse(null);
                String title = macro == null ? AetherLang.localize("Resume Macro")
                        : AetherLang.localize("Resume") + " " + AetherLang.localize(macro);
                out.accept(action("resume", title, List.of("Resume Macro", "Resume"), actions,
                        Icon.item("minecraft:lime_dye"), this::resumeMacro));
            }
            out.accept(action("macro-menu", AetherLang.localize("Open Macro Menu"), List.of("Macros", "Start Macro"),
                    actions, Icon.item("minecraft:diamond_hoe"), this::openMacroMenu));
            out.accept(action("hud-editor", AetherLang.localize("Open HUD Editor"), List.of("HUD Positions"),
                    actions, Icon.item("minecraft:item_frame"), this::openHudEditor));
            if (!changes.recent().isEmpty()) {
                out.accept(action("undo", AetherLang.localize("Undo Last Change"), List.of("Undo"), actions,
                        Icon.item("minecraft:clock"), this::undo));
            }
            for (GuiStyle style : styles.all()) {
                String name = AetherLang.localize(style.displayName());
                out.accept(action("style:" + style.id(), AetherLang.localize("Style") + ": " + name,
                        List.of("style " + style.id(), "style " + style.displayName()), actions,
                        Icon.item("minecraft:painting"), () -> setStyle(style.id())));
            }
            for (ThemePreset preset : ThemePreset.values()) {
                String name = AetherLang.localize(preset.label());
                out.accept(action("theme:" + preset.name().toLowerCase(Locale.ROOT),
                        AetherLang.localize("Theme") + ": " + name,
                        List.of("theme " + preset.label(), "colours " + preset.label(), "colors " + preset.label()),
                        actions, Icon.item("minecraft:brush"), () -> applyTheme(preset.name())));
            }
            out.accept(action("theme:default", AetherLang.localize("Theme") + ": "
                            + AetherLang.localize("Default Colours"),
                    List.of("Default Colours", "Default Colors", "Reset Colours"), actions,
                    Icon.item("minecraft:brush"), this::defaultColours));
            for (String profile : ConfigProfileManager.list()) {
                out.accept(action("profile:" + profile, AetherLang.localize("Load Profile") + ": " + profile,
                        List.of("load " + profile, "profile " + profile), actions,
                        Icon.item("minecraft:bookshelf"), () -> loadProfile(profile)));
            }
        };
    }

    private static SearchSource.Candidate action(String id, String title, List<String> terms, String category,
                                                 Icon icon, Runnable run) {
        return new SearchSource.Candidate(SearchResult.Kind.ACTION, title, terms, null, List.of(category), icon,
                run, "action:" + id, null, id, null);
    }

    // -- view hooks ------------------------------------------------------------

    // a deep link that no longer resolves (like the old "Authentication" target) lands on home
    @Override
    public void open(LaunchRequest launch) {
        peeks.clear();
        reveal = null;
        scrollPending = false;
        query = "";
        location = launch == null ? resolve(lastLocation) : NavLocation.HOME;
        if (launch != null) {
            model().locate(launch.pageId(), launch.moduleRawName(), launch.groupRawName(), launch.settingRawName())
                    .ifPresent(target -> {
                        if (target.setting() != null) {
                            revealSetting(target.setting());
                        } else if (target.group() != null) {
                            revealGroup(target.group());
                        } else {
                            openPage(target.location().pageId());
                        }
                    });
        }
        history.clear();
    }

    @Override
    public void close() {
        lastLocation = location.withAnchor(null);
    }

    // the registry was rebuilt: same ids, new objects. history entries that no longer resolve fall back too
    @Override
    public void snapshotChanged(MainGUIRegistry.Snapshot snapshot) {
        location = resolve(location);
        List<NavLocation> resolved = new ArrayList<>();
        for (NavLocation entry : history) {
            resolved.add(resolve(entry));
        }
        history.clear();
        history.addAll(resolved);
    }

    @Override
    public boolean clearSearch() {
        if (query.isEmpty()) {
            return false;
        }
        query = "";
        return true;
    }

    // ctrl+f search, ctrl+z undo, ctrl+enter resume
    @Override
    public boolean shortcut(GuiFrame frame, KeyInput key) {
        if (!key.shortcut()) {
            return false;
        }
        if (key.is(GLFW.GLFW_KEY_F)) {
            return openSearch(frame);
        }
        if (key.is(GLFW.GLFW_KEY_Z)) {
            undo();
            return true;
        }
        if (key.is(GLFW.GLFW_KEY_ENTER) || key.is(GLFW.GLFW_KEY_KP_ENTER)) {
            return resumeMacro();
        }
        return false;
    }

    // '/' opens search on every keyboard layout because it arrives as typed text
    @Override
    public boolean typed(GuiFrame frame, String chars) {
        return "/".equals(chars) && openSearch(frame);
    }
}
