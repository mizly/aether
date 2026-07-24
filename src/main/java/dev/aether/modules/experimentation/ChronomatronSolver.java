package dev.aether.modules.experimentation;

import java.util.ArrayList;
import java.util.List;

import dev.aether.config.AetherConfig;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-plays Chronomatron.
 * <p>
 * The game plays a sequence of notes, one longer each round, and the player
 * plays it back. So: record every note as it lights up, and once the expected
 * number for this round has been played, click them back in order. There is no
 * rule about repeated colours or note ordering, and none is assumed - a note is
 * simply whatever lights up next.
 * <p>
 * Recording is deliberately NOT gated on the slot 49 marker: the last note of a
 * round is played as the marker flips to the countdown, so a phase-gated
 * recorder silently drops it.
 * <p>
 * Notes are identified by colour, since the board swaps terracotta for glass
 * between phases and repeats the same colour row several times - any slot of
 * the right colour is a valid click.
 */
public final class ChronomatronSolver {

    // Odin's scan window: covers every board layout across all tiers.
    private static final int SCAN_START = 10;
    private static final int SCAN_END = 43;
    private static final int MAX_XP_CHAIN = 15;
    private static final int REWARD_CAP_CHAIN = 11;
    /** Hypixel reopens this screen between phases; state must survive that. */
    private static final long NEW_GAME_GAP_MS = 5000L;
    private static final long HEARTBEAT_MS = 500L;

    private static final List<String> notes = new ArrayList<>();
    private static List<String> litPrev = new ArrayList<>();
    /** How many notes the game should play this round (the round number). */
    private static int expected = 1;
    private static boolean clicking = false;
    private static int clicks = 0;
    private static long lastClickAt = 0L;
    private static long clickDelay = 0L;
    /** Our own clicked note stays lit into the next round's playback. */
    private static boolean awaitingDark = false;
    private static boolean active = false;
    private static boolean missLogged = false;
    private static long lastSeenAt = 0L;
    private static long lastHeartbeatAt = 0L;

    private ChronomatronSolver() {
    }

    public static void reset() {
        notes.clear();
        litPrev = new ArrayList<>();
        expected = 1;
        clicking = false;
        clicks = 0;
        lastClickAt = 0L;
        awaitingDark = false;
        active = false;
        missLogged = false;
        lastSeenAt = 0L;
        lastHeartbeatAt = 0L;
    }

    public static boolean isGameScreen(AbstractContainerScreen<?> screen) {
        // Tier suffix required: bare "Chronomatron" is the stakes menu.
        return screen.getTitle().getString().replaceAll("(?i)§.", "").trim()
                .matches("(?i)chronomatron ?\\(.*");
    }

    public static void handleMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!AetherConfig.AUTO_EXPERIMENTS.get() || client.player == null) {
            return;
        }
        if (!isGameScreen(screen)) {
            // Never reset here: Hypixel swaps this screen out between phases.
            return;
        }

        long now = System.currentTimeMillis();
        if (!active || now - lastSeenAt > NEW_GAME_GAP_MS) {
            boolean restart = active;
            reset();
            active = true;
            ClientUtils.sendDebugMessage("[CM] " + (restart ? "new game" : "started") + ": "
                    + screen.getTitle().getString().replaceAll("(?i)§.", "").trim());
        }
        lastSeenAt = now;

        List<String> lit = litColours(screen);
        if (now - lastHeartbeatAt > HEARTBEAT_MS) {
            lastHeartbeatAt = now;
            ClientUtils.sendDebugMessage("[CM] hb lit=" + lit + " notes=" + notes.size()
                    + "/" + expected + " clicking=" + clicking + " clicks=" + clicks
                    + " dark?" + awaitingDark);
        }

        if (clicking) {
            playBack(screen, lit, now);
            return;
        }

        // Ignore glints left over from our own clicks at the start of a round.
        if (awaitingDark) {
            if (lit.isEmpty()) {
                awaitingDark = false;
            }
            litPrev = lit;
            return;
        }

        // A note begins when the board lights up after being dark.
        if (!lit.isEmpty() && litPrev.isEmpty()) {
            if (lit.size() > 1) {
                ClientUtils.sendDebugMessage("[CM] note " + (notes.size() + 1)
                        + " ambiguous, lit=" + lit + " (taking " + lit.get(0) + ")");
            }
            notes.add(lit.get(0));
            ClientUtils.sendDebugMessage("[CM] note " + notes.size() + "/" + expected
                    + ": " + lit.get(0));
        }

        // Sequence played and the board has gone dark again: play it back.
        if (notes.size() >= expected && lit.isEmpty() && !litPrev.isEmpty()) {
            clicking = true;
            clicks = 0;
            missLogged = false;
            ClientUtils.sendDebugMessage("[CM] playing back " + notes);
        }
        litPrev = lit;
    }

    private static void playBack(AbstractContainerScreen<?> screen, List<String> lit, long now) {
        if (clicks >= notes.size()) {
            // Round done; the next playback is one note longer.
            expected = notes.size() + 1;
            notes.clear();
            litPrev = new ArrayList<>();
            clicking = false;
            awaitingDark = true;
            int maxChain = maxChain();
            if (expected > maxChain) {
                ClientUtils.sendDebugMessage("[CM] reached target chain " + maxChain
                        + ", closing for rewards.");
                reset();
                ExperimentUtils.closeScreen();
            }
            return;
        }
        if (now - lastClickAt < clickDelay) {
            return;
        }

        String colour = notes.get(clicks);
        int target = slotForColour(screen, colour);
        if (target < 0) {
            if (!missLogged) {
                missLogged = true;
                ClientUtils.sendDebugMessage("[CM] no slot for '" + colour + "' (step "
                        + (clicks + 1) + "/" + notes.size() + "), lit=" + lit);
            }
            return;
        }
        ExperimentUtils.clickSlotMiddle(screen, target);
        ClientUtils.sendDebugMessage("[CM] click " + (clicks + 1) + "/" + notes.size()
                + " " + colour + " @ " + target);
        lastClickAt = now;
        clickDelay = ExperimentUtils.noteClickDelay();
        clicks++;
    }

    /** Distinct lit (glinting) colours, in board order. */
    private static List<String> litColours(AbstractContainerScreen<?> screen) {
        List<String> colours = new ArrayList<>();
        for (int i = SCAN_START; i <= SCAN_END; i++) {
            ItemStack stack = ExperimentUtils.stackAt(screen, i);
            if (stack.isEmpty() || !stack.hasFoil()) {
                continue;
            }
            String colour = ExperimentUtils.colourOf(stack);
            if (colour != null && !colours.contains(colour)) {
                colours.add(colour);
            }
        }
        return colours;
    }

    private static int slotForColour(AbstractContainerScreen<?> screen, String colour) {
        for (int i = SCAN_START; i <= SCAN_END; i++) {
            if (colour.equals(ExperimentUtils.colourOf(ExperimentUtils.stackAt(screen, i)))) {
                return i;
            }
        }
        return -1;
    }

    private static int maxChain() {
        int loreTarget = ExperimentUtils.getRewardTarget();
        if (AetherConfig.EXPERIMENTS_STOP_AT_MAX_REWARD.get() && loreTarget > 0) {
            return loreTarget;
        }
        if (AetherConfig.EXPERIMENTS_MAX_CLICKS.get()) {
            return MAX_XP_CHAIN;
        }
        return REWARD_CAP_CHAIN - AetherConfig.EXPERIMENTS_SERUM_COUNT.get();
    }
}
