package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.settings.PlotSetting;

// how plot rows draw their live garden thumbnail and open the plot screen; the orbit menu supplies both
public interface PlotHooks {
    PlotHooks NONE = new PlotHooks() {
    };

    default void paintThumbnail(GuiCanvas canvas, PlotSetting setting, Rect area) {
    }

    // area is the thumbnail in the panel's root units, where the plot screen grows out of
    default void open(PlotSetting setting, Rect area) {
    }
}
