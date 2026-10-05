package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotSetting;
import dev.aether.ui.settings.PlotSetting.EmptyMeaning;
import dev.aether.ui.settings.PlotSetting.Mode;
import dev.aether.ui.settings.PlotToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// what the Configure Plots picker shows and does, without drawing. one meaning per channel: full opacity and
// the accent frame mean picked, the compass is where you stand, red is pests, a gray numbered pane is a plot
// the menu was never read for. lines keep § codes so they draw like hypixel lore
public final class PlotPickerModel {
    public static final float DIMMED = 0.45f;
    public static final int MODE_SLOT = 47;
    public static final int CURRENT_SLOT = 50;
    public static final int CLEAR_SLOT = 51;
    public static final int HELP_SLOT = 53;

    private static final String UNKNOWN_PLOT_ITEM = "minecraft:gray_stained_glass_pane";
    private static final String DEFAULT_BARN_ITEM = "minecraft:dark_oak_planks";
    private static final List<String> LOAD_HINT = List.of("§7Open §e/desk §7› §aConfigure Plots", "§7once to load your plots.");

    public enum Tool { GO_BACK, CLOSE, MODE, CURRENT, CLEAR, HELP }

    // included = counted by the feature (framed); marked = picked by hand (check or visit number)
    public record PlotLook(int plot, String itemId, int count, boolean included, boolean marked, int order,
                           float alpha, boolean current, int pests, boolean selectable, boolean unknown) {
    }

    public record ToolLook(Tool tool, int slot, String itemId, List<String> tooltip) {
    }

    private final PlotSetting setting;
    private boolean onlySelected;

    public PlotPickerModel(PlotSetting setting) {
        this.setting = setting;
        this.onlySelected = !setting.isEmpty();
    }

    public PlotSetting setting() {
        return setting;
    }

    // an ALL setting with nothing stored runs everywhere, so every plot it can use counts as picked
    public boolean allMode() {
        return setting.emptyMeaning() == EmptyMeaning.ALL && !onlySelected && setting.isEmpty();
    }

    public PlotLook look(int plot, GardenFacts facts) {
        PlotToken token = token(plot);
        PlotInfo info = facts.info(plot);
        boolean unknown = info == null;
        String itemId = unknown ? (plot == PlotToken.BARN ? DEFAULT_BARN_ITEM : UNKNOWN_PLOT_ITEM) : info.item().itemId();
        boolean marked = setting.isSelected(token);
        boolean selectable = selectable(token, info);
        boolean all = allMode();
        boolean included = marked || all && selectable;
        boolean anyPicked = !setting.selection().isEmpty();
        float alpha = included || !anyPicked && !all && selectable ? 1f : DIMMED;
        int order = setting.mode() == Mode.ORDERED && marked ? setting.order(token) : 0;
        return new PlotLook(plot, itemId, unknown && plot != PlotToken.BARN ? plot : 0, included, marked, order, alpha,
                facts.currentPlot() == plot, facts.pestCount(plot), selectable, unknown);
    }

    // true when the stored value changed
    public boolean click(int plot, GardenFacts facts) {
        PlotToken token = token(plot);
        if (setting.isSelected(token)) {
            if (setting.mode() == Mode.SINGLE) {
                return false;
            }
            setting.remove(token);
            // nothing left means every plot again, so show it that way instead of an empty "only selected"
            onlySelected = !setting.isEmpty();
            return true;
        }
        if (!selectable(token, facts.info(plot))) {
            return false;
        }
        onlySelected = true;
        setting.toggle(token);
        return true;
    }

