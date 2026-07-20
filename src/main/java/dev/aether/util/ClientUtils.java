package dev.aether.util;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.mixin.AccessorAbstractContainerScreen;
import dev.aether.mixin.AccessorKeyMapping;
import dev.aether.mixin.MixinMinecraft;
import dev.aether.macro.MacroState;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.visuals.StreamerModeManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientUtils {
    private static final int JACOBS_CONTEST_START_MINUTE_UTC = 15;
    private static final int JACOBS_CONTEST_END_MINUTE_UTC = 35;
    private static final Object SIDEBAR_CACHE_LOCK = new Object();
    private static final Pattern STRIP_SCOREBOARD_FORMATTING = Pattern.compile("(?i)\u00A7[0-9A-FK-ORZ]");
    private static final Pattern STRIP_MINECRAFT_FORMATTING = Pattern.compile("(?i)\u00A7[0-9A-FK-ORZ]");
    private static final DateTimeFormatter DEBUG_LOG_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm'.txt'");
    private static final DateTimeFormatter DEBUG_LOG_LINE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object DEBUG_LOG_LOCK = new Object();
    private static final Path DEBUG_LOG_FILE = createDebugLogFile();
    private static int sidebarCacheTick = Integer.MIN_VALUE;
    private static int sidebarCacheConnectionId = 0;
    private static List<String> sidebarCacheLines = List.of();
    private static final Object COMMAND_QUEUE_LOCK = new Object();
    private static final ScheduledExecutorService COMMAND_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aether-command-dispatch");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService INPUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aether-input-dispatch");
        thread.setDaemon(true);
        return thread;
    });
    private static long nextCommandTime = 0;
    private static final long COMMAND_COOLDOWN_MS = 250;
    private static final long INPUT_CLICK_HOLD_MIN_MS = 80L;
    private static final long INPUT_CLICK_HOLD_JITTER_MS = 60L;

    private static long randomizedClickHoldMs() {
        return INPUT_CLICK_HOLD_MIN_MS + ThreadLocalRandom.current().nextLong(INPUT_CLICK_HOLD_JITTER_MS + 1);
    }
    private static final String USER_MESSAGE_PREFIX = "\u00A7c\u00A7lAether >> \u00A77";
    private static final AtomicLong USE_CLICK_SEQUENCE = new AtomicLong();
    private static final AtomicLong ATTACK_CLICK_SEQUENCE = new AtomicLong();

    public static void sendMessage(String message) {
        sendMessage(message, false);
    }

    public static void sendMessage(String message, boolean overlay) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        if (StreamerModeManager.isEnabled()) {
            return;
        }

        String normalized = normalizeUserMessage(AetherLang.localize(message));
        client.execute(() -> {
            if (client.player != null) {
                if (overlay) {
                    client.player.sendOverlayMessage(Component.literal(USER_MESSAGE_PREFIX + normalized));
                } else {
                    client.player.sendSystemMessage(Component.literal(USER_MESSAGE_PREFIX + normalized));
                }
            }
        });
    }

    public static void sendDebugMessage(String message) {
        String normalized = normalizeDebugMessage(AetherLang.localize(message));
        writeDebugLine(normalized);
    }

    public static String formatElapsedMs(long startTimeMs, long nowMs) {
        if (startTimeMs <= 0L) {
            return "0ms";
        }
        return Math.max(0L, nowMs - startTimeMs) + "ms";
    }

    private static String normalizeUserMessage(String message) {
        if (message == null) {
            return "";
        }

        String normalized = message
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Aether\\]\\s*", "")
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*Aether\\s*>>\\s*(?:\u00A7[0-9A-FK-OR])*", "")
                .trim();

        return normalized;
    }

    private static String normalizeDebugMessage(String message) {
        if (message == null) {
            return "";
        }

        String withoutPrefixes = message
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Debug\\]\\s*", "")
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Aether\\]\\s*", "")
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*Aether\\s*>>\\s*(?:\u00A7[0-9A-FK-OR])*", "")
                .trim();
        return STRIP_MINECRAFT_FORMATTING.matcher(withoutPrefixes).replaceAll("").trim();
    }

    private static Path createDebugLogFile() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("aether")
                .resolve("logs")
                .resolve(LocalDateTime.now().format(DEBUG_LOG_FILE_FORMAT));
    }

    private static void writeDebugLine(String message) {
        synchronized (DEBUG_LOG_LOCK) {
            try {
                Path parent = DEBUG_LOG_FILE.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        DEBUG_LOG_FILE,
                        "[" + LocalTime.now().format(DEBUG_LOG_LINE_TIME_FORMAT) + "] " + message + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[Aether] Failed to write debug log: " + e.getMessage());
            }
        }
    }

    public static void sendCommand(String cmd) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || cmd == null || cmd.isBlank()) {
            return;
        }

        long delayMs;
        synchronized (COMMAND_QUEUE_LOCK) {
            long now = System.currentTimeMillis();
            long scheduledTime = Math.max(now, nextCommandTime);
            nextCommandTime = scheduledTime + COMMAND_COOLDOWN_MS;
            delayMs = Math.max(0L, scheduledTime - now);
        }

        COMMAND_EXECUTOR.schedule(() -> client.execute(() -> {
            if (client.player == null || client.getConnection() == null) {
                return;
            }

            if (cmd.startsWith("/")) {
                client.getConnection().sendCommand(cmd.substring(1));
            } else {
                client.getConnection().sendChat(cmd);
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    public static void disconnectWithScreen(Screen screen, Component reason) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            ClientPacketListener connection = client.getConnection();
            if (connection != null) {
                connection.getConnection().disconnect(reason);
            }

            client.disconnect(screen, false);
        });
    }

    public static void forceReleaseKeys() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        if (client.options != null) {
            releaseKeyMapping(client.options.keyUp);
            releaseKeyMapping(client.options.keyDown);
            releaseKeyMapping(client.options.keyLeft);
            releaseKeyMapping(client.options.keyRight);
            releaseKeyMapping(client.options.keyJump);
            releaseKeyMapping(client.options.keyShift);
            releaseKeyMapping(client.options.keySprint);
            releaseKeyMapping(client.options.keyAttack);
            releaseKeyMapping(client.options.keyUse);
            ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false);
            restorePhysicalKeyStates(client);
        }

        if (client.mouseHandler != null) {
            client.mouseHandler.releaseMouse();
        }
    }

    public static boolean isInventoryScreenOpen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.screen instanceof AbstractContainerScreen<?>;
    }

    public static void forceReleaseMovementKeys() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        releaseKeyMapping(client.options.keyUp);
        releaseKeyMapping(client.options.keyDown);
        releaseKeyMapping(client.options.keyLeft);
        releaseKeyMapping(client.options.keyRight);
        releaseKeyMapping(client.options.keyJump);
        releaseKeyMapping(client.options.keyShift);
        releaseKeyMapping(client.options.keySprint);
    }

    public static MacroState.Location getCurrentLocation() {
        Minecraft client = Minecraft.getInstance();
        return getCurrentLocation(client);
    }

    private static MacroState.Location getCurrentLocation(Minecraft client) {
        if (client.level == null || client.player == null)
            return MacroState.Location.UNKNOWN;

        if (!client.isSameThread()) {
            CompletableFuture<MacroState.Location> future = new CompletableFuture<>();
            client.execute(() -> {
                future.complete(getCurrentLocation(client));
            });
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                return MacroState.Location.UNKNOWN;
            }
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard != null
                ? scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
                : null;

        if (sidebar == null)
            return MacroState.Location.LIMBO;

        boolean hasLobbyItems = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty()) {
                String itemName = TablistUtils.stripColors(stack.getHoverName().getString()).trim();
                if (itemName.contains("Game Menu") || itemName.contains("My Profile")) {
                    hasLobbyItems = true;
                    break;
                }
            }
        }

        if (hasLobbyItems) {
            return MacroState.Location.LOBBY;
        }

        String areaLine = TablistUtils.findLine(client, "Area:");
        if (areaLine != null) {
            if (areaLine.contains("Area: Garden"))
                return MacroState.Location.GARDEN;
            if (areaLine.contains("Area: Crystal Hollows"))
                return MacroState.Location.CRYSTAL_HOLLOWS;
            return MacroState.Location.HUB;
        }

        return MacroState.Location.HUB;
    }

    public static boolean isSupportedHudArea() {
        Minecraft client = Minecraft.getInstance();
        return isSupportedHudArea(getCurrentLocation(client));
    }

    public static boolean isSupportedHudArea(MacroState.Location location) {
        return location == MacroState.Location.GARDEN || location == MacroState.Location.CRYSTAL_HOLLOWS;
    }

    public static long getJacobsContestRemainingMs() {
        LocalTime utcTime = LocalTime.now(ZoneOffset.UTC);
        int minute = utcTime.getMinute();
        if (minute < JACOBS_CONTEST_START_MINUTE_UTC || minute >= JACOBS_CONTEST_END_MINUTE_UTC) {
            return 0;
        }

        LocalTime contestEnd = utcTime.withMinute(JACOBS_CONTEST_END_MINUTE_UTC).withSecond(0).withNano(0);
        return Duration.between(utcTime, contestEnd).toMillis();
    }

    public static long getPurse() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null)
            return 0;

        Scoreboard scoreboard = client.level.getScoreboard();
        if (scoreboard == null)
            return 0;

        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null)
            return 0;

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        for (PlayerScoreEntry entry : scores) {
            String entryName = entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entryName);
            String fullText = entryName;
            if (team != null) {
                fullText = team.getPlayerPrefix().getString() + entryName + team.getPlayerSuffix().getString();
            }
            String line = fullText.replaceAll("(?i)\\u00A7[0-9A-FK-ORZ]", "").replaceAll(",", "").trim();

            if (line.contains("Purse:")) {
                try {
                    String valuePart = line.split("Purse:")[1].trim();
                    // Handle "26,000,000 (+300)" by taking only the first part before any space
                    String mainBalance = valuePart.split(" ")[0].replaceAll("[^0-9]", "");
                    return Long.parseLong(mainBalance);
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    public static String getCurrentPlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null)
            return "Unknown";

        Scoreboard scoreboard = client.level.getScoreboard();
        if (scoreboard == null)
            return "Unknown";

        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null)
            return "Unknown";

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        for (PlayerScoreEntry entry : scores) {
            String entryName = entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entryName);
            String fullText = entryName;
            if (team != null) {
                fullText = team.getPlayerPrefix().getString() + entryName + team.getPlayerSuffix().getString();
            }
            String line = fullText.replaceAll("(?i)\u00A7.", "").trim();

            // Match formats: "Plot: 14", "Plot - 6", "Plot #14", "Plot: Barn"
            // Handles non-standard color codes like \u00A7y and multiple spaces.
            if (line.toLowerCase().contains("plot")) {
                Pattern p = Pattern.compile("plot\\s*[:\\-#]\\s*([a-z0-9]+)",
                        Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(line);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        }

        sendDebugMessage("Failed to find Plot in Scoreboard. Lines found:");
        for (PlayerScoreEntry entry : scores) {
            String entryName = entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entryName);
            String fullText = entryName;
            if (team != null) {
                fullText = team.getPlayerPrefix().getString() + entryName + team.getPlayerSuffix().getString();
            }
            sendDebugMessage(" - " + fullText.replaceAll("(?i)\u00A7[0-9A-FK-ORZ]", ""));
        }

        return "Unknown";
    }

    public static List<String> getSidebarLines() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }

        if (!client.isSameThread()) {
            CompletableFuture<List<String>> future = new CompletableFuture<>();
            client.execute(() -> future.complete(getSidebarLines()));
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                return List.of();
            }
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard != null ? scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) : null;
        if (sidebar == null || client.getConnection() == null) {
            return List.of();
        }

        int tick = client.player.tickCount;
        int connectionId = System.identityHashCode(client.getConnection());
        synchronized (SIDEBAR_CACHE_LOCK) {
            if (tick == sidebarCacheTick && connectionId == sidebarCacheConnectionId) {
                return sidebarCacheLines;
            }
        }

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        List<String> lines = new ArrayList<>(scores.size());
        for (PlayerScoreEntry entry : scores) {
            String entryName = entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entryName);
            String fullText = entryName;
            if (team != null) {
                fullText = team.getPlayerPrefix().getString() + entryName + team.getPlayerSuffix().getString();
            }
            lines.add(STRIP_SCOREBOARD_FORMATTING.matcher(fullText).replaceAll("").trim());
        }

        List<String> cachedLines = List.copyOf(lines);
        synchronized (SIDEBAR_CACHE_LOCK) {
            sidebarCacheTick = tick;
            sidebarCacheConnectionId = connectionId;
            sidebarCacheLines = cachedLines;
            return sidebarCacheLines;
        }
    }

    public static boolean hasLineOfSight(Player player, Vec3 target) {
        if (player.level() == null)
            return false;
        Vec3 eyePos = player.getEyePosition();
        ClipContext context = new ClipContext(
                eyePos, target,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player);
        BlockHitResult result = player.level().clip(context);
        return result.getType() == HitResult.Type.MISS;
    }

    public static void lookAt(Player player, Vec3 target) {
        RotationUtils.Rotation rot = RotationUtils
                .calculateLookAt(player.getEyePosition(), target);
        RotationUtils.Rotation adjustedRot = RotationUtils.getAdjustedEnd(
                new RotationUtils.Rotation(player.getYRot(), player.getXRot()),
                rot);
        player.setYRot(adjustedRot.yaw);
        player.setXRot(adjustedRot.pitch);
        FailsafeManager.expectRotation(adjustedRot.yaw, adjustedRot.pitch);
    }

    public static void sleepRandom(int min, int max) throws InterruptedException {
        long sleepTime = min + (long) (Math.random() * (max - min + 1));
        Thread.sleep(sleepTime);
    }

    public static void waitForGearAndGui() {
        Minecraft client = Minecraft.getInstance();
        try {
            long loadoutStart = System.currentTimeMillis();
            while (LoadoutManager.isSwappingLoadout
                    && System.currentTimeMillis() - loadoutStart < 6000) {
                Thread.sleep(50);
            }
            if (LoadoutManager.isSwappingLoadout) {
                sendDebugMessage(
                        "\u00A7eWARNING: Loadout swap detection timeout. Force-completing and resuming sequence...");
                LoadoutManager.forceLoadoutCompletionFailsafe(client);
            }

            long guiStart = System.currentTimeMillis();
            while (client.screen != null && System.currentTimeMillis() - guiStart < 5000) {
                Thread.sleep(50);
            }

            // Small safety delay after GUI is gone - reduced from 250ms
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
    }

    public static void waitForWardrobeGui() {
        Minecraft client = Minecraft.getInstance();
        try {
            long start = System.currentTimeMillis();
            long lastRetry = start;
            int retryCount = 0;
            while (!LoadoutManager.loadoutGuiDetected
                    && System.currentTimeMillis() - start < 5000) {
                if (!LoadoutManager.isSwappingLoadout)
                    return;

                long now = System.currentTimeMillis();
                if (now - lastRetry >= 500) {
                    retryCount++;
                    sendDebugMessage(
                            "Loadout GUI not detected after " + (now - start)
                                    + "ms. Retrying /loadout (" + retryCount + ")");
                    client.execute(() -> sendCommand("/loadout"));
                    lastRetry = now;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException ignored) {
        }
    }

    public static boolean waitForYChange(double startY, long timeoutMs) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return false;

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            double currentY = client.player.getY();
            if (Math.abs(currentY - startY) > 0.1) {
                sendDebugMessage("AOTV Y-change detected: " + String.format("%.2f", startY) + " -> " + String.format("%.2f", currentY) + " (+" + (System.currentTimeMillis() - startTime) + "ms)");
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        sendDebugMessage("AOTV Y-change timeout after " + timeoutMs + "ms. startY=" + String.format("%.2f", startY) + " currentY=" + String.format("%.2f", client.player.getY()));
        return false;
    }

    public static int findAspectOfTheVoidSlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty()) {
                String itemName = stack.getHoverName().getString().replaceAll("\u00A7[0-9a-fk-or]", "").trim();
                String lowercaseName = itemName.toLowerCase();
                if (lowercaseName.contains("aspect of the void") || lowercaseName.contains("aspect of the end")) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static void performShiftRightClick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options == null)
            return;

        pressAndReleaseInput(client, USE_CLICK_SEQUENCE,
                () -> {
                    setKeyMappingState(client.options.keyShift, true);
                    setKeyMappingState(client.options.keyUse, true);
                },
                () -> {
                    setKeyMappingState(client.options.keyUse, false);
                    setKeyMappingState(client.options.keyShift, false);
                },
                randomizedClickHoldMs());
    }

    /**thismake
     * Holds shift, fires one swing packet, and returns - shift is NOT released.
     * The caller must call releaseShiftKey() when done (e.g. after the GUI opens).
     */
    public static void performShiftLeftClick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null)
            return;

        client.execute(() -> {
            if (client.player != null) client.player.setShiftKeyDown(true);
            setKeyMappingState(client.options.keyShift, true);
        });

        if (!client.isSameThread()) {
            try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        }

        client.execute(() -> {
            if (client.player == null) return;
            ((MixinMinecraft) client).aether$startAttack();
        });
    }

    public static void releaseShiftKey() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;
        client.execute(() -> {
            setKeyMappingState(client.options.keyShift, false);
            if (client.player != null) client.player.setShiftKeyDown(false);
        });
    }

    public static void performUseClick() {
        performUseClick(null);
    }

    public static void performUseClick(Runnable beforePress) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        pressAndReleaseInput(client, USE_CLICK_SEQUENCE,
                () -> {
                    if (beforePress != null) {
                        beforePress.run();
                    }
                    setKeyMappingState(client.options.keyUse, true);
                },
                () -> setKeyMappingState(client.options.keyUse, false),
                randomizedClickHoldMs());
    }

    public static void performAttackClickDirect() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (client.player == null) return;
            ((MixinMinecraft) client).aether$startAttack();
        });
    }

    public static void performAttackClick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        if (UngrabMouse.isMouseUngrabbed() && client.screen == null) {
            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                ((MixinMinecraft) client).aether$startAttack();
            });
            return;
        }

        pressAndReleaseInput(client, ATTACK_CLICK_SEQUENCE,
                () -> setKeyMappingState(client.options.keyAttack, true),
                () -> setKeyMappingState(client.options.keyAttack, false),
                randomizedClickHoldMs());
    }

    /**
     * Simulates clicking a slot in an open container screen.
     * Routes through the screen's slotClicked handler rather than calling
     * gameMode.handleInventoryMouseClick directly.
     */
    public static void performSlotClick(AbstractContainerScreen<?> screen, int slotIndex, int mouseButton, ContainerInput type) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || screen.getMenu() == null) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) return;
        Slot slot = slots.get(slotIndex);
        ((AccessorAbstractContainerScreen) screen).invokeSlotClicked(slot, slot.index, mouseButton, type);
    }

    public static long getGuiClickDelayMs(boolean firstClick) {
        return firstClick
                ? ConfigHelpers.getRandomizedDelay(
                        AetherConfig.GUI_FIRST_CLICK_DELAY_MIN.get(),
                        AetherConfig.GUI_FIRST_CLICK_DELAY_MAX.get())
                : ConfigHelpers.getRandomizedDelay(
                        AetherConfig.GUI_CLICK_DELAY_MIN.get(),
                        AetherConfig.GUI_CLICK_DELAY_MAX.get());
    }

    public static void performHotbarSlotClick(int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null || slot < 0 || slot >= client.options.keyHotbarSlots.length) {
            return;
        }

        if (client.isSameThread()) {
            clickKeyMapping(client.options.keyHotbarSlots[slot]);
            return;
        }

        client.execute(() -> clickKeyMapping(client.options.keyHotbarSlots[slot]));
    }

    public static void setKeyMappingState(KeyMapping mapping, boolean down) {
        if (mapping == null) {
            return;
        }

        mapping.setDown(down);
    }

    /**
     * Re-presses keys the macro is holding programmatically. Vanilla {@code grabMouse()}
     * calls {@code KeyMapping.setAll()}, which resets bindings to the physical key state
     * and would drop macro-held keys until the macro's next tick.
     */
    public static void reapplyProgrammaticKeyStates(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        reapplyProgrammaticKey(client.options.keyLeft);
        reapplyProgrammaticKey(client.options.keyRight);
        reapplyProgrammaticKey(client.options.keyUp);
        reapplyProgrammaticKey(client.options.keyDown);
        reapplyProgrammaticKey(client.options.keyShift);
        reapplyProgrammaticKey(client.options.keySprint);
        reapplyProgrammaticKey(client.options.keyJump);
        if (ProgrammaticAttackTracker.isHeld()) {
            setKeyMappingState(client.options.keyAttack, true);
        }
    }

    private static void reapplyProgrammaticKey(KeyMapping mapping) {
        if (ProgrammaticMovementTracker.isDown(mapping)) {
            setKeyMappingState(mapping, true);
        }
    }

    private static void releaseKeyMapping(KeyMapping mapping) {
        setKeyMappingState(mapping, false);
        ProgrammaticMovementTracker.clear(mapping);
    }

    private static void restorePhysicalKeyStates(Minecraft client) {
        if (client == null) {
            return;
        }

        if (client.isSameThread()) {
            KeyMapping.setAll();
            return;
        }

        client.execute(KeyMapping::setAll);
    }

    public static void clickKeyMapping(KeyMapping mapping) {
        if (mapping == null) {
            return;
        }

        InputConstants.Key key = getBoundKey(mapping);
        if (key != null && key != InputConstants.UNKNOWN) {
            KeyMapping.click(key);
        }
    }

    private static void pressAndReleaseInput(
            Minecraft client,
            AtomicLong sequence,
            Runnable pressAction,
            Runnable releaseAction,
            long holdMs
    ) {
        long clickId = sequence.incrementAndGet();

        if (client.isSameThread()) {
            pressAction.run();
            scheduleClientAction(client, holdMs, () -> {
                if (sequence.get() == clickId) {
                    releaseAction.run();
                }
            });
            return;
        }

        client.execute(pressAction);

        try {
            Thread.sleep(holdMs);
        } catch (InterruptedException ignored) {
        }

        client.execute(() -> {
            if (sequence.get() == clickId) {
                releaseAction.run();
            }
        });
    }

    private static void scheduleClientAction(Minecraft client, long delayMs, Runnable action) {
        INPUT_EXECUTOR.schedule(() -> client.execute(action), delayMs, TimeUnit.MILLISECONDS);
    }

    private static InputConstants.Key getBoundKey(KeyMapping mapping) {
        return ((AccessorKeyMapping) mapping).getKey();
    }

    public static void waitForRotationToComplete(float targetPitch, int rotationTime) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;

        long startTime = System.currentTimeMillis();
        long timeout = 5000; // 5 second timeout

        while (System.currentTimeMillis() - startTime < timeout) {
            float currentPitch = client.player.getXRot();
            float pitchDiff = Math.abs(currentPitch - targetPitch);

            if (pitchDiff < 1.0f) {
                break; // Rotation complete
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
