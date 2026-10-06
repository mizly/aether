package dev.aether.ui.gui.nav;

import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

// categories and pages for one registry snapshot under one placement. pages hold the snapshot's subtabs and
// read their groups live, so in-place rebuilds (farm type, rewarp pairs, pets) show without a new model
public final class NavModel {

    // where a deep link lands: the page location plus the group and setting it names, when found
    public record Target(NavLocation location, GroupKey group, SettingKey setting) {
    }

    private record SourceTab(ModulesTab.SubTab subTab, String sectionId, String sectionName) {
    }

    private static volatile NavModel cached;

    private final MainGUIRegistry.Snapshot snapshot;
    private final Placement placement;
    private final List<NavCategory> categories;
    private final Map<String, NavCategory> categoriesById = new HashMap<>();
    private final Map<String, NavPage> pagesById = new LinkedHashMap<>();
    private final Map<String, NavPage> ownerBySubTab = new HashMap<>();
    private final List<ModulesTab.SubTab> subTabs;

    private NavModel(MainGUIRegistry.Snapshot snapshot, Placement placement, List<NavCategory> categories,
                     Map<String, NavPage> ownerBySubTab, List<ModulesTab.SubTab> subTabs) {
        this.snapshot = snapshot;
        this.placement = placement;
        this.categories = List.copyOf(categories);
        for (NavCategory category : this.categories) {
            categoriesById.put(category.id(), category);
            for (NavPage page : category.pages()) {
                pagesById.put(page.id(), page);
            }
        }
        this.ownerBySubTab.putAll(ownerBySubTab);
        this.subTabs = List.copyOf(subTabs);
    }

    // the model for the live registry snapshot, rebuilt only when the snapshot or placement changes
    public static NavModel current() {
        return current(PlacementTable.current());
    }

    public static NavModel current(Placement placement) {
        MainGUIRegistry.Snapshot snapshot = MainGUIRegistry.snapshot();
        NavModel model = cached;
        if (model == null || model.snapshot != snapshot || model.placement != placement) {
            model = build(snapshot, placement);
            cached = model;
        }
        return model;
    }

