package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.config.entries.StringEntry;
import dev.aether.modules.movement.MovementPlaybackManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class FailsafeCustomReplayManager {
    public static final String RANDOM_LOCAL_OPTION = "Random (Local)";
    public static final String RANDOM_GLOBAL_OPTION = "Random (Global)";
    private static final String LEGACY_RANDOM_OPTION = "Random";

    private static final Path REPLAY_ROOT = FabricLoader.getInstance().getConfigDir()
            .resolve("aether")
            .resolve("movement")
            .resolve("failsafes");

    private FailsafeCustomReplayManager() {
    }

    public enum FailsafeReplayType {
        INVENTORY_SLOT("inventory_slot", AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_CUSTOM_REPLAY),
        GUI_OPENED("gui_opened", AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_CUSTOM_REPLAY),
        BPS("bps", AetherConfig.FAILSAFE_BPS_CUSTOM_REPLAY),
        GHOST_BLOCK("ghost_block", AetherConfig.FAILSAFE_GHOST_BLOCK_CUSTOM_REPLAY),
        DIRT_CHECK("dirt_check", AetherConfig.FAILSAFE_DIRT_CHECK_CUSTOM_REPLAY),
        ROTATION("rotation", AetherConfig.FAILSAFE_ROTATION_CUSTOM_REPLAY),
        PEST_ROTATION("pest_rotation", AetherConfig.FAILSAFE_PEST_ROTATION_CUSTOM_REPLAY),
        WORLD_CHANGE("world_change", AetherConfig.FAILSAFE_WORLD_CHANGE_CUSTOM_REPLAY);

        private final String folderName;
        private final StringEntry replayEntry;

        FailsafeReplayType(String folderName, StringEntry replayEntry) {
            this.folderName = folderName;
            this.replayEntry = replayEntry;
        }
    }

    public static void playConfiguredReplay(Minecraft client, FailsafeReplayType type) {
        if (type == null) {
            return;
        }

        String selected = sanitizeReplayName(type.replayEntry.get());
        if (isRandomGlobalOption(selected)) {
            List<Path> replays = getAvailableGlobalReplays();
            if (replays.isEmpty()) {
                ClientUtils.sendMessage(client, "\u00A7c" + AetherLang.localize("No custom failsafe replays found."), false);
                return;
            }

            Path replay = replays.get(ThreadLocalRandom.current().nextInt(replays.size()));
            MovementPlaybackManager.playFromDirectory(client, replay.getParent(), replay.getFileName().toString());
            return;
        }

        List<String> replays = getAvailableReplays(type);
        if (replays.isEmpty()) {
            ClientUtils.sendMessage(client, "\u00A7c" + AetherLang.localize("No custom failsafe replays found."), false);
            return;
        }

        String replay = isRandomLocalOption(selected)
                ? replays.get(ThreadLocalRandom.current().nextInt(replays.size()))
                : selected;
        if (!replays.contains(replay)) {
            replay = replays.getFirst();
            type.replayEntry.set(replay);
            AetherConfig.save();
        }

        MovementPlaybackManager.playFromDirectory(client, getReplayDirectory(type), replay);
    }

    public static List<String> getAvailableReplayOptions(FailsafeReplayType type) {
        List<String> options = new java.util.ArrayList<>();
        options.add(RANDOM_LOCAL_OPTION);
        options.add(RANDOM_GLOBAL_OPTION);
        options.addAll(getAvailableReplays(type));
        return options;
    }

    public static int getSelectedReplayIndex(FailsafeReplayType type, List<String> options) {
        String selected = sanitizeReplayName(type.replayEntry.get());
        if (LEGACY_RANDOM_OPTION.equalsIgnoreCase(selected)) {
            return 0;
        }
        int index = options.indexOf(selected);
        return index >= 0 ? index : 0;
    }

    public static void setSelectedReplay(FailsafeReplayType type, List<String> options, int index) {
        if (index < 0 || index >= options.size()) {
            return;
        }
        type.replayEntry.set(options.get(index));
        AetherConfig.save();
    }

    public static void refreshReplayOptions(FailsafeReplayType type, List<String> options) {
        options.clear();
        options.addAll(getAvailableReplayOptions(type));
    }

    public static void openReplayFolder(FailsafeReplayType type) {
        Path directory = getReplayDirectory(type);
        try {
            Files.createDirectories(directory);
            if (isWindows()) {
                new ProcessBuilder("explorer.exe", directory.toAbsolutePath().toString()).start();
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(directory.toFile());
            }
        } catch (IOException e) {
            System.err.println("[Aether] Failed to open failsafe replay folder: " + e.getMessage());
        }
    }

    private static List<String> getAvailableReplays(FailsafeReplayType type) {
        return MovementPlaybackManager.listReplayFiles(getReplayDirectory(type));
    }

    private static List<Path> getAvailableGlobalReplays() {
        if (!Files.isDirectory(REPLAY_ROOT)) {
            return List.of();
        }

        try (var files = Files.walk(REPLAY_ROOT)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted((left, right) -> REPLAY_ROOT.relativize(left).toString()
                            .compareToIgnoreCase(REPLAY_ROOT.relativize(right).toString()))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static Path getReplayDirectory(FailsafeReplayType type) {
        return REPLAY_ROOT.resolve(type.folderName);
    }

    private static String sanitizeReplayName(String name) {
        if (name == null || name.isBlank()) {
            return RANDOM_LOCAL_OPTION;
        }
        String fileName = Path.of(name).getFileName().toString();
        if (isRandomLocalOption(fileName) || isRandomGlobalOption(fileName) || LEGACY_RANDOM_OPTION.equalsIgnoreCase(fileName)) {
            return fileName;
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(".json")
                ? fileName
                : fileName + ".json";
    }

    private static boolean isRandomLocalOption(String name) {
        return RANDOM_LOCAL_OPTION.equalsIgnoreCase(name) || LEGACY_RANDOM_OPTION.equalsIgnoreCase(name);
    }

    private static boolean isRandomGlobalOption(String name) {
        return RANDOM_GLOBAL_OPTION.equalsIgnoreCase(name);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
