package dev.aether.ui.gui.plot;

import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.GardenPlots;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public GardenFacts now() {
        PlotMenuSnapshot menu = PlotMenuReader.current();
        Minecraft minecraft = client.get();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return new GardenFacts(GardenFacts.UNKNOWN, Map.of(), menu);
        }
        List<String> sidebar = ClientUtils.getSidebarLines();
        if (!onGarden(sidebar)) {
            return new GardenFacts(GardenFacts.UNKNOWN, Map.of(), menu);
        }
        int current = PlotSlots.plotAtCell(GardenPlots.gridIndex(minecraft.player.getX()) + 2,
                GardenPlots.gridIndex(minecraft.player.getZ()) + 2);
        Map<Integer, Integer> pests = new HashMap<>();
        for (String plot : PestManager.getInfestedPlotsFromTab(minecraft)) {
            int number = number(plot);
            if (number > 0) {
                pests.put(number, -1);
            }
        }
        // the sidebar counts pests only on the plot you stand on
        for (String line : sidebar) {
            Matcher matcher = SIDEBAR_PESTS.matcher(line);
            if (matcher.find() && number(matcher.group(1)) == current && current > 0) {
                pests.put(current, number(matcher.group(2)));
            }
        }
        return new GardenFacts(current, pests, menu);
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