    public static NavModel build(MainGUIRegistry.Snapshot snapshot, Placement placement) {
        Map<String, SourceTab> tabs = new LinkedHashMap<>();
        for (MainGUIRegistry.ModuleSection section : snapshot.sections()) {
            for (ModulesTab.SubTab subTab : section.subtabs()) {
                tabs.putIfAbsent(subTab.rawName(), new SourceTab(subTab, section.id(), section.displayName()));
            }
        }
        addAll(tabs, snapshot.colors(), Categories.COLORS_SECTION);
        addAll(tabs, snapshot.keybinds(), Categories.KEYBINDS_SECTION);
        addAll(tabs, snapshot.settings(), Categories.SETTINGS_SECTION);

        Set<String> wholeClaimed = new HashSet<>();
        Map<String, Set<String>> claimedGroups = new HashMap<>();
        for (Placement.PageSpec spec : placement.pages()) {
            for (Placement.Source source : spec.sources()) {
                if (source.whole()) {
                    wholeClaimed.add(source.subTabRawName());
                } else {
                    claimedGroups.computeIfAbsent(source.subTabRawName(), k -> new HashSet<>())
                            .addAll(source.groupRawNames());
                }
                claimedGroups.computeIfAbsent(source.subTabRawName(), k -> new HashSet<>())
                        .addAll(spec.hiddenGroups());
            }
        }

        Map<String, List<NavPage>> pagesByCategory = new LinkedHashMap<>();
        Map<String, NavPage> owners = new HashMap<>();
        Set<String> remaindersPlaced = new HashSet<>();
        Set<String> usedIds = new HashSet<>();
        for (Placement.PageSpec spec : placement.pages()) {
            NavPage page = spec.custom() ? customPage(spec) : modulePage(spec, tabs, wholeClaimed, claimedGroups,
                    remaindersPlaced, false);
            if (page == null) {
                continue;
            }
            usedIds.add(page.id());
            pagesByCategory.computeIfAbsent(spec.categoryId(), k -> new ArrayList<>()).add(page);
            for (Placement.Source source : spec.sources()) {
                if (tabs.containsKey(source.subTabRawName())) {
                    NavPage owner = owners.get(source.subTabRawName());
                    if (owner == null || (source.whole() && !ownerTakesWhole(owner, source.subTabRawName()))) {
                        owners.put(source.subTabRawName(), page);
                    }
                }
            }
        }

        Map<String, Placement.CategorySpec> categorySpecs = new LinkedHashMap<>();
        for (Placement.CategorySpec category : placement.categories()) {
            categorySpecs.put(category.id(), category);
        }
        int fallthroughIndex = 0;
        for (SourceTab tab : tabs.values()) {
            String raw = tab.subTab().rawName();
            if (wholeClaimed.contains(raw) || claimedGroups.containsKey(raw)) {
                continue;
            }
            String categoryId = placement.categoryForSection(tab.sectionId());
            if (categoryId == null) {
                categoryId = "section-" + tab.sectionId();
                if (!categorySpecs.containsKey(categoryId)) {
                    categorySpecs.put(categoryId, new Placement.CategorySpec(categoryId, tab.sectionName(), null,
                            null, 1000 + categorySpecs.size()));
                }
            }
            String id = uniqueId(tab.subTab().id(), usedIds);
            Placement.PageSpec spec = new Placement.PageSpec(id, categoryId, raw, null, null,
                    10_000 + fallthroughIndex++, List.of(new Placement.Source(raw, List.of())),
                    Placement.ToggleChoice.AUTO, null, List.of(), List.of(), Set.of(), Set.of(), Set.of(), false);
            NavPage page = modulePage(spec, tabs, wholeClaimed, claimedGroups, remaindersPlaced, true);
            usedIds.add(id);
            pagesByCategory.computeIfAbsent(categoryId, k -> new ArrayList<>()).add(page);
            owners.put(raw, page);
        }

        List<NavCategory> categories = new ArrayList<>();
        for (Placement.CategorySpec spec : categorySpecs.values()) {
            List<NavPage> pages = new ArrayList<>(pagesByCategory.getOrDefault(spec.id(), List.of()));
            if (pages.isEmpty()) {
                continue;
            }
            pages.sort(Comparator.comparingInt(NavPage::order));
            categories.add(new NavCategory(spec.id(), spec.rawName(), spec.rawDescription(), spec.icon(), spec.order(),
                    pages));
        }
        categories.sort(Comparator.comparingInt(NavCategory::order));
        List<ModulesTab.SubTab> subTabs = tabs.values().stream().map(SourceTab::subTab).toList();
        return new NavModel(snapshot, placement, categories, owners, subTabs);
    }

    private static void addAll(Map<String, SourceTab> tabs, List<ModulesTab.SubTab> subTabs, String sectionId) {
        for (ModulesTab.SubTab subTab : subTabs) {
            tabs.putIfAbsent(subTab.rawName(), new SourceTab(subTab, sectionId, sectionId));
        }
    }

    private static boolean ownerTakesWhole(NavPage owner, String subTabRawName) {
        return owner.spec().sources().stream()
                .anyMatch(source -> source.whole() && source.subTabRawName().equals(subTabRawName));
    }

    private static String uniqueId(String base, Set<String> used) {
        String id = base.isEmpty() ? "page" : base;
        int suffix = 2;
        while (used.contains(id)) {
            id = base + "-" + suffix++;
        }
        return id;
    }

    private static NavPage customPage(Placement.PageSpec spec) {
        return new NavPage(spec, spec.categoryId(), spec.rawName(), spec.rawDescription(), spec.icon(), spec.order(),
                NavPage.Kind.CUSTOM, List.of(), null, false);
    }

