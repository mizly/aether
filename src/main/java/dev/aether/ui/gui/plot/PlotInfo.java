package dev.aether.ui.gui.plot;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// a plot slot read into facts: label is the custom name or number hypixel shows after "Plot - ", cleanup is
// -1 once cleaned or when the lore says nothing, pests is what the lore counted when the menu was read
public record PlotInfo(int plot, PlotMenuItem item, PlotStatus status, String label, int cleanup, int pests,
                       String spray) {
    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern NAME = Pattern.compile("(?i)^plot\\s*-\\s*(.+)$");
    // the pest glyph and spacing differ between 1.8 and modern clients, so only the words are matched
    private static final Pattern PESTS = Pattern.compile("(?i)this plot has\\s*(\\d+)\\s*pests?");
    private static final Pattern CLEANUP = Pattern.compile("(?i)cleanup:\\s*(\\d{1,3})(?:[.,]\\d+)?\\s*%");
    private static final Pattern SPRAY = Pattern.compile("(?i)^sprayed with\\s+(.+)$");

    public static PlotInfo of(int plot, PlotMenuItem item) {
        String label = plot == 0 ? "The Barn" : Integer.toString(plot);
        Matcher name = NAME.matcher(strip(item.name()));
        if (plot != 0 && name.find() && !name.group(1).isBlank()) {
            label = name.group(1).trim();
        }
        int cleanup = -1;
        int pests = 0;
        String spray = null;
        boolean cost = false;
        boolean greenhouse = false;
        for (String raw : item.lore()) {
            String line = strip(raw);
            Matcher matcher = PESTS.matcher(line);
            if (matcher.find()) {
                pests = Integer.parseInt(matcher.group(1));
            }
            matcher = CLEANUP.matcher(line);
            if (matcher.find()) {
                cleanup = Math.min(100, Integer.parseInt(matcher.group(1)));
            }
            matcher = SPRAY.matcher(line);
            if (matcher.find()) {
                spray = matcher.group(1).trim();
            }
            String lower = line.toLowerCase(Locale.ROOT);
            cost |= lower.startsWith("cost:");
            greenhouse |= lower.equals("greenhouse plot");
        }
        return new PlotInfo(plot, item, status(plot, item.itemId(), cost, greenhouse, cleanup), label, cleanup, pests,
                spray);
    }

    public static String strip(String formatted) {
        return formatted == null ? "" : FORMATTING.matcher(formatted).replaceAll("").trim();
    }

    // locked plots carry a cost; an oak button is one you can buy right now
    private static PlotStatus status(int plot, String itemId, boolean cost, boolean greenhouse, int cleanup) {
        if (plot == 0) {
            return PlotStatus.BARN;
        }
        if (itemId.equals("minecraft:oak_button")) {
            return PlotStatus.UNLOCKABLE;
        }
        if (cost || itemId.equals("minecraft:red_stained_glass_pane")) {
            return PlotStatus.LOCKED;
        }
        if (greenhouse || itemId.equals("minecraft:white_stained_glass")) {
            return PlotStatus.GREENHOUSE;
        }
        if (itemId.equals("minecraft:orange_stained_glass_pane") || cleanup >= 0 && cleanup < 100) {
            return PlotStatus.UNCLEANED;
        }
        return itemId.equals("minecraft:lime_stained_glass_pane") ? PlotStatus.CLEANED : PlotStatus.PRESET;
    }
}
