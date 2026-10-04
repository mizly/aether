package dev.aether.ui.gui.nav;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.settings.ModulesTab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// where every registered subtab and group shows up: categories, pages composed from (subtab, groups), page
// toggles, aliases and mirrors. pure data, built once; NavModel applies it to each registry snapshot
public final class Placement {

    public record CategorySpec(String id, String rawName, String rawDescription, String iconItem, int order) {
        public Icon icon() {
            return iconItem == null ? null : Icon.item(iconItem);
        }
    }

    // a whole subtab, or only the named groups of it
    public record Source(String subTabRawName, List<String> groupRawNames) {
        public Source {
            groupRawNames = List.copyOf(groupRawNames);
        }

        public boolean whole() {
            return groupRawNames.isEmpty();
        }

        public boolean includes(String groupRawName) {
            return whole() || groupRawNames.contains(groupRawName);
        }
    }

    public enum ToggleMode { AUTO, SUBTAB, GROUP, NONE }

    // AUTO: the toggle of the page's only source subtab, if it has one. kind overrides the subtab's own kind
    public record ToggleChoice(ToggleMode mode, String subTabRawName, String groupRawName,
                               ModulesTab.ToggleKind kind) {
        public static final ToggleChoice AUTO = new ToggleChoice(ToggleMode.AUTO, null, null, null);
        public static final ToggleChoice NONE = new ToggleChoice(ToggleMode.NONE, null, null, null);

        public ToggleChoice withKind(ModulesTab.ToggleKind kind) {
            return new ToggleChoice(mode, subTabRawName, groupRawName, kind);
        }
    }

    // a group (settingRawName null) or one setting on this page that is a copy of a knob owned by another page
    public record Mirror(String groupRawName, String settingRawName, String canonicalPageId) {
        public boolean matches(String group, String setting) {
            return (groupRawName == null || groupRawName.equals(group))
                    && (settingRawName == null || settingRawName.equals(setting));
        }
    }

    public record PageSpec(String id, String categoryId, String rawName, String rawDescription, Icon icon, int order,
                           List<Source> sources, ToggleChoice toggle, String requires, List<String> aliases,
                           List<Mirror> mirrors, Set<String> hiddenGroups, Set<HiddenSetting> hiddenSettings,
                           Set<String> pageToggleGroups, boolean custom) {
        public PageSpec {
            sources = List.copyOf(sources);
            aliases = List.copyOf(aliases);
            mirrors = List.copyOf(mirrors);
            hiddenGroups = Set.copyOf(hiddenGroups);
            hiddenSettings = Set.copyOf(hiddenSettings);
            pageToggleGroups = Set.copyOf(pageToggleGroups);
        }

        public boolean references(String subTabRawName) {
            return sources.stream().anyMatch(source -> source.subTabRawName().equals(subTabRawName));
        }
    }

    public record HiddenSetting(String groupRawName, String settingRawName) {
    }

    private final List<CategorySpec> categories;
    private final Map<String, String> sectionCategories;
    private final List<PageSpec> pages;

