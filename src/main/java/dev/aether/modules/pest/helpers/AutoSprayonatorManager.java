package dev.aether.modules.pest.helpers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.BazaarUtils;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class AutoSprayonatorManager {

    private static final Pattern NO_MATERIAL_PATTERN = Pattern.compile("(?i)^you don't have enough\\s+(.+?)(?:!|\\.)?$");
    private static final Pattern MATERIAL_CHANGE_PATTERN = Pattern.compile("(?i)^sprayonator! your selected material is now (.+?)!?\\s*$");

    private static volatile boolean running = false;
    private static volatile boolean cancelRequested = false;
    private static volatile boolean awaitingSprayResult = false;
    private static volatile SprayResult pendingResult = SprayResult.NONE;
    private static volatile String missingMaterial = null;
    private static volatile String pendingMaterialChange = null;
    private static volatile long lastRunMs = 0L;
    private static volatile long sprayNeededSinceMs = 0L;

    private static final long RUN_COOLDOWN_MS = 10_000L;
    private static final long TAB_CHECK_INTERVAL_MS = 750L;
    private static long lastTabCheckMs = 0L;

    private AutoSprayonatorManager() {
    }

    private enum SprayResult {
        NONE,
        SUCCESS,
        NO_MATERIAL,
        INVALID_PLOT
    }

    public static void reset() {
        cancelRequested = false;
        running = false;
        awaitingSprayResult = false;
        pendingResult = SprayResult.NONE;
        missingMaterial = null;
        pendingMaterialChange = null;
        lastRunMs = 0L;
        sprayNeededSinceMs = 0L;
        lastTabCheckMs = 0L;
    }

    public static void cancel() {
        cancelRequested = true;
        running = false;
        awaitingSprayResult = false;
        pendingResult = SprayResult.NONE;
        missingMaterial = null;
        pendingMaterialChange = null;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isSprayNeededNow(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return false;
        }
        return tabListNeedsSpray(client);
    }

    public static long getSprayNeededElapsedMs(Minecraft client) {
        if (!isSprayNeededNow(client) || sprayNeededSinceMs == 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - sprayNeededSinceMs);
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getConnection() == null) return;
        if (!AetherConfig.AUTO_SPRAYONATOR.get()) return;
        if (running) return;
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) return;

        long now = System.currentTimeMillis();
        if (now - lastRunMs < RUN_COOLDOWN_MS) return;
        if (now - lastTabCheckMs < TAB_CHECK_INTERVAL_MS) return;
        lastTabCheckMs = now;

        if (!tabListNeedsSpray(client)) {
            sprayNeededSinceMs = 0L;
            return;
        }

        if (sprayNeededSinceMs == 0L) {
            sprayNeededSinceMs = now;
            return;
        }

        if (now - sprayNeededSinceMs < AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.get() * 1000L) return;

        // Sprayonator sprays the plot we're standing on, and the Barn refuses
        // sprays. Stay armed and fire once farming moves us off the Barn strip.
        if ("Barn".equalsIgnoreCase(ClientUtils.getCurrentPlot())) return;

        running = true;
        cancelRequested = false;
        lastRunMs = now;
        sprayNeededSinceMs = 0L;
        MacroWorkerThread.getInstance().submit("AutoSprayonator", () -> runSequence(client));
    }

    public static void onChatMessage(String plainText) {
        if (plainText == null) return;

        String msg = plainText.replaceAll("(?i)[\u00A7&][0-9a-fk-or]", "").trim();
        String lower = msg.toLowerCase();

        Matcher matChange = MATERIAL_CHANGE_PATTERN.matcher(msg);
        if (matChange.find()) {
            pendingMaterialChange = matChange.group(1).trim();
            return;
        }

        if (!awaitingSprayResult) return;

        if (lower.startsWith("sprayonator! you sprayed plot")
                || lower.equals("this plot was sprayed with that item recently! try again soon!")) {
            pendingResult = SprayResult.SUCCESS;
            return;
        }

        // e.g. "You cannot spray a Barn plot!" - the server refuses some plots
        // outright; without recognising this the run times out and re-triggers
        // every cooldown forever.
        if (lower.startsWith("you cannot spray")) {
            pendingResult = SprayResult.INVALID_PLOT;
            return;
        }

        Matcher noMat = NO_MATERIAL_PATTERN.matcher(msg);
        if (noMat.find()) {
            missingMaterial = noMat.group(1).trim();
            pendingResult = SprayResult.NO_MATERIAL;
        }
    }

    private static void runSequence(Minecraft client) {
        long guiDelay = Math.max(50L, ClientUtils.getGuiClickDelayMs(false));

        try {
            cancelRequested = false;
            if (PestClientThread.call(client, () -> findSprayonatorSlot(client), -1) < 0) {
                msg(client, "\u00A7cSprayonator not found in hotbar. Skipping auto spray.");
                return;
            }

            MacroStateManager.setCurrentState(MacroState.State.SPRAYING);
            PestManager.setCleaningInProgress(true);

            msg(client, "\u00A7eUnsprayed plot detected. Pausing farming to spray...");
            PestClientThread.run(client, () -> FarmingMacroManager.disable(client));
            MacroWorkerThread.sleep(guiDelay);

            if (!holdSprayonator(client) || shouldAbort()) {
                msg(client, "\u00A7cCould not safely equip the sprayonator. Skipping auto spray.");
                return;
            }

            if (!attemptSprayAndHandleMaterial(client, guiDelay) && !shouldAbort()) {
                msg(client, "\u00A7cAutoSprayonator failed to spray this plot.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!shouldAbort()) {
                msg(client, "\u00A7cAutoSprayonator error: " + e.getMessage());
            }
        } finally {
            boolean aborted = shouldAbort();
            MacroWorkerThread.sleep(guiDelay);
            if (!aborted && MacroStateManager.isMacroRunning()) {
                client.execute(() -> dev.aether.macro.farming.FarmingMacroManager.enable(client, dev.aether.macro.farming.FarmingMacroManager.createMacroFromConfig()));
                MacroStateManager.setCurrentState(MacroState.State.FARMING);
            }
            PestManager.setCleaningInProgress(false);
            running = false;
            awaitingSprayResult = false;
            pendingResult = SprayResult.NONE;
            missingMaterial = null;
            if (!aborted && MacroStateManager.isMacroRunning()) {
                msg(client, "\u00A7aAutoSprayonator finished. Resuming farming.");
            }
        }
    }

    private static boolean attemptSprayAndHandleMaterial(Minecraft client, long guiDelay) {
        if (!ensureCorrectMaterial(client, guiDelay) || shouldAbort()) {
            if (!shouldAbort()) {
                msg(client, "\u00A7cFailed to set correct spray material.");
            }
            return false;
        }

        return sprayHeldMaterialAndHandleMissing(client, guiDelay);
    }

    public static boolean sprayHeldMaterialAndHandleMissing(Minecraft client, long guiDelay) {
        if (!trySprayAndWaitResult(client, guiDelay) || shouldAbort()) {
            return false;
        }

        if (pendingResult == SprayResult.SUCCESS) {
            return true;
        }

        if (pendingResult == SprayResult.INVALID_PLOT) {
            // Expected on the Barn strip; farming will carry us onto a sprayable
            // plot and the armed detector re-fires there.
            msg(client, "§eThis plot can't be sprayed. Will retry from a farm plot.");
            return true;
        }

        if (pendingResult != SprayResult.NO_MATERIAL) {
            return false;
        }

        if (!AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.get()) {
            msg(client, "\u00A7cMissing spray material and auto-buy is disabled.");
            return false;
        }

        String materialToBuy;
        if (missingMaterial != null && !missingMaterial.isBlank()) {
            materialToBuy = missingMaterial;
        } else {
            String configured = configuredMaterial();
            if (configured.equalsIgnoreCase("use selected")) {
                materialToBuy = getSprayonatorMaterialFromHotbar(client);
                if (materialToBuy == null) {
                    materialToBuy = getCurrentMaterial(client);
                }
            } else {
                materialToBuy = configured;
            }
        }

        if (materialToBuy == null || materialToBuy.isBlank()) {
            msg(client, "\u00A7cCould not determine which material to buy for the sprayonator.");
            return false;
        }

        int amount = Math.max(1, AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT.get());
        msg(client, "\u00A7eBuying spray material: \u00A7e" + amount + "x " + materialToBuy);
        boolean bought = BazaarUtils.executeBuy(client, materialToBuy, amount);
        if (!bought || shouldAbort()) {
            if (!shouldAbort()) {
                msg(client, "\u00A7cFailed to buy spray material from Bazaar.");
            }
            return false;
        }

        MacroWorkerThread.sleep(guiDelay);
        if (!holdSprayonator(client) || shouldAbort()) {
            if (!shouldAbort()) {
                msg(client, "\u00A7cSprayonator not found in hotbar after buy.");
            }
            return false;
        }

        pendingResult = SprayResult.NONE;
        missingMaterial = null;
        return trySprayAndWaitResult(client, guiDelay) && pendingResult == SprayResult.SUCCESS && !shouldAbort();
    }

    private static boolean trySprayAndWaitResult(Minecraft client, long guiDelay) {
        pendingResult = SprayResult.NONE;
        missingMaterial = null;
        awaitingSprayResult = true;

        try {
            PestClientThread.run(client, ClientUtils::performUseClick);

            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                if (shouldAbort()) {
                    return false;
                }
                if (pendingResult != SprayResult.NONE) return true;
                MacroWorkerThread.sleep(100);
            }
            return false;
        } finally {
            awaitingSprayResult = false;
        }
    }

    private static boolean ensureCorrectMaterial(Minecraft client, long guiDelay) {
        if (client.player == null) return false;

        String configured = configuredMaterial();
        String currentMaterial = getCurrentMaterial(client);

        if (configured.equalsIgnoreCase("use selected")) {
            if (currentMaterial != null) {
                ClientUtils.sendDebugMessage("Sprayonator material (using selected): " + currentMaterial);
                return true;
            }
            msg(client, "\u00A7cNo sprayonator material detected in hand. Please select a material.");
            return false;
        }

        if (currentMaterial != null && currentMaterial.equalsIgnoreCase(configured)) {
            ClientUtils.sendDebugMessage("Sprayonator material correct: " + currentMaterial);
            return true;
        }

        msg(client, "\u00A7eWrong sprayonator material ("
                + (currentMaterial != null ? currentMaterial : "unknown")
                + "), selecting \u00A7a" + configured + "\u00A7e...");
        if (cycleToMaterial(client, configured, guiDelay)) {
            msg(client, "\u00A7aSprayonator material set to " + configured + ".");
            return true;
        }
        msg(client, "\u00A7cFailed to select sprayonator material " + configured + ".");
        return false;
    }

    // safe to call from outside this class as long as the sprayonator is held
    public static boolean cycleToMaterial(Minecraft client, String target, long guiDelay) {
        if (client == null || client.player == null || target == null || target.isBlank()) {
            return false;
        }

        pendingMaterialChange = null;
        PestClientThread.run(client, ClientUtils::performAttackClickDirect);

        if (!waitForSprayonatorGui(client, 3000L) || shouldAbort()) {
            closeGui(client, guiDelay);
            return false;
        }

        MacroWorkerThread.sleep(Math.max(50L, ClientUtils.getGuiClickDelayMs(true)));
        if (shouldAbort()) {
            closeGui(client, guiDelay);
            return false;
        }

        boolean clicked = clickMaterialSlot(client, target);
        MacroWorkerThread.sleep(guiDelay);
        closeGui(client, guiDelay);
        return clicked && !shouldAbort();
    }

    private static boolean waitForSprayonatorGui(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (shouldAbort()) return false;

            boolean open = PestClientThread.call(client,
                    () -> client.screen instanceof AbstractContainerScreen<?> screen
                            && TablistUtils.stripColors(screen.getTitle().getString())
                                    .toLowerCase().contains("sprayonator"),
                    false);
            if (open) {
                return true;
            }

            MacroWorkerThread.sleep(50);
        }
        return false;
    }

    private static boolean clickMaterialSlot(Minecraft client, String target) {
        if (!client.isSameThread()) {
            return PestClientThread.call(client, () -> clickMaterialSlot(client, target), false);
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }

        String normalizedTarget = normalizeText(target);
        int fallbackSlot = -1;

        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String itemName = normalizeText(stack.getHoverName().getString());
            if (itemName.equals(normalizedTarget)) {
                clickSlot(client, i);
                return true;
            }

            if (fallbackSlot < 0 && stackTextMatches(client, stack, normalizedTarget)) {
                fallbackSlot = i;
            }
        }

        if (fallbackSlot >= 0) {
            clickSlot(client, fallbackSlot);
            return true;
        }

        return false;
    }

    private static boolean stackTextMatches(Minecraft client, ItemStack stack, String normalizedTarget) {
        String itemName = normalizeText(stack.getHoverName().getString());
        if (itemName.contains(normalizedTarget)) {
            return true;
        }

        try {
            for (Component line : stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    client.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL)) {
                if (normalizeText(line.getString()).contains(normalizedTarget)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private static void clickSlot(Minecraft client, int slotIndex) {
        PestClientThread.run(client, () -> {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                ClientUtils.performSlotClick(screen, slotIndex, 0, ContainerInput.PICKUP);
            }
        });
    }

    private static void closeGui(Minecraft client, long guiDelay) {
        if (client == null) {
            return;
        }

        PestClientThread.run(client, () -> {
            if (client.player != null && client.screen != null) {
                client.player.closeContainer();
            }
        });
        MacroWorkerThread.sleep(guiDelay);
    }

    private static String normalizeText(String text) {
        return TablistUtils.stripColors(text == null ? "" : text).trim().toLowerCase();
    }

    public static boolean ensureMaterial(Minecraft client, String target, long guiDelay) {
        if (client.player == null || target == null || target.isBlank()) return false;

        String currentMaterial = getCurrentMaterial(client);
        if (currentMaterial != null && currentMaterial.equalsIgnoreCase(target)) {
            ClientUtils.sendDebugMessage("Sprayonator material already correct: " + currentMaterial);
            return true;
        }

        ClientUtils.sendDebugMessage("Sprayonator material target: " + target
                + ", current: " + (currentMaterial != null ? currentMaterial : "unknown"));
        return cycleToMaterial(client, target, guiDelay);
    }

    public static String getHeldMaterial(Minecraft client) {
        return getCurrentMaterial(client);
    }

    public static String getHotbarMaterial(Minecraft client) {
        return getSprayonatorMaterialFromHotbar(client);
    }

    private static String getCurrentMaterial(Minecraft client) {
        if (client != null && !client.isSameThread()) {
            return PestClientThread.call(client, () -> getCurrentMaterial(client), null);
        }
        if (client.player == null) return null;

        ItemStack held = client.player.getMainHandItem();
        if (held == null || held.isEmpty()) return null;

        return SprayonatorItem.selectedMaterial(held);
    }

    private static String getSprayonatorMaterialFromHotbar(Minecraft client) {
        if (client != null && !client.isSameThread()) {
            return PestClientThread.call(client, () -> getSprayonatorMaterialFromHotbar(client), null);
        }
        if (client.player == null) return null;

        int slot = findSprayonatorSlot(client);
        return slot < 0 ? null : SprayonatorItem.selectedMaterial(client.player.getInventory().getItem(slot));
    }

    public static boolean holdSprayonator(Minecraft client) {
        if (client == null || client.isSameThread() || shouldAbort()) return false;
        int slot = PestClientThread.call(client, () -> findSprayonatorSlot(client), -1);
        if (slot < 0) {
            return false;
        }
        SprayonatorSwapGuard guard = new SprayonatorSwapGuard();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!shouldAbort() && System.currentTimeMillis() < deadline) {
            boolean selected = PestClientThread.call(client, () -> {
                if (shouldAbort() || System.currentTimeMillis() >= deadline
                        || client.player == null || client.options == null
                        || client.gameMode == null || findSprayonatorSlot(client) != slot) {
                    return false;
                }
                boolean attacking = client.options.keyAttack.isDown()
                        || ProgrammaticAttackTracker.isHeld();
                boolean queuedAttack = client.options.keyAttack.consumeClick();
                FarmingMacroManager.releaseInputs(client);
                ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false);
                ClientUtils.setKeyMappingState(client.options.keyAttack, false);
                ClientUtils.discardQueuedClicks(client.options.keyAttack);
                client.gameMode.stopDestroyBlock();
                if (!guard.readyToSwap(client.player.tickCount, attacking || queuedAttack)) {
                    return false;
                }
                FailsafeManager.selectHotbarSlot(client, slot);
                return true;
            }, false);
            if (selected) {
                return MacroWorkerThread.sleep(150) && !shouldAbort();
            }
            if (!MacroWorkerThread.sleep(25)) {
                return false;
            }
        }
        return false;
    }

    private static int findSprayonatorSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (SprayonatorItem.matches(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean tabListNeedsSpray(Minecraft client) {
        if (client != null && !client.isSameThread()) {
            return PestClientThread.call(client, () -> tabListNeedsSpray(client), false);
        }
        for (String line : TablistUtils.getRawTabLines(client)) {
            String lower = line.toLowerCase();
            if (lower.contains("spray:") && lower.contains("none")) {
                return true;
            }
        }
        return false;
    }

    private static String configuredMaterial() {
        String configured = AetherConfig.AUTO_SPRAYONATOR_MATERIAL.get();
        return configured == null || configured.isBlank() ? "Use Selected" : configured.trim();
    }

    private static boolean shouldAbort() {
        return cancelRequested || MacroWorkerThread.getInstance().isCancelled();
    }

    private static void msg(Minecraft client, String text) {
        ClientUtils.sendMessage(text);
    }
}
