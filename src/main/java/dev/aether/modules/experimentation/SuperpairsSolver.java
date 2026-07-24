package dev.aether.modules.experimentation;

import java.util.HashMap;
import java.util.Map;

import dev.aether.config.AetherConfig;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-plays Superpairs: reveal cards, remember what each slot held, and click
 * a known pair together as soon as both halves are known.
 * <p>
 * Card state comes from the board's own labels ("Click any button!" =
 * face down, "Click a second button!" = face down mid-attempt) rather than the
 * item id, and pairs are matched on display name + count rather than a full
 * NBT comparison, because Hypixel's reward stacks are not byte-identical.
 */
public final class SuperpairsSolver {

    private static final int BOARD_START = 9;
    private static final int BOARD_END = 44;
    private static final long REVEAL_TIMEOUT_MS = 2500L;
    private static final long STALL_TIMEOUT_MS = 8000L;
    private static final long MISMATCH_FLIPBACK_MS = 1200L;
    /**
     * The board relabels the other cards a frame or two after the first card of
     * an attempt is revealed. Never trust a missing mid-attempt marker inside
     * this window, or we drop our own first card and desync from the server.
     */
    private static final long MARKER_GRACE_MS = 1500L;

    private static final Map<Integer, String> knownKey = new HashMap<>();
    private static long lastClickAt = 0L;
    private static long clickDelay = 0L;
    private static int pendingRevealSlot = -1;
    private static long pendingRevealSince = 0L;
    private static String attemptFirstKey = null;
    private static int attemptFirstSlot = -1;
    private static long attemptFirstAt = 0L;
    private static long lastProgressAt = 0L;
    private static boolean active = false;
    /** Set once a populated board has been seen, so an empty sync frame is
     * never mistaken for a finished board. */
    private static boolean boardSeen = false;

    private SuperpairsSolver() {
    }

    public static void reset() {
        knownKey.clear();
        pendingRevealSlot = -1;
        clearAttempt();
        lastClickAt = 0L;
        lastProgressAt = 0L;
        active = false;
        boardSeen = false;
    }

    private static void clearAttempt() {
        attemptFirstKey = null;
        attemptFirstSlot = -1;
        attemptFirstAt = 0L;
    }

    public static boolean isGameScreen(AbstractContainerScreen<?> screen) {
        // Require the tier suffix: the stakes menu is titled just "Superpairs".
        String title = screen.getTitle().getString().replaceAll("(?i)§.", "").trim();
        return title.matches("(?i)superpairs ?\\(.*");
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

        long now = System.currentTimeMillis();
        if (!active) {
            reset();
            active = true;
            lastProgressAt = now;
            ClientUtils.sendDebugMessage("[SP] started, face-down cards: " + countHidden(screen)
                    + " of " + countPopulated(screen) + " populated");
        }

        // Absorb the outcome of the last click before deciding anything new.
        if (pendingRevealSlot >= 0) {
            ItemStack revealed = ExperimentUtils.stackAt(screen, pendingRevealSlot);
            if (isRevealedReward(revealed)) {
                String key = keyOf(revealed);
                knownKey.put(pendingRevealSlot, key);
                if (attemptFirstKey == null) {
                    attemptFirstKey = key;
                    attemptFirstSlot = pendingRevealSlot;
                    attemptFirstAt = now;
                    ClientUtils.sendDebugMessage("[SP] reveal#1 slot " + pendingRevealSlot
                            + " = " + key + " (known=" + knownKey.size() + ")");
                } else {
                    boolean matched = attemptFirstKey.equals(key);
                    ClientUtils.sendDebugMessage("[SP] reveal#2 slot " + pendingRevealSlot
                            + " = " + key + (matched ? " MATCH" : " MISMATCH vs slot "
                                    + attemptFirstSlot + " " + attemptFirstKey));
                    if (!matched) {
                        // Both cards flip back; clicking during that is ignored.
                        clickDelay = MISMATCH_FLIPBACK_MS + ExperimentUtils.nextClickDelay();
                    }
                    clearAttempt();
                }
                pendingRevealSlot = -1;
                lastProgressAt = now;
            } else if (now - pendingRevealSince > REVEAL_TIMEOUT_MS) {
                ClientUtils.sendDebugMessage("[SP] reveal timeout on slot " + pendingRevealSlot
                        + " (shows '" + ExperimentUtils.stackName(revealed) + "')");
                pendingRevealSlot = -1;
            } else {
                return;
            }
        }

        // Corrective resync, but only once the board has had time to relabel.
        boolean midAttempt = hasSecondButtonMarker(screen);
        if (!midAttempt && attemptFirstKey != null && now - attemptFirstAt > MARKER_GRACE_MS) {
            ClientUtils.sendDebugMessage("[SP] resync: attempt ended without a second reveal");
            clearAttempt();
        }

        // Hypixel re-sends the container mid-game; those frames arrive empty and
        // must not read as "every card matched".
        int populated = countPopulated(screen);
        if (populated == 0) {
            return;
        }
        boardSeen = true;

        int hiddenCount = countHidden(screen);
        if (hiddenCount == 0) {
            if (!boardSeen) {
                return;
            }
            ClientUtils.sendDebugMessage("[SP] board complete (" + populated + " cards up).");
            finish();
            return;
        }
        if (now - lastProgressAt > STALL_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[SP] stalled (out of clicks?), closing.");
            finish();
            return;
        }
        if (now - lastClickAt < clickDelay) {
            return;
        }

        int target = chooseTarget(screen);
        if (target < 0) {
            return;
        }
        String reason = attemptFirstKey != null
                ? (knownKey.containsKey(target) ? "partner-of-first" : "learn-second")
                : (knownKey.containsKey(target) ? "known-pair-start" : "learn-first");
        ClientUtils.sendDebugMessage("[SP] click slot " + target + " (" + reason
                + ", mid=" + midAttempt + ", known=" + knownKey.size()
                + ", hidden=" + hiddenCount + ")");
        ExperimentUtils.clickSlotMiddle(screen, target);
        pendingRevealSlot = target;
        pendingRevealSince = now;
        lastClickAt = now;
        clickDelay = ExperimentUtils.nextClickDelay();
    }

