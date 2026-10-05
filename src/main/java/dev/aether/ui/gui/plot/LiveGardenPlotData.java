package dev.aether.ui.gui.plot;

import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.GardenPlots;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// the garden as the game shows it now. the sidebar only says whether you are on the garden: the plot comes from
// your position, because ClientUtils.getCurrentPlot logs every miss and custom plot names hide the number
public final class LiveGardenPlotData implements GardenPlotData {
    private static final Pattern SIDEBAR_PLOT = Pattern.compile("(?i)\\bplot\\s*[:\\-#]\\s*[a-z0-9]+");
    private static final Pattern SIDEBAR_PESTS = Pattern.compile("(?i)\\bplot\\s*[-:#]?\\s*(\\d+)\\D+?x\\s*(\\d+)\\b");

    private final Supplier<Minecraft> client;

    public LiveGardenPlotData(Supplier<Minecraft> client) {
        this.client = client;
    }

    @Override
    public int currentPlot() {
        Minecraft minecraft = client.get();
        if (minecraft == null || minecraft.player == null || minecraft.level == null
                || !onGarden(ClientUtils.getSidebarLines())) {
            return UNKNOWN_PLOT;
        }
        return PlotSlots.plotAtCell(GardenPlots.gridIndex(minecraft.player.getX()) + 2,
                GardenPlots.gridIndex(minecraft.player.getZ()) + 2);
    }

    @Override
    public Set<Integer> infestedPlots() {
        Minecraft minecraft = client.get();
        if (minecraft == null || minecraft.player == null) {
            return Set.of();
        }
        Set<Integer> plots = new LinkedHashSet<>();
        for (String plot : PestManager.getInfestedPlotsFromTab(minecraft)) {
            int number = number(plot);
            if (number > 0) {
                plots.add(number);
            }
        }
        return plots;
    }

    // the sidebar counts pests only on the plot you stand on
    @Override
    public int pestCount() {
        int current = currentPlot();
        if (current <= 0) {
            return 0;
        }
        for (String line : ClientUtils.getSidebarLines()) {
            Matcher matcher = SIDEBAR_PESTS.matcher(line);
            if (matcher.find() && number(matcher.group(1)) == current) {
                return Math.max(0, number(matcher.group(2)));
            }
        }
        return 0;
    }

    @Override
    public PlotMenuSnapshot snapshot() {
        return PlotMenuReader.current();
    }

    private static boolean onGarden(List<String> sidebar) {
        for (String line : sidebar) {
            if (line.toLowerCase(Locale.ROOT).contains("the garden") || SIDEBAR_PLOT.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static int number(String digits) {
        try {
            return Integer.parseInt(digits.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
