package dev.aether.ui.orbit;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.UUID;

// where what the menu remembers of a player's garden lives on disk: the plot pictures
final class GardenMemory {
    private GardenMemory() {
    }

    static Path dir(UUID player) {
        return FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("garden").resolve(player.toString());
    }
}
