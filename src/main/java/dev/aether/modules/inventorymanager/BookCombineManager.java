package dev.aether.modules.inventorymanager;

import dev.aether.config.ConfigHelpers;
import dev.aether.config.AetherConfig;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.*;
import dev.aether.util.EnchantmentUtils;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.modules.visitor.VisitorManager;

public class BookCombineManager {
    public static volatile long interactionTime = 0;
    public static volatile int interactionStage = 0;

    /** Pre-computed slot indices for the current pair being combined. */
    private static volatile int pendingSlot0 = -1;
    private static volatile int pendingSlot1 = -1;

    public static volatile boolean isCombining = false;
    public static volatile boolean isPreparingToCombine = false;

    public static void reset() {
        isCombining = false;
        isPreparingToCombine = false;
        interactionStage = 0;
        interactionTime = 0;
        pendingSlot0 = -1;
        pendingSlot1 = -1;
    }

    public static void handleAnvilMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!AetherConfig.AUTO_BOOK_COMBINE.get() || screen == null || client.player == null)
            return;

        if (!AetherConfig.ALWAYS_ACTIVE_COMBINE.get() && !isCombining)
            return;

        long now = System.currentTimeMillis();
        long requiredDelay = interactionStage == 0
                ? ClientUtils.getGuiClickDelayMs(true)
                : ConfigHelpers.getRandomizedDelay(AetherConfig.BOOK_COMBINE_DELAY.get());
        if (now - interactionTime < requiredDelay)
            return;

        String title = screen.getTitle().getString();
        if (!title.toLowerCase().contains("anvil"))
            return;

        int totalSlots = screen.getMenu().slots.size();
        if (totalSlots < 54)
            return;

        // Stage 0: Scan inventory, find a combinable pair. Pre-store BOTH slot indices,
        // then click the first one. We do NOT re-scan in later stages.
        if (interactionStage == 0) {
            Map<String, List<Integer>> bookPairs = getInventoryBooks(screen);

            for (Map.Entry<String, List<Integer>> entry : bookPairs.entrySet()) {
                String key = entry.getKey();
                List<Integer> slots = entry.getValue();

                if (slots.size() < 2)
                    continue;

                if (isMaxLevel(key))
                    continue;

                // Lock in both slot indices before touching anything
                pendingSlot0 = slots.get(0);
                pendingSlot1 = slots.get(1);

                ClientUtils.sendMessage("\u00A77BookCombine: combining " + key
                                + "' (slots " + pendingSlot0 + " + " + pendingSlot1 + ")", true);

                dev.aether.util.ClientUtils.performSlotClick(client, screen, pendingSlot0, 0, ContainerInput.QUICK_MOVE);
                interactionTime = now;
                interactionStage = 1;
                return;
            }

            // No combinable pairs - nothing to do
            pendingSlot0 = -1;
            pendingSlot1 = -1;
            finishCombining(client);
        }
        // Stage 1: Click the pre-stored second book (same type guaranteed since both
        // slots were frozen in stage 0 before any clicking)
        else if (interactionStage == 1) {
            if (pendingSlot1 == -1) {
                interactionStage = 0;
                return;
            }
            dev.aether.util.ClientUtils.performSlotClick(client, screen, pendingSlot1, 0, ContainerInput.QUICK_MOVE);
            interactionTime = now;
            interactionStage = 2;
        }
        // Stage 2: Pick up the combined result from the output slot (slot 22)
        else if (interactionStage == 2) {
            dev.aether.util.ClientUtils.performSlotClick(client, screen, 22, 0, ContainerInput.PICKUP);
            interactionTime = now;
            interactionStage = 3;
        }
        // Stage 3: Place the result back in inventory
        else if (interactionStage == 3) {
            dev.aether.util.ClientUtils.performSlotClick(client, screen, 22, 0, ContainerInput.PICKUP);
            interactionTime = now;
            interactionStage = 0;
            pendingSlot0 = -1;
            pendingSlot1 = -1;

            // After one successful combine, check if more pairs exist
            Map<String, List<Integer>> remainingPairs = getInventoryBooks(screen);
            boolean hasMore = false;
            for (Map.Entry<String, List<Integer>> entry : remainingPairs.entrySet()) {
                if (entry.getValue().size() >= 2 && !isMaxLevel(entry.getKey())) {
                    hasMore = true;
                    break;
                }
            }

            if (!hasMore && isCombining) {
                finishCombining(client);
            }
        }
    }

    private static boolean isPriorityEventActive(Minecraft client) {
        return dev.aether.macro.MacroStateManager.getCurrentState() != dev.aether.macro.MacroState.State.FARMING
                || PestManager.isCleaningInProgress
                || PestPrepSwapManager.prepSwappedForCurrentPestCycle
                || (AetherConfig.AUTO_VISITOR.get() && VisitorManager.getVisitorCount(client) >= AetherConfig.VISITOR_THRESHOLD.get())
                || LoadoutManager.isSwappingLoadout
                || AutoSellManager.isSelling
                || AutoSellManager.isPreparingToSell
                || GeorgeManager.isSelling
                || GeorgeManager.isPreparingToSell
                || JunkManager.isDropping
                || JunkManager.isPreparingToDrop
                || (AetherConfig.AUTO_DROP_JUNK.get() && JunkManager.countJunkItems(client) >= AetherConfig.JUNK_THRESHOLD.get());
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!AetherConfig.AUTO_BOOK_COMBINE.get() || client.player == null)
            return;

        if (isPreparingToCombine) {
            if (isPriorityEventActive(client)) {
                isPreparingToCombine = false;
                ClientUtils.sendMessage("\u00A7cAborting Book Combine prep due to priority event.", false);
            }
            return;
        }

        if (isCombining) {
            if (isPriorityEventActive(client)) {
                isCombining = false;
                ClientUtils.sendMessage("\u00A7cAborting Book Combine due to priority event.", false);
                return;
            }

            // If GUI closed but we were supposed to be combining and still have pairs
            if (client.screen == null) {
                long now = System.currentTimeMillis();
                if (now - interactionTime > 1500) {
                    finishCombining(client);
                }
            }
            return;
        }

        if (isPriorityEventActive(client))
            return;

        int bookCount = countBooksInInventory(client);
        if (bookCount >= AetherConfig.BOOK_THRESHOLD.get()) {
            triggerAutomaticCombine(client, bookCount);
        }
    }

    private static void triggerAutomaticCombine(Minecraft client, int count) {
        ClientUtils.sendMessage("\u00A7eAuto combining books (" + count + " books in inventory)...", false);

        dev.aether.util.ClientUtils.forceReleaseKeys(client);

        isPreparingToCombine = true;
        isCombining = false;

        MacroWorkerThread.getInstance().submit("BookCombine-Trigger", () -> {
            try {
                if (MacroWorkerThread.shouldAbortTask(client, dev.aether.macro.MacroState.State.FARMING)) {
                    isPreparingToCombine = false;
                    return;
                }
                ClientUtils.sendDebugMessage("Disabling farming macro: Preparing book combine");
                client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));

                MacroWorkerThread.sleep(400); // Stabilization delay
                
                if (isPreparingToCombine) {
                    isPreparingToCombine = false;
                    isCombining = true;
                    interactionStage = 0;
                    interactionTime = System.currentTimeMillis();
                    dev.aether.util.ClientUtils.sendCommand(client, "/av");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void finishCombining(Minecraft client) {
        boolean automaticSession = isCombining;

        isCombining = false;
        isPreparingToCombine = false;
        interactionStage = 0;
        interactionTime = 0;
        pendingSlot0 = -1;
        pendingSlot1 = -1;

        if (!automaticSession)
            return;

        if (client.player != null && client.screen != null) {
            client.execute(() -> client.player.closeContainer());
        }

        if (client.player != null) {
            ClientUtils.sendMessage("\u00A7aBook Combine finished. Resuming script...", true);
        }

        if (dev.aether.macro.MacroStateManager.getCurrentState() == dev.aether.macro.MacroState.State.FARMING) {
            MacroWorkerThread.getInstance().submit("BookCombine-Finish", () -> {
                if (MacroWorkerThread.shouldAbortTask(client, dev.aether.macro.MacroState.State.FARMING))
                    return;
                // Wait for GUI to fully close
                long guiWait = System.currentTimeMillis();
                while (client.screen != null && System.currentTimeMillis() - guiWait < 3000)
                    MacroWorkerThread.sleep(50);
                MacroWorkerThread.sleep(300);
                if (MacroWorkerThread.shouldAbortTask(client, dev.aether.macro.MacroState.State.FARMING))
                    return;

                client.execute(() -> GearManager.swapToFarmingTool(client));
                MacroWorkerThread.sleep(200);
                if (MacroWorkerThread.shouldAbortTask(client, dev.aether.macro.MacroState.State.FARMING))
                    return;

                ClientUtils.sendDebugMessage("Restarting farming macro after book combine");
                client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client, dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
            });
        }
    }

    public static int getBookCountInInventory(Minecraft client) {
        if (client == null || client.player == null) {
            return 0;
        }
        return countBooksInInventory(client);
    }

    private static int countBooksInInventory(Minecraft client) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains("enchanted_book")) {
                if (!isExemptBook(stack)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isExemptBook(ItemStack stack) {
        String name = stack.getHoverName().getString().toLowerCase();
        if (name.contains("sunder") || name.contains("pesterminator 5") || name.contains("pesterminator v")) {
            return true;
        }

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String text = line.getString().toLowerCase();
                if (text.contains("sunder") || text.contains("pesterminator 5") || text.contains("pesterminator v")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scans the player's inventory section of the open container and returns a map
     * from unique book key -> list of container slot indices.
     *
     * Key is the first non-empty lore line of the book (e.g. "Sharpness VI"),
     * which is unique per enchantment type AND level in Hypixel Skyblock 1.21.
     */
    private static Map<String, List<Integer>> getInventoryBooks(AbstractContainerScreen<?> screen) {
        Map<String, List<Integer>> pairs = new LinkedHashMap<>();
        int totalSlots = screen.getMenu().slots.size();
        int inventoryStart = totalSlots - 36;

        for (int i = inventoryStart; i < totalSlots; i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem())
                continue;

            ItemStack stack = slot.getItem();
            if (!stack.getItem().toString().toLowerCase().contains("enchanted_book"))
                continue;

            if (isExemptBook(stack))
                continue;

            String key = getBookKey(stack);
            if (key == null)
                continue;

            pairs.computeIfAbsent(key, k -> new ArrayList<>()).add(slot.index);
        }
        return pairs;
    }

    /**
     * Returns a unique key for this enchanted book. Uses the first non-empty
     * lore line, which in Hypixel Skyblock 1.21 is the enchantment name + level
     * (e.g. "Sharpness VI"). Falls back to hover name if no lore is present.
     * Returns null if no usable key can be determined.
     */
    private static String getBookKey(ItemStack stack) {
        // Collect all non-empty lore lines (stripped of color codes)
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> nonEmpty = new ArrayList<>();
        if (lore != null) {
            for (net.minecraft.network.chat.Component line : lore.lines()) {
                String text = line.getString().replaceAll("(?i)\\u00A7.", "").trim();
                if (!text.isEmpty())
                    nonEmpty.add(text);
            }
        }


        // Prefer a lore line that looks like an enchantment + level (e.g. ends with
        // a roman numeral or a numeric level). If it contains a known enchantment
        // name, return the canonical enchantment name + the level token so that
        // downstream parsing (isMaxLevel) works reliably.
        for (String s : nonEmpty) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(.*)\\s+([IVXLCDM]+|\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                String namePart = m.group(1).trim();
                String levelPart = m.group(2).trim();
                String matched = EnchantmentUtils.findEnchantmentNameIn(namePart);
                if (matched != null) {
                    return matched + " " + levelPart;
                }
                // If we couldn't map to a known enchantment, still prefer this
                // line because it contains an explicit level.
                return s;
            }
        }

        // Next: if any lore line contains a known enchantment name without an
        // explicit trailing level, prefer that canonical enchant name.
        for (String s : nonEmpty) {
            String matched = EnchantmentUtils.findEnchantmentNameIn(s);
            if (matched != null) {
                return matched; // level will default to 1 when parsed elsewhere
            }
        }

        // If nothing matched the pattern, fall back to the first non-empty lore
        // line if present.
        if (!nonEmpty.isEmpty()) {
            return nonEmpty.get(0);
        }

        // Final fallback: hover name (stripped of color codes). Return null if
        // hover name is empty.
        String hover = stack.getHoverName().getString().replaceAll("(?i)\\u00A7.", "").trim();
        return hover.isEmpty() ? null : hover;
    }

    /**
     * Returns true if the key represents a book that should not be combined
     * further.
     * Parses the enchantment name and level from the key and checks against
     * the known max levels for each enchantment.
     */
    private static boolean isMaxLevel(String key) {
        int lastSpace = key.lastIndexOf(' ');
        String name;
        String levelStr;

        if (lastSpace == -1) {
            name = key;
            levelStr = "1";
        } else {
            String suffix = key.substring(lastSpace + 1).trim();
            // Check if suffix is a valid Roman numeral or numeric string
            if (suffix.matches("^[IVXLCDM]+$") || suffix.matches("^[0-9]+$")) {
                name = key.substring(0, lastSpace).trim();
                levelStr = suffix;
            } else {
                name = key;
                levelStr = "1";
            }
        }

        int currentLevel = EnchantmentUtils.parseLevel(levelStr);
        int maxLevel = EnchantmentUtils.getMaxLevel(name);

        return currentLevel >= maxLevel;
    }
}

