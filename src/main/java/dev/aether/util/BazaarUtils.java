package dev.aether.util;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroWorkerThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

// translated from bazar_buyer.lua: opens /bz, navigates the menus, and instant-buys
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

    private static final long TICK_MS = 50;
    private static final long SELL_SETTLE_MS = 750;
    private static final long SELL_SETTLE_TIMEOUT_MS = 4000;

    private static volatile BazaarBuySession activeBuy;

    public static volatile boolean isBuying = false;
    public static volatile boolean isSellingBazaar = false;
    public static volatile boolean detectedInstantSell = false;
    public static volatile boolean detectedNoItemsToSell = false;

    public static void cancel() {
        activeBuy = null;
        isBuying = false;
        isSellingBazaar = false;
    }

    // runs async on the macro worker thread; itemName is a display-name substring with colour codes stripped
    public static void buy(Minecraft client, String itemName, int count, Consumer<Boolean> callback) {
        if (isBuying) {
            ClientUtils.sendDebugMessage("[BazaarUtils] Already buying, skipping.");
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

    public static CompletableFuture<Boolean> buyAsync(Minecraft client, String itemName, int count) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        buy(client, itemName, count, future::complete);
        return future;
    }

    // /bz -> "Sell Inventory Now" -> "Selling whole inventory"
    public static void instantSell(Minecraft client, Consumer<Boolean> callback) {
        if (isSellingBazaar || isBuying) {
            ClientUtils.sendDebugMessage("[BazaarUtils] Busy with another Bazaar operation.");
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
        if (client == null || count <= 0 || itemName == null || itemName.isBlank()) return false;
        BazaarBuySession purchase = new BazaarBuySession(itemName, count);
        activeBuy = purchase;
        try {
            return executeBuy(client, itemName, count, purchase);
        } finally {
            if (activeBuy == purchase) activeBuy = null;
        }
    }

    public static void onBuyChatMessage(String text) {
        BazaarBuySession purchase = activeBuy;
        if (purchase != null) purchase.onChat(text);
    }

    private static boolean executeBuy(Minecraft client, String itemName, int count, BazaarBuySession purchase) {
        long guiDelay = Math.max(50L, ConfigHelpers.getRandomizedDelay(
                AetherConfig.BAZAAR_DELAY_MIN.get(),
                AetherConfig.BAZAAR_DELAY_MAX.get()));
        long fastDelay = Math.max(75L, guiDelay / 2L);
        long longDelay = guiDelay + 250L;

        // Step 0: Wait for any existing screen to close
        ClientUtils.sendDebugMessage("[BazaarUtils] Waiting for screen to close...");
        long screenCloseDeadline = System.currentTimeMillis() + 3000;
        while (!MacroWorkerThread.getInstance().isCancelled() && client.screen != null && System.currentTimeMillis() < screenCloseDeadline) {
            MacroWorkerThread.sleep(100);
        }
        if (client.screen != null) {
            ClientUtils.sendDebugMessage("[BazaarUtils] Screen still open after 3s, forcing close");
            closeScreen(client);
            MacroWorkerThread.sleep(fastDelay);
        }

        // Step 1: Open the Bazaar for this item
        msg(client, "\u00A7eOpening Bazaar for: \u00A7e" + itemName + " x" + count);
        ClientUtils.sendDebugMessage("[BazaarUtils] Sending /bz command");
        MacroWorkerThread.runOnClient(client, () -> ClientUtils.sendCommand("/bz " + itemName));
        MacroWorkerThread.sleep(longDelay);

        // Step 2: Wait for the Bazaar search result screen
        ClientUtils.sendDebugMessage("[BazaarUtils] Waiting for Bazaar screen...");
        if (!waitForContainerTitle(client, "Bazaar", 10_000)) {
            msg(client, "\u00A7cBazaar screen did not open. Aborting.");
            ClientUtils.sendDebugMessage("[BazaarUtils] STUCK: Bazaar screen never opened");
            closeScreen(client);
            return false;
        }
        ClientUtils.sendDebugMessage("[BazaarUtils] Bazaar screen opened");
        MacroWorkerThread.sleep(fastDelay);

        String target = stripColors(itemName).toLowerCase().trim();
        var stages = new java.util.ArrayList<RetryingClickSequence.Stage>();
        stages.add(new RetryingClickSequence.Stage("Bazaar search result for " + target,
                () -> isBuyInstantlyStage(client),
                () -> clickMatchingSlot(client, target), 8_000L));
        stages.add(new RetryingClickSequence.Stage("Buy Instantly",
                () -> isQuantityScreen(client),
                () -> clickNamedSlot(client, SLOT_BUY_INSTANTLY, name -> name.contains("buy instantly")), 8_000L));

        if (count == 1 || count == 64) {
            int quantitySlot = count == 1 ? SLOT_QTY_1 : SLOT_QTY_64;
            stages.add(new RetryingClickSequence.Stage("quantity " + count,
                    () -> purchase.completed() || isPurchaseDialogOpen(client),
                    () -> clickQuantitySlot(client, quantitySlot), 8_000L));
        } else {
            stages.add(new RetryingClickSequence.Stage("Custom Amount",
                    () -> isSignScreen(client),
                    () -> clickNamedSlot(client, SLOT_QTY_CUSTOM,
                            name -> name.contains("custom") || name.contains("amount") || name.contains("sign")),
                    5_000L));
            stages.add(new RetryingClickSequence.Stage("custom amount sign",
                    () -> purchase.completed() || isPurchaseDialogOpen(client),
                    () -> submitSignAmountIfOpen(client, count), 8_000L));
        }

        long retryDelay = Math.max(2500L, guiDelay);
        if (!RetryingClickSequence.run(stages, fastDelay, retryDelay,
                () -> MacroWorkerThread.getInstance().isCancelled(), MacroWorkerThread::sleep,
                message -> ClientUtils.sendDebugMessage("[BazaarUtils] " + message))) {
            msg(client, "\u00A7cBazaar buy click sequence failed. Aborting.");
            closeScreen(client);
            return false;
        }
        return finishBuy(client, count, fastDelay, guiDelay, purchase);
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
            ClientUtils.sendDebugMessage("[BazaarUtils] Sending /bz command for instant sell");
            MacroWorkerThread.runOnClient(client, () -> ClientUtils.sendCommand("/bz"));
            MacroWorkerThread.sleep(longDelay);

            // Step 2: Click "Sell Inventory Now"
            if (!waitForContainerTitle(client, "Bazaar", Math.max(100, globalDeadline - System.currentTimeMillis()))) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Failed to open Bazaar menu (timeout)");
                closeScreen(client);
                return false;
            }
            MacroWorkerThread.sleep(fastDelay);

            if (!clickMatchingSlotInAny(client, "Sell Inventory Now")) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Could not find 'Sell Inventory Now'");
                closeScreen(client);
                return false;
            }
            MacroWorkerThread.sleep(longDelay);

            // Check if "nothing to sell" appeared after click
            if (detectedNoItemsToSell) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Nothing to sell detected after click. Aborting.");
                closeScreen(client);
                return true;
            }

            // Step 3: Click "Selling whole inventory"
            if (!waitForContainerTitle(client, "Are you sure?",
                    Math.max(100, globalDeadline - System.currentTimeMillis()))) {
                if (detectedNoItemsToSell) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Nothing to sell detected while waiting for menu. Aborting.");
                    closeScreen(client);
                    return true;
                }
                ClientUtils.sendDebugMessage("[BazaarUtils] Failed to open Sell Inventory menu (timeout). Current: "
                                + (client.screen != null ? client.screen.getTitle().getString() : "null"));
                closeScreen(client);
                return false;
            }
            MacroWorkerThread.sleep(fastDelay);

            if (!clickMatchingSlotInAny(client, "Selling whole inventory")) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Could not find 'Selling whole inventory'");
                closeScreen(client);
                return false;
            }

            // Step 4: Wait for chat detection
            ClientUtils.sendDebugMessage("[BazaarUtils] Waiting for 'Executing instant sell...' in chat...");
            String completionTitle = currentContainerTitle(client);
            while (!MacroWorkerThread.getInstance().isCancelled() && System.currentTimeMillis() < globalDeadline
                    && !isInstantSellFinished(detectedInstantSell, detectedNoItemsToSell, completionTitle)) {
                MacroWorkerThread.sleep(100);
                completionTitle = currentContainerTitle(client);
            }

            if (detectedInstantSell) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Instant sell complete.");
            } else if (detectedNoItemsToSell) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Nothing to sell detected during wait.");
            } else if (isInstantSellFinished(false, false, completionTitle)) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Instant sell completed via Bazaar menu return.");
            } else {
                ClientUtils.sendDebugMessage("[BazaarUtils] Timeout waiting for confirm message or process completion.");
            }

            closeScreen(client);
            // Hypixel may reopen the Bazaar home page after our close, which trips the GUI failsafe.
            awaitScreenClosed(client, SELL_SETTLE_MS, SELL_SETTLE_TIMEOUT_MS);
            MacroWorkerThread.sleep(fastDelay);
            return isInstantSellFinished(detectedInstantSell, detectedNoItemsToSell, completionTitle);
        } finally {
            isSellingBazaar = false;
        }
    }

    private static void awaitScreenClosed(Minecraft client, long settleMs, long timeoutMs) {
        if (client.isSameThread()) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        long closedSince = -1L;
        while (!MacroWorkerThread.getInstance().isCancelled() && System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?>) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Container reopened after the sale, closing it again.");
                closedSince = -1L;
                closeScreen(client);
                continue;
            }
            long now = System.currentTimeMillis();
            if (closedSince < 0L) {
                closedSince = now;
            } else if (now - closedSince >= settleMs) {
                return;
            }
            MacroWorkerThread.sleep(TICK_MS);
        }
        if (client.screen instanceof AbstractContainerScreen<?>) {
            ClientUtils.sendDebugMessage("[BazaarUtils] Container still open after the sell settle timeout.");
        }
    }

    private static boolean finishBuy(Minecraft client, int count, long fastDelay, long guiDelay,
                                     BazaarBuySession purchase) {
        var confirmation = new RetryingClickSequence.Stage("purchase confirmation",
                purchase::completed, () -> clickPurchaseConfirmation(client, purchase, guiDelay), 30_000L);
        // a refusal never turns into a confirmation, so stop retrying instead of burning
        // the whole 30s timeout on it
        boolean completed = RetryingClickSequence.run(java.util.List.of(confirmation), 0L,
                Math.max(500L, guiDelay),
                () -> MacroWorkerThread.getInstance().isCancelled() || purchase.blocker() != null,
                MacroWorkerThread::sleep, message -> ClientUtils.sendDebugMessage("[BazaarUtils] " + message));
        closeScreen(client);
        if (!completed) {
            String blocker = purchase.blocker();
            if (blocker != null) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Bazaar refused the purchase of "
                        + count + "x " + purchase.item() + ": " + blocker);
                msg(client, "§cBazaar refused the buy: §e" + blocker);
            } else {
                msg(client, "§cBazaar purchase was not confirmed. Aborting.");
            }
            return false;
        }
        MacroWorkerThread.sleep(fastDelay);
        msg(client, "§aBazaar buy completed (§e" + count + "§7).");
        return true;
    }

    private static boolean clickPurchaseConfirmation(Minecraft client, BazaarBuySession purchase, long guiDelay) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        MacroWorkerThread.runOnClient(client, () -> {
            try {
                result.complete(activeBuy == purchase && pollPurchaseConfirmation(client, purchase, guiDelay));
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        try {
            return result.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            ClientUtils.sendDebugMessage("[BazaarUtils] Confirmation poll failed: " + error.getMessage());
            return false;
        }
    }

    private static boolean pollPurchaseConfirmation(Minecraft client, BazaarBuySession purchase, long guiDelay) {
        if (purchase.completed() || !(client.screen instanceof AbstractContainerScreen<?> screen)) return false;
        var items = new java.util.ArrayList<BazaarBuySession.MenuItem>();
        for (Slot slot : screen.getMenu().slots) {
            if (client.player == null || slot.container == client.player.getInventory() || !slot.hasItem()) continue;
            ItemLore lore = slot.getItem().get(DataComponents.LORE);
            items.add(new BazaarBuySession.MenuItem(slot.index, slot.getItem().getHoverName().getString(),
                    slot.getItem().is(Items.BARRIER),
                    lore == null ? java.util.List.of() : lore.lines().stream().map(Component::getString).toList()));
        }
        int confirmSlot = purchase.confirmationSlot(screen.getMenu().containerId, screen.getTitle().getString(),
                items, System.currentTimeMillis(), guiDelay);
        if (confirmSlot >= 0) {
            ClientUtils.sendDebugMessage("[BazaarUtils] Confirming unlocked purchase in slot " + confirmSlot);
            ClientUtils.performSlotClick(screen, confirmSlot, 0, ContainerInput.PICKUP);
            return true;
        }
        return false;
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
                ClientUtils.sendDebugMessage("[BazaarUtils] Exact match found: '" + name + "' at slot " + slotIdx);
                int finalSlotIdx = slotIdx;
                int containerId = screen.getMenu().containerId;
                MacroWorkerThread.runOnClient(client, () -> {
                    if (client.screen instanceof AbstractContainerScreen<?> s
                            && s.getMenu().containerId == containerId
                            && slotNameMatches(s, finalSlotIdx, candidate -> candidate.equals(target))) {
                        ClientUtils.performSlotClick(s, finalSlotIdx, 0, ContainerInput.PICKUP);
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
                ClientUtils.sendDebugMessage("[BazaarUtils] Found '" + targetName + "' at slot " + slotIdx);
                MacroWorkerThread.runOnClient(client, () -> {
                    if (client.screen instanceof AbstractContainerScreen<?> s) {
                        ClientUtils.performSlotClick(s, slotIdx, 0, ContainerInput.PICKUP);
                    }
                });
                return true;
            }
        }
        return false;
    }

    private static boolean clickNamedSlot(Minecraft client, int slotIdx, Predicate<String> expectedName) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !slotNameMatches(screen, slotIdx, expectedName)) {
            return false;
        }
        int containerId = screen.getMenu().containerId;
        MacroWorkerThread.runOnClient(client, () -> {
            if (client.screen instanceof AbstractContainerScreen<?> current
                    && current.getMenu().containerId == containerId
                    && slotNameMatches(current, slotIdx, expectedName)) {
                ClientUtils.performSlotClick(current, slotIdx, 0, ContainerInput.PICKUP);
            }
        });
        return true;
    }

    private static boolean clickQuantitySlot(Minecraft client, int slotIdx) {
        return isQuantityScreen(client) && clickNamedSlot(client, slotIdx, name -> true);
    }

    private static boolean slotNameMatches(AbstractContainerScreen<?> screen, int slotIdx,
                                           Predicate<String> expectedName) {
        if (slotIdx < 0 || slotIdx >= screen.getMenu().slots.size()) return false;
        Slot slot = screen.getMenu().slots.get(slotIdx);
        if (!slot.hasItem()) return false;
        String name = stripColors(slot.getItem().getHoverName().getString()).toLowerCase().trim();
        return expectedName.test(name);
    }

    private static boolean waitForContainerTitle(Minecraft client, String substring, long timeoutMs) {
        String target = stripColors(substring).toLowerCase();
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastTitle = "";
        int iterations = 0;
        while (!MacroWorkerThread.getInstance().isCancelled() && System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                String title = stripColors(screen.getTitle().getString()).toLowerCase();
                if (!title.equals(lastTitle)) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Current screen: '" + title + "' (waiting for '" + target + "')");
                    lastTitle = title;
                }
                if (title.contains(target))
                    return true;
            } else if (iterations % 20 == 0) {
                ClientUtils.sendDebugMessage("[BazaarUtils] No container screen open (waiting for '" + target + "')");
            }
            iterations++;
            MacroWorkerThread.sleep(TICK_MS);
        }
        ClientUtils.sendDebugMessage("[BazaarUtils] Timeout waiting for '" + target + "'. Last screen: '" + lastTitle + "'");
        return false;
    }

    static boolean isInstantSellFinished(boolean sold, boolean nothingToSell, String screenTitle) {
        return sold || nothingToSell
                || screenTitle != null && stripColors(screenTitle).toLowerCase().contains("bazaar");
    }

    private static String currentContainerTitle(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen ? screen.getTitle().getString() : null;
    }

    private static boolean isSignScreen(Minecraft client) {
        return client.screen instanceof AbstractSignEditScreen;
    }

    private static boolean isQuantityScreen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && slotNameMatches(screen, SLOT_QTY_CUSTOM,
                name -> name.contains("custom") || name.contains("amount") || name.contains("sign"));
    }

    private static boolean isBuyInstantlyStage(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && slotNameMatches(screen, SLOT_BUY_INSTANTLY, name -> name.contains("buy instantly"));
    }

    private static boolean isPurchaseDialogOpen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && BazaarBuySession.isPurchaseDialog(screen.getTitle().getString());
    }

    private static boolean submitSignAmountIfOpen(Minecraft client, int amount) {
        if (!isSignScreen(client)) return false;
        submitSignAmount(client, amount);
        return true;
    }

    private static void submitSignAmount(Minecraft client, int amount) {
        String text = String.valueOf(amount);
        MacroWorkerThread.runOnClient(client, () -> {
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
        MacroWorkerThread.runOnClient(client, () -> {
            if (client.screen instanceof AbstractSignEditScreen signScreen) {
                signScreen.onClose();
            }
        });
    }

    private static void closeScreen(Minecraft client) {
        // closeGui blocks while re-closing reopened GUIs, so it must stay off the render thread.
        if (client.isSameThread()) {
            ClientUtils.closeGuiAsync(client);
        } else {
            ClientUtils.closeGui(client);
        }
    }

    private static String stripColors(String s) {
        return TablistUtils.stripColors(s);
    }

    private static void msg(Minecraft client, String text) {
        if (!MacroWorkerThread.getInstance().isCancelled()) ClientUtils.sendMessage(text);
    }
}
