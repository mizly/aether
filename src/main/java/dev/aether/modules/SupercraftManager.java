package dev.aether.modules;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.List;
import java.util.Locale;

public final class SupercraftManager {
    private static final int SLOT_OPEN_SUPERCRAFT = 10;
    private static final int SLOT_CRAFT_AMOUNT = 32;
    private static final long ACTION_DELAY_MS = 500L;
    private static final long SHIFT_TO_CRAFT_CLICK_DELAY_MS = 500L;
    private static final long GUI_OPEN_DELAY_MS = 550L;
    private static final long NEXT_ITEM_DELAY_MS = 500L;
    private static final long STALL_TIMEOUT_MS = 5_000L;

    private static volatile boolean running = false;
    private static volatile boolean autoSequence = false;
    private static volatile long lastAutoRunCompletedMs = System.currentTimeMillis();
    private static volatile Runnable completionCallback = null;
    private static volatile boolean resumeFarmingOnFinish = false;

    private static volatile int currentItemIndex = 0;
    private static volatile int craftingStage = 0;
    private static volatile long nextActionAtMs = 0L;
    private static volatile long guiOpenedAtMs = 0L;
    private static volatile long lastProgressAtMs = 0L;

    private SupercraftManager() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void reset() {
        running = false;
        autoSequence = false;
        completionCallback = null;
        resumeFarmingOnFinish = false;
        currentItemIndex = 0;
        craftingStage = 0;
        nextActionAtMs = 0L;
        guiOpenedAtMs = 0L;
        lastProgressAtMs = 0L;
    }

