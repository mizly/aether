package dev.aether.modules.profit;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.visitor.VisitorManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProfitChatParser {
    private static final String SECTION = "\u00A7";
    private static final Pattern PEST_PATTERN = Pattern.compile("received\\s+(\\d+)x\\s+(.+?)\\s+for\\s+killing",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RARE_DROP_PATTERN = Pattern.compile(
            "(?:UNCOMMON|RARE|CRAZY RARE|PRAY TO RNGESUS) DROP!\\s+(?:You dropped\\s+)?(?:an?\\s+)?(?:(\\d+)x\\s+)?(.+?)(?=\\s*(?:\\u00A7[0-9a-fk-or])*\\s*[\\(!]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PET_DROP_PATTERN = Pattern.compile(
            "PET DROP!\\s+.*?\\u00A7([0-9a-f])(?:\\u00A7[0-9a-fk-or])*\\s*(?:(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)\\s+(?:\\u00A7[0-9a-fk-or])*)?(.+?)(?=\\s*(?:\\u00A7[0-9a-fk-or])*\\s*[\\(!]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RARE_CROP_PATTERN = Pattern.compile(
            "RARE CROP!\\s+(.+?)(?=\\s*(?:\\u00A7[0-9a-fk-or])*\\s*[\\(!]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERFLOW_DROP_PATTERN = Pattern.compile(
            "OVERFLOW!\\s+.*?\\s+has\\s+just\\s+dropped\\s+(?:an?\\s+)?(?:(\\d+)x\\s+)?(.+?)(?=\\s*\\(!|!|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PEST_SHARD_PATTERN = Pattern.compile(
            "charmed\\s+a\\s+Pest\\s+and\\s+captured\\s+(?:its\\s+Shard|(\\d+)\\s+Shards)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BAZAAR_BUY_PATTERN = Pattern.compile(
            "\\[Bazaar\\] Bought (\\d+)x (.+?) for [\\d,]+ coins!",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPRAY_PATTERN = Pattern.compile(
            "SPRAYONATOR! You sprayed Plot - \\d+ with (.+?)(?:!|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VISITOR_REWARD_PATTERN =
            Pattern.compile("^\\+?([\\d,.]+(?:\\.\\d+)?[kKmMbB]?)[xX]?\\s+(.+)$");

    private long lastBazaarSprayBuyTime = 0L;
    private boolean trackingVisitorRewards = false;
    private boolean awaitingVisitorRewards = false;
    private boolean suppressNonVisitorRewardBlock = false;

    void resetSessionState() {
        lastBazaarSprayBuyTime = 0L;
        trackingVisitorRewards = false;
        awaitingVisitorRewards = false;
        suppressNonVisitorRewardBlock = false;
    }

    void handleChatMessage(Component component, ProfitEventSink sink) {
        String text = toLegacyText(component);

        Matcher petMatcher = PET_DROP_PATTERN.matcher(text);
        if (petMatcher.find()) {
            String colorCode = petMatcher.group(1).toLowerCase();
            String petName = petMatcher.group(2).trim();
            String finalName = petName;

            if (petName.equalsIgnoreCase("Slug")) {
                if (colorCode.equals("5") || colorCode.equals("d")) {
                    finalName = "Epic Slug";
                } else if (colorCode.equals("6")) {
                    finalName = "Legendary Slug";
                }
            } else if (petName.equalsIgnoreCase("Rat")) {
                finalName = "Rat";
            }

            sink.addDrop(finalName, 1);
            return;
        }

        Matcher cropMatcher = RARE_CROP_PATTERN.matcher(text);
        if (cropMatcher.find()) {
            sink.addDrop(cropMatcher.group(1).trim(), 1);
            return;
        }

        String plainText = TablistUtils.stripColors(text).trim();

        if (MacroStateManager.getCurrentState() != MacroState.State.VISITING) {
            trackingVisitorRewards = false;
            awaitingVisitorRewards = false;
            suppressNonVisitorRewardBlock = false;
        }

        if (MacroStateManager.getCurrentState() == MacroState.State.VISITING
                && VisitorManager.extractAcceptedVisitorName(plainText) != null) {
            awaitingVisitorRewards = true;
            trackingVisitorRewards = false;
            suppressNonVisitorRewardBlock = false;
            return;
        }

        if (isRewardBlockBoundary(plainText)) {
            trackingVisitorRewards = false;
            suppressNonVisitorRewardBlock = false;
            return;
        }

        if (isNonVisitorRewardHeader(plainText)) {
            if (awaitingVisitorRewards || trackingVisitorRewards) {
                suppressNonVisitorRewardBlock = true;
                trackingVisitorRewards = false;
            }
            return;
        }

        Matcher overflowMatcher = OVERFLOW_DROP_PATTERN.matcher(plainText);
        if (overflowMatcher.find()) {
            try {
                String countStr = overflowMatcher.group(1);
                int count = countStr != null ? Integer.parseInt(countStr) : 1;
                sink.addDrop(overflowMatcher.group(2).trim(), count);
                return;
            } catch (Exception ignored) {
            }
        }

        Matcher pestMatcher = PEST_PATTERN.matcher(plainText);
        if (pestMatcher.find()) {
            try {
                int count = Integer.parseInt(pestMatcher.group(1));
                sink.addDrop(pestMatcher.group(2).trim(), count);
                return;
            } catch (Exception ignored) {
            }
        }

        Matcher rareMatcher = RARE_DROP_PATTERN.matcher(plainText);
        if (rareMatcher.find()) {
            try {
                String countStr = rareMatcher.group(1);
                int count = countStr != null ? Integer.parseInt(countStr) : 1;
                sink.addDrop(rareMatcher.group(2).trim(), count);
            } catch (Exception ignored) {
            }
        }

        Matcher shardMatcher = PEST_SHARD_PATTERN.matcher(plainText);
        if (shardMatcher.find()) {
            try {
                String countStr = shardMatcher.group(1);
                int count = countStr != null ? Integer.parseInt(countStr) : 1;
                sink.addDrop("Pest Shard", count);
            } catch (Exception ignored) {
            }
        }

        Matcher bazaarMatcher = BAZAAR_BUY_PATTERN.matcher(plainText);
        if (bazaarMatcher.find()) {
            if (MacroStateManager.getCurrentState() == MacroState.State.VISITING) {
                ClientUtils.sendDebugMessage(Minecraft.getInstance(), "Bazaar buy ignored (Visiting state)");
                return;
            }
            try {
                int count = Integer.parseInt(bazaarMatcher.group(1));
                String itemName = bazaarMatcher.group(2).trim();
                ClientUtils.sendDebugMessage(Minecraft.getInstance(), "Bazaar buy detected: " + count + "x " + itemName);
                sink.addDrop(itemName, -count);
                lastBazaarSprayBuyTime = System.currentTimeMillis();
            } catch (Exception ignored) {
            }
        }

        if (plainText.equalsIgnoreCase("REWARDS")) {
            if (suppressNonVisitorRewardBlock) {
                return;
            }
            trackingVisitorRewards = awaitingVisitorRewards
                    && MacroStateManager.getCurrentState() == MacroState.State.VISITING;
            awaitingVisitorRewards = false;
            return;
        }

        if (trackingVisitorRewards) {
            if (plainText.contains("[NPC]")) {
                trackingVisitorRewards = false;
                awaitingVisitorRewards = false;
                return;
            }

            if (isNonVisitorRewardLine(plainText)) {
                return;
            }

            Matcher rewardMatcher = VISITOR_REWARD_PATTERN.matcher(plainText);
            if (rewardMatcher.matches()) {
                String item = rewardMatcher.group(2).trim();
                String countStr = rewardMatcher.group(1).replace(",", "");
                long count = parseVisitorRewardCount(countStr);
                sink.addVisitorGain(item, count);
                return;
            }

            if (isLikelyVisitorRewardItemName(plainText)) {
                sink.addVisitorGain(plainText, 1L);
            }
            return;
        }

        Matcher sprayMatcher = SPRAY_PATTERN.matcher(plainText);
        if (sprayMatcher.find()) {
            String baitName = sprayMatcher.group(1).trim();
            long now = System.currentTimeMillis();
            if (now - lastBazaarSprayBuyTime < 15000) {
                ClientUtils.sendDebugMessage(Minecraft.getInstance(), "Sprayonator use ignored due to recent Bazaar buy.");
            } else {
                ClientUtils.sendDebugMessage(Minecraft.getInstance(), "Sprayonator use detected (" + baitName + ").");
                sink.addDrop(baitName, -1);
            }
        }
    }

    private static boolean isRewardBlockBoundary(String plainText) {
        if (plainText == null || plainText.length() < 20) {
            return false;
        }

        int firstCodePoint = plainText.codePointAt(0);
        if (Character.isLetterOrDigit(firstCodePoint) || Character.isWhitespace(firstCodePoint)) {
            return false;
        }

        for (int i = Character.charCount(firstCodePoint); i < plainText.length();) {
            int codePoint = plainText.codePointAt(i);
            if (codePoint != firstCodePoint) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isNonVisitorRewardHeader(String plainText) {
        String lower = plainText.toLowerCase();
        return lower.contains("skill level up")
                || lower.contains("garden milestone")
                || lower.contains("garden level up")
                || lower.contains("crop milestone");
    }

    private static boolean isNonVisitorRewardLine(String plainText) {
        String lower = plainText.toLowerCase();
        return lower.contains("skyblock xp")
                || lower.contains("health")
                || lower.contains("farmhand")
                || lower.contains("grants ")
                || lower.contains("chance for multiple crops")
                || lower.contains("milestone ")
                || lower.contains("garden experience")
                || lower.contains("farming experience")
                || lower.contains("farming xp");
    }

    private static boolean isLikelyVisitorRewardItemName(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return false;
        }

        String lower = plainText.toLowerCase();
        return !plainText.contains("[")
                && !plainText.contains("]")
                && !plainText.contains("(")
                && !plainText.contains(")")
                && !plainText.contains(":")
                && plainText.indexOf('\u279c') < 0
                && !plainText.endsWith(".")
                && !lower.startsWith("milestone ")
                && !lower.contains(" in ");
    }

    private static String toLegacyText(Component component) {
        StringBuilder builder = new StringBuilder();
        component.visit((style, part) -> {
            net.minecraft.network.chat.TextColor color = style.getColor();
            if (color != null) {
                int rgb = color.getValue();
                String code = "f";
                if (rgb == 16755200) {
                    code = "6";
                } else if (rgb == 11141290) {
                    code = "5";
                } else if (rgb == 5636095) {
                    code = "b";
                } else if (rgb == 16733695) {
                    code = "d";
                } else if (rgb == 5592405) {
                    code = "8";
                } else if (rgb == 11184810) {
                    code = "7";
                } else if (rgb == 5592575) {
                    code = "9";
                } else if (rgb == 5635925) {
                    code = "a";
                } else if (rgb == 16711680) {
                    code = "c";
                } else if (rgb == 16777045) {
                    code = "e";
                }
                builder.append(SECTION).append(code);
            }
            if (style.isBold()) {
                builder.append(SECTION).append('l');
            }
            if (style.isItalic()) {
                builder.append(SECTION).append('o');
            }
            builder.append(part);
            return Optional.empty();
        }, Style.EMPTY);
        return builder.toString();
    }

    private static long parseVisitorRewardCount(String countText) {
        try {
            return dev.aether.util.NumberUtils.parseShorthand(countText);
        } catch (Exception ignored) {
            return 1L;
        }
    }

    interface ProfitEventSink {
        void addDrop(String itemName, long count);

        void addVisitorGain(String itemName, long count);
    }
}
