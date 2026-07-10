package dev.aether.modules.pest.helpers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.BazaarUtils;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class AutoSprayonatorManager {

    private static final Pattern NO_MATERIAL_PATTERN = Pattern.compile("(?i)^you don't have any\\s+(.+?)(?:!|\\.)?$");
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
        NO_MATERIAL
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
            String hotbarMaterial = getSprayonatorMaterialFromHotbar(client);
            if (hotbarMaterial == null) {
                msg(client, "\u00A7cSprayonator not found in hotbar. Skipping auto spray.");
                return;
            }

            MacroStateManager.setCurrentState(MacroState.State.SPRAYING);
            PestManager.isCleaningInProgress = true;

            msg(client, "\u00A7eUnsprayed plot detected. Pausing farming to spray...");
            client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
            MacroWorkerThread.sleep(guiDelay);

            if (!holdSprayonator(client) || shouldAbort()) {
                msg(client, "\u00A7cSprayonator not found in hotbar. Skipping auto spray.");
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
                client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client, dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
                MacroStateManager.setCurrentState(MacroState.State.FARMING);
            }
            PestManager.isCleaningInProgress = false;
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
            ClientUtils.performUseClick();

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
                if (AetherConfig.SHOW_DEBUG.get()) {
                    ClientUtils.sendDebugMessage("Sprayonator material (using selected): " + currentMaterial);
                }
                return true;
            }
            msg(client, "\u00A7cNo sprayonator material detected in hand. Please select a material.");
            return false;
        }

        if (currentMaterial != null && currentMaterial.equalsIgnoreCase(configured)) {
            if (AetherConfig.SHOW_DEBUG.get()) {
                ClientUtils.sendDebugMessage("Sprayonator material correct: " + currentMaterial);
            }
            return true;
        }

        msg(client, "\u00A7eWrong sprayonator material ("
                + (currentMaterial != null ? currentMaterial : "unknown")
                + "), cycling to \u00A7a" + configured + "\u00A7e...");
        if (cycleToMaterial(client, configured, guiDelay)) {
            msg(client, "\u00A7aSprayonator material set to " + configured + ".");
            return true;
        }
        msg(client, "\u00A7cFailed to cycle sprayonator to " + configured + ".");
        return false;
    }

    /**
     * Left-clicks the sprayonator repeatedly until the chat confirms the target material is selected.
     * Max 10 attempts. Returns true if the target was reached.
     * Safe to call from outside this class (e.g. dynamic pests) as long as the sprayonator is held.
     */
    public static boolean cycleToMaterial(Minecraft client, String target, long guiDelay) {
        final int MAX_ATTEMPTS = 10;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (shouldAbort()) return false;

            pendingMaterialChange = null;
            ClientUtils.performAttackClickDirect();

            // Wait for the click to fire and Hypixel to respond before polling
            MacroWorkerThread.sleep(200);

            long deadline = System.currentTimeMillis() + 2800L;
            while (System.currentTimeMillis() < deadline) {
                if (shouldAbort()) return false;
                String changed = pendingMaterialChange;
                if (changed != null) {
                    if (changed.equalsIgnoreCase(target)) return true;
                    break;
                }
                MacroWorkerThread.sleep(50);
            }

            MacroWorkerThread.sleep(guiDelay);
        }
        return false;
    }

    public static boolean ensureMaterial(Minecraft client, String target, long guiDelay) {
        if (client.player == null || target == null || target.isBlank()) return false;

        String currentMaterial = getCurrentMaterial(client);
        if (currentMaterial != null && currentMaterial.equalsIgnoreCase(target)) {
            if (AetherConfig.SHOW_DEBUG.get()) {
                ClientUtils.sendDebugMessage("Sprayonator material already correct: " + currentMaterial);
            }
            return true;
        }

        if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage("Sprayonator material target: " + target
                    + ", current: " + (currentMaterial != null ? currentMaterial : "unknown"));
        }
        return cycleToMaterial(client, target, guiDelay);
    }

    public static String getHeldMaterial(Minecraft client) {
        return getCurrentMaterial(client);
    }

    public static String getHotbarMaterial(Minecraft client) {
        return getSprayonatorMaterialFromHotbar(client);
    }

    private static String getCurrentMaterial(Minecraft client) {
        if (client.player == null) return null;

        ItemStack held = client.player.getMainHandItem();
        if (held == null || held.isEmpty()) return null;

        return getMaterialFromSprayonatorStack(client, held);
    }

    private static String getSprayonatorMaterialFromHotbar(Minecraft client) {
        if (client.player == null) return null;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;

            String material = getMaterialFromSprayonatorStack(client, stack);
            if (material != null) {
                return material;
            }
        }

        return null;
    }

    private static String getMaterialFromSprayonatorStack(Minecraft client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        String name = TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase();
        if (!name.contains("sprayonator")) return null;

        try {
            Component hoverText = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    client.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL)
                    .stream()
                    .filter(c -> {
                        String line = TablistUtils.stripColors(c.getString()).toLowerCase();
                        return line.contains("selected material:");
                    })
                    .findFirst()
                    .orElse(null);

            if (hoverText != null) {
                String line = TablistUtils.stripColors(hoverText.getString());
                int idx = line.toLowerCase().indexOf("selected material:");
                if (idx >= 0) {
                    return line.substring(idx + "selected material:".length()).trim();
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public static boolean holdSprayonator(Minecraft client) {
        if (client.player == null) return false;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;

            String clean = TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase();
            if (clean.contains("sprayonator")) {
                int slot = i;
                client.execute(() -> {
                    if (client.player != null) {
                        FailsafeManager.selectHotbarSlot(client, slot);
                    }
                });
                MacroWorkerThread.sleep(150);
                return true;
            }
        }
        return false;
    }

    private static boolean tabListNeedsSpray(Minecraft client) {
        for (String line : TablistUtils.getRawTabLines(client)) {
            String lower = line.toLowerCase();
            if (lower.contains("spray:") && lower.contains("none")) {
                return true;
            }
        }
        return false;
    }

    private static String configuredMaterial() {
        return "Use Selected";
    }

    private static boolean shouldAbort() {
        return cancelRequested || MacroWorkerThread.getInstance().isCancelled();
    }

    private static void msg(Minecraft client, String text) {
        ClientUtils.sendMessage(text);
    }
}