    private static int chooseTarget(AbstractContainerScreen<?> screen) {
        if (attemptFirstKey != null) {
            // Mid-attempt: finish the pair if we already know where its twin is.
            for (Map.Entry<Integer, String> entry : knownKey.entrySet()) {
                int slot = entry.getKey();
                if (slot != attemptFirstSlot && isFaceDown(screen, slot)
                        && attemptFirstKey.equals(entry.getValue())) {
                    return slot;
                }
            }
            return firstUnknownFaceDown(screen);
        }

        // Fresh attempt: start a pair we can complete immediately.
        for (Map.Entry<Integer, String> a : knownKey.entrySet()) {
            if (!isFaceDown(screen, a.getKey())) {
                continue;
            }
            for (Map.Entry<Integer, String> b : knownKey.entrySet()) {
                if (a.getKey().intValue() < b.getKey().intValue()
                        && isFaceDown(screen, b.getKey())
                        && a.getValue().equals(b.getValue())) {
                    return a.getKey();
                }
            }
        }
        return firstUnknownFaceDown(screen);
    }

    private static int firstUnknownFaceDown(AbstractContainerScreen<?> screen) {
        int fallback = -1;
        for (int i = BOARD_START; i <= BOARD_END; i++) {
            if (!isFaceDown(screen, i)) {
                continue;
            }
            if (!knownKey.containsKey(i)) {
                return i;
            }
            if (fallback < 0) {
                fallback = i;
            }
        }
        return fallback;
    }

    private static int countPopulated(AbstractContainerScreen<?> screen) {
        int count = 0;
        for (int i = BOARD_START; i <= BOARD_END; i++) {
            if (!ExperimentUtils.stackAt(screen, i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int countHidden(AbstractContainerScreen<?> screen) {
        int count = 0;
        for (int i = BOARD_START; i <= BOARD_END; i++) {
            if (isFaceDown(screen, i)) {
                count++;
            }
        }
        return count;
    }

    /** Face-down cards are labelled "Click any/a second button!". */
    private static boolean isFaceDown(AbstractContainerScreen<?> screen, int slot) {
        return ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, slot)).startsWith("Click a");
    }

    private static boolean hasSecondButtonMarker(AbstractContainerScreen<?> screen) {
        for (int i = BOARD_START; i <= BOARD_END; i++) {
            if (ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, i)).startsWith("Click a second")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRevealedReward(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String name = ExperimentUtils.stackName(stack);
        return !name.isEmpty() && !name.startsWith("Click a");
    }

    /** Pair identity: what the player sees, not the raw NBT. */
    private static String keyOf(ItemStack stack) {
        return ExperimentUtils.stackName(stack) + " x" + stack.getCount();
    }

    private static void finish() {
        reset();
        ExperimentUtils.closeScreen();
    }
}