    private static NavPage modulePage(Placement.PageSpec spec, Map<String, SourceTab> tabs, Set<String> wholeClaimed,
                                      Map<String, Set<String>> claimedGroups, Set<String> remaindersPlaced,
                                      boolean fallthrough) {
        List<Placement.Source> sources = spec.sources().stream()
                .filter(source -> tabs.containsKey(source.subTabRawName()))
                .toList();
        if (sources.isEmpty()) {
            return null;
        }
        List<ModulesTab.SubTab> distinct = new ArrayList<>();
        for (Placement.Source source : sources) {
            ModulesTab.SubTab subTab = tabs.get(source.subTabRawName()).subTab();
            if (!distinct.contains(subTab)) {
                distinct.add(subTab);
            }
        }
        PageToggle toggle = resolveToggle(spec.toggle(), sources, tabs);
        boolean multi = sources.size() > 1;
        List<PageBlock> blocks = new ArrayList<>();
        Set<String> blockIds = new HashSet<>();
        for (Placement.Source source : sources) {
            ModulesTab.SubTab subTab = tabs.get(source.subTabRawName()).subTab();
            Predicate<SettingGroup> filter = group -> source.includes(group.getRawName())
                    && !spec.hiddenGroups().contains(group.getRawName());
            blocks.add(new PageBlock(uniqueId(subTab.id(), blockIds), subTab, filter,
                    multi ? blockToggle(subTab, toggle) : null, multi));
            blockIds.add(blocks.getLast().id());
        }
        for (ModulesTab.SubTab subTab : distinct) {
            String raw = subTab.rawName();
            if (wholeClaimed.contains(raw) || !remaindersPlaced.add(raw)) {
                continue;
            }
            // groups no placement names (added after the table was written) still land on the subtab's first page
            Set<String> claimed = claimedGroups.getOrDefault(raw, Set.of());
            blocks.add(new PageBlock(uniqueId(subTab.id() + "-more", blockIds), subTab,
                    group -> !claimed.contains(group.getRawName()), blockToggle(subTab, toggle), true));
            blockIds.add(blocks.getLast().id());
        }
        String description = spec.rawDescription();
        Icon icon = spec.icon();
        if (distinct.size() == 1) {
            if (description == null) {
                description = distinct.getFirst().rawDescription();
            }
            if (icon == null) {
                icon = distinct.getFirst().icon();
            }
        }
        return new NavPage(spec, spec.categoryId(), spec.rawName(), description, icon, spec.order(),
                NavPage.Kind.MODULE, blocks, toggle, fallthrough);
    }

    private static PageToggle blockToggle(ModulesTab.SubTab subTab, PageToggle pageToggle) {
        if (!subTab.hasToggle()) {
            return null;
        }
        if (pageToggle != null && pageToggle.source() == PageToggle.Source.SUBTAB
                && pageToggle.subTabRawName().equals(subTab.rawName())) {
            return null;
        }
        return PageToggle.of(subTab, null);
    }

    // AUTO only hands a page the switch of a subtab it shows whole: a split-off page must not switch the
    // whole feature off
    private static PageToggle resolveToggle(Placement.ToggleChoice choice, List<Placement.Source> sources,
                                            Map<String, SourceTab> tabs) {
        return switch (choice.mode()) {
            case NONE -> null;
            case AUTO -> {
                if (sources.size() != 1 || !sources.getFirst().whole()) {
                    yield null;
                }
                ModulesTab.SubTab only = tabs.get(sources.getFirst().subTabRawName()).subTab();
                yield only.hasToggle() ? PageToggle.of(only, choice.kind()) : null;
            }
            case SUBTAB -> {
                SourceTab tab = tabs.get(choice.subTabRawName());
                yield tab != null && tab.subTab().hasToggle() ? PageToggle.of(tab.subTab(), choice.kind()) : null;
            }
            case GROUP -> {
                SourceTab tab = tabs.get(choice.subTabRawName());
                yield tab == null ? null : PageToggle.ofGroup(tab.subTab(), choice.groupRawName(), choice.kind());
            }
        };
    }

    public MainGUIRegistry.Snapshot snapshot() {
        return snapshot;
    }

    public long generation() {
        return snapshot.generation();
    }

    public Placement placement() {
        return placement;
    }

    public List<NavCategory> categories() {
        return categories;
    }

    public Optional<NavCategory> category(String id) {
        return Optional.ofNullable(id == null ? null : categoriesById.get(id));
    }

    // every page, by category order and then page order
    public List<NavPage> pages() {
        return List.copyOf(pagesById.values());
    }

    public Optional<NavPage> page(String id) {
        return Optional.ofNullable(id == null ? null : pagesById.get(id));
    }

    public List<ModulesTab.SubTab> subTabs() {
        return subTabs;
    }

    // the page a subtab belongs to: the first page that takes it whole, else the first that takes part of it
    public Optional<NavPage> pageForSubTab(String subTabRawName) {
        return Optional.ofNullable(subTabRawName == null ? null : ownerBySubTab.get(subTabRawName));
    }

