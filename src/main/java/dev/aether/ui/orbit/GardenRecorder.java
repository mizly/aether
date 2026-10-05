package dev.aether.ui.orbit;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.UUID;

// keeps what the menu shows of your garden up to date while you play: each plot is photographed whenever it is
// loaded and its picture is old, and the saved spot's blocks are copied whenever you are near it. reads blocks only
public final class GardenRecorder {
    private static final long PLOT_STALE_MS = 45_000L;
    private static final long SPOT_STALE_MS = 300_000L;
    private static final int PLOTS = 25;

    private static int ticks;
    private static boolean garden;
    private static int nextPlot;
    private static SpotSnapshot spot;
    private static UUID spotOwner;
    private static boolean spotLoaded;

    private GardenRecorder() {
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        ticks++;
        if (ticks % 60 == 0) garden = ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;
        if (!garden || ticks % 20 != 0) return;
        // one piece of work per second keeps the cost out of sight: the spot when it is due, else one plot
        if (ticks % 200 == 0 && !(client.screen instanceof OrbitScreen) && spotDue(client)) {
            recordSpot(client);
            return;
        }
        for (int tries = 0; tries < PLOTS; tries++) {
            int plot = nextPlot;
            nextPlot = (nextPlot + 1) % PLOTS;
            if (PlotMiniatures.age(plot) > PLOT_STALE_MS && PlotMiniatures.record(client, plot)) return;
        }
    }

    // called by the settings page right after the spot is saved, while its blocks are certainly loaded
    public static void recordSpotNow() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.player != null) recordSpot(client);
    }

    // the snapshot of the saved spot for whoever is playing, read from disk the first time
    static SpotSnapshot spot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;
        UUID id = client.player.getUUID();
        if (!id.equals(spotOwner) || !spotLoaded) {
            spotOwner = id;
            spotLoaded = true;
            spot = SpotSnapshot.load(file(id));
        }
        return spot != null && spot.centredOn(spotX(), spotY(), spotZ()) ? spot : null;
    }

    private static boolean spotDue(Minecraft client) {
        if (!AetherConfig.ORBIT_SPOT_SET.get()) return false;
        SpotSnapshot current = spot();
        return current == null || System.currentTimeMillis() - current.takenAt > SPOT_STALE_MS;
    }

    private static void recordSpot(Minecraft client) {
        if (!AetherConfig.ORBIT_SPOT_SET.get()) return;
        SpotSnapshot taken = SpotSnapshot.capture(client.level, spotX(), spotY(), spotZ());
        if (taken == null) return;
        spot = taken;
        spotOwner = client.player.getUUID();
        spotLoaded = true;
        try {
            taken.save(file(spotOwner));
        } catch (Exception e) {
            System.err.println("[Aether] could not save the menu spot: " + e.getMessage());
        }
    }

    private static Path file(UUID player) {
        return GardenMemory.dir(player).resolve("spot.bin");
    }

    private static int spotX() {
        return (int) Math.floor(AetherConfig.ORBIT_SPOT_X.get());
    }

    private static int spotY() {
        return (int) Math.floor(AetherConfig.ORBIT_SPOT_Y.get() + 1e-3);
    }

    private static int spotZ() {
        return (int) Math.floor(AetherConfig.ORBIT_SPOT_Z.get());
    }
}
