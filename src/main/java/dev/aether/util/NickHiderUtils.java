package dev.aether.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.aether.config.AetherConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public class NickHiderUtils {

    private static final String SCORE_NUMBER_PATTERN = "(?:[0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)(?:\\.[0-9]+)?";
    private static final String COLOR_PATTERN = "(?:\u00A7[0-9a-fk-orq-z])";
    private static final Pattern PURSE_PATTERN = Pattern.compile("(?i)(Purse:\\s*)(" + COLOR_PATTERN + "*?" + SCORE_NUMBER_PATTERN + ")(.*)");
    private static final Pattern BITS_PATTERN = Pattern.compile("(?i)(Bits:\\s*)(" + COLOR_PATTERN + "*?" + SCORE_NUMBER_PATTERN + ")(.*)");
    private static final Pattern COPPER_PATTERN = Pattern.compile("(?i)(Copper:\\s*)(" + COLOR_PATTERN + "*?" + SCORE_NUMBER_PATTERN + ")(.*)");
    private static final Pattern SAWDUST_PATTERN = Pattern.compile("(?i)(S[oa]wdust:\\s*)(" + COLOR_PATTERN + "*?" + SCORE_NUMBER_PATTERN + ")(" + COLOR_PATTERN + "*?)([KMB]?)(.*)");
    // Farming exp: +7.8 Farming (426,756,875/0)
    private static final Pattern FARMING_EXP_PATTERN = Pattern.compile("(?i)(Farming\\s*\\()(" + COLOR_PATTERN + "*?" + SCORE_NUMBER_PATTERN + ")(.*)");
    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("(?i)(\\d{2}/\\d{2}/\\d{2}\\s+)(" + COLOR_PATTERN + "*?[a-z0-9]+)");
    private static final Pattern SERVER_TAB_PATTERN = Pattern.compile("(?i)(Server:\\s+)(" + COLOR_PATTERN + "*?[a-z0-9]+)");
    private static final Pattern SB_LEVEL_PATTERN = Pattern.compile("\\[([0-9]+)\\]");

    private static final String[] WORDS = {
            "Cheese", "Gobbler", "Pickle", "Snatcher", "Muffin", "Stomper", "Taco", "Runner",
            "Wizard", "Slayer", "Dragon", "Hunter", "Potato", "Farmer", "Shadow", "Walker",
            "Sparkle", "Bouncer", "Banana", "Grabber", "Cookie", "Monster", "Flying", "Turtle",
            "Golden", "Knight", "Silver", "Wolf", "Gamer", "Pro", "Epic", "Legend",
            "Master", "Chief", "Wonder", "Wander", "Storm", "Thunder", "Blaze", "Frost",
            "Spirit", "Ghost", "Ancient", "Modern", "Super", "Ultra", "Mega", "Hyper",
            "Ninja", "Samurai", "Pirate", "Viking", "Cactus", "Pigeon", "Dolphin", "Panda"
    };

    private static final java.util.Map<String, String> cachedCoopNames = new java.util.HashMap<>();

    private static Pattern cachedPattern;
    private static String lastUsername;

    public static Pattern getUsernamePattern() {
        String username = Minecraft.getInstance().getUser().getName();

        if (cachedPattern == null || !username.equals(lastUsername)) {
            lastUsername = username;
            cachedPattern = Pattern.compile("(?i)" + Pattern.quote(username));
        }
        return cachedPattern;
    }

    public static String replaceNames(String text) {
        if (!isNickHiderActive()) {
            return text;
        }

        if (AetherConfig.NICK_HIDER_ENABLED.get()) {
            String replacement = AetherConfig.CUSTOM_USERNAME.get();
            if (!replacement.isEmpty()) {
                text = getUsernamePattern().matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
            }
        }

        if (AetherConfig.HIDE_SERVER_ID.get()) {
            String replacement = AetherConfig.CUSTOM_SERVER_ID.get();
            Matcher m = SERVER_ID_PATTERN.matcher(text);
            if (m.find()) {
                text = m.replaceFirst(m.group(1) + Matcher.quoteReplacement(replacement));
            }
            Matcher mTab = SERVER_TAB_PATTERN.matcher(text);
            if (mTab.find()) {
                text = mTab.replaceFirst(mTab.group(1) + Matcher.quoteReplacement(replacement));
            }
        }

        if (AetherConfig.COOP_HIDER_ENABLED.get()) {
            for (String target : AetherConfig.COOP_NAMES.get()) {
                String obfuscated = getObfuscatedName(target);
                text = Pattern.compile("(?i)" + Pattern.quote(target)).matcher(text)
                        .replaceAll(Matcher.quoteReplacement(obfuscated));
            }
        }

        if (areSpoofValuesEnabled() && AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get()) {
            Matcher m = SB_LEVEL_PATTERN.matcher(text);
            if (m.find()) {
                int level = AetherConfig.CUSTOM_SB_LEVEL.get();
                String replacement = "\u00A77[" + getLevelColor(level) + level + "\u00A77]";
                text = m.replaceFirst(Matcher.quoteReplacement(replacement));
            }
        }

        return text;
    }

    public static String getLevelColor(int level) {
        if (level >= 480) return "\u00A74";
        if (level >= 440) return "\u00A7c";
        if (level >= 400) return "\u00A76";
        if (level >= 360) return "\u00A75";
        if (level >= 320) return "\u00A7d";
        if (level >= 280) return "\u00A79";
        if (level >= 240) return "\u00A73";
        if (level >= 200) return "\u00A7b";
        if (level >= 160) return "\u00A72";
        if (level >= 120) return "\u00A7a";
        if (level >= 80) return "\u00A7e";
        if (level >= 40) return "\u00A7f";
        return "\u00A77";
    }

    private static String getObfuscatedName(String original) {
        return cachedCoopNames.computeIfAbsent(original.toLowerCase(), k -> {
            java.util.Random rand = new java.util.Random();
            String w1 = WORDS[rand.nextInt(WORDS.length)];
            String w2 = WORDS[rand.nextInt(WORDS.length)];
            int num = rand.nextInt(99) + 1;
            return w1 + w2 + num;
        });
    }

    public static String transformString(String text) {
        if (!isNickHiderActive())
            return text;
        return replaceNames(text);
    }

    public static boolean containsFarmingExpText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        return stripColorCodesWithMap(text).stripped().contains("+");
    }

    public static Component transformComponent(Component component) {
        if (component == null)
            return null;
        if (!isNickHiderActive()) {
            return component;
        }
        String fullText = component.getString();

        // Remove color codes for regex matching, but keep mapping to original
        ColorCodeStripResult stripResult = stripColorCodesWithMap(fullText);
        String plainText = stripResult.stripped();
        int[] map = stripResult.origIndices();

        java.util.List<ScoreTextReplacement> replacements = new java.util.ArrayList<>();

        if (areSpoofValuesEnabled()) {
            getOffsetReplacementMapped(plainText, map, PURSE_PATTERN, AetherConfig.PURSE_OFFSET.get(), replacements);
            getOffsetReplacementMapped(plainText, map, BITS_PATTERN, AetherConfig.BITS_OFFSET.get(), replacements);
            getOffsetReplacementMapped(plainText, map, COPPER_PATTERN, AetherConfig.COPPER_OFFSET.get(), replacements);
            getOffsetReplacementMapped(plainText, map, SAWDUST_PATTERN, AetherConfig.SAWDUST_OFFSET.get(), replacements);
            getOffsetReplacementMapped(plainText, map, FARMING_EXP_PATTERN, AetherConfig.FARMING_EXP_OFFSET.get(),
                    replacements);
        }

        if (AetherConfig.HIDE_SERVER_ID.get()) {
            String replacement = AetherConfig.CUSTOM_SERVER_ID.get();
            Matcher m = SERVER_ID_PATTERN.matcher(plainText);
            if (m.find()) {
                int origStart = map[m.start(2)];
                int origEnd = map[m.end(2) - 1] + 1;
                replacements.add(new ScoreTextReplacement(origStart, origEnd, replacement));
            }
            Matcher mTab = SERVER_TAB_PATTERN.matcher(plainText);
            if (mTab.find()) {
                int origStart = map[mTab.start(2)];
                int origEnd = map[mTab.end(2) - 1] + 1;
                replacements.add(new ScoreTextReplacement(origStart, origEnd, replacement));
            }
        }

        if (areSpoofValuesEnabled() && AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get()) {
            Matcher m = SB_LEVEL_PATTERN.matcher(plainText);
            if (m.find()) {
                int level = AetherConfig.CUSTOM_SB_LEVEL.get();
                int origStart = map[m.start()];
                int origEnd = map[m.end() - 1] + 1;
                String replacement = "\u00A77[" + getLevelColor(level) + level + "\u00A77]";
                replacements.add(new ScoreTextReplacement(origStart, origEnd, replacement));
            }
        }

        String username = Minecraft.getInstance().getUser().getName();
        String customNick = AetherConfig.CUSTOM_USERNAME.get();

        if (AetherConfig.NICK_HIDER_ENABLED.get() && !customNick.isEmpty()) {
            addNameReplacement(plainText, map, username, customNick, replacements);
        }

        if (AetherConfig.COOP_HIDER_ENABLED.get()) {
            for (String target : AetherConfig.COOP_NAMES.get()) {
                addNameReplacement(plainText, map, target, getObfuscatedName(target), replacements);
            }
        }

        if (replacements.isEmpty()) {
            return component;
        }

        return replaceTextByIndex(component, fullText, replacements);
    }

    private static boolean isNickHiderActive() {
        return AetherConfig.NICK_HIDER_MASTER_ENABLED.get()
                && (AetherConfig.NICK_HIDER_ENABLED.get()
                || AetherConfig.COOP_HIDER_ENABLED.get()
                || AetherConfig.HIDE_SERVER_ID.get()
                || areSpoofValuesEnabled());
    }

    private static boolean areSpoofValuesEnabled() {
        return AetherConfig.SPOOF_VALUES_ENABLED.get();
    }

    // Strips color codes and returns both the stripped string and a mapping from
    // stripped index to original index
    private static ColorCodeStripResult stripColorCodesWithMap(String text) {
        StringBuilder sb = new StringBuilder();
        java.util.List<Integer> origIndices = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                i += 2; // skip color code
            } else {
                sb.append(c);
                origIndices.add(i);
                i++;
            }
        }
        int[] map = new int[sb.length()];
        for (int j = 0; j < sb.length(); j++)
            map[j] = origIndices.get(j);
        return new ColorCodeStripResult(sb.toString(), map);
    }

    private record ColorCodeStripResult(String stripped, int[] origIndices) {
    }

    // Like getOffsetReplacement, but works on stripped text and maps indices back
    // to original
    private static void getOffsetReplacementMapped(String plainText, int[] map, Pattern pattern, double offset,
            java.util.List<ScoreTextReplacement> replacements) {
        if (offset <= 0.001 && offset >= -0.001)
            return;
        Matcher m = pattern.matcher(plainText);
        if (m.find()) {
            try {
                String fullValueStr = m.group(2);
                // Strip color codes before parsing
                String valueStr = fullValueStr.replaceAll("\u00A7[0-9a-fk-orq-z]", "").replace(",", "");
                
                String suffix = "";
                int end = m.end(2);
                
                // Group 4 for SAWDUST, Group 3 for others
                int suffixGroup = (pattern == SAWDUST_PATTERN) ? 4 : 3;
                if (m.groupCount() >= suffixGroup && isAbbreviationSuffix(m.group(suffixGroup))) {
                    suffix = m.group(suffixGroup);
                    end = m.end(suffixGroup);
                }

                int origStart = map[m.start(2)];
                int origEnd = map[end - 1] + 1;

                if (!suffix.isEmpty()) {
                    double val = parseAbbreviated(valueStr + suffix);
                    val += offset;
                    replacements.add(new ScoreTextReplacement(origStart, origEnd, formatAbbreviated(val)));
                } else {
                    double val = Double.parseDouble(valueStr);
                    val += offset;
                    replacements.add(new ScoreTextReplacement(origStart, origEnd, formatNumber(val)));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static Component replaceTextByIndex(Component component, String fullText,
            java.util.List<ScoreTextReplacement> replacements) {
        java.util.List<StyledTextSegment> segments = new java.util.ArrayList<>();
        component.visit((style, part) -> {
            if (!part.isEmpty()) {
                segments.add(new StyledTextSegment(part, style));
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);

        if (segments.isEmpty()) {
            MutableComponent literal = Component.literal(applyReplacements(fullText, replacements));
            literal.setStyle(component.getStyle());
            return literal;
        }

        java.util.List<Style> styles = new java.util.ArrayList<>(fullText.length());
        for (StyledTextSegment segment : segments) {
            for (int i = 0; i < segment.text().length(); i++) {
                styles.add(segment.style());
            }
        }

        if (styles.size() != fullText.length()) {
            MutableComponent literal = Component.literal(applyReplacements(fullText, replacements));
            literal.setStyle(component.getStyle());
            return literal;
        }

        java.util.List<ScoreTextReplacement> ordered = new java.util.ArrayList<>(replacements);
        ordered.sort(java.util.Comparator.comparingInt(ScoreTextReplacement::start).reversed());

        StringBuilder transformed = new StringBuilder(fullText);
        for (ScoreTextReplacement replacement : ordered) {
            if (replacement.start() < 0 || replacement.end() > transformed.length()
                    || replacement.start() >= replacement.end()) {
                continue;
            }

            Style replacementStyle = replacement.start() < styles.size() ? styles.get(replacement.start())
                    : component.getStyle();
            transformed.replace(replacement.start(), replacement.end(), replacement.replacement());
            styles.subList(replacement.start(), replacement.end()).clear();
            for (int i = 0; i < replacement.replacement().length(); i++) {
                styles.add(replacement.start() + i, replacementStyle);
            }
        }

        MutableComponent rebuilt = Component.literal("");
        rebuilt.setStyle(component.getStyle());

        int index = 0;
        while (index < transformed.length()) {
            Style style = styles.get(index);
            int next = index + 1;
            while (next < transformed.length() && java.util.Objects.equals(style, styles.get(next))) {
                next++;
            }

            MutableComponent piece = Component.literal(transformed.substring(index, next));
            piece.setStyle(style);
            rebuilt.append(piece);
            index = next;
        }

        return rebuilt;
    }

    private static String applyReplacements(String text, java.util.List<ScoreTextReplacement> replacements) {
        java.util.List<ScoreTextReplacement> ordered = new java.util.ArrayList<>(replacements);
        ordered.sort(java.util.Comparator.comparingInt(ScoreTextReplacement::start).reversed());

        StringBuilder transformed = new StringBuilder(text);
        for (ScoreTextReplacement replacement : ordered) {
            if (replacement.start() >= 0 && replacement.end() <= transformed.length()
                    && replacement.start() < replacement.end()) {
                transformed.replace(replacement.start(), replacement.end(), replacement.replacement());
            }
        }
        return transformed.toString();
    }

    private static void addNameReplacement(String plainText, int[] map, String target, String replacement,
            java.util.List<ScoreTextReplacement> replacements) {
        if (target == null || target.isEmpty())
            return;
        Pattern p = Pattern.compile("(?i)" + Pattern.quote(target));
        Matcher m = p.matcher(plainText);
        while (m.find()) {
            int origStart = map[m.start()];
            int origEnd = map[m.end() - 1] + 1;
            replacements.add(new ScoreTextReplacement(origStart, origEnd, replacement));
        }
    }

    private static void getOffsetReplacement(String text, Pattern pattern, double offset,
            java.util.List<ScoreTextReplacement> replacements) {
        if (offset <= 0.001 && offset >= -0.001)
            return;
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                String fullValueStr = m.group(2);
                String valueStr = fullValueStr.replaceAll("\u00A7[0-9a-fk-orq-z]", "").replace(",", "");
                
                String suffix = "";
                int end = m.end(2);
                
                int suffixGroup = (pattern == SAWDUST_PATTERN) ? 4 : 3;
                if (m.groupCount() >= suffixGroup && isAbbreviationSuffix(m.group(suffixGroup))) {
                    suffix = m.group(suffixGroup);
                    end = m.end(suffixGroup);
                }

                if (!suffix.isEmpty()) {
                    double val = parseAbbreviated(valueStr + suffix);
                    val += offset;
                    replacements.add(new ScoreTextReplacement(m.start(2), end, formatAbbreviated(val)));
                } else {
                    double val = Double.parseDouble(valueStr);
                    val += offset;
                    replacements.add(new ScoreTextReplacement(m.start(2), end, formatNumber(val)));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isAbbreviationSuffix(String value) {
        return value != null
                && (value.equalsIgnoreCase("K") || value.equalsIgnoreCase("M") || value.equalsIgnoreCase("B"));
    }

    private static double parseAbbreviated(String str) {
        str = str.toUpperCase().replace(",", "");
        if (str.endsWith("K"))
            return Double.parseDouble(str.substring(0, str.length() - 1)) * 1000;
        if (str.endsWith("M"))
            return Double.parseDouble(str.substring(0, str.length() - 1)) * 1000000;
        if (str.endsWith("B"))
            return Double.parseDouble(str.substring(0, str.length() - 1)) * 1000000000;
        return Double.parseDouble(str);
    }

    private static String formatAbbreviated(double val) {
        if (val >= 1000000000)
            return String.format("%.1fB", val / 1000000000.0);
        if (val >= 1000000)
            return String.format("%.1fM", val / 1000000.0);
        if (val >= 1000)
            return String.format("%.1fk", val / 1000.0);
        return String.format("%.0f", val);
    }

    private static String formatNumber(double val) {
        return String.format("%,.0f", val);
    }

    private record ScoreTextReplacement(int start, int end, String replacement) {
    }

    private record StyledTextSegment(String text, Style style) {
    }
}