    // the page currently drawing this group object, if any
    public Optional<NavPage> pageOf(SettingGroup group) {
        for (NavPage page : pagesById.values()) {
            for (SettingGroup candidate : page.groups()) {
                if (candidate == group) {
                    return Optional.of(page);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<NavPage> parent(NavPage page) {
        return page(page.requires());
    }

    // false while the page this one depends on (requires) is switched off
    public boolean requirementMet(NavPage page) {
        return parent(page).map(NavPage::isEnabled).orElse(true);
    }

    public GroupKey keyOf(NavPage page, SettingGroup group) {
        int ordinal = 0;
        for (SettingGroup candidate : page.groups()) {
            if (candidate == group) {
                return new GroupKey(page.id(), group.getRawName(), ordinal);
            }
            if (candidate.getRawName().equals(group.getRawName())) {
                ordinal++;
            }
        }
        return new GroupKey(page.id(), group.getRawName(), 0);
    }

    public SettingKey keyOf(NavPage page, SettingGroup group, Setting setting) {
        return keyOf(keyOf(page, group), group, setting);
    }

    public static SettingKey keyOf(GroupKey groupKey, SettingGroup group, Setting setting) {
        int ordinal = 0;
        for (Setting candidate : group.getSettings()) {
            if (candidate == setting) {
                break;
            }
            if (candidate.getRawName().equals(setting.getRawName())) {
                ordinal++;
            }
        }
        return new SettingKey(groupKey, setting.getRawName(), ordinal);
    }

    public Optional<SettingGroup> group(GroupKey key) {
        if (key == null) {
            return Optional.empty();
        }
        return page(key.pageId()).flatMap(page -> {
            int seen = 0;
            for (SettingGroup group : page.groups()) {
                if (group.getRawName().equals(key.groupRawName()) && seen++ == key.ordinal()) {
                    return Optional.of(group);
                }
            }
            return Optional.empty();
        });
    }

    public Optional<Setting> setting(SettingKey key) {
        if (key == null) {
            return Optional.empty();
        }
        return group(key.group()).flatMap(group -> {
            int seen = 0;
            for (Setting setting : group.getSettings()) {
                if (setting.getRawName().equals(key.settingRawName()) && seen++ == key.ordinal()) {
                    return Optional.of(setting);
                }
            }
            return Optional.empty();
        });
    }

    // resolves a deep link: a page id, or a module by raw name (old LaunchTarget names such as
    // "Farming Macro" and "Strider Fishing"), optionally down to a group and a setting by raw name
    public Optional<Target> locate(String pageId, String moduleRawName, String groupRawName, String settingRawName) {
        NavPage page = page(pageId).orElse(null);
        if (page == null && moduleRawName != null) {
            page = findModulePage(moduleRawName);
        }
        if (page == null) {
            return Optional.empty();
        }
        GroupKey groupKey = null;
        SettingKey settingKey = null;
        if (groupRawName != null || settingRawName != null) {
            for (SettingGroup group : page.groups()) {
                if (groupRawName != null && !groupRawName.equals(group.getRawName())) {
                    continue;
                }
                if (settingRawName == null) {
                    groupKey = keyOf(page, group);
                    break;
                }
                Setting setting = group.getSettings().stream()
                        .filter(candidate -> settingRawName.equals(candidate.getRawName()))
                        .findFirst().orElse(null);
                if (setting != null) {
                    groupKey = keyOf(page, group);
                    settingKey = keyOf(groupKey, group, setting);
                    break;
                }
            }
        }
        String anchor = settingKey != null ? settingKey.anchor() : groupKey != null ? groupKey.anchor() : null;
        return Optional.of(new Target(NavLocation.page(page.categoryId(), page.id()).withAnchor(anchor), groupKey,
                settingKey));
    }

    private NavPage findModulePage(String name) {
        NavPage owner = ownerBySubTab.get(name);
        if (owner != null) {
            return owner;
        }
        String folded = name.toLowerCase(Locale.ROOT);
        for (ModulesTab.SubTab subTab : subTabs) {
            if (subTab.name().equalsIgnoreCase(name) || subTab.rawName().equalsIgnoreCase(name)
                    || subTab.id().equals(ModulesTab.SubTab.idFor(name))) {
                NavPage page = ownerBySubTab.get(subTab.rawName());
                if (page != null) {
                    return page;
                }
            }
        }
        for (NavPage page : pagesById.values()) {
            if (page.rawName().toLowerCase(Locale.ROOT).equals(folded) || page.name().equalsIgnoreCase(name)
                    || page.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(name))) {
                return page;
            }
        }
        return null;
    }
}
