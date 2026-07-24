package dev.aether.modules.experimentation;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.util.ClientUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Shared helpers for the Experimentation Table solvers. */
final class ExperimentUtils {

    // Slot 49 holds the phase indicator item in all experiment GUIs.
    static final int PHASE_SLOT = 49;
    static final String PHASE_SHOW = "Remember the pattern!";
    static final String PHASE_INPUT_PREFIX = "Timer: ";

    private ExperimentUtils() {
    }

    static String stackName(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? ""
                : stack.getHoverName().getString().replaceAll("(?i)§.", "").trim();
    }

    static ItemStack stackAt(AbstractContainerScreen<?> screen, int index) {
        if (screen.getMenu() == null || index < 0 || index >= screen.getMenu().slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = screen.getMenu().slots.get(index);
        return slot.hasItem() ? slot.getItem() : ItemStack.EMPTY;
    }

    static String phaseName(AbstractContainerScreen<?> screen) {
        return stackName(stackAt(screen, PHASE_SLOT));
    }

    static boolean isInputPhase(AbstractContainerScreen<?> screen) {
        return phaseName(screen).startsWith(PHASE_INPUT_PREFIX);
    }

    static boolean isShowPhase(AbstractContainerScreen<?> screen) {
        return phaseName(screen).equals(PHASE_SHOW);
    }

    /** Item-id substring check, the codebase convention for Hypixel GUI items. */
    static boolean itemIdContains(ItemStack stack, String... needles) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = stack.getItem().toString().toLowerCase();
        for (String needle : needles) {
            if (!id.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    // Ordered so compound names match before their suffixes (light_blue before blue).
    private static final String[] COLOURS = {
            "light_blue", "light_gray", "magenta", "purple", "orange", "yellow",
            "lime", "green", "cyan", "blue", "pink", "brown", "black", "white", "gray", "red"
    };

    /**
     * Colour keyword from the item id. The Chronomatron board shows dyed
     * terracotta during the memory phase but can swap to stained glass for the
     * input phase, so matching must be by colour, not item identity.
     */
    static String colourOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = stack.getItem().toString().toLowerCase();
        for (String colour : COLOURS) {
            if (id.contains(colour)) {
                return colour;
            }
        }
        return null;
    }

    /**
     * Highest "Series of N" / "Chain of N" reward threshold from the tier lore
     * of the run in progress, or 0 when unknown. Playing past it earns nothing
     * extra, so the solvers stop there and let the rewards screen open.
     */
    private static volatile int rewardTarget = 0;

    static void setRewardTarget(int target) {
        rewardTarget = target;
    }

    static int getRewardTarget() {
        return rewardTarget;
    }

    /** Parses the biggest reward threshold out of a tier's lore. */
    static int parseRewardTarget(String lore) {
        if (lore == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(?:series|chain) of (\\d+)")
                .matcher(lore);
        int best = 0;
        while (matcher.find()) {
            best = Math.max(best, Integer.parseInt(matcher.group(1)));
        }
        return best;
    }

    static long nextClickDelay() {
        return ConfigHelpers.getRandomizedDelay(
                AetherConfig.EXPERIMENTS_CLICK_DELAY_MIN.get(),
                AetherConfig.EXPERIMENTS_CLICK_DELAY_MAX.get());
    }

    /** Delay between note clicks while replaying a sequence. */
    static long noteClickDelay() {
        return ConfigHelpers.getRandomizedDelay(
                AetherConfig.EXPERIMENTS_NOTE_DELAY_MIN.get(),
                AetherConfig.EXPERIMENTS_NOTE_DELAY_MAX.get());
    }

    static void clickSlot(AbstractContainerScreen<?> screen, int index) {
        ClientUtils.performSlotClick(screen, index, 0, ContainerInput.PICKUP);
    }

    /** Right-click, which the stakes menu uses to start a practice run. */
    static void clickSlotRight(AbstractContainerScreen<?> screen, int index) {
        ClientUtils.performSlotClick(screen, index, 1, ContainerInput.PICKUP);
    }

    /**
     * Middle-click for the minigame boards (the Odin/SBC convention): CLONE
     * never lifts the fake item onto the cursor, so no client/server desync
     * shuffling the player's own inventory.
     */
    static void clickSlotMiddle(AbstractContainerScreen<?> screen, int index) {
        ClientUtils.performSlotClick(screen, index, 2, ContainerInput.CLONE);
    }

    /**
     * Number of slots belonging to the open container itself. Chest menus
     * always append the player's 36 inventory slots after the container's, and
     * scanning into those picks up the player's own items.
     */
    static int containerSlotCount(AbstractContainerScreen<?> screen) {
        if (screen.getMenu() == null) {
            return 0;
        }
        return Math.max(0, screen.getMenu().slots.size() - 36);
    }

    static void closeScreen() {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.player != null) {
            client.player.closeContainer();
        }
    }
}
