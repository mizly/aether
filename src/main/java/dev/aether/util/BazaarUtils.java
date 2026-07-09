package dev.aether.util;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroWorkerThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Utility for buying items from the Bazaar programmatically.
 * Translated from bazar_buyer.lua - opens /bz, navigates menus,
 * and instant-buys the requested amount.
 *
 * <p>
 * Usage: {@code BazaarUtils.buy(client, "Cobblestone", 64, success -> ...)}
 */
public final class BazaarUtils {

    private BazaarUtils() {
    }

    // The search-result slot range inside the "Bazaar" browser chest (0-indexed).
    // User-facing slots 11..43 map to Java slots 10..42.
    private static final int SEARCH_SLOT_START = 10;
    private static final int SEARCH_SLOT_END = 42;

    // Slot indices inside the item-specific Bazaar page
    private static final int SLOT_BUY_INSTANTLY = 10;

    // Slots inside the "How many do you want?" / Instant Buy page
    private static final int SLOT_QTY_1 = 10;
    private static final int SLOT_QTY_64 = 12; // a full stack preset
    private static final int SLOT_QTY_CUSTOM = 16; // "Custom Amount" (sign)

    // Slot inside the "Confirm Instant Buy" page
    private static final int SLOT_CONFIRM = 13;

    private static final long TICK_MS = 50;

    public static volatile boolean isBuying = false;
    public static volatile boolean isSellingBazaar = false;
    public static volatile boolean detectedInstantSell = false;
    public static volatile boolean detectedNoItemsToSell = false;