    public boolean useTool(Tool tool, GardenFacts facts) {
        switch (tool) {
            case MODE -> {
                if (allMode()) {
                    onlySelected = true;
                } else {
                    onlySelected = false;
                    setting.clear();
                }
                return true;
            }
            case CURRENT -> {
                int current = facts.currentPlot();
                if (current == GardenFacts.UNKNOWN || setting.isSelected(token(current))) {
                    return false;
                }
                return click(current, facts);
            }
            case CLEAR -> {
                if (setting.isEmpty()) {
                    return false;
                }
                onlySelected = false;
                setting.clear();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public List<ToolLook> tools(GardenFacts facts) {
        List<ToolLook> tools = new ArrayList<>();
        if (setting.emptyMeaning() == EmptyMeaning.ALL) {
            tools.add(new ToolLook(Tool.MODE, MODE_SLOT, allMode() ? "minecraft:filled_map" : "minecraft:hopper", modeLore()));
        }
        tools.add(new ToolLook(Tool.GO_BACK, PlotSlots.GO_BACK_SLOT, "minecraft:arrow", List.of("§aGo Back", "§7To Aether")));
        tools.add(new ToolLook(Tool.CLOSE, PlotSlots.CLOSE_SLOT, "minecraft:barrier", List.of("§cClose")));
        tools.add(new ToolLook(Tool.CURRENT, CURRENT_SLOT, "minecraft:compass", currentLore(facts)));
        if (hasEmptyState()) {
            tools.add(new ToolLook(Tool.CLEAR, CLEAR_SLOT, "minecraft:bucket", clearLore()));
        }
        tools.add(new ToolLook(Tool.HELP, HELP_SLOT, "minecraft:book", helpLore(facts)));
        return tools;
    }

    public List<String> tooltip(int plot, GardenFacts facts) {
        PlotLook look = look(plot, facts);
        PlotInfo info = facts.info(plot);
        List<String> lines = new ArrayList<>();
        lines.add(title(plot, info));
        if (info != null) {
            statusLine(info, lines);
        } else if (plot != PlotToken.BARN) {
            lines.add("§8Not loaded yet");
        }
        if (look.current()) {
            lines.add("§aYou are here");
        }
        if (look.pests() > 0) {
            lines.add("§cInfested: §f" + look.pests() + (look.pests() == 1 ? " pest" : " pests"));
        } else if (look.pests() < 0) {
            lines.add("§cInfested");
        }
        if (look.marked()) {
            lines.add(look.order() > 0 ? "§aSelected §8#" + look.order() : "§aSelected");
        } else if (look.included()) {
            lines.add("§aIncluded §8(all plots)");
        }
        String action = action(plot, look, info);
        if (action != null) {
            lines.add("");
            lines.add(action);
        }
        if (!facts.hasMenu()) {
            lines.add("");
            lines.addAll(LOAD_HINT);
        }
        return lines;
    }

    // the inline summary: "Plots 3, 5, 12", "All plots", "Stay where I am", "None" or the invalid "Pick a plot"
    public static String summary(PlotSetting setting) {
        List<PlotToken> tokens = setting.tokens();
        if (tokens.isEmpty()) {
            return switch (setting.emptyMeaning()) {
                case ALL -> "All plots";
                case STAY -> "Stay where I am";
                case CURRENT -> "Current plot";
                case NONE -> setting.mode() == Mode.SINGLE ? "Pick a plot" : "None";
            };
        }
        boolean ordered = setting.mode() == Mode.ORDERED;
        List<PlotToken> shown = ordered ? tokens : tokens.stream()
                .sorted(Comparator.comparingInt(PlotPickerModel::summaryRank)).toList();
        List<String> parts = shown.stream()
                .map(token -> token.isBarn() ? "The Barn" : token.isKnown() ? token.text() : "unknown: " + token.text())
                .toList();
        String joined = String.join(ordered ? " › " : ", ", parts);
        PlotToken first = shown.getFirst();
        if (!first.isKnown() || first.isBarn()) {
            return joined;
        }
        return (parts.size() == 1 ? "Plot " : "Plots ") + joined;
    }

    // a single required plot (rewarp /plottp) with nothing usable stored
    public static boolean invalid(PlotSetting setting) {
        return setting.mode() == Mode.SINGLE && setting.emptyMeaning() == EmptyMeaning.NONE
                && setting.selection().isEmpty();
    }

    private boolean hasEmptyState() {
        return setting.mode() != Mode.SINGLE || setting.emptyMeaning() != EmptyMeaning.NONE;
    }

    private boolean selectable(PlotToken token, PlotInfo info) {
        if (!setting.isSelectable(token)) {
            return false;
        }
        // a teleport to a plot you don't own fails, and those are the only single-plot settings
        return setting.mode() != Mode.SINGLE || info == null || info.status().unlocked();
    }

    private String action(int plot, PlotLook look, PlotInfo info) {
        if (look.marked()) {
            return setting.mode() == Mode.SINGLE ? null : "§eClick to remove!";
        }
        PlotToken token = token(plot);
        if (!look.selectable()) {
            if (token.isBarn() && !setting.allowsBarn()) {
                return "§cThe barn can't be picked here.";
            }
            if (setting.isRestricted(token)) {
                String reason = setting.restrictionReason();
                return reason == null ? "§cThis plot can't be picked here." : "§c" + reason;
            }
            return info != null && info.status() == PlotStatus.UNLOCKABLE ? "§cUnlock this plot first." : "§cYou don't own this plot yet.";
        }
        if (allMode()) {
            return "§eClick to pick only this plot!";
        }
        return switch (setting.mode()) {
            case SINGLE -> "§eClick to pick!";
            case MULTI -> "§eClick to add!";
            case ORDERED -> "§eClick to add as stop #" + (setting.selection().size() + 1) + "!";
        };
    }

    private static String title(int plot, PlotInfo info) {
        if (info != null && !PlotInfo.strip(info.item().name()).isEmpty()) {
            return info.item().name();
        }
        return plot == PlotToken.BARN ? "§aThe Barn" : "§fPlot §7- §b" + plot;
    }

    private static void statusLine(PlotInfo info, List<String> lines) {
        switch (info.status()) {
            case GREENHOUSE -> lines.add("§7Greenhouse Plot");
            case LOCKED -> lines.add("§cLocked");
            case UNLOCKABLE -> lines.add("§eReady to unlock");
            case UNCLEANED -> lines.add("§6Not cleaned" + (info.cleanup() >= 0 ? " §8(" + info.cleanup() + "%)" : ""));
            default -> {
            }
        }
        if (info.spray() != null) {
            lines.add("§7Sprayed with §a" + info.spray());
        }
    }

    private List<String> modeLore() {
        boolean all = allMode();
        List<String> lines = new ArrayList<>(List.of("§aPlot Filter", "§7Choose which plots this", "§7runs on.", "",
                (all ? "§b» §a" : "§8» §7") + "All plots",
                (all ? "§8» §7" : "§b» §a") + "Only selected"));
        if (!all && setting.isEmpty()) {
            lines.add("");
            lines.add("§7Pick at least one plot, or");
            lines.add("§7it keeps running on all.");
        }
        lines.add("");
        lines.add("§eClick to switch!");
        return lines;
    }

    private List<String> currentLore(GardenFacts facts) {
        List<String> lines = new ArrayList<>(List.of("§aCurrent Plot", "§7Pick the plot you are", "§7standing on.", ""));
        int current = facts.currentPlot();
        if (current == GardenFacts.UNKNOWN) {
            lines.add("§cYou are not on your Garden.");
            return lines;
        }
        PlotInfo info = facts.info(current);
        lines.add("§7You are on: " + (current == PlotToken.BARN ? "§aThe Barn"
                : "§bPlot - " + (info == null ? Integer.toString(current) : info.label())));
        lines.add("");
        if (setting.isSelected(token(current))) {
            lines.add("§aAlready picked.");
        } else if (selectable(token(current), info)) {
            lines.add("§eClick to pick it!");
        } else {
            lines.add("§cIt can't be picked here.");
        }
        return lines;
    }

    private List<String> clearLore() {
        List<String> lines = new ArrayList<>(List.of("§cClear Selection"));
        lines.addAll(emptyMeaningLines());
        lines.add("");
        lines.add(setting.isEmpty() ? "§7Nothing is picked." : "§eClick to clear!");
        return lines;
    }

    private List<String> helpLore(GardenFacts facts) {
        List<String> lines = new ArrayList<>(List.of("§aHelp"));
        lines.addAll(switch (setting.mode()) {
            case SINGLE -> List.of("§7Click a plot to pick it.");
            case MULTI -> List.of("§7Click plots to add or", "§7remove them.");
            case ORDERED -> List.of("§7Click plots in the order to", "§7visit them. Click one again", "§7to take it out.");
        });
        lines.add("");
        lines.addAll(emptyMeaningLines());
        lines.add("");
        lines.add("§7Faded plots are not picked.");
        lines.add("§7The compass marks your plot.");
        lines.add("§7Red plots have pests.");
        if (!facts.hasMenu()) {
            lines.add("");
            lines.addAll(LOAD_HINT);
        }
        return lines;
    }

    private List<String> emptyMeaningLines() {
        boolean single = setting.mode() == Mode.SINGLE;
        String nothing = single ? "§7With no plot picked, it" : "§7With no plots picked, it";
        return switch (setting.emptyMeaning()) {
            case ALL -> List.of(nothing, "§7runs on every plot.");
            case STAY -> List.of(nothing, "§7works where you stand.");
            case CURRENT -> List.of(nothing, "§7uses the plot you are on.");
            case NONE -> single ? List.of("§7A plot must be picked.") : List.of(nothing, "§7does nothing.");
        };
    }

    // numbers ascending, then the barn, then entries the picker doesn't recognise
    private static int summaryRank(PlotToken token) {
        return token.isBarn() ? 100 : token.isKnown() ? token.number() : 200;
    }

    private static PlotToken token(int plot) {
        return plot == PlotToken.BARN ? PlotToken.barn() : PlotToken.plot(plot);
    }
}
