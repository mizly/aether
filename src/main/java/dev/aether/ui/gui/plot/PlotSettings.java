package dev.aether.ui.gui.plot;

import dev.aether.config.AetherConfig;
import dev.aether.config.RewarpPointPairs;
import dev.aether.config.entries.ListEntry;
import dev.aether.config.entries.StringEntry;
import dev.aether.ui.settings.PlotSetting;
import dev.aether.ui.settings.PlotSetting.EmptyMeaning;
import dev.aether.ui.settings.PlotSetting.Mode;

// the plot settings the providers build, so every one writes bare digits or "barn" and saves on edit
public final class PlotSettings {
    // what single-plot entries keep for "no plot": their teleports skip it, and a blank would send "/plottp "
    public static final String NO_PLOT = "0";

    private PlotSettings() {
    }

    public static PlotSetting list(String name, Mode mode, ListEntry<String> entry, EmptyMeaning emptyMeaning) {
        return new PlotSetting(name, mode, entry::get, values -> {
            entry.set(values);
            AetherConfig.save();
        }).emptyMeaning(emptyMeaning).bind(entry);
    }

    // a teleport target kept in a string entry; empty means do it where the player stands
    public static PlotSetting teleport(String name, StringEntry entry) {
        return PlotSetting.single(name, entry::get, value -> {
                    entry.set(value);
                    AetherConfig.save();
                }, NO_PLOT)
                .allowBarn()
                .emptyMeaning(EmptyMeaning.STAY)
                .bind(entry);
    }

    // visit order matters, and once the menu was read only greenhouse plots can be picked
    public static PlotSetting greenhouses(String name, ListEntry<String> entry) {
        return restrictToGreenhouses(list(name, Mode.ORDERED, entry, EmptyMeaning.NONE)).menuTitle("Greenhouse Plots");
    }

    static PlotSetting restrictToGreenhouses(PlotSetting setting) {
        return setting.restrictTo(token -> {
            PlotMenuSnapshot menu = GardenPlotData.active().now().menu();
            return menu == null || menu.isGreenhouse(token.number());
        }, "Only greenhouse plots can be picked.");
    }

    // the /plottp target of one rewarp pair; it has no "none", so "0" shows as "pick a plot" until one is chosen
    public static PlotSetting rewarpPlot(String name, int pairIndex) {
        return PlotSetting.single(name,
                        () -> RewarpPointPairs.get(pairIndex).plotTpNumber,
                        value -> RewarpPointPairs.update(pairIndex, pair -> pair.plotTpNumber = value),
                        NO_PLOT)
                .allowBarn();
    }
}
