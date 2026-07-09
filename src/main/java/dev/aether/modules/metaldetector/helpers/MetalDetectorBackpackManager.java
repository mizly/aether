package dev.aether.modules.metaldetector.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MetalDetectorBackpackManager {
    private static final String SCAVENGED_KEYWORD = "scavenged";
    private static final String EMPTY_KEYWORD = "empty";
    private static final String LOCKED_KEYWORD = "locked";
    private static final int STORAGE_SLOT_START = 27;
    private static final int STORAGE_SLOT_END = 44;
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    private static final long MENU_TIMEOUT_MS = 4000L;

    private static final List<BackpackStatus> backpacks = new ArrayList<>();

    private static boolean backpackListKnown = false;
    private static int currentBackpackIndex = 0;
    private static Phase phase = Phase.IDLE;
    private static long phaseStartedAt = 0L;
    private static long lastActionAt = 0L;

    private enum Phase {
        IDLE,
        WAITING_STORAGE_MENU,
        WAITING_BACKPACK_MENU,
        FILLING_BACKPACK
    }

    private MetalDetectorBackpackManager() {
    }

    public static void reset() {
        backpacks.clear();
        backpackListKnown = false;
        currentBackpackIndex = 0;
        phase = Phase.IDLE;
        phaseStartedAt = 0L;
        lastActionAt = 0L;
    }

    public static boolean shouldHandle(Minecraft client) {
        return phase != Phase.IDLE || getScavengedToolCount(client) >= 4;
    }

    public static void update(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }

        advancePastFullBackpacks();

        switch (phase) {
            case IDLE -> handleIdle(client);
            case WAITING_STORAGE_MENU -> {
                if (client.screen == null && isTimedOut()) {
                    openStorageMenu(client);
                }
            }
            case WAITING_BACKPACK_MENU -> {
                if (client.screen == null && isTimedOut()) {
                    openCurrentBackpack(client);
                }
            }
            case FILLING_BACKPACK -> {
                if (client.screen == null) {
                    phase = Phase.IDLE;
                    phaseStartedAt = 0L;
                }
            }
        }
    }

    public static void handleContainerMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client == null || client.player == null || screen == null) {
            return;
        }

        if (phase == Phase.WAITING_STORAGE_MENU) {
            scanStorageMenu(client, screen);
            return;
        }

        if (phase == Phase.WAITING_BACKPACK_MENU || phase == Phase.FILLING_BACKPACK) {
            handleBackpackMenu(client, screen);
        }
    }

    public static String getFilledBackpacksSummary() {
        if (!backpackListKnown) {
            return "Unknown";
        }

        if (backpacks.isEmpty()) {
            return "0/0";
        }

        int filled = 0;
        for (BackpackStatus backpack : backpacks) {
            if (backpack.isFull()) {
                filled++;
            }
        }
        return filled + "/" + backpacks.size();
    }

    public static int getScavengedToolCount(Minecraft client) {
        if (client == null || client.player == null) {
            return 0;
        }

        int count = 0;
        for (int slot = 0; slot < PLAYER_INVENTORY_SLOTS; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (isScavengedTool(stack)) {
                count++;
            }
        }
        return count;
    }

    private static void handleIdle(Minecraft client) {
        if (client.screen != null || getScavengedToolCount(client) < 4) {
            return;
        }

        if (!backpackListKnown) {
            openStorageMenu(client);
            return;
        }

        if (currentBackpackIndex >= backpacks.size()) {
            stopForFullBackpacks(client);
            return;
        }

        openCurrentBackpack(client);
    }

    private static void openStorageMenu(Minecraft client) {
        if (!canPerformAction()) {
            return;
        }

        ClientUtils.sendDebugMessage(client, "MetalDetectorBackpack: opening storage");
        ClientUtils.sendCommand(client, "/st");
        markActionPerformed();
        phase = Phase.WAITING_STORAGE_MENU;
        phaseStartedAt = System.currentTimeMillis();
    }

    private static void scanStorageMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!canPerformAction()) {
            return;
        }

        backpacks.clear();
        Set<Integer> blacklistedBackpacks = getBlacklistedBackpacks();

        int lastSlot = Math.min(STORAGE_SLOT_END, screen.getMenu().slots.size() - 1);
        for (int slotIndex = STORAGE_SLOT_START; slotIndex <= lastSlot; slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (!slot.hasItem()) {
                continue;
            }

            String name = cleanName(slot.getItem());
            if (name.contains(EMPTY_KEYWORD) || name.contains(LOCKED_KEYWORD)) {
                continue;
            }

            int backpackNumber = slotIndex - STORAGE_SLOT_START + 1;
            if (blacklistedBackpacks.contains(backpackNumber)) {
                continue;
            }

            backpacks.add(new BackpackStatus(backpackNumber));
        }

        backpackListKnown = true;
        currentBackpackIndex = 0;
        advancePastFullBackpacks();
        phase = Phase.IDLE;
        phaseStartedAt = 0L;

        client.player.closeContainer();
        markActionPerformed();

        if (backpacks.isEmpty()) {
            ClientUtils.sendMessage(client, "\u00A7cNo usable backpacks found in /st. Stopping macro.", false);
            MacroStateManager.stopMacro(client, "MetalDetectorBackpack: no usable backpacks found");
        } else {
            ClientUtils.sendDebugMessage(client,
                    "MetalDetectorBackpack: found " + backpacks.size() + " usable backpacks");
        }
    }

    private static void openCurrentBackpack(Minecraft client) {
        if (!canPerformAction()) {
            return;
        }

        BackpackStatus current = getCurrentBackpack();
        if (current == null) {
            stopForFullBackpacks(client);
            return;
        }

        ClientUtils.sendDebugMessage(client, "MetalDetectorBackpack: opening backpack " + current.backpackNumber);
        ClientUtils.sendCommand(client, "/bp " + current.backpackNumber);
        markActionPerformed();
        phase = Phase.WAITING_BACKPACK_MENU;
        phaseStartedAt = System.currentTimeMillis();
    }

    private static void handleBackpackMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        BackpackStatus current = getCurrentBackpack();
        if (current == null) {
            stopForFullBackpacks(client);
            return;
        }

        int totalSlots = screen.getMenu().slots.size();
        if (totalSlots <= PLAYER_INVENTORY_SLOTS) {
            return;
        }

        if (!canPerformAction()) {
            return;
        }

        int containerSlots = totalSlots - PLAYER_INVENTORY_SLOTS;
        int usableStart = Math.min(9, containerSlots);
        int usableTotal = Math.max(0, containerSlots - usableStart);
        int emptySlots = 0;
        for (int slotIndex = usableStart; slotIndex < containerSlots; slotIndex++) {
            if (!screen.getMenu().slots.get(slotIndex).hasItem()) {
                emptySlots++;
            }
        }

        current.totalSlots = usableTotal;
        current.emptySlots = emptySlots;
        phase = Phase.FILLING_BACKPACK;
        phaseStartedAt = System.currentTimeMillis();

        if (usableTotal == 0 || emptySlots == 0) {
            currentBackpackIndex++;
            advancePastFullBackpacks();
            client.player.closeContainer();
            markActionPerformed();
            phase = Phase.IDLE;
            phaseStartedAt = 0L;
            if (currentBackpackIndex >= backpacks.size()) {
                stopForFullBackpacks(client);
            }
            return;
        }

        int scavengedSlot = findScavengedInventorySlot(screen, containerSlots);
        if (scavengedSlot == -1) {
            client.player.closeContainer();
            markActionPerformed();
            phase = Phase.IDLE;
            phaseStartedAt = 0L;
            return;
        }

        ClientUtils.performSlotClick(client, screen, scavengedSlot, 0, ContainerInput.QUICK_MOVE);
        markActionPerformed();
    }

    private static int findScavengedInventorySlot(AbstractContainerScreen<?> screen, int containerSlots) {
        for (int slotIndex = containerSlots; slotIndex < screen.getMenu().slots.size(); slotIndex++) {
            Slot slot = screen.getMenu().slots.get(slotIndex);
            if (!slot.hasItem()) {
                continue;
            }
            if (isScavengedTool(slot.getItem())) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static BackpackStatus getCurrentBackpack() {
        if (currentBackpackIndex < 0 || currentBackpackIndex >= backpacks.size()) {
            return null;
        }
        return backpacks.get(currentBackpackIndex);
    }

    private static void advancePastFullBackpacks() {
        while (currentBackpackIndex < backpacks.size()) {
            BackpackStatus backpack = backpacks.get(currentBackpackIndex);
            if (backpack.emptySlots != 0 || backpack.totalSlots < 0) {
                break;
            }
            currentBackpackIndex++;
        }
    }

    private static boolean isTimedOut() {
        return phaseStartedAt > 0L && System.currentTimeMillis() - phaseStartedAt >= MENU_TIMEOUT_MS;
    }

    private static boolean canPerformAction() {
        return System.currentTimeMillis() - lastActionAt >= ClientUtils.getGuiClickDelayMs(lastActionAt == 0L);
    }

    private static void markActionPerformed() {
        lastActionAt = System.currentTimeMillis();
    }

    private static void stopForFullBackpacks(Minecraft client) {
        ClientUtils.sendMessage(client, "\u00A7eAll known backpacks are full. Stopping macro.", false);
        MacroStateManager.stopMacro(client, "MetalDetectorBackpack: all backpacks are full");
    }

    private static boolean isScavengedTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return cleanName(stack).contains(SCAVENGED_KEYWORD);
    }

    private static Set<Integer> getBlacklistedBackpacks() {
        Set<Integer> blacklisted = new HashSet<>();
        for (String raw : AetherConfig.METAL_DETECTOR_BACKPACK_BLACKLIST.get()) {
            if (raw == null) {
                continue;
            }

            String normalized = raw.trim();
            if (!normalized.matches("\\d+")) {
                continue;
            }

            try {
                int backpackNumber = Integer.parseInt(normalized);
                if (backpackNumber >= 1 && backpackNumber <= 18) {
                    blacklisted.add(backpackNumber);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return blacklisted;
    }

    private static String cleanName(ItemStack stack) {
        return TablistUtils.stripColors(stack.getHoverName().getString()).trim().toLowerCase();
    }

    private static final class BackpackStatus {
        private final int backpackNumber;
        private int emptySlots = -1;
        private int totalSlots = -1;

        private BackpackStatus(int backpackNumber) {
            this.backpackNumber = backpackNumber;
        }

        private boolean isFull() {
            return emptySlots == 0 && totalSlots >= 0;
        }
    }
}