    public static long getAutoSupercraftElapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - lastAutoRunCompletedMs);
    }

    public static boolean shouldRunAutoSupercraft() {
        if (!AetherConfig.AUTO_SUPERCRAFT.get() || running) {
            return false;
        }

        if (getConfiguredItems().isEmpty()) {
            return false;
        }

        long intervalMs = Math.max(1L, AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.get()) * 60_000L;
        return getAutoSupercraftElapsedMs() >= intervalMs;
    }

    public static void runAutoSupercraftIfDue(Runnable onComplete) {
        if (!shouldRunAutoSupercraft()) {
            runCompletion(onComplete);
            return;
        }

        autoSequence = true;
        completionCallback = onComplete;
        start(false);
    }

    public static void update() {
        if (!running) {
            return;
        }

        long lastProgress = lastProgressAtMs;
        if (lastProgress <= 0L) {
            markProgress();
            return;
        }

        long stalledMs = System.currentTimeMillis() - lastProgress;
        if (stalledMs < STALL_TIMEOUT_MS) {
            return;
        }

        failAndContinue("stalled for " + stalledMs + "ms");
    }

    public static void manualTrigger() {
        start(true);
    }

    private static synchronized void start(boolean manual) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            runCompletionIfAuto();
            return;
        }

        if (running) {
            ClientUtils.sendMessage("\u00A7cAuto Supercraft is already running.", false);
            runCompletionIfAuto();
            return;
        }

        List<String> items = getConfiguredItems();
        if (items.isEmpty()) {
            ClientUtils.sendMessage("\u00A7cNo supercraft items configured.", false);
            runCompletionIfAuto();
            return;
        }

        resumeFarmingOnFinish = manual && MacroStateManager.getCurrentState() == MacroState.State.FARMING;
        if (resumeFarmingOnFinish) {
            client.execute(() -> FarmingMacroManager.disable(client));
        }

        running = true;
        currentItemIndex = 0;
        craftingStage = 0;
        nextActionAtMs = 0L;
        guiOpenedAtMs = 0L;
        markProgress();

        ClientUtils.sendMessage("\u00A7eStarting Auto Supercraft...", false);
        sendRecipeCommand();
    }

    public static void handleRecipeGui(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (!running || client == null || client.player == null) {
            return;
        }

        List<String> items = getConfiguredItems();
        if (currentItemIndex < 0 || currentItemIndex >= items.size()) {
            finish();
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) {
            return;
        }

        String currentItem = items.get(currentItemIndex);
        String normalizedCurrentItem = currentItem.toLowerCase(Locale.ROOT);
        String title = TablistUtils.stripColors(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (!title.contains(normalizedCurrentItem)) {
            guiOpenedAtMs = 0L;
            return;
        }

        if (guiOpenedAtMs == 0L) {
            guiOpenedAtMs = now;
            markProgress(now);
        }
        if (now - guiOpenedAtMs < GUI_OPEN_DELAY_MS) {
            return;
        }

        if (screen.getMenu().slots.size() <= SLOT_CRAFT_AMOUNT) {
            return;
        }

        switch (craftingStage) {
            case 0 -> {
                ClientUtils.sendDebugMessage("Supercraft: opening supercraft for " + currentItem);
                ClientUtils.performSlotClick(client, screen, SLOT_OPEN_SUPERCRAFT, 0, ContainerInput.PICKUP);
                markProgress(now);
                nextActionAtMs = now + ACTION_DELAY_MS;
                craftingStage = 1;
            }
            case 1 -> {
                ClientUtils.sendDebugMessage("Supercraft: maximizing craft amount for " + currentItem);
                ClientUtils.performSlotClick(client, screen, SLOT_CRAFT_AMOUNT, 0, ContainerInput.QUICK_MOVE);
                markProgress(now);
                nextActionAtMs = now + SHIFT_TO_CRAFT_CLICK_DELAY_MS;
                craftingStage = 2;
            }
            case 2 -> {
                ClientUtils.sendDebugMessage("Supercraft: crafting " + currentItem);
                ClientUtils.performSlotClick(client, screen, SLOT_CRAFT_AMOUNT, 0, ContainerInput.PICKUP);
                markProgress(now);
                nextActionAtMs = now + ACTION_DELAY_MS;
                craftingStage = 3;
            }
            case 3 -> advanceToNextItem();
            default -> {
            }
        }
    }

    private static void advanceToNextItem() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            if (client.player != null) {
                client.player.closeContainer();
            }
        });

        List<String> items = getConfiguredItems();
        currentItemIndex++;
        craftingStage = 0;
        guiOpenedAtMs = 0L;
        nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
        markProgress();

        if (currentItemIndex >= items.size()) {
            finish();
            return;
        }

        MacroWorkerThread.getInstance().submit("AutoSupercraft-Next", () -> {
            MacroWorkerThread.sleep((int) NEXT_ITEM_DELAY_MS);
            if (shouldAbort()) {
                return;
            }
            sendRecipeCommand();
        });
    }

    private static void sendRecipeCommand() {
        Minecraft client = Minecraft.getInstance();
        List<String> items = getConfiguredItems();
        if (currentItemIndex < 0 || currentItemIndex >= items.size()) {
            finish();
            return;
        }

        String item = items.get(currentItemIndex);
        ClientUtils.sendDebugMessage("Supercraft: sending /recipe " + item);
        markProgress();
        ClientUtils.sendCommand(client, "/recipe " + item);
    }

    private static void finish() {
        Minecraft client = Minecraft.getInstance();
        boolean shouldResumeFarming = resumeFarmingOnFinish;
        if (autoSequence) {
            lastAutoRunCompletedMs = System.currentTimeMillis();
        }

        Runnable callback = completionCallback;
        reset();
        runCompletion(callback);

        if (shouldResumeFarming && client != null && MacroStateManager.isMacroRunning()) {
            client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
        }

        if (client != null && client.player != null) {
            ClientUtils.sendMessage("\u00A7aAuto Supercraft finished.", false);
        }
    }

    private static synchronized void failAndContinue(String reason) {
        if (!running) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        ClientUtils.sendDebugMessage("Supercraft: " + reason + ". Continuing handoff.");
        boolean shouldResumeFarming = resumeFarmingOnFinish;
        if (autoSequence) {
            lastAutoRunCompletedMs = System.currentTimeMillis();
        }

        Runnable callback = completionCallback;
        reset();

        if (client != null) {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.closeContainer();
                }
            });
        }

        runCompletion(callback);

        if (shouldResumeFarming && client != null && MacroStateManager.isMacroRunning()) {
            client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
        }
    }

    private static void markProgress() {
        markProgress(System.currentTimeMillis());
    }

    private static void markProgress(long now) {
        lastProgressAtMs = now;
    }

    private static void runCompletionIfAuto() {
        Runnable callback = completionCallback;
        completionCallback = null;
        autoSequence = false;
        runCompletion(callback);
    }

    private static void runCompletion(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private static List<String> getConfiguredItems() {
        return AetherConfig.AUTO_SUPERCRAFT_ITEMS.get().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static boolean shouldAbort() {
        Minecraft client = Minecraft.getInstance();
        return !running || client == null || client.player == null || client.level == null;
    }
}