    /**
     * Buy {@code count} of {@code itemName} from the Bazaar.
     * Runs asynchronously on the macro worker thread.
     *
     * @param client   Minecraft instance
     * @param itemName Display-name substring to match in the Bazaar search results
     *                 (colour codes stripped)
     * @param count    Amount to buy (1, 64, or custom)
     * @param callback Called with {@code true} on success, {@code false} on
     *                 failure/timeout
     */
    public static void buy(Minecraft client, String itemName, int count, Consumer<Boolean> callback) {
        if (isBuying) {
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Already buying, skipping.");
            if (callback != null)
                callback.accept(false);
            return;
        }
        isBuying = true;
        MacroWorkerThread.getInstance().submit("BazaarBuy-" + itemName, () -> {
            try {
                boolean result = executeBuy(client, itemName, count);
                if (callback != null)
                    callback.accept(result);
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null)
                    callback.accept(false);
            } finally {
                isBuying = false;
            }
        });
    }

    /**
     * Blocking version that returns a {@link CompletableFuture}.
     */
    public static CompletableFuture<Boolean> buyAsync(Minecraft client, String itemName, int count) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        buy(client, itemName, count, future::complete);
        return future;
    }

    /**
     * Sell all bazaar-able items in inventory instantly.
     * Steps: /bz -> "Sell Inventory Now" -> "Selling whole inventory"
     */
    public static void instantSell(Minecraft client, Consumer<Boolean> callback) {
        if (isSellingBazaar || isBuying) {
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Busy with another Bazaar operation.");
            if (callback != null)
                callback.accept(false);
            return;
        }
        isSellingBazaar = true;
        detectedInstantSell = false;
        MacroWorkerThread.getInstance().submit("BazaarInstantSell", () -> {
            try {
                boolean result = executeInstantSell(client);
                if (callback != null)
                    callback.accept(result);
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null)
                    callback.accept(false);
            } finally {
                isSellingBazaar = false;
            }
        });
    }

    // -- Core state machine (blocking, runs on worker thread) --

    public static boolean executeBuy(Minecraft client, String itemName, int count) {
        long guiDelay = Math.max(50L, ConfigHelpers.getRandomizedDelay(
                AetherConfig.BAZAAR_DELAY_MIN.get(),
                AetherConfig.BAZAAR_DELAY_MAX.get()));
        long fastDelay = Math.max(75L, guiDelay / 2L);
        long longDelay = guiDelay + 250L;

        // Step 0: Wait for any existing screen to close
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for screen to close...");
        long screenCloseDeadline = System.currentTimeMillis() + 3000;
        while (client.screen != null && System.currentTimeMillis() < screenCloseDeadline) {
            MacroWorkerThread.sleep(100);
        }
        if (client.screen != null) {
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Screen still open after 3s, forcing close");
            closeScreen(client);
            MacroWorkerThread.sleep(fastDelay);
        }

        // Step 1: Open the Bazaar for this item
        msg(client, "\u00A7eOpening Bazaar for: \u00A7e" + itemName + " x" + count);
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Sending /bz command");
        ClientUtils.sendCommand(client, "/bz " + itemName);
        MacroWorkerThread.sleep(longDelay);

        // Step 2: Wait for the Bazaar search result screen
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for Bazaar screen...");
        if (!waitForContainerTitle(client, "Bazaar", 10_000)) {
            msg(client, "\u00A7cBazaar screen did not open. Aborting.");
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] STUCK: Bazaar screen never opened");
            closeScreen(client);
            return false;
        }
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Bazaar screen opened");
        MacroWorkerThread.sleep(fastDelay);

        // Step 3: Find the matching item in the search results
        String target = stripColors(itemName).toLowerCase().trim();
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Searching for item: " + target);
        if (!clickMatchingSlot(client, target)) {
            msg(client, "\u00A7cCould not find '\u00A7e" + itemName + "\u00A7c' in Bazaar. Aborting.");
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] STUCK: Item not found in search results");
            closeScreen(client);
            return false;
        }
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Clicked item, waiting for item page");
        MacroWorkerThread.sleep(fastDelay); // Wait for click to process

        // Step 4: Wait for item page by checking slot 10 for "Buy Instantly"
        if (!waitForBuyInstantlyStage(client, 8000)) {
            msg(client, "\u00A7cItem buy screen did not open. Aborting.");
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] STUCK: Buy Instantly stage never opened");
            closeScreen(client);
            return false;
        }
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Buy Instantly stage opened");
        MacroWorkerThread.sleep(fastDelay);

        // Step 5: Click "Buy Instantly"
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Clicking Buy Instantly (slot " + SLOT_BUY_INSTANTLY + ")");
        clickSlot(client, SLOT_BUY_INSTANTLY);
        MacroWorkerThread.sleep(fastDelay); // Wait for click to process

        // Step 6: Wait for the Instant Buy quantity screen
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for quantity selection screen...");
        if (!waitForQuantityScreen(client, 8000)) {
            msg(client, "\u00A7cInstant Buy screen did not open. Aborting.");
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] STUCK: Instant Buy screen never opened");
            closeScreen(client);
            return false;
        }
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Instant Buy screen opened");
        MacroWorkerThread.sleep(fastDelay);

        // Step 7: Pick quantity
        if (count == 1) {
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Selecting quantity: 1 (slot " + SLOT_QTY_1 + ")");
            clickSlot(client, SLOT_QTY_1);
            MacroWorkerThread.sleep(fastDelay); // Wait for click
            MacroWorkerThread.sleep(guiDelay);
            // Preset quantities buy directly - no confirm screen
            closeScreen(client);
            MacroWorkerThread.sleep(fastDelay);
            msg(client, "\u00A7aBazaar buy completed (\u00A7e" + count + "\u00A77).");
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Buy complete");
            return true;
        } else if (count == 64) {
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Selecting quantity: 64 (slot " + SLOT_QTY_64 + ")");
            clickSlot(client, SLOT_QTY_64);
            MacroWorkerThread.sleep(fastDelay); // Wait for click
            MacroWorkerThread.sleep(guiDelay);
            closeScreen(client);
            MacroWorkerThread.sleep(fastDelay);
            msg(client, "\u00A7aBazaar buy completed (\u00A7e" + count + "\u00A77).");
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Buy complete");
            return true;
        } else {
            // Custom amount - need to use the sign editor
            ClientUtils.sendDebugMessage(client,
                    "[BazaarUtils] Selecting custom quantity: " + count + " (slot " + SLOT_QTY_CUSTOM + ")");
            clickSlot(client, SLOT_QTY_CUSTOM);
            MacroWorkerThread.sleep(fastDelay); // Wait for click
            MacroWorkerThread.sleep(longDelay);

            // Wait for sign editor to appear
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for sign screen...");
            if (!waitForSignScreen(client, 5000)) {
                msg(client, "\u00A7cSign screen did not open. Aborting.");
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] STUCK: Sign screen never opened");
                closeScreen(client);
                return false;
            }
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Sign screen opened, submitting amount");
            MacroWorkerThread.sleep(fastDelay);

            // Type the amount into the sign
            submitSignAmount(client, count);
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Sign submitted");
            MacroWorkerThread.sleep(longDelay);

            return finishBuy(client, count, fastDelay, guiDelay);
        }
    }

    public static boolean executeInstantSell(Minecraft client) {
        isSellingBazaar = true;
        detectedInstantSell = false;
        detectedNoItemsToSell = false;
        try {
            long startTime = System.currentTimeMillis();
            long globalDeadline = startTime + 10000;

            long guiDelay = Math.max(50L, ConfigHelpers.getRandomizedDelay(
                    AetherConfig.BAZAAR_DELAY_MIN.get(),
                    AetherConfig.BAZAAR_DELAY_MAX.get()));
            long fastDelay = Math.max(75L, guiDelay / 2L);
            long longDelay = guiDelay + 250L;

            // Step 1: Open Bazaar
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Sending /bz command for instant sell");
            ClientUtils.sendCommand(client, "/bz");
            MacroWorkerThread.sleep(longDelay);

            // Step 2: Click "Sell Inventory Now"
            if (!waitForContainerTitle(client, "Bazaar", Math.max(100, globalDeadline - System.currentTimeMillis()))) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Failed to open Bazaar menu (timeout)");
                closeScreen(client);
                return false;
            }
            MacroWorkerThread.sleep(fastDelay);

            if (!clickMatchingSlotInAny(client, "Sell Inventory Now")) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Could not find 'Sell Inventory Now'");
                closeScreen(client);
                return false;
            }
            MacroWorkerThread.sleep(longDelay);

            // Check if "nothing to sell" appeared after click
            if (detectedNoItemsToSell) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Nothing to sell detected after click. Aborting.");
                closeScreen(client);
                return true;
            }

            // Step 3: Click "Selling whole inventory"
            if (!waitForContainerTitle(client, "Are you sure?",
                    Math.max(100, globalDeadline - System.currentTimeMillis()))) {
                if (detectedNoItemsToSell) {
                    ClientUtils.sendDebugMessage(client,
                            "[BazaarUtils] Nothing to sell detected while waiting for menu. Aborting.");
                    closeScreen(client);
                    return true;
                }
                ClientUtils.sendDebugMessage(client,
                        "[BazaarUtils] Failed to open Sell Inventory menu (timeout). Current: "
                                + (client.screen != null ? client.screen.getTitle().getString() : "null"));
                closeScreen(client);
                return false;
            }
            MacroWorkerThread.sleep(fastDelay);

            if (!clickMatchingSlotInAny(client, "Selling whole inventory")) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Could not find 'Selling whole inventory'");
                closeScreen(client);
                return false;
            }

            // Step 4: Wait for chat detection
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for 'Executing instant sell...' in chat...");
            while (System.currentTimeMillis() < globalDeadline && !detectedInstantSell && !detectedNoItemsToSell) {
                MacroWorkerThread.sleep(100);
            }

            if (detectedInstantSell) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Instant sell complete.");
            } else if (detectedNoItemsToSell) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Nothing to sell detected during wait.");
            } else {
                ClientUtils.sendDebugMessage(client,
                        "[BazaarUtils] Timeout waiting for confirm message or process completion.");
            }

            // Safety delay to allow GUI transitions to settle
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] Concluding sequence, waiting 1s...");
            MacroWorkerThread.sleep(2000);

            closeScreen(client);
            MacroWorkerThread.sleep(fastDelay);
            return detectedInstantSell || detectedNoItemsToSell;
        } finally {
            isSellingBazaar = false;
        }
    }

    private static boolean finishBuy(Minecraft client, int count, long fastDelay, long guiDelay) {
        // Wait for the Confirm screen
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for Confirm screen...");
        if (!waitForContainerTitle(client, "Confirm Instant Buy", 5000)) {
            // Some quantities skip the confirm screen and buy directly
            ClientUtils.sendDebugMessage(client, "[BazaarUtils] No confirm screen - may have bought directly.");
            closeScreen(client);
            MacroWorkerThread.sleep(fastDelay);
            msg(client, "\u00A7aBazaar buy completed (\u00A7e" + count + "\u00A77).");
            return true;
        }
        ClientUtils.sendDebugMessage(client,
                "[BazaarUtils] Confirm screen opened, clicking confirm (slot " + SLOT_CONFIRM + ")");
        MacroWorkerThread.sleep(fastDelay);

        // Click confirm
        clickSlot(client, SLOT_CONFIRM);
        MacroWorkerThread.sleep(fastDelay); // Wait for click
        MacroWorkerThread.sleep(guiDelay);
        handlePostConfirmWarning(client, fastDelay, guiDelay);

        closeScreen(client);
        MacroWorkerThread.sleep(fastDelay);
        msg(client, "\u00A7aBazaar buy completed (\u00A7e" + count + "\u00A77).");
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Buy complete");
        return true;
    }

    // -- Helpers --

    private static boolean clickMatchingSlot(Minecraft client, String target) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen))
            return false;

        for (int slotIdx = SEARCH_SLOT_START; slotIdx <= SEARCH_SLOT_END; slotIdx++) {
            if (slotIdx >= screen.getMenu().slots.size()) {
                break;
            }
            Slot slot = screen.getMenu().slots.get(slotIdx);
            if (!slot.hasItem())
                continue;

            String name = stripColors(slot.getItem().getHoverName().getString()).toLowerCase().trim();

            // Priority 1: Exact Match
            if (name.equals(target)) {
                ClientUtils.sendDebugMessage(client,
                        "[BazaarUtils] Exact match found: '" + name + "' at slot " + slotIdx);
                int finalSlotIdx = slotIdx;
                client.execute(() -> {
                    if (client.screen instanceof AbstractContainerScreen<?> s) {
                        ClientUtils.performSlotClick(client, s, finalSlotIdx, 0, ContainerInput.PICKUP);
                    }
                });
                return true;
            }
        }

        return false;
    }

    private static boolean clickMatchingSlotInAny(Minecraft client, String targetName) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen))
            return false;
        String target = stripColors(targetName).toLowerCase();

        // Search through all slots in the menu
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.hasItem())
                continue;
            String name = stripColors(slot.getItem().getHoverName().getString()).toLowerCase();
            if (name.contains(target)) {
                int slotIdx = slot.index;
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Found '" + targetName + "' at slot " + slotIdx);
                client.execute(() -> {
                    if (client.screen instanceof AbstractContainerScreen<?> s) {
                        ClientUtils.performSlotClick(client, s, slotIdx, 0, ContainerInput.PICKUP);
                    }
                });
                return true;
            }
        }
        return false;
    }

    private static void clickSlot(Minecraft client, int slotIdx) {
        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?> s) {
                if (slotIdx < s.getMenu().slots.size()) {
                    Slot slot = s.getMenu().slots.get(slotIdx);
                    String itemName = slot.hasItem() ? slot.getItem().getHoverName().getString() : "[empty]";
                    ClientUtils.sendDebugMessage(client,
                            "[BazaarUtils] Clicking slot " + slotIdx + ": " + stripColors(itemName));
                    ClientUtils.performSlotClick(client, s, slotIdx, 0, ContainerInput.PICKUP);
                } else {
                    ClientUtils.sendDebugMessage(client, "[BazaarUtils] ERROR: Slot " + slotIdx
                            + " out of bounds (size=" + s.getMenu().slots.size() + ")");
                }
            } else {
                ClientUtils.sendDebugMessage(client,
                        "[BazaarUtils] ERROR: Not in container screen when trying to click");
            }
        });
    }

    private static boolean waitForContainerTitle(Minecraft client, String substring, long timeoutMs) {
        String target = stripColors(substring).toLowerCase();
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastTitle = "";
        int iterations = 0;
        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                String title = stripColors(screen.getTitle().getString()).toLowerCase();
                if (!title.equals(lastTitle)) {
                    ClientUtils.sendDebugMessage(client,
                            "[BazaarUtils] Current screen: '" + title + "' (waiting for '" + target + "')");
                    lastTitle = title;
                }
                if (title.contains(target))
                    return true;
            } else if (iterations % 20 == 0) {
                ClientUtils.sendDebugMessage(client,
                        "[BazaarUtils] No container screen open (waiting for '" + target + "')");
            }
            iterations++;
            MacroWorkerThread.sleep(TICK_MS);
        }
        ClientUtils.sendDebugMessage(client,
                "[BazaarUtils] Timeout waiting for '" + target + "'. Last screen: '" + lastTitle + "'");
        return false;
    }

    private static boolean waitForSignScreen(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractSignEditScreen)
                return true;
            MacroWorkerThread.sleep(TICK_MS);
        }
        return false;
    }

    private static boolean waitForQuantityScreen(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int iterations = 0;
        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                if (SLOT_QTY_CUSTOM < screen.getMenu().slots.size()) {
                    Slot customSlot = screen.getMenu().slots.get(SLOT_QTY_CUSTOM);
                    if (customSlot.hasItem()) {
                        String itemName = stripColors(customSlot.getItem().getHoverName().getString()).toLowerCase();
                        if (itemName.contains("custom") || itemName.contains("amount") || itemName.contains("sign")) {
                            ClientUtils.sendDebugMessage(client,
                                    "[BazaarUtils] Detected quantity screen via slot 16: " + itemName);
                            return true;
                        }
                    }
                }
            }
            if (iterations % 20 == 0) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for quantity screen (checking slot 16)...");
            }
            iterations++;
            MacroWorkerThread.sleep(TICK_MS);
        }
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Timeout waiting for quantity screen");
        return false;
    }

    private static boolean waitForBuyInstantlyStage(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int iterations = 0;
        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                if (SLOT_BUY_INSTANTLY < screen.getMenu().slots.size()) {
                    Slot buySlot = screen.getMenu().slots.get(SLOT_BUY_INSTANTLY);
                    if (buySlot.hasItem()) {
                        String itemName = stripColors(buySlot.getItem().getHoverName().getString()).toLowerCase();
                        if (itemName.contains("buy instantly")) {
                            ClientUtils.sendDebugMessage(client,
                                    "[BazaarUtils] Detected buy stage via slot 10: " + itemName);
                            return true;
                        }
                    }
                }
            }
            if (iterations % 20 == 0) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Waiting for buy stage (checking slot 10)...");
            }
            iterations++;
            MacroWorkerThread.sleep(TICK_MS);
        }
        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Timeout waiting for buy stage");
        return false;
    }

    private static void handlePostConfirmWarning(Minecraft client, long fastDelay, long guiDelay) {
        if (!isWarningBarrierInConfirmSlot(client)) {
            return;
        }

        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Confirm warning detected, waiting for slot 13 to unlock");
        long deadline = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < deadline) {
            if (!(client.screen instanceof AbstractContainerScreen<?>)) {
                ClientUtils.sendDebugMessage(client, "[BazaarUtils] Confirm warning screen closed while waiting");
                return;
            }

            if (!isWarningBarrierInConfirmSlot(client)) {
                ClientUtils.sendDebugMessage(client,
                        "[BazaarUtils] Confirm warning cleared, waiting Bazaar action delay before clicking slot 13");
                MacroWorkerThread.sleep(guiDelay);
                clickSlot(client, SLOT_CONFIRM);
                MacroWorkerThread.sleep(fastDelay);
                return;
            }

            MacroWorkerThread.sleep(TICK_MS);
        }

        ClientUtils.sendDebugMessage(client, "[BazaarUtils] Timed out waiting for confirm warning to clear");
    }

    private static boolean isWarningBarrierInConfirmSlot(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        if (SLOT_CONFIRM >= screen.getMenu().slots.size()) {
            return false;
        }

        Slot slot = screen.getMenu().slots.get(SLOT_CONFIRM);
        if (!slot.hasItem() || !slot.getItem().is(Items.BARRIER)) {
            return false;
        }

        String itemName = stripColors(slot.getItem().getHoverName().getString()).trim();
        return itemName.equalsIgnoreCase("WARNING");
    }

    private static void submitSignAmount(Minecraft client, int amount) {
        String text = String.valueOf(amount);
        client.execute(() -> {
            if (!(client.screen instanceof AbstractSignEditScreen signScreen)) {
                msg(client, "\u00A7cSign screen not found for amount input!");
                return;
            }
            // Clear existing text: select all, then delete
            signScreen.keyPressed(new net.minecraft.client.input.KeyEvent(
                    org.lwjgl.glfw.GLFW.GLFW_KEY_A, 0,
                    org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
            signScreen.keyPressed(new net.minecraft.client.input.KeyEvent(
                    org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE, 0, 0));
            // Type each digit
            for (char c : text.toCharArray()) {
                signScreen.charTyped(new net.minecraft.client.input.CharacterEvent(c));
            }
        });
        // Small delay then close the sign - onClose() triggers onDone -> removed -> sends
        // packet
        MacroWorkerThread
                .sleep(Math.max(50L, ConfigHelpers.getRandomizedDelay(
                        AetherConfig.BAZAAR_DELAY_MIN.get(),
                        AetherConfig.BAZAAR_DELAY_MAX.get()) / 2L));
        client.execute(() -> {
            if (client.screen instanceof AbstractSignEditScreen signScreen) {
                signScreen.onClose();
            }
        });
    }

    private static void closeScreen(Minecraft client) {
        client.execute(() -> {
            if (client.player != null && client.screen != null) {
                client.player.closeContainer();
            }
        });
    }

    private static String stripColors(String s) {
        return TablistUtils.stripColors(s);
    }

    private static void msg(Minecraft client, String text) {
        ClientUtils.sendMessage(client, text);
    }
}
