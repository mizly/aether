package dev.aether.modules.visitor;

import dev.aether.config.AetherConfig;
import dev.aether.util.TablistUtils;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.util.CommandUtils;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.CustomData;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.modules.profit.ProfitManager;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class VisitorManager {
    private static final Pattern VISITORS_PATTERN = Pattern.compile("Visitors:\\s*\\(?(\\d+)\\)?");
    private static final Pattern OFFER_ACCEPTED_PATTERN = Pattern.compile(
            "^OFFER ACCEPTED with (.+?) \\(([A-Z]+(?: [A-Z]+)*)\\)$");
    private static final long VISITOR_REENTRY_COOLDOWN_MS = 60_000L;

    private static VisitorOffer pendingOffer = null;
    private static volatile long visitorReentryCooldownUntilMs = 0L;

    // -- Inner Data Classes --

    public static class VisitorOffer {
        public String visitorName;
        public long totalCost = 0;
    }

    // -- Existing Methods (unchanged) --

    public static int getVisitorCount(Minecraft client) {
        if (!AetherConfig.AUTO_VISITOR.get() || client.level == null)
            return 0;

        if (!client.isSameThread()) {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            client.execute(() -> {
                future.complete(getVisitorCount(client));
            });
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                return 0;
            }
        }

        try {
            for (String line : TablistUtils.getRawTabLines(client)) {
                Matcher m = VISITORS_PATTERN.matcher(line);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static void handleVisitorScriptFinished(Minecraft client) {
        startVisitorReentryCooldown(client);
        ClientUtils.sendMessage("\u00A7aVisitor sequence complete. Returning to farm...", true);
        MacroWorkerThread.getInstance().submit("VisitorFinished-ReturnToFarm", () -> {
            try {
                if (MacroWorkerThread.shouldAbortTask(client))
                    return;
                ClientUtils.sendDebugMessage("Warping to garden...");
                CommandUtils.warpGarden();
                VisitorsMacro.reenableCompactorsIfPending(client);
                PestReturnManager.isReturningFromPestVisitor = true;
                if (MacroWorkerThread.shouldAbortTask(client))
                    return;
                ClientUtils.sendDebugMessage("Finalizing return to farm...");
                finalizeReturnToFarm(client);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void finalizeReturnToFarm(Minecraft client) {
        if (MacroWorkerThread.shouldAbortTask(client))
            return;
        ClientUtils.sendDebugMessage("finalizeReturnToFarm triggered. State: " + MacroStateManager.getCurrentState());
        if (MacroStateManager.getCurrentState() == MacroState.State.OFF)
            return;

        int visitors = getVisitorCount(client);
        if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && shouldSkipVisitorsDuringJacobsContest(client, true)) {
            ClientUtils.sendDebugMessage("Visitor threshold met, but Jacob's Contest window is active. Continuing farming.");
        } else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && !isVisitorReentryCooldownActive(client, true)) {
            ClientUtils.sendMessage("\u00A7eVisitor threshold met (" + visitors + "). Redirecting to Visitors...", true);
            
            client.execute(() -> VisitorsMacro.start(client));
            return;
        }

        if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) {
            ClientUtils.sendDebugMessage("Visitor threshold met, but re-entry cooldown is active. Continuing farming.");
        }

        client.execute(() -> {
            GearManager.swapToFarmingTool(client);
        });
        MacroWorkerThread.sleep(250);

        if (AetherConfig.LOADOUT_SLOT_FARMING.get() > 0
                && LoadoutManager.trackedLoadoutSlot != AetherConfig.LOADOUT_SLOT_FARMING.get()) {
            ClientUtils.sendMessage("\u00A7eRestoring farming loadout (slot " + AetherConfig.LOADOUT_SLOT_FARMING.get() + ")...", true);
            GearManager.ensureLoadoutSlot(client, AetherConfig.LOADOUT_SLOT_FARMING.get());
            if (LoadoutManager.isSwappingLoadout) {
                ClientUtils.sendDebugMessage("finalizeReturnToFarm: Waiting for loadout GUI...");
                ClientUtils.waitForWardrobeGui();
                ClientUtils.sendDebugMessage("finalizeReturnToFarm: Loadout GUI detected, waiting for swap to complete...");
                while (LoadoutManager.isSwappingLoadout)
                    MacroWorkerThread.sleep(50);
                while (LoadoutManager.loadoutCleanupTicks > 0)
                    MacroWorkerThread.sleep(50);
                MacroWorkerThread.sleep(350);
                ClientUtils.sendDebugMessage("finalizeReturnToFarm: Loadout swap fully complete.");
            }
        }


        ClientUtils.waitForGearAndGui();

        ClientUtils.waitForGearAndGui();
        PestReturnManager.isReturningFromPestVisitor = false;
        PestReturnManager.isReturnToLocationActive = false;
        restartFarmingAfterVisitors(client);
        PestPrepSwapManager.prepSwappedForCurrentPestCycle = false;
        PestManager.isCleaningInProgress = false;
    }

    private static void restartFarmingAfterVisitors(Minecraft client) {
        ClientUtils.sendMessage("\u00A7aRestarting farming...", true);
        ClientUtils.sendDebugMessage("Restarting farming macro after visitor sequence.");
        client.execute(() -> {
            if (client.player == null) {
                return;
            }

            FarmingMacroManager.disable(client);
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            GearManager.swapToFarmingTool(client);
            FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig());
        });
    }

    // -- Visitor ROI: GUI Scanning --

    @SuppressWarnings("rawtypes")
    public static void scanVisitorGui(Minecraft client,
            AbstractContainerScreen screen) {
        if (!MacroStateManager.isMacroRunning() || client.player == null)
            return;

        Component titleComp = screen.getTitle();
        String title = titleComp.getString().trim();

        // The accept button is usually in slot 29 of a 54-slot chest
        int slotIndex = 29;
        if (screen.getMenu().slots.size() <= slotIndex)
            return;

        Slot slot = screen.getMenu().getSlot(slotIndex);
        if (slot == null || !slot.hasItem())
            return;

        ItemStack stack = slot.getItem();
        String name = stack.getHoverName().getString();

        if (!name.contains("Accept Offer"))
            return;

        VisitorOffer offer = new VisitorOffer();
        offer.visitorName = title;
        StringBuilder costBreakdown = new StringBuilder("\u00A7d[Aether] \u00A77Costs: ");

        ItemLore loreCmp = stack.get(DataComponents.LORE);
        if (loreCmp == null) {
            ClientUtils.sendMessage("\u00A7cNo lore found on the Accept Offer button!", false);
            return;
        }
        List<Component> lore = loreCmp.lines();
        boolean parsingRequirements = false;

        for (Component line : lore) {
            String text = line.getString().replaceAll("\u00A7[0-9a-fk-or]", "").trim();
            if (text.isEmpty())
                continue;

            if (text.contains("Items Required:")) {
                parsingRequirements = true;
                continue;
            }
            if (text.contains("Rewards:")) {
                parsingRequirements = false;
                continue;
            }

            if (parsingRequirements && !text.contains("Farming XP") && !text.contains("Garden Experience")) {
                parseRequirement(client, text, offer, stack, costBreakdown);
            }
        }

        if (offer.totalCost > 0) {
            pendingOffer = offer;
        }
    }

    // -- Helpers --

    private static String formatPrice(long price) {
        if (price >= 1_000_000)
            return String.format("%.1fM", price / 1_000_000.0);
        if (price >= 1_000)
            return String.format("%.1fk", price / 1_000.0);
        return String.valueOf(price);
    }

    private static void parseRequirement(Minecraft client, String text, VisitorOffer offer, ItemStack stack,
            StringBuilder breakdown) {
        // Handle "Enchanted Hay Bale x256" format
        Matcher m = Pattern.compile("(.+?)\\s+x(\\d+)$").matcher(text);
        String itemName;
        long count = 1;

        if (m.find()) {
            itemName = m.group(1).trim();
            count = Long.parseLong(m.group(2));
        } else {
            itemName = text.trim();
        }

        String id = resolveId(itemName, stack);
        double price = ProfitManager.getItemPrice(id != null ? id : itemName);

        if (price > 0) {
            long total = (long) (price * count);
            offer.totalCost += total;
            breakdown.append("\u00A7e").append(count).append("x ").append(itemName)
                    .append(" \u00A77(\u00A7c").append(formatPrice(total)).append("\u00A77), ");
        } else {
            breakdown.append("\u00A7c?x ").append(itemName).append(" (price unknown), ");
            System.out.println("[Aether] Unknown Visitor Cost Item: " + itemName + " (ID: " + id + ")");
        }
    }

    /**
     * Resolves a Skyblock Item ID from NBT custom data, or falls back to the
     * Cofl API search cache in ProfitManager.fetchIdByName.
     */
    @SuppressWarnings("unchecked")
    private static String resolveId(String name, ItemStack scannerStack) {
        // 1. Try NBT lookup from the "Accept Offer" stack's custom data
        try {
            CustomData customData = scannerStack.get(DataComponents.CUSTOM_DATA);
            if (customData != null) {
                CompoundTag tag = customData.copyTag();
                if (tag.contains("ExtraAttributes")) {
                    Optional<CompoundTag> eaOpt = tag.getCompound("ExtraAttributes");
                    if (eaOpt.isPresent()) {
                        CompoundTag ea = eaOpt.get();
                        if (ea.contains("id")) {
                            String nbtId = ea.getString("id").orElse("");
                            if (!nbtId.isEmpty()) {
                                return nbtId;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Aether] NBT lookup failed for '" + name + "': " + e.getMessage());
        }

        // 2. Fallback: Cofl API name-based lookup with cache
        return ProfitManager.fetchIdByName(name);
    }

    public static void onOfferAccepted(String visitorName) {
        if (pendingOffer != null) {
            String cleanPending = normalizeVisitorName(pendingOffer.visitorName);
            String cleanAccepted = normalizeVisitorName(visitorName);

            if (cleanAccepted.equals(cleanPending)) {
                ProfitManager.addVisitorCost(pendingOffer.totalCost);
                pendingOffer = null;
            }
        }
    }

    public static String extractAcceptedVisitorName(String plainText) {
        if (plainText == null) {
            return null;
        }

        Matcher matcher = OFFER_ACCEPTED_PATTERN.matcher(plainText.trim());
        if (!matcher.matches()) {
            return null;
        }

        return matcher.group(1).trim();
    }

    public static void clearPendingOffer() {
        pendingOffer = null;
    }

    private static String normalizeVisitorName(String name) {
        String normalized = TablistUtils.stripColors(name).trim();
        normalized = normalized.replaceAll(
                "\\s*\\((COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL)\\)\\s*$", "");
        return normalized.trim().toLowerCase();
    }

    public static void startVisitorReentryCooldown(Minecraft client) {
        visitorReentryCooldownUntilMs = System.currentTimeMillis() + VISITOR_REENTRY_COOLDOWN_MS;
        ClientUtils.sendDebugMessage("Visitor re-entry cooldown started (1 minute).");
    }

    public static long getVisitorReentryCooldownRemainingMs() {
        return Math.max(0L, visitorReentryCooldownUntilMs - System.currentTimeMillis());
    }

    public static boolean isVisitorReentryCooldownActive(Minecraft client, boolean showMessage) {
        long now = System.currentTimeMillis();
        long remainingMs = visitorReentryCooldownUntilMs - now;
        if (remainingMs <= 0) {
            return false;
        }

        long remainingSeconds = (remainingMs + 999L) / 1000L;
        ClientUtils.sendDebugMessage("Visitor re-entry cooldown active (" + remainingSeconds + "s remaining). Skipping visitor macro.");
        if (showMessage && client.player != null) {
            ClientUtils.sendMessage("\u00A7eVisitor cooldown active (" + remainingSeconds + "s). Staying on farm.", true);
        }
        return true;
    }

    public static boolean shouldSkipVisitorsDuringJacobsContest(Minecraft client, boolean showMessage) {
        if (!AetherConfig.DISABLE_VISITORS_DURING_JACOBS_CONTEST.get()) {
            return false;
        }

        if (ClientUtils.getJacobsContestRemainingMs() <= 0) {
            return false;
        }

        ClientUtils.sendDebugMessage("Jacob's Contest visitor skip window active (:15-:35). Skipping visitors.");
        if (showMessage && client.player != null) {
            ClientUtils.sendMessage("\u00A7eJacob's Contest window active (:15-:35). Skipping visitors.", true);
        }
        return true;
    }
}


