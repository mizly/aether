package dev.aether.ui.gui.nav;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SettingType;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

// search over pages, groups, sections, settings and actions. nothing is cached: every query collects from
// the live objects (about 600 candidates), so rebuilt groups (farm type, rewarp pairs, pets) are found and
// hidden settings never are. ranking: match class first, then pages > actions > groups > sections > settings
public final class SearchIndex {
    public static final int DEFAULT_LIMIT = 50;
    // one character only searches pages and actions by prefix, like the old two-character minimum
    private static final int FULL_QUERY_LENGTH = 2;
    private static final int FUZZY_MIN_LENGTH = 3;

    private final Supplier<NavModel> model;
    private final List<SearchSource> sources = new ArrayList<>();

    public SearchIndex(Supplier<NavModel> model) {
        this.model = model;
    }

    public SearchIndex add(SearchSource source) {
        sources.add(source);
        return this;
    }

    public List<SearchResult> query(String query) {
        return query(query, DEFAULT_LIMIT);
    }

    public List<SearchResult> query(String query, int limit) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return List.of();
        }
        NavModel nav = model.get();
        List<Scored> scored = new ArrayList<>();
        for (SearchSource source : sources) {
            source.collect(nav, candidate -> {
                Scored hit = score(candidate, q, scored.size());
                if (hit != null) {
                    scored.add(hit);
                }
            });
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.match().ordinal())
                .thenComparingInt(s -> s.candidate().kind().ordinal())
                .thenComparingInt(Scored::detail)
                .thenComparingInt(Scored::order));
        return collapseMirrors(scored, nav, limit);
    }

    // a mirror whose owner also matched folds into the owner's row as "also on <page>"
    private static List<SearchResult> collapseMirrors(List<Scored> scored, NavModel nav, int limit) {
        Map<String, Integer> owners = new HashMap<>();
        for (int i = 0; i < scored.size(); i++) {
            SearchSource.Candidate c = scored.get(i).candidate();
            if (c.mirrorOf() == null) {
                owners.putIfAbsent(mirrorKey(c.kind(), c.pageId(), c.rawName()), i);
            }
        }
        Map<Integer, List<String>> alsoOn = new HashMap<>();
        boolean[] folded = new boolean[scored.size()];
        for (int i = 0; i < scored.size(); i++) {
            SearchSource.Candidate c = scored.get(i).candidate();
            Integer owner = c.mirrorOf() == null ? null : owners.get(mirrorKey(c.kind(), c.mirrorOf(), c.rawName()));
            if (owner != null) {
                folded[i] = true;
                String pageName = nav.page(c.pageId()).map(NavPage::name).orElse(c.pageId());
                List<String> pages = alsoOn.computeIfAbsent(owner, k -> new ArrayList<>());
                if (!pages.contains(pageName)) {
                    pages.add(pageName);
                }
            }
        }
        List<SearchResult> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && out.size() < limit; i++) {
            if (folded[i]) {
                continue;
            }
            Scored hit = scored.get(i);
            SearchSource.Candidate c = hit.candidate();
            out.add(new SearchResult(c.kind(), c.title(), c.path(), c.icon(), c.action(), c.key(), hit.match(),
                    alsoOn.getOrDefault(i, List.of())));
        }
        return out;
    }

    private static String mirrorKey(SearchResult.Kind kind, String pageId, String rawName) {
        return kind + "\u001f" + pageId + "\u001f" + rawName;
    }

    private record Scored(SearchSource.Candidate candidate, SearchResult.Match match, int detail, int order) {
    }

    private static Scored score(SearchSource.Candidate candidate, String q, int order) {
        SearchResult.Match best = null;
        int detail = Integer.MAX_VALUE;
        List<String> fields = new ArrayList<>(candidate.terms().size() + 1);
        fields.add(candidate.title());
        fields.addAll(candidate.terms());
        for (String field : fields) {
            String f = normalize(field);
            if (f.isEmpty()) {
                continue;
            }
            SearchResult.Match match;
            int fieldDetail;
            int contains = f.indexOf(q);
            if (f.equals(q)) {
                match = SearchResult.Match.EXACT;
                fieldDetail = 0;
            } else if (contains == 0) {
                match = SearchResult.Match.PREFIX;
                fieldDetail = f.length();
            } else if (wordPrefix(f, q)) {
                match = SearchResult.Match.WORD_PREFIX;
                fieldDetail = f.length();
            } else if (contains > 0) {
                match = SearchResult.Match.CONTAINS;
                fieldDetail = contains;
            } else {
                int span = q.length() >= FUZZY_MIN_LENGTH ? fuzzySpan(f, q.replace(" ", "")) : -1;
                if (span < 0) {
                    continue;
                }
                match = SearchResult.Match.FUZZY;
                fieldDetail = span;
            }
            if (best == null || match.ordinal() < best.ordinal()
                    || (match == best && fieldDetail < detail)) {
                best = match;
                detail = fieldDetail;
            }
        }
        if (best == null && q.length() >= FUZZY_MIN_LENGTH && candidate.description() != null) {
            int index = normalize(candidate.description()).indexOf(q);
            if (index >= 0) {
                best = SearchResult.Match.DESCRIPTION;
                detail = index;
            }
        }
        if (best == null) {
            return null;
        }
        if (q.length() < FULL_QUERY_LENGTH && (best.ordinal() > SearchResult.Match.WORD_PREFIX.ordinal()
                || (candidate.kind() != SearchResult.Kind.PAGE && candidate.kind() != SearchResult.Kind.ACTION))) {
            return null;
        }
        return new Scored(candidate, best, detail, order);
    }

    // every query word starts some word of the field, in any order: "fov pest" finds "Pest FOV Range"
    private static boolean wordPrefix(String field, String q) {
        String[] words = field.split("[^\\p{L}\\p{N}]+");
        for (String token : q.split(" ")) {
            boolean found = false;
            for (String word : words) {
                if (word.startsWith(token)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    // the shortest stretch of the field holding the query's letters in order, or -1 when it is too loose to
    // be what the user meant
    private static int fuzzySpan(String field, String q) {
        int best = -1;
        for (int start = field.indexOf(q.charAt(0)); start >= 0; start = field.indexOf(q.charAt(0), start + 1)) {
            int at = start;
            int matched = 1;
            while (matched < q.length()) {
                at = field.indexOf(q.charAt(matched), at + 1);
                if (at < 0) {
                    break;
                }
                matched++;
            }
            if (matched < q.length()) {
                break;
            }
            int span = at - start + 1;
            if (best < 0 || span < best) {
                best = span;
            }
        }
        return best >= 0 && best <= q.length() * 3 ? best : -1;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    // -- built-in sources ----------------------------------------------------------

    public static SearchSource pages(Function<NavPage, Runnable> open) {
        return (model, out) -> {
            for (NavCategory category : model.categories()) {
                for (NavPage page : category.pages()) {
                    List<String> terms = new ArrayList<>();
                    terms.add(page.rawName());
                    for (String alias : page.aliases()) {
                        terms.add(alias);
                        terms.add(AetherLang.localize(alias));
                    }
                    Icon icon = page.icon() != null ? page.icon() : category.icon();
                    out.accept(new SearchSource.Candidate(SearchResult.Kind.PAGE, page.name(), terms,
                            page.description(), List.of(category.name()), icon, open.apply(page), page.id(),
                            page.id(), page.rawName(), null));
                }
            }
        };
    }

    public static SearchSource groups(Function<GroupKey, Runnable> reveal) {
        return (model, out) -> forEachGroup(model, (category, page, group, key) -> {
            if (normalize(group.getRawName()).equals(normalize(page.rawName()))) {
                return;
            }
            out.accept(new SearchSource.Candidate(SearchResult.Kind.GROUP, group.getName(),
                    List.of(group.getRawName()), group.getDescription(), List.of(category.name(), page.name()),
                    iconOf(category, page, group), reveal.apply(key), key, page.id(), group.getRawName(),
                    page.mirrorOf(group, null)));
        });
    }

    // section dividers inside groups, as jump targets
    public static SearchSource sections(Function<SettingKey, Runnable> reveal) {
        return settingsOfType(reveal, true);
    }

    public static SearchSource settings(Function<SettingKey, Runnable> reveal) {
        return settingsOfType(reveal, false);
    }

    private static SearchSource settingsOfType(Function<SettingKey, Runnable> reveal, boolean sections) {
        return (model, out) -> forEachGroup(model, (category, page, group, groupKey) -> {
            Map<String, Integer> seen = new HashMap<>();
            for (Setting setting : group.getSettings()) {
                int ordinal = seen.merge(setting.getRawName(), 1, Integer::sum) - 1;
                if ((setting.getType() == SettingType.SECTION) != sections || !page.shows(group, setting)
                        || setting.getName() == null || setting.getName().isBlank()) {
                    continue;
                }
                SettingKey key = new SettingKey(groupKey, setting.getRawName(), ordinal);
                out.accept(new SearchSource.Candidate(sections ? SearchResult.Kind.SECTION : SearchResult.Kind.SETTING,
                        setting.getName(), List.of(setting.getRawName()), setting.explicitDescription(),
                        List.of(category.name(), page.name(), group.getName()), iconOf(category, page, group),
                        reveal.apply(key), key, page.id(), setting.getRawName(), page.mirrorOf(group, setting)));
            }
        });
    }

    private static Icon iconOf(NavCategory category, NavPage page, SettingGroup group) {
        if (group.icon() != null) {
            return group.icon();
        }
        return page.icon() != null ? page.icon() : category.icon();
    }

    private interface GroupVisitor {
        void visit(NavCategory category, NavPage page, SettingGroup group, GroupKey key);
    }

    private static void forEachGroup(NavModel model, GroupVisitor visitor) {
        for (NavCategory category : model.categories()) {
            for (NavPage page : category.pages()) {
                Map<String, Integer> seen = new HashMap<>();
                for (SettingGroup group : page.groups()) {
                    int ordinal = seen.merge(group.getRawName(), 1, Integer::sum) - 1;
                    visitor.visit(category, page, group, new GroupKey(page.id(), group.getRawName(), ordinal));
                }
            }
        }
    }
}
