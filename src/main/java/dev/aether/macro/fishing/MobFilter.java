package dev.aether.macro.fishing;

import dev.aether.util.TablistUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MobFilter {
    public enum Verdict { ACCEPT, IGNORE, UNKNOWN }

    // hypixel's font draws mob-type and stat icons as private use glyphs
    private static final Pattern PRIVATE_USE = Pattern.compile("[\\uE000-\\uF8FF]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final PatternCache WHITELIST = new PatternCache();
    private static final PatternCache BLACKLIST = new PatternCache();

    private MobFilter() {}

    public static Verdict classify(String plate, List<String> whitelist, List<String> blacklist) {
        String name = normalize(plate);
        if (name.isEmpty()) return Verdict.UNKNOWN;
        if (matchesAny(BLACKLIST.patternsFor(blacklist), name)) return Verdict.IGNORE;
        List<Pattern> allowed = WHITELIST.patternsFor(whitelist);
        return allowed.isEmpty() || matchesAny(allowed, name) ? Verdict.ACCEPT : Verdict.IGNORE;
    }

    static String normalize(String text) {
        if (text == null) return "";
        String plain = TablistUtils.stripColors(text).replace(' ', ' ');
        plain = PRIVATE_USE.matcher(plain).replaceAll("");
        return SPACES.matcher(plain).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).find()) return true;
        }
        return false;
    }

    private static List<Pattern> compile(List<String> entries) {
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : entries) {
            String needle = normalize(entry);
            if (!needle.isEmpty()) {
                patterns.add(Pattern.compile("(?<![a-z0-9])" + Pattern.quote(needle) + "(?![a-z0-9])"));
            }
        }
        return List.copyOf(patterns);
    }

    private static final class PatternCache {
        private List<String> source = List.of();
        private List<Pattern> patterns = List.of();

        // keyed by a copy of the contents, so an entry edited in place still recompiles
        synchronized List<Pattern> patternsFor(List<String> entries) {
            List<String> current = entries == null ? List.of() : entries;
            if (!source.equals(current)) {
                source = new ArrayList<>(current);
                patterns = compile(source);
            }
            return patterns;
        }
    }
}
