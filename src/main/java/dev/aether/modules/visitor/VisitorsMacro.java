package dev.aether.modules.visitor;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.mixin.MixinMinecraft;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.BazaarUtils;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import dev.aether.util.TablistUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone visitors macro triggered by {@code /aether visitors}.
 * <p>
 * Flow:
 * <ol>
 * <li>TP to barn ({@code /plottp barn})</li>
 * <li>Scan tab-list for visitor names</li>
 * <li>For each visitor NPC in the barn area:
 * <ul>
 * <li>Walk close, rotate, interact (right-click)</li>
 * <li>Read the "Accept Offer" lore to extract required items</li>
 * <li>Buy each item from Bazaar via {@link BazaarUtils}</li>
 * <li>Re-open visitor GUI and click "Accept Offer"</li>
 * </ul>
 * </li>
 * <li>When all visitors are served, stop.</li>
 * </ol>
 */
public class VisitorsMacro {

    private static final Pattern ITEM_PATTERN = Pattern.compile("^(.+?)\\s+x([\\d,]+)$");
    private static final int ACCEPT_OFFER_SLOT = 29;
    private static final int REJECT_OFFER_SLOT = 33;
    private static final long COMPACTOR_GUI_TIMEOUT_MS = 2500L;
    private static final long COMPACTOR_POST_OPEN_DELAY_MS = 500L;
    private static final long COMPACTOR_SLOT_TIMEOUT_MS = 2500L;
    private static final float VISITOR_INTERACTION_RANGE = 2.5f;
    private static final float VISITOR_RETRY_RANGE = 2.0f;
    private static final int MAX_VISITOR_INTERACTION_RETRIES = 3;

    public static volatile boolean isRunning = false;
    private static volatile boolean shouldStop = false;
    private static volatile boolean workerActive = false;
    private static volatile int runGeneration = 0;
    public static volatile boolean hasBarnTeleportMessage = false;
    private static volatile boolean compactorsPendingReenable = false;
    private static boolean wasRunningBefore = false;
    // Controls whether the current visitor's pathfinds should latch sneak.
    // Semantic: for each visitor, the first pathfind should NOT latch sneak. If
    // that first attempt overshoots and we perform retries for the same visitor,
    // we set this to true so subsequent pathfind attempts for that visitor will
    // latch sneak. After the visitor is served (or skipped), this is reset for
    // the next visitor.
    private static volatile boolean currentVisitorRetrySneak = false;

    // -- Public API --

    public static void start(Minecraft client) {
        start(client, false);
    }