    private Placement(List<CategorySpec> categories, Map<String, String> sectionCategories, List<PageSpec> pages) {
        this.categories = List.copyOf(categories);
        this.sectionCategories = Map.copyOf(sectionCategories);
        this.pages = List.copyOf(pages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<CategorySpec> categories() {
        return categories;
    }

    public List<PageSpec> pages() {
        return pages;
    }

    // the category an unplaced subtab of this registry section falls through to, or null for an unknown section
    public String categoryForSection(String sectionId) {
        return sectionCategories.get(sectionId);
    }

    public static final class Builder {
        private final Map<String, CategorySpec> categories = new LinkedHashMap<>();
        private final Map<String, String> sections = new HashMap<>();
        private final List<PageSpec> pages = new ArrayList<>();

        private Builder() {
        }

        public Builder category(CategorySpec spec) {
            categories.put(spec.id(), spec);
            return this;
        }

        public Builder categories(List<CategorySpec> specs) {
            specs.forEach(this::category);
            return this;
        }

        public Builder section(String sectionId, String categoryId) {
            sections.put(sectionId, categoryId);
            return this;
        }

        public Builder sections(Map<String, String> sectionCategories) {
            sections.putAll(sectionCategories);
            return this;
        }

        // pages in declaration order; a page's order defaults to its position, in steps of 10
        public Builder pages(String categoryId, Consumer<Pages> body) {
            Pages builder = new Pages(categoryId);
            body.accept(builder);
            for (PageBuilder page : builder.pages) {
                pages.add(page.build());
            }
            return this;
        }

        public Placement build() {
            Set<String> ids = new HashSet<>();
            for (PageSpec page : pages) {
                if (!ids.add(page.id())) {
                    throw new IllegalStateException("duplicate page id " + page.id());
                }
                if (!categories.containsKey(page.categoryId())) {
                    throw new IllegalStateException("page " + page.id() + " is in unknown category " + page.categoryId());
                }
            }
            for (PageSpec page : pages) {
                if (page.requires() != null && !ids.contains(page.requires())) {
                    throw new IllegalStateException("page " + page.id() + " requires unknown page " + page.requires());
                }
                for (Mirror mirror : page.mirrors()) {
                    if (!ids.contains(mirror.canonicalPageId())) {
                        throw new IllegalStateException("page " + page.id() + " mirrors unknown page " + mirror.canonicalPageId());
                    }
                }
            }
            for (Map.Entry<String, String> section : sections.entrySet()) {
                if (!categories.containsKey(section.getValue())) {
                    throw new IllegalStateException("section " + section.getKey() + " maps to unknown category " + section.getValue());
                }
            }
            return new Placement(List.copyOf(categories.values()), sections, pages);
        }
    }

    public static final class Pages {
        private final String categoryId;
        private final List<PageBuilder> pages = new ArrayList<>();

        private Pages(String categoryId) {
            this.categoryId = categoryId;
        }

        public Pages page(String id, String rawName, Consumer<PageBuilder> body) {
            PageBuilder page = new PageBuilder(id, categoryId, rawName, pages.size() * 10);
            body.accept(page);
            pages.add(page);
            return this;
        }

        // one whole subtab as its own page, named and keyed like the subtab
        public Pages subTab(String subTabRawName, String iconItem) {
            return page(ModulesTab.SubTab.idFor(subTabRawName), subTabRawName,
                    page -> page.icon(iconItem).from(subTabRawName));
        }

        // a page the styles draw themselves (Appearance, Profiles)
        public Pages custom(String id, String rawName, String rawDescription, String iconItem, String... aliases) {
            return page(id, rawName, page -> page.custom().description(rawDescription).icon(iconItem).aliases(aliases));
        }
    }

    public static final class PageBuilder {
        private final String id;
        private final String categoryId;
        private final String rawName;
        private int order;
        private String rawDescription;
        private Icon icon;
        private final List<Source> sources = new ArrayList<>();
        private ToggleChoice toggle = ToggleChoice.AUTO;
        private String requires;
        private final List<String> aliases = new ArrayList<>();
        private final List<Mirror> mirrors = new ArrayList<>();
        private final Set<String> hiddenGroups = new LinkedHashSet<>();
        private final Set<HiddenSetting> hiddenSettings = new LinkedHashSet<>();
        private final Set<String> pageToggleGroups = new LinkedHashSet<>();
        private boolean custom;

        private PageBuilder(String id, String categoryId, String rawName, int order) {
            this.id = id;
            this.categoryId = categoryId;
            this.rawName = rawName;
            this.order = order;
        }

        // when unset, the page shows its only source subtab's description
        public PageBuilder description(String rawDescription) {
            this.rawDescription = rawDescription;
            return this;
        }

        public PageBuilder icon(String itemId) {
            this.icon = itemId == null ? null : Icon.item(itemId);
            return this;
        }

        public PageBuilder icon(Icon icon) {
            this.icon = icon;
            return this;
        }

        public PageBuilder order(int order) {
            this.order = order;
            return this;
        }

        public PageBuilder from(String subTabRawName, String... groupRawNames) {
            sources.add(new Source(subTabRawName, List.of(groupRawNames)));
            return this;
        }

        public PageBuilder toggleFromSubTab(String subTabRawName) {
            toggle = new ToggleChoice(ToggleMode.SUBTAB, subTabRawName, null, toggle.kind());
            return this;
        }

        // the group's switch becomes the page switch; the group then draws no switch of its own
        public PageBuilder toggleFromGroup(String subTabRawName, String groupRawName) {
            toggle = new ToggleChoice(ToggleMode.GROUP, subTabRawName, groupRawName, toggle.kind());
            return this;
        }

        public PageBuilder noToggle() {
            toggle = new ToggleChoice(ToggleMode.NONE, null, null, toggle.kind());
            return this;
        }

        // the page switch acts right away (opens a window, frees the cursor) instead of gating a feature
        public PageBuilder runtimeToggle() {
            toggle = toggle.withKind(ModulesTab.ToggleKind.RUNTIME);
            return this;
        }

        public PageBuilder requires(String parentPageId) {
            this.requires = parentPageId;
            return this;
        }

        // old names search still finds this page by
        public PageBuilder aliases(String... rawAliases) {
            aliases.addAll(List.of(rawAliases));
            return this;
        }

        public PageBuilder mirror(String groupRawName, String settingRawName, String canonicalPageId) {
            mirrors.add(new Mirror(groupRawName, settingRawName, canonicalPageId));
            return this;
        }

        public PageBuilder mirrorGroup(String groupRawName, String canonicalPageId) {
            return mirror(groupRawName, null, canonicalPageId);
        }

        public PageBuilder hideGroup(String groupRawName) {
            hiddenGroups.add(groupRawName);
            return this;
        }

        public PageBuilder hideSetting(String groupRawName, String settingRawName) {
            hiddenSettings.add(new HiddenSetting(groupRawName, settingRawName));
            return this;
        }

        // a group switch that duplicates the page switch: drawn without its own switch, children always shown
        public PageBuilder groupToggleIsPageToggle(String groupRawName) {
            pageToggleGroups.add(groupRawName);
            return this;
        }

        public PageBuilder custom() {
            this.custom = true;
            return this;
        }

        private PageSpec build() {
            if (!custom && sources.isEmpty()) {
                throw new IllegalStateException("page " + id + " has no sources");
            }
            return new PageSpec(id, categoryId, rawName, rawDescription, icon, order, sources, toggle, requires,
                    aliases, mirrors, hiddenGroups, hiddenSettings, pageToggleGroups, custom);
        }
    }
}
