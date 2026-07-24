package dev.aether.modules.experimentation;

import java.util.HashMap;
import java.util.Map;

import dev.aether.config.AetherConfig;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-plays Ultrasequencer. During the show phase the board panes carry their
 * order as a numeric display name; the numbers hide when the timer starts, so
 * the slot for each number is remembered and clicked back in ascending order.
 */
public final class UltrasequencerSolver {

    private static final int BOARD_START = 9;
    private static final int BOARD_END = 44;
    private static final int MAX_XP_CLICKS = 20;
    private static final int REWARD_CAP_CLICKS = 9;

    private static final int ROUND_SETTLE_MS = 450;

    private static final Map<Integer, Integer> orderToSlot = new HashMap<>();
    private static int nextOrdinal = 0;
    private static boolean wasShowPhase = false;
    private static long inputStartAt = 0L;
    private static String lastPhaseKey = "";
    private static long lastClickAt = 0L;
    private static long clickDelay = 0L;
    private static boolean captured = false;
    private static boolean active = false;

    private UltrasequencerSolver() {
    }

    public static void reset() {
        orderToSlot.clear();
        nextOrdinal = 0;
        lastClickAt = 0L;
        captured = false;
        active = false;
        wasShowPhase = false;
        inputStartAt = 0L;
        lastPhaseKey = "";
    }

    public static boolean isGameScreen(AbstractContainerScreen<?> screen) {
        // Tier suffix required: bare "Ultrasequencer" is the stakes menu.
        return screen.getTitle().getString().replaceAll("(?i)§.", "").trim()
                .matches("(?i)ultrasequencer ?\\(.*");
    }

    public static void handleMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!AetherConfig.AUTO_EXPERIMENTS.get() || client.player == null) {
            return;
        }
        if (!isGameScreen(screen)) {
            if (active) {
                reset();
            }
            return;
        }
        if (!active) {
            reset();
            active = true;
            ClientUtils.sendDebugMessage("[Experiments] Ultrasequencer started.");
        }

        logPhaseChange(screen);
        if (!isInputPhase(screen)) {
            wasShowPhase = true;
            captureBoard(screen);
            return;
        }
        if (!captured || orderToSlot.isEmpty()) {
            return;
        }
        if (wasShowPhase) {
            wasShowPhase = false;
            inputStartAt = System.currentTimeMillis();
        }
        // Same settle rule as Chronomatron: never click mid board swap.
        if (System.currentTimeMillis() - inputStartAt < ROUND_SETTLE_MS) {
            return;
        }

        if (nextOrdinal >= orderToSlot.size()) {
            // Round replayed fully; wait for the next show phase (captureBoard
            // rearms) or the end of the game.
            int maxClicks = maxClicks();
            if (orderToSlot.size() >= maxClicks) {
                ClientUtils.sendDebugMessage("[Experiments] Ultrasequencer finished at "
                        + orderToSlot.size() + "/" + maxClicks + ", closing.");
                reset();
                ExperimentUtils.closeScreen();
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickAt < clickDelay) {
            return;
        }
        Integer target = orderToSlot.get(nextOrdinal);
        if (target == null) {
            return;
        }
        ExperimentUtils.clickSlotMiddle(screen, target);
        ClientUtils.sendDebugMessage("[US] click " + (nextOrdinal + 1) + "/" + orderToSlot.size()
                + " slot=" + target);
        lastClickAt = now;
        clickDelay = ExperimentUtils.noteClickDelay();
        nextOrdinal++;
    }

    /** Input phase = the countdown clock is in the marker slot. */
    private static boolean isInputPhase(AbstractContainerScreen<?> screen) {
        ItemStack marker = ExperimentUtils.stackAt(screen, ExperimentUtils.PHASE_SLOT);
        return ExperimentUtils.itemIdContains(marker, "clock")
                || ExperimentUtils.stackName(marker).startsWith(ExperimentUtils.PHASE_INPUT_PREFIX);
    }

    private static void logPhaseChange(AbstractContainerScreen<?> screen) {
        ItemStack marker = ExperimentUtils.stackAt(screen, ExperimentUtils.PHASE_SLOT);
        String key = ExperimentUtils.stackName(marker) + "|" + marker.getItem();
        if (key.equals(lastPhaseKey)) {
            return;
        }
        lastPhaseKey = key;
        ClientUtils.sendDebugMessage("[US] phase slot49='" + ExperimentUtils.stackName(marker)
                + "' item=" + marker.getItem() + " input=" + isInputPhase(screen)
                + " captured=" + orderToSlot.size());
    }

    private static void captureBoard(AbstractContainerScreen<?> screen) {
        Map<Integer, Integer> found = new HashMap<>();
        for (int i = BOARD_START; i <= BOARD_END; i++) {
            ItemStack stack = ExperimentUtils.stackAt(screen, i);
            String name = ExperimentUtils.stackName(stack);
            if (!stack.isEmpty() && name.matches("\\d+")) {
                found.put(Integer.parseInt(name) - 1, i);
            }
        }
        if (!found.isEmpty()) {
            orderToSlot.clear();
            orderToSlot.putAll(found);
            captured = true;
            nextOrdinal = 0;
            ClientUtils.sendDebugMessage("[Experiments] Ultrasequencer captured " + found.size()
                    + " numbers: " + new java.util.TreeMap<>(found));
        }
    }

    private static int maxClicks() {
        int loreTarget = ExperimentUtils.getRewardTarget();
        if (AetherConfig.EXPERIMENTS_STOP_AT_MAX_REWARD.get() && loreTarget > 0) {
            return loreTarget;
        }
        if (AetherConfig.EXPERIMENTS_MAX_CLICKS.get()) {
            return MAX_XP_CLICKS;
        }
        return REWARD_CAP_CLICKS - AetherConfig.EXPERIMENTS_SERUM_COUNT.get();
    }
}