    public static synchronized void start(Minecraft client, boolean forceNoResume) {
        if (isRunning) {
            msg(client, "\u00A7cVisitors macro is already running.");
            return;
        }
        if (client.player == null)
            return;
        if (VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) {
            return;
        }

        wasRunningBefore = !forceNoResume && dev.aether.macro.MacroStateManager.isMacroRunning();
        isRunning = true;
        shouldStop = false;
        int generation = ++runGeneration;
        msg(client, "\u00A7eStarting visitors macro...");

        MacroWorkerThread.getInstance().submit("VisitorsMacro", () -> {
            workerActive = true;
            try {
                if (generation != runGeneration || !isRunning || shouldStop) {
                    return;
                }
                // Ensure farming is disabled
                client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));

                if (wasRunningBefore) {
                    dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.VISITING);
                }

                run(client);
            } catch (Exception e) {
                msg(client, "\u00A7cVisitors macro error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                workerActive = false;
                if (generation == runGeneration) {
                    isRunning = false;
                    shouldStop = false;
                }
            }
        });
    }

    public static synchronized void stop(Minecraft client) {
        if (!isRunning)
            return;
        shouldStop = true;
        PathfindingManager.stop();
        msg(client, "\u00A7eStopping visitors macro...");
        if (!workerActive) {
            runGeneration++;
            isRunning = false;
            shouldStop = false;
            compactorsPendingReenable = false;
        }
    }

    // -- Main Flow --

    private static void run(Minecraft client) {
        if (client.player == null)
            return;

        boolean compactorsDisabled = false;
        try {
        // Step 0: check auto sell after wardrobe per user request (regardless of threshold)
        // This is now synchronous when called from the worker thread, so we don't need to restart the macro.
        dev.aether.modules.inventorymanager.AutoSellManager.checkBeforeVisitors(client, true, wasRunningBefore);
        if (shouldStop) return;

        // Step 1: TP to barn
        closeScreen(client);
        MacroWorkerThread.sleep(300);
        msg(client, "\u00A7eTeleporting to barn...");

        Vec3 posBefore = client.player.position();
        hasBarnTeleportMessage = false;
        FailsafeManager.addRotationGracePeriod(dev.aether.config.AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        CommandUtils.ChatWindow barnTeleportWindow = CommandUtils.beginChatWindow();
        ClientUtils.sendCommand(client, "/plottp barn");
        MacroWorkerThread.sleep(600);

        if (!waitForTeleport(client, posBefore, barnTeleportWindow, 5000)) {
            msg(client, "\u00A7cTeleport to barn timed out. Aborting.");
            return;
        }
        if (shouldStop)
            return;
        MacroWorkerThread.sleep(800);

        // Ensure a farming tool is equipped and wardrobe is correct
        if (!ensureVisitorLoadout(client)) {
            msg(client, "\u00A7cFailed to equip the visitor item setup. Aborting.");
            return;
        }
        MacroWorkerThread.sleep(150);
        ClientUtils.waitForGearAndGui(client);

        if (AetherConfig.DISABLE_COMPACTORS_DURING_VISITORS.get()) {
            compactorsDisabled = setCompactorsEnabled(client, false);
            if (shouldStop) {
                return;
            }
        }

        // Step 2+: Re-scan the queue each round and process from the back of the line.
        int totalServed = 0;
        int roundsWithoutProgress = 0;
        int round = 1;
        Set<String> servedVisitorsThisRun = new HashSet<>();
        Set<String> skippedVisitorsThisRun = new HashSet<>();

        while (!shouldStop) {
            List<String> queueNames = getVisitorNamesFromTab(client);
            if (queueNames.isEmpty()) {
                msg(client, "\u00A7aNo visitors left in queue. Done.");
                break;
            }

            List<String> eligibleVisitors = filterignoredVisitors(client, queueNames);
            if (eligibleVisitors.isEmpty()) {
                msg(client, "\u00A7eQueue contains only ignored visitors. Done.");
                break;
            }

            // Filter out already-served and already-skipped visitors
            eligibleVisitors.removeIf(v -> servedVisitorsThisRun.contains(normalizeVisitorName(v)) ||
                    skippedVisitorsThisRun.contains(normalizeVisitorName(v)));

            if (eligibleVisitors.isEmpty()) {
                msg(client, "\u00A7aNo new visitors to process. Done.");
                break;
            }

            // Last visitor in line first.
            Collections.reverse(eligibleVisitors);
            msg(client, "\u00A7eRound " + round + " \u00A76processing \u00A7e" + eligibleVisitors.size()
                    + " \u00A76visitor(s) (back to front): \u00A77" + String.join(", ", eligibleVisitors));

            int servedThisRound = 0;
            for (String visitorName : eligibleVisitors) {
                if (shouldStop)
                    return;
                String normalizedName = normalizeVisitorName(visitorName);

                boolean success;
                if (isRejectedVisitor(visitorName)) {
                    success = processRejectedVisitor(client, visitorName);
                } else {
                    success = processVisitor(client, visitorName);
                }
                if (success) {
                    totalServed++;
                    servedThisRound++;
                    servedVisitorsThisRun.add(normalizedName);
                } else {
                    ClientUtils.sendDebugMessage(client, "[VisitorsMacro] Failed to process visitor: " + visitorName);
                    skippedVisitorsThisRun.add(normalizedName);
                }
                MacroWorkerThread.sleep(500);
            }

            if (servedThisRound == 0) {
                roundsWithoutProgress++;
                ClientUtils.sendDebugMessage(client,
                        "[VisitorsMacro] No visitors were served this round (" + roundsWithoutProgress
                                + "/3). Rescanning queue...");
                if (roundsWithoutProgress >= 3) {
                    msg(client,
                            "\u00A7cStopping visitors macro: no progress after 3 rounds. Remaining visitors may be unreachable.");
                    break;
                }
            } else {
                roundsWithoutProgress = 0;
            }

            round++;
            MacroWorkerThread.sleep(700);
        }

        // Step 4: Done
        msg(client, "\u00A7aVisitors macro complete. Served \u00A7e" + totalServed + " \u00A7avisitor(s).");
        if (!shouldStop && wasRunningBefore) {
            VisitorManager.handleVisitorScriptFinished(client);
        } else {
            dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.OFF);
        }
        } finally {
            if (compactorsDisabled) {
                if (!shouldStop && wasRunningBefore) {
                    compactorsPendingReenable = true;
                } else {
                    setCompactorsEnabled(client, true);
                }
            }
        }
    }

    // -- Visitor Processing --

    private static boolean processVisitor(Minecraft client, String visitorName) {
        if (shouldStop)
            return false;
        msg(client, "\u00A7eLooking for visitor: \u00A7e" + visitorName);

        // Find the visitor entity by name
        Entity visitor = findVisitorEntity(client, visitorName);
        if (visitor == null) {
            msg(client, "\u00A7cCould not find entity for: " + visitorName);
            return false;
        }

        visitor = prepareVisitorForInteractionWithRetries(client, visitorName, visitor);
        if (visitor == null) {
            return false;
        }

        // Right-click the visitor repeatedly until GUI opens
        if (!interactUntilGui(client, visitorName, visitor, 15000)) {
            msg(client, "\u00A7cVisitor GUI did not open for: " + visitorName);
            return false;
        }
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));

        // Read required items from "Accept Offer" lore
        List<ItemRequirement> requirements = readRequirements(client);
        if (requirements == null) {
            msg(client, "\u00A7cCould not read requirements for: " + visitorName);
            closeScreen(client);
            return false;
        }

        // Check if we already have items (lore says "Click to give")
        boolean hasItems = checkHasItems(client);

        if (!hasItems && !requirements.isEmpty()) {
            for (ItemRequirement req : requirements) {
                if (!isPurchaseWithinLimit(client, req)) {
                    clickRejectOffer(client);
                    MacroWorkerThread.sleep(300);
                    closeScreen(client);
                    return false;
                }
            }

            // Close visitor GUI, buy from Bazaar
            closeScreen(client);
            MacroWorkerThread.sleep(800); // Increased wait to ensure GUI fully closes

            for (ItemRequirement req : requirements) {
                if (shouldStop)
                    return false;
                msg(client, "\u00A7eBuying \u00A7e" + req.count + "x " + req.name + " \u00A76from Bazaar...");

                // Call executeBuy directly - we're already on the worker thread,
                // so buyAsync() would deadlock waiting for the same thread.
                boolean buySuccess = BazaarUtils.executeBuy(client, req.name, req.count);

                if (!buySuccess) {
                    msg(client, "\u00A7cFailed to buy: " + req.name + ". Refusing visitor.");
                    rejectVisitor(client, visitorName);
                    return false;
                }
                MacroWorkerThread.sleep(500);
            }

            // Re-open the visitor GUI to accept
            if (shouldStop)
                return false;

            if (!reopenVisitorGui(client, visitorName)) {
                return false;
            }
        }

        // Click "Accept Offer"
        CommandUtils.ChatWindow offerAcceptedWindow = CommandUtils.beginChatWindow();
        if (!clickAcceptOffer(client)) {
            msg(client, "\u00A7cCould not click Accept Offer for: " + visitorName);
            closeScreen(client);
            return false;
        }

        boolean acceptedByChat = waitForOfferAcceptedChat(offerAcceptedWindow, visitorName, 3000);
        if (!acceptedByChat) {
            ClientUtils.sendDebugMessage(client,
                    "[VisitorsMacro] Chat confirmation missing for " + visitorName + ", using fallback checks...");
            if (!waitForVisitorCompletionFallback(client, visitorName, 3000)) {
                msg(client, "\u00A7cCould not confirm offer acceptance for: " + visitorName);
                closeScreen(client);
                return false;
            }
        }

        MacroWorkerThread.sleep(300);
        closeScreen(client);
        MacroWorkerThread.sleep(300);

        // Track cost for profit manager
        VisitorManager.onOfferAccepted(visitorName);
        // After successfully serving a visitor, reset the per-visitor retry-sneak
        // flag so the next visitor will start with a normal (non-sneak) pathfind.
        currentVisitorRetrySneak = false;
        msg(client, "\u00A7aServed visitor: \u00A7e" + visitorName);
        return true;
    }

    private static boolean isPurchaseWithinLimit(Minecraft client, ItemRequirement req) {
        int limit = AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.get();
        long estimatedCost = estimatePurchaseCost(req);
        if (estimatedCost <= 0L) {
            msg(client, "\u00A7cCould not verify Bazaar cost for " + req.count + "x " + req.name
                    + ". Refusing visitor.");
            ClientUtils.sendDebugMessage(client,
                    "VisitorsMacro: purchase price unknown for " + req.count + "x " + req.name);
            return false;
        }

        if (estimatedCost > limit) {
            msg(client, "\u00A7cSkipping " + req.count + "x " + req.name + ": estimated cost "
                    + formatCoins(estimatedCost) + " exceeds limit " + formatCoins(limit) + ".");
            ClientUtils.sendDebugMessage(client,
                    "VisitorsMacro: purchase exceeds limit: " + req.count + "x " + req.name
                            + " costs " + estimatedCost + ", limit " + limit);
            return false;
        }

        return true;
    }

    private static long estimatePurchaseCost(ItemRequirement req) {
        double buyValue = ProfitManager.getItemValue(req.name, -req.count);
        if (buyValue == 0.0d) {
            buyValue = ProfitManager.getItemPrice(req.name) * req.count;
        }
        return (long) Math.ceil(Math.abs(buyValue));
    }

    private static String formatCoins(long coins) {
        return String.format("%,d coins", coins);
    }

    private static boolean reopenVisitorGui(Minecraft client, String visitorName) {
        Entity visitor = findVisitorEntity(client, visitorName);
        if (visitor == null) {
            msg(client, "\u00A7cLost visitor entity: " + visitorName);
            return false;
        }

        visitor = prepareVisitorForInteractionWithRetries(client, visitorName, visitor);
        if (visitor == null) {
            return false;
        }
        if (!interactUntilGui(client, visitorName, visitor, 15000)) {
            msg(client, "\u00A7cCould not reopen visitor GUI for: " + visitorName);
            return false;
        }
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));
        return true;
    }

    private static void rejectVisitor(Minecraft client, String visitorName) {
        if (!reopenVisitorGui(client, visitorName))
            return;

        clickRejectOffer(client);
        MacroWorkerThread.sleep(300);
        closeScreen(client);
        MacroWorkerThread.sleep(300);
    }

    private static boolean waitForOfferAcceptedChat(CommandUtils.ChatWindow window, String visitorName, long timeoutMs) {
        final String normalizedTarget = normalizeVisitorName(visitorName);
        return CommandUtils.waitForChatMessageMatching(window, msg -> {
            String rawName = VisitorManager.extractAcceptedVisitorName(msg);
            if (rawName == null) {
                return false;
            }
            String normalizedAccepted = normalizeVisitorName(rawName);

            return normalizedAccepted.equals(normalizedTarget)
                    || normalizedAccepted.startsWith(normalizedTarget)
                    || normalizedTarget.startsWith(normalizedAccepted);
        }, timeoutMs);
    }

    private static boolean waitForVisitorCompletionFallback(Minecraft client, String visitorName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String normalizedTarget = normalizeVisitorName(visitorName);

        while (System.currentTimeMillis() < deadline && !shouldStop) {
            boolean stillInTab = false;
            for (String tabName : getVisitorNamesFromTab(client)) {
                String normalizedTab = normalizeVisitorName(tabName);
                if (normalizedTab.equals(normalizedTarget)
                        || normalizedTab.startsWith(normalizedTarget)
                        || normalizedTarget.startsWith(normalizedTab)) {
                    stillInTab = true;
                    break;
                }
            }

            if (!stillInTab) {
                return true;
            }

            Entity stillPresent = findVisitorEntity(client, visitorName);
            if (stillPresent == null) {
                return true;
            }

            MacroWorkerThread.sleep(120);
        }

        return false;
    }

    private static String normalizeVisitorName(String name) {
        String normalized = stripColors(name).trim();
        normalized = normalized.replaceAll(
                "\\s*\\((COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL)\\)\\s*$", "");
        return normalized.trim().toLowerCase();
    }

    private static List<String> filterignoredVisitors(Minecraft client, List<String> visitorNames) {
        List<String> filtered = new ArrayList<>();
        List<String> ignore = AetherConfig.VISITOR_ignore.get();

        for (String name : visitorNames) {
            boolean isignored = false;
            String cleanName = name.toLowerCase();
            for (String bl : ignore) {
                if (cleanName.contains(bl.toLowerCase())) {
                    msg(client, "\u00A7eSkipping ignored visitor: \u00A7c" + name);
                    isignored = true;
                    break;
                }
            }
            if (!isignored) {
                filtered.add(name);
            }
        }

        return filtered;
    }

    private static boolean isRejectedVisitor(String name) {
        String cleanName = stripColors(name).toLowerCase();
        for (String rej : AetherConfig.VISITOR_REJECT.get()) {
            if (cleanName.contains(rej.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean processRejectedVisitor(Minecraft client, String visitorName) {
        if (shouldStop)
            return false;
        msg(client, "\u00A7eActively rejecting visitor: \u00A7e" + visitorName);

        if (!reopenVisitorGui(client, visitorName)) {
            return false;
        }

        if (!clickRejectOffer(client)) {
            msg(client, "\u00A7cCould not click Refuse Offer for: " + visitorName);
            closeScreen(client);
            return false;
        }

        MacroWorkerThread.sleep(500);
        closeScreen(client);
        MacroWorkerThread.sleep(300);

        msg(client, "\u00A7aRejected visitor: \u00A7e" + visitorName);
        return true;
    }

    // -- Tab List Parsing --

    private static List<String> getVisitorNamesFromTab(Minecraft client) {
        List<String> names = new ArrayList<>();
        if (client.getConnection() == null)
            return names;

        try {
            List<String> tabLines = TablistUtils.getTabLines(client);

            boolean foundVisitorsHeader = false;
            for (String clean : tabLines) {
                if (clean.contains("Visitors:")) {
                    foundVisitorsHeader = true;
                    continue;
                }

                if (foundVisitorsHeader) {
                    if (clean.isEmpty() || clean.contains("Next Visitor") || names.size() >= 5) {
                        break;
                    }
                    String name = clean.replace("NEW!", "").trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return names;
    }

    // -- Entity Finding --

    private static Entity findVisitorEntity(Minecraft client, String visitorName) {
        if (client.level == null || client.player == null)
            return null;

        String target = stripColors(visitorName).toLowerCase().trim();

        // Look for armor stands with matching name (visitor NPCs have name tags)
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player)
                continue;
            String entityName = stripColors(entity.getName().getString()).toLowerCase().trim();

            if (entityName.contains(target) || target.contains(entityName)) {
                double dist = entity.distanceToSqr(client.player);
                // Prefer non-armor-stand entities (the actual NPC character)
                // but accept armor stands if that's all we find
                if (!(entity instanceof ArmorStand)) {
                    if (dist < bestDist || best instanceof ArmorStand) {
                        bestDist = dist;
                        best = entity;
                    }
                } else if (best == null) {
                    bestDist = dist;
                    best = entity;
                }
            }
        }

        // If we only found an armor stand, try to find the actual character near it
        if (best instanceof ArmorStand) {
            Entity character = findCharacterNearArmorStand(client, best);
            if (character != null)
                return character;
        }

        return best;
    }

    private static Entity findCharacterNearArmorStand(Minecraft client, Entity armorStand) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity instanceof ArmorStand)
                continue;
            if (entity.distanceToSqr(armorStand) < 4.0) {
                return entity;
            }
        }
        return null;
    }

    // -- Movement / Interaction --

    private static void walkToEntity(Minecraft client, Entity entity, float retryRange) {
        if (client.player == null) return;

        BlockPos target = findBestWalkingTarget(client, entity, retryRange);
        if (target == null) target = entity.blockPosition();

        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();

        ClientUtils.sendDebugMessage(client, "[VisitorsMacro] Walking to destination near visitor: " + x + ", " + y + ", " + z);
        // Start the pathfind and then set the walk-sneak latch on the main thread.
        // Latch decision is per-visitor: `currentVisitorRetrySneak` is false for
        // the first attempt (walk normally), and set to true if we need to retry
        // for the same visitor (in which case we want to sneak).
        try {
            final boolean latchSneak = currentVisitorRetrySneak; // true => latch sneak, false => do not
            client.execute(() -> {
                PathfindingManager.startPathfind(client, x, y, z, false, entity);
                PathfindingManager.setWalkSneakLatched(latchSneak);
            });
        } catch (Exception ignored) {
        }
        MacroWorkerThread.sleep(500);

        long deadline = System.currentTimeMillis() + 15000;
        while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline && !shouldStop) {
            MacroWorkerThread.sleep(200);
        }

        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
        }
        // Clear the sneak latch now that navigation has finished/stopped.
        try {
            client.execute(() -> PathfindingManager.setWalkSneakLatched(false));
        } catch (Exception ignored) {
        }
        MacroWorkerThread.sleep(200);
    }

    private static BlockPos findBestWalkingTarget(Minecraft client, Entity entity, float retryRange) {
        if (client.level == null || client.player == null) return null;
        BlockPos base = entity.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double retryRangeSqr = retryRange * retryRange;

        // Search a 7x5x7 area around the visitor
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    double dE = pos.distToCenterSqr(entity.position());
                    if (dE > retryRangeSqr) continue;

                    if (isWalkable(client, pos)) {
                        double dP = pos.distToCenterSqr(client.player.position());
                        if (dP < bestDistSq) {
                            bestDistSq = dP;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isWalkable(Minecraft client, BlockPos pos) {
        if (client.level == null) return false;
        return !client.level.getBlockState(pos.below()).getCollisionShape(client.level, pos.below()).isEmpty() &&
                client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty() &&
                client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty();
    }

    private static boolean isLookingAt(Minecraft client, Entity entity, float tolerance) {
        if (client.player == null) return false;
        return RotationUtils.isLookingAt(client.player.getYRot(), client.player.getXRot(),
                client.player.getEyePosition(), entity.getEyePosition(), tolerance);
    }

    private static void faceVisitorForInteraction(Minecraft client, Entity entity) {
        rotateToEntity(client, entity);
        waitForRotationToFinish();
        if (!isLookingAt(client, entity, AetherConfig.VISITOR_FOV_RANGE.get())) {
            rotateToEntity(client, entity);
            waitForRotationToFinish();
        }
    }

    private static void rotateToEntity(Minecraft client, Entity entity) {
        client.execute(() -> RotationManager.initiateRotation(client,
                new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()),
                AetherConfig.ROTATION_TIME.get(),
                AetherConfig.VISITOR_FOV_RANGE.get()));
        MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get() + 50L);
    }

    private static void waitForRotationToFinish() {
        long deadline = System.currentTimeMillis() + 1500L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) {
            MacroWorkerThread.sleep(25);
        }
    }

    private static boolean interactWithEntity(Minecraft client, Entity entity) {
        if (client.player == null || client.options == null) {
            return false;
        }

        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            ((MixinMinecraft) client).aether$startAttack();
        });
        MacroWorkerThread.sleep(100);
        return true;
    }

    private static Entity prepareVisitorForInteraction(
            Minecraft client,
            String visitorName,
            Entity visitor,
            float retryRange
    ) {
        if (client.player == null) {
            return null;
        }

        Entity refreshedVisitor = findVisitorEntity(client, visitorName);
        if (refreshedVisitor != null) {
            visitor = refreshedVisitor;
        }

        if (!isVisitorInRetryRange(client, visitor, retryRange)) {
            walkToEntity(client, visitor, retryRange);
            if (shouldStop) {
                return null;
            }

            refreshedVisitor = findVisitorEntity(client, visitorName);
            if (refreshedVisitor != null) {
                visitor = refreshedVisitor;
            }
        }

        if (!isVisitorInInteractionRange(client, visitor)) {
            return null;
        }

        faceVisitorForInteraction(client, visitor);
        return visitor;
    }

    private static Entity prepareVisitorForInteractionWithRetries(Minecraft client, String visitorName, Entity visitor) {
        Entity preparedVisitor = visitor;
        // Reset retry-sneak for this visitor; first attempt should NOT sneak.
        currentVisitorRetrySneak = false;
        for (int attempt = 0; attempt <= MAX_VISITOR_INTERACTION_RETRIES && !shouldStop; attempt++) {
            // If we're retrying (attempt > 0), ensure future pathfinds latch sneak
            // since retries should use the sneak path execution option.
            if (attempt > 0) currentVisitorRetrySneak = true;
            float retryRange = getRetryRangeForAttempt(attempt);
            Entity refreshedVisitor = findVisitorEntity(client, visitorName);
            if (refreshedVisitor != null) {
                preparedVisitor = refreshedVisitor;
            }

            preparedVisitor = prepareVisitorForInteraction(client, visitorName, preparedVisitor, retryRange);
            if (preparedVisitor != null) {
                return preparedVisitor;
            }

            if (attempt < MAX_VISITOR_INTERACTION_RETRIES) {
                ClientUtils.sendDebugMessage(client,
                        "[VisitorsMacro] Initial prep retry (" + (attempt + 1) + "/"
                                + MAX_VISITOR_INTERACTION_RETRIES + ") for " + visitorName);
                MacroWorkerThread.sleep(250);
            }
        }

        msg(client, "\u00A7cVisitor is still out of range for interaction: " + visitorName);
        return null;
    }

    private static boolean isVisitorInInteractionRange(Minecraft client, Entity visitor) {
        return client.player != null && client.player.distanceTo(visitor) <= VISITOR_INTERACTION_RANGE;
    }

    private static boolean isVisitorInRetryRange(Minecraft client, Entity visitor, float retryRange) {
        return client.player != null && client.player.distanceTo(visitor) <= retryRange;
    }

    /**
     * Repeatedly right-clicks the entity every 500ms until a container GUI opens or
     * timeout.
     */
    private static boolean interactUntilGui(Minecraft client, String visitorName, Entity entity, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int prepRetries = 0;
        while (System.currentTimeMillis() < deadline && !shouldStop) {
            Entity refreshedVisitor = findVisitorEntity(client, visitorName);
            if (refreshedVisitor != null) {
                entity = refreshedVisitor;
            }

            float retryRange = getRetryRangeForAttempt(prepRetries);
            if (!isVisitorInRetryRange(client, entity, retryRange)
                    || !isLookingAt(client, entity, AetherConfig.VISITOR_FOV_RANGE.get())) {
                if (prepRetries >= MAX_VISITOR_INTERACTION_RETRIES) {
                    ClientUtils.sendDebugMessage(client,
                            "[VisitorsMacro] Visitor interaction retries exceeded for " + visitorName);
                    return false;
                }

                prepRetries++;
                ClientUtils.sendDebugMessage(client,
                        "[VisitorsMacro] Re-prepping visitor interaction (" + prepRetries + "/"
                                + MAX_VISITOR_INTERACTION_RETRIES + ") for " + visitorName);
                entity = prepareVisitorForInteraction(client, visitorName, entity, getRetryRangeForAttempt(prepRetries));
                if (entity == null) {
                    continue;
                }
            }

            interactWithEntity(client, entity);
            // Wait up to 500ms, checking for GUI every 100ms
            for (int i = 0; i < 5; i++) {
                MacroWorkerThread.sleep(100);
                if (client.screen instanceof AbstractContainerScreen)
                    return true;
                if (shouldStop)
                    return false;
            }
        }
        return client.screen instanceof AbstractContainerScreen;
    }

    // -- GUI Interaction --

    private static List<ItemRequirement> readRequirements(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen))
            return null;

        if (ACCEPT_OFFER_SLOT >= screen.getMenu().slots.size())
            return null;
        Slot slot = screen.getMenu().slots.get(ACCEPT_OFFER_SLOT);
        if (!slot.hasItem())
            return null;

        ItemStack stack = slot.getItem();
        String name = stripColors(stack.getHoverName().getString());
        if (!name.contains("Accept Offer"))
            return null;

        ItemLore loreCmp = stack.get(DataComponents.LORE);
        if (loreCmp == null)
            return null;

        List<ItemRequirement> requirements = new ArrayList<>();
        boolean parsingRequired = false;

        for (Component line : loreCmp.lines()) {
            String text = stripColors(line.getString()).trim();
            if (text.isEmpty())
                continue;

            if (text.contains("Required:") || text.contains("Items Required:")) {
                parsingRequired = true;
                continue;
            }
            if (text.contains("Rewards:") || text.contains("Copper") || text.contains("Garden Experience")) {
                if (parsingRequired)
                    parsingRequired = false;
                continue;
            }

            if (parsingRequired) {
                if (text.contains("Farming XP") || text.contains("Garden Experience"))
                    continue;

                Matcher m = ITEM_PATTERN.matcher(text);
                if (m.matches()) {
                    String itemName = m.group(1).trim();
                    int count = Integer.parseInt(m.group(2).replace(",", ""));
                    requirements.add(new ItemRequirement(itemName, count));
                    ClientUtils.sendDebugMessage(Minecraft.getInstance(),
                            "[VisitorsMacro] Required: " + itemName + " x" + count);
                } else if (!text.isEmpty()) {
                    // Single item with no count suffix
                    requirements.add(new ItemRequirement(text.trim(), 1));
                    ClientUtils.sendDebugMessage(Minecraft.getInstance(),
                            "[VisitorsMacro] Required: " + text.trim() + " x1");
                }
            }
        }

        return requirements;
    }

    private static boolean checkHasItems(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen))
            return false;
        if (ACCEPT_OFFER_SLOT >= screen.getMenu().slots.size())
            return false;

        Slot slot = screen.getMenu().slots.get(ACCEPT_OFFER_SLOT);
        if (!slot.hasItem())
            return false;

        ItemStack stack = slot.getItem();
        List<Component> tooltipLines = stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.EMPTY,
                client.player,
                net.minecraft.world.item.TooltipFlag.NORMAL);

        for (Component line : tooltipLines) {
            String text = stripColors(line.getString());
            if (text.contains("Click to give"))
                return true;
        }
        return false;
    }

    private static boolean clickAcceptOffer(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen))
            return false;
        if (ACCEPT_OFFER_SLOT >= screen.getMenu().slots.size())
            return false;

        Slot slot = screen.getMenu().slots.get(ACCEPT_OFFER_SLOT);
        if (!slot.hasItem())
            return false;

        String name = stripColors(slot.getItem().getHoverName().getString());
        if (!name.contains("Accept Offer"))
            return false;

        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?> s) {
                ClientUtils.performSlotClick(client, s, ACCEPT_OFFER_SLOT, 0, ContainerInput.PICKUP);
            }
        });
        return true;
    }

    private static boolean clickRejectOffer(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen))
            return false;
        if (REJECT_OFFER_SLOT >= screen.getMenu().slots.size())
            return false;

        Slot slot = screen.getMenu().slots.get(REJECT_OFFER_SLOT);
        if (!slot.hasItem())
            return false;

        String name = stripColors(slot.getItem().getHoverName().getString());
        if (!name.contains("Refuse Offer"))
            return false;

        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?> s) {
                ClientUtils.performSlotClick(client, s, REJECT_OFFER_SLOT, 0, ContainerInput.PICKUP);
            }
        });
        return true;
    }

    // -- Helpers --

    private static boolean waitForTeleport(Minecraft client, Vec3 posBefore, CommandUtils.ChatWindow window, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !shouldStop) {
            if (hasBarnTeleportMessage)
                return true;
            if (dev.aether.util.CommandUtils
                    .hasReceivedMessageMatching(window, msg -> msg.contains("Teleported you to The Barn!")
                            || msg.contains("Teleported you to the Barn!"))) {
                return true;
            }
            if (client.player != null && client.player.position().distanceTo(posBefore) > 5) {
                return true;
            }
            MacroWorkerThread.sleep(100);
        }
        return false;
    }

    private static boolean waitForContainer(Minecraft client, long timeoutMs) {
        return waitForContainer(client, timeoutMs, false);
    }

    private static boolean waitForContainer(Minecraft client, long timeoutMs, boolean ignoreStop) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && (ignoreStop || !shouldStop)) {
            if (client.screen instanceof AbstractContainerScreen)
                return true;
            MacroWorkerThread.sleep(100);
        }
        return false;
    }

    private static void closeScreen(Minecraft client) {
        client.execute(() -> {
            if (client.player != null && client.screen != null) {
                client.player.closeContainer();
            }
        });
    }

    private static boolean ensureVisitorLoadout(Minecraft client) {
        if (AetherConfig.AUTO_LOADOUT_VISITOR.get() && AetherConfig.LOADOUT_SLOT_VISITOR.get() > 0
                && dev.aether.modules.gear.helpers.LoadoutManager.trackedLoadoutSlot != AetherConfig.LOADOUT_SLOT_VISITOR
                        .get()) {
            msg(client, "\u00A7eSwapping to visitor loadout (Slot " + AetherConfig.LOADOUT_SLOT_VISITOR.get()
                    + ")...");
            GearManager.ensureLoadoutSlot(client, AetherConfig.LOADOUT_SLOT_VISITOR.get());
            if (dev.aether.modules.gear.helpers.LoadoutManager.isSwappingLoadout) {
                ClientUtils.waitForWardrobeGui(client);
                while (dev.aether.modules.gear.helpers.LoadoutManager.isSwappingLoadout)
                    MacroWorkerThread.sleep(50);
                while (dev.aether.modules.gear.helpers.LoadoutManager.loadoutCleanupTicks > 0)
                    MacroWorkerThread.sleep(50);
                MacroWorkerThread.sleep(250);
            }
        }


        return equipVisitorTool(client);
    }

    private static boolean equipVisitorTool(Minecraft client) {
        if (AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.get()) {
            String customItem = AetherConfig.VISITOR_CUSTOM_ITEM.get().trim();
            if (customItem.isEmpty()) {
                ClientUtils.sendDebugMessage(client, "VisitorsMacro: custom item name is blank.");
                return false;
            }

            boolean swapped = GearManager.swapToNamedHotbarItemSync(client, customItem);
            if (!swapped) {
                ClientUtils.sendDebugMessage(client, "VisitorsMacro: custom item not found in hotbar: " + customItem);
            }
            return swapped;
        }

        if (GearManager.findFarmingToolSlot(client) == -1) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: no farming tool found in hotbar.");
            return false;
        }

        GearManager.swapToFarmingToolSync(client);
        return true;
    }

    private static boolean setCompactorsEnabled(Minecraft client, boolean enabled) {
        if (client.player == null) {
            return false;
        }

        List<Integer> compactorSlots = findCompactorHotbarSlots(client);
        if (compactorSlots.isEmpty()) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: no compactors found in hotbar.");
            return false;
        }

        int originalSlot = client.player.getInventory().getSelectedSlot();
        boolean changedAny = false;
        for (int slot : compactorSlots) {
            if (!enabled && shouldStop) {
                break;
            }
            changedAny |= setCompactorEnabled(client, slot, enabled);
            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
        }

        closeScreen(client);
        if (originalSlot >= 0 && originalSlot < 9) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, originalSlot));
            waitForSelectedHotbarSlot(client, originalSlot, 1000L);
        }
        return changedAny;
    }

    public static void reenableCompactorsIfPending(Minecraft client) {
        if (!compactorsPendingReenable) {
            return;
        }

        if (setCompactorsEnabled(client, true)) {
            compactorsPendingReenable = false;
            return;
        }

        if (client.player == null || findCompactorHotbarSlots(client).isEmpty()) {
            compactorsPendingReenable = false;
        }
    }

    private static List<Integer> findCompactorHotbarSlots(Minecraft client) {
        List<Integer> slots = new ArrayList<>();
        if (client.player == null) {
            return slots;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stripColors(stack.getHoverName().getString()).contains("Compactor")) {
                slots.add(i);
            }
        }
        return slots;
    }

    private static boolean setCompactorEnabled(Minecraft client, int hotbarSlot, boolean enabled) {
        if (client.player == null) {
            return false;
        }

        closeScreen(client);
        client.execute(() -> FailsafeManager.selectHotbarSlot(client, hotbarSlot));
        if (!waitForSelectedHotbarSlot(client, hotbarSlot, 1500L)) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: failed to select compactor slot " + hotbarSlot);
            return false;
        }

        ItemStack currentItem = client.player.getMainHandItem();
        if (currentItem.isEmpty() || !stripColors(currentItem.getHoverName().getString()).contains("Compactor")) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: selected item is not a compactor.");
            return false;
        }

        ClientUtils.performUseClick(client);
        if (!waitForContainer(client, COMPACTOR_GUI_TIMEOUT_MS, true)) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: compactor GUI did not open.");
            return false;
        }
        MacroWorkerThread.sleep(COMPACTOR_POST_OPEN_DELAY_MS);

        int statusSlotIndex = waitForCompactorStatusSlot(client, COMPACTOR_SLOT_TIMEOUT_MS);
        if (statusSlotIndex < 0) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: compactor status slot was not found.");
            closeScreen(client);
            return false;
        }

        AbstractContainerScreen<?> statusScreen = (AbstractContainerScreen<?>) client.screen;
        Slot statusSlot = statusScreen.getMenu().slots.get(statusSlotIndex);
        Boolean currentEnabled = readCompactorEnabled(client, statusSlot.getItem());
        if (currentEnabled == null) {
            ClientUtils.sendDebugMessage(client, "VisitorsMacro: compactor status could not be read.");
            closeScreen(client);
            return false;
        }

        if (currentEnabled == enabled) {
            closeScreen(client);
            return false;
        }

        ClientUtils.sendDebugMessage(client,
                "VisitorsMacro: " + (enabled ? "enabling" : "disabling") + " compactor in hotbar slot "
                        + (hotbarSlot + 1));
        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                ClientUtils.performSlotClick(client, screen, statusSlotIndex, 0, ContainerInput.PICKUP);
            }
        });
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
        closeScreen(client);
        return true;
    }

    private static int waitForCompactorStatusSlot(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen && isCompactorScreen(screen)) {
                for (int i = 0; i < screen.getMenu().slots.size(); i++) {
                    Slot slot = screen.getMenu().slots.get(i);
                    if (!slot.hasItem()) {
                        continue;
                    }
                    String name = stripColors(slot.getItem().getHoverName().getString());
                    if (name.contains("Compactor Currently")) {
                        return i;
                    }
                }
            }
            MacroWorkerThread.sleep(50);
        }
        return -1;
    }

    private static boolean isCompactorScreen(AbstractContainerScreen<?> screen) {
        return stripColors(screen.getTitle().getString()).contains("Compactor");
    }

    private static Boolean readCompactorEnabled(Minecraft client, ItemStack stack) {
        String name = stripColors(stack.getHoverName().getString()).toUpperCase(java.util.Locale.ROOT);
        if (name.contains("ON")) {
            return true;
        }
        if (name.contains("OFF")) {
            return false;
        }

        List<Component> tooltipLines = stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.EMPTY,
                client.player,
                net.minecraft.world.item.TooltipFlag.NORMAL);
        for (Component line : tooltipLines) {
            String text = stripColors(line.getString()).toUpperCase(java.util.Locale.ROOT);
            if (text.contains("ON")) {
                return true;
            }
            if (text.contains("OFF")) {
                return false;
            }
        }
        return null;
    }

    private static boolean waitForSelectedHotbarSlot(Minecraft client, int slot, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.player != null && client.player.getInventory().getSelectedSlot() == slot) {
                return true;
            }
            MacroWorkerThread.sleep(10);
        }
        return client.player != null && client.player.getInventory().getSelectedSlot() == slot;
    }

    private static String stripColors(String s) {
        return TablistUtils.stripColors(s);
    }

    private static void msg(Minecraft client, String text) {
        ClientUtils.sendMessage(client, text);
    }

    private static float getRetryRangeForAttempt(int attempt) {
        return Math.max(0.0f, VISITOR_RETRY_RANGE - attempt);
    }

    // -- Data Classes --

    private static class ItemRequirement {
        final String name;
        final int count;

        ItemRequirement(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }
}
