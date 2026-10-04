package dev.aether.ui.gui.plot;

// where the plot picker reads the garden from; bootstrap installs the live source, the preview harness and
// tests install fixed facts. now() is called once per frame, so implementations read their sources there
@FunctionalInterface
public interface GardenPlotData {
    GardenFacts now();

    static GardenPlotData of(GardenFacts facts) {
        return () -> facts;
    }

    static GardenPlotData active() {
        return Active.source;
    }

    static void install(GardenPlotData source) {
        Active.source = source == null ? of(GardenFacts.NONE) : source;
    }

    final class Active {
        private static volatile GardenPlotData source = of(GardenFacts.NONE);

        private Active() {
        }
    }
}
