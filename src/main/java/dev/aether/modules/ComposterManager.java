package dev.aether.modules;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.mixin.MixinMinecraft;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.BazaarUtils;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ComposterManager {
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("^(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)/(\\d{1,3})k$");
    private static final String SOURCE_BAZAAR = "BAZAAR";
    private static final int SLOT_INSERT_CROPS_FROM_SACKS = 39;
    private static final int SLOT_INSERT_FUEL_FROM_SACKS = 41;
    private static final int SLOT_CONFIRM_SACKS = 11;
    private static final long ACTION_DELAY_MS = 1_000L;
    private static final long PATH_TIMEOUT_MS = 25_000L;
    private static final long MENU_TIMEOUT_MS = 5_000L;
    private static final long INTERACT_ROTATION_MS = 200L;

    private static volatile boolean running = false;
    private static volatile boolean autoSequence = false;
    private static volatile long lastAutoRunCompletedMs = System.currentTimeMillis();
    private static volatile Runnable completionCallback = null;

    private ComposterManager() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void reset() {
        running = false;
        autoSequence = false;
        completionCallback = null;
        PathfindingManager.stop(false);
    }

    public static long getAutoComposterElapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - lastAutoRunCompletedMs);
    }

    public static boolean shouldRunAutoComposter() {
        if (!AetherConfig.AUTO_COMPOSTER.get() || running) {
            return false;
        }

        long intervalMs = Math.max(1L, AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.get()) * 60_000L;
        return getAutoComposterElapsedMs() >= intervalMs;
    }

    public static void runAutoComposterIfDue(Minecraft client, Runnable onComplete) {
        if (!shouldRunAutoComposter()) {
            runCompletion(onComplete);
            return;
        }

        autoSequence = true;
        completionCallback = onComplete;
        start(client, false);
    }

    public static void manualTrigger(Minecraft client) {
        start(client, true);
    }

    private static synchronized void start(Minecraft client, boolean manual) {
        if (client == null || client.player == null || client.level == null) {
            runCompletionIfAuto();
            return;
        }

        if (running) {
            ClientUtils.sendMessage(client, "\u00A7cAuto Composter is already running.", false);
            runCompletionIfAuto();
            return;
        }

        if (!manual
                && isBazaarMode()
                && ClientUtils.getPurse(client) >= 0
                && ClientUtils.getPurse(client) < AetherConfig.AUTO_COMPOSTER_MIN_PURSE.get()) {
            ClientUtils.sendDebugMessage(client, "Composter: skipping because purse is below configured minimum.");
            markAutoComplete();
            runCompletionIfAuto();
            return;
        }

        running = true;
        boolean shouldResumeFarming = manual && MacroStateManager.getCurrentState() == MacroState.State.FARMING;
        if (shouldResumeFarming) {
            client.execute(() -> FarmingMacroManager.disable(client));
        }

        ClientUtils.sendMessage(client, "\u00A7eStarting Auto Composter...", false);
        MacroWorkerThread.getInstance().submit("AutoComposter", () -> {
            try {
                runSequence(client);
                if (autoSequence) {
                    markAutoComplete();
                }
            } catch (Exception e) {
                ClientUtils.sendDebugMessage(client, "Composter error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                finish(client, shouldResumeFarming);
            }
        });
    }

    private static void runSequence(Minecraft client) throws InterruptedException {
        if (shouldAbort(client)) {
            return;
        }

        ensureScreenClosed(client);
        ClientUtils.sendCommand(client, "/tptoplot barn");
        MacroWorkerThread.sleep(2_000);

        Entity composter = findComposter(client);
        BlockPos target = composter != null ? composter.blockPosition() : configuredComposterPos();
        boolean openedAfterPath = walkNearAndOpen(client, target);
        if (shouldAbort(client)) {
            return;
        }

        composter = findComposter(client);
        if (composter == null) {
            ClientUtils.sendMessage(client, "\u00A7cCould not find the Composter.", false);
            return;
        }

        if (!openedAfterPath && !openComposter(client, composter)) {
            ClientUtils.sendMessage(client, "\u00A7cCould not open the Composter.", false);
            return;
        }

        if (isSacksMode()) {
            insertFromSacks(client);
            return;
        }

        List<Purchase> purchases = getConfiguredBazaarPurchases();
        ensureScreenClosed(client);
        for (Purchase purchase : purchases) {
            if (shouldAbort(client)) {
                return;
            }
            ClientUtils.sendDebugMessage(client, "Composter: buying " + purchase.amount() + " " + purchase.itemName());
            boolean bought = BazaarUtils.executeBuy(client, purchase.itemName(), purchase.amount());
            if (!bought) {
                ClientUtils.sendMessage(client, "\u00A7cFailed to buy " + purchase.itemName() + ".", false);
                return;
            }
            waitActionDelay();
        }

        composter = findComposter(client);
        if (composter == null) {
            ClientUtils.sendMessage(client, "\u00A7cCould not find the Composter after Bazaar buys.", false);
            return;
        }

        if (!openComposter(client, composter)) {
            ClientUtils.sendMessage(client, "\u00A7cCould not reopen the Composter.", false);
            return;
        }

        fillComposter(client, purchases);
        ClientUtils.sendMessage(client, "\u00A7aSupplied composter with resources.", false);
    }

    public static int getSourceModeIndex() {
        return isBazaarMode() ? 1 : 0;
    }

    public static boolean isBazaarMode() {
        return SOURCE_BAZAAR.equalsIgnoreCase(AetherConfig.AUTO_COMPOSTER_SOURCE_MODE.get());
    }

    public static boolean isSacksMode() {
        return !isBazaarMode();
    }

    private static boolean walkNearAndOpen(Minecraft client, BlockPos target) throws InterruptedException {
        if (client.player == null) {
            return false;
        }

        if (client.player.blockPosition().distSqr(target) <= 9.0) {
            return false;
        }

        AtomicBoolean pathFinished = new AtomicBoolean(false);
        AtomicBoolean pathFailed = new AtomicBoolean(false);
        AtomicBoolean rotationStarted = new AtomicBoolean(false);

        client.execute(() -> PathfindingManager.startConfiguredWalk(
                client,
                target.getX(),
                target.getY(),
                target.getZ(),
                () -> {
                    rotationStarted.set(true);
                    RotationManager.initiateRotation(
                            client,
                            Vec3.atCenterOf(configuredComposterPos()),
                            INTERACT_ROTATION_MS);
                    pathFinished.set(true);
                },
                () -> pathFailed.set(true),
                true,
                0.5));

        long deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS;
        while (!pathFinished.get() && !pathFailed.get() && System.currentTimeMillis() < deadline && !shouldAbort(client)) {
            MacroWorkerThread.sleep(20);
        }

        if (!pathFinished.get()) {
            PathfindingManager.stop(false);
            return false;
        }

        if (!rotationStarted.get()) {
            rotateToComposterInteractTarget(client);
        } else {
            waitForComposterRotation(client);
        }
        directAttackClick(client);
        return waitForComposterScreen(client, MENU_TIMEOUT_MS);
    }

    private static boolean openComposter(Minecraft client, Entity composter) throws InterruptedException {
        ensureScreenClosed(client);
        for (int attempt = 0; attempt < 3 && !shouldAbort(client); attempt++) {
            Entity refreshed = findComposter(client);
            if (refreshed != null) {
                composter = refreshed;
            }

            attemptComposterInteract(client);

            if (waitForComposterScreen(client, MENU_TIMEOUT_MS)) {
                return true;
            }
            ensureScreenClosed(client);
            MacroWorkerThread.sleep(250);
        }
        return false;
    }

    private static void attemptComposterInteract(Minecraft client) throws InterruptedException {
        if (shouldAbort(client)) {
            return;
        }

        rotateToComposterInteractTarget(client);
        directAttackClick(client);
    }

    private static void rotateToComposterInteractTarget(Minecraft client) throws InterruptedException {
        Vec3 lookTarget = Vec3.atCenterOf(configuredComposterPos());
        client.execute(() -> RotationManager.initiateRotation(client, lookTarget, INTERACT_ROTATION_MS));
        waitForComposterRotation(client);
    }

    private static void waitForComposterRotation(Minecraft client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(600L, INTERACT_ROTATION_MS + 250L);
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline && !shouldAbort(client)) {
            MacroWorkerThread.sleep(20);
        }
        MacroWorkerThread.sleep(50);
    }

    private static void directAttackClick(Minecraft client) throws InterruptedException {
        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            client.player.swing(InteractionHand.MAIN_HAND);
            ((MixinMinecraft) client).aether$startAttack();
            ((MixinMinecraft) client).aether$continueAttack(false);
        });
        MacroWorkerThread.sleep(120);
    }

    private static List<Purchase> getConfiguredBazaarPurchases() {
        List<Purchase> purchases = new ArrayList<>();
        String cropMaterial = AetherConfig.AUTO_COMPOSTER_CROP_MATERIAL.get().trim();
        String fuelMaterial = AetherConfig.AUTO_COMPOSTER_FUEL_MATERIAL.get().trim();
        if (!cropMaterial.isEmpty()) {
            purchases.add(new Purchase(cropMaterial, Math.max(1, AetherConfig.AUTO_COMPOSTER_CROP_AMOUNT.get())));
        }
        if (!fuelMaterial.isEmpty()) {
            purchases.add(new Purchase(fuelMaterial, Math.max(1, AetherConfig.AUTO_COMPOSTER_FUEL_AMOUNT.get())));
        }
        return purchases;
    }

    private static void insertFromSacks(Minecraft client) throws InterruptedException {
        if (!(client.screen instanceof AbstractContainerScreen<?>)) {
            return;
        }

        clickSlot(client, SLOT_INSERT_CROPS_FROM_SACKS);
        waitActionDelay();
        clickSlot(client, SLOT_CONFIRM_SACKS);
        waitActionDelay();

        if (!waitForComposterScreen(client, MENU_TIMEOUT_MS)) {
            ClientUtils.sendDebugMessage(client, "Composter: composter menu did not return after crop sacks insert.");
        }

        clickSlot(client, SLOT_INSERT_FUEL_FROM_SACKS);
        waitActionDelay();
        clickSlot(client, SLOT_CONFIRM_SACKS);
        waitActionDelay();

        ensureScreenClosed(client);
        ClientUtils.sendMessage(client, "\u00A7aInserted composter resources from sacks.", false);
    }

    private static Resource readResource(Minecraft client, AbstractContainerScreen<?> screen, int preferredSlot, String namePart) {
        Slot slot = preferredSlot < screen.getMenu().slots.size() ? screen.getMenu().slots.get(preferredSlot) : null;
        Resource resource = readResource(client, slot);
        if (resource.known()) {
            return resource;
        }

        int containerSize = Math.max(0, screen.getMenu().slots.size() - 36);
        for (int i = 0; i < containerSize; i++) {
            Slot candidate = screen.getMenu().slots.get(i);
            if (!candidate.hasItem()) {
                continue;
            }
            String itemName = TablistUtils.stripColors(candidate.getItem().getHoverName().getString()).toLowerCase();
            if (itemName.contains(namePart)) {
                resource = readResource(client, candidate);
                if (resource.known()) {
                    return resource;
                }
            }
        }

        return Resource.unknown();
    }

    private static Resource readResource(Minecraft client, Slot slot) {
        if (slot == null || !slot.hasItem() || client.player == null) {
            return Resource.unknown();
        }

        ItemStack stack = slot.getItem();
        List<Component> lore = stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.EMPTY,
                client.player,
                net.minecraft.world.item.TooltipFlag.NORMAL);
        for (Component component : lore) {
            String line = TablistUtils.stripColors(component.getString()).replace(" ", "").trim();
            Matcher matcher = RESOURCE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            int current = (int) Double.parseDouble(matcher.group(1).replace(",", ""));
            int max = Integer.parseInt(matcher.group(2)) * 1_000;
            return new Resource(current, max);
        }
        return Resource.unknown();
    }

    private static void fillComposter(Minecraft client, List<Purchase> purchases) throws InterruptedException {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }

        for (Purchase purchase : purchases) {
            if (shouldAbort(client)) {
                return;
            }

            int slotId = findItemSlot(screen, purchase.itemName());
            if (slotId == -1) {
                continue;
            }

            int finalSlotId = slotId;
            client.execute(() -> {
                if (client.screen instanceof AbstractContainerScreen<?> currentScreen) {
                    ClientUtils.performSlotClick(client, currentScreen, finalSlotId, 0, ContainerInput.PICKUP);
                }
            });
            waitActionDelay();
        }

        ensureScreenClosed(client);
    }

    private static void waitActionDelay() {
        MacroWorkerThread.sleep((int) ACTION_DELAY_MS);
    }

    private static void clickSlot(Minecraft client, int slotId) {
        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?> screen
                    && slotId >= 0
                    && slotId < screen.getMenu().slots.size()) {
                ClientUtils.performSlotClick(client, screen, slotId, 0, ContainerInput.PICKUP);
            }
        });
    }

    private static int findItemSlot(AbstractContainerScreen<?> screen, String itemName) {
        String target = itemName.toLowerCase();
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }

            String name = TablistUtils.stripColors(slot.getItem().getHoverName().getString()).toLowerCase();
            if (name.contains(target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean waitForComposterScreen(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                String title = TablistUtils.stripColors(screen.getTitle().getString()).toLowerCase();
                if (title.contains("composter")) {
                    return true;
                }
            }
            MacroWorkerThread.sleep(50);
        }
        return false;
    }

    private static Entity findComposter(Minecraft client) {
        if (client.level == null || client.player == null) {
            return null;
        }

        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)) {
                continue;
            }

            String name = TablistUtils.stripColors(entity.getName().getString()).toUpperCase();
            String displayName = TablistUtils.stripColors(entity.getDisplayName().getString()).toUpperCase();
            if (!name.contains("COMPOSTER") && !displayName.contains("COMPOSTER")) {
                continue;
            }

            double distance = entity.distanceToSqr(client.player);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = entity;
            }
        }
        return closest;
    }

    private static BlockPos configuredComposterPos() {
        return new BlockPos(
                AetherConfig.AUTO_COMPOSTER_X.get(),
                AetherConfig.AUTO_COMPOSTER_Y.get(),
                AetherConfig.AUTO_COMPOSTER_Z.get());
    }

    private static void ensureScreenClosed(Minecraft client) {
        if (client == null || client.screen == null) {
            return;
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.closeContainer();
            }
        });
        MacroWorkerThread.sleep(ThreadLocalRandom.current().nextInt(150, 301));
    }

    private static boolean shouldAbort(Minecraft client) {
        return !running || MacroWorkerThread.getInstance().isCancelled() || client == null || client.player == null || client.level == null;
    }

    private static void finish(Minecraft client, boolean shouldResumeFarming) {
        ensureScreenClosed(client);
        PathfindingManager.stop(false);
        RotationManager.cancelRotation();
        running = false;

        Runnable callback = completionCallback;
        completionCallback = null;
        boolean wasAuto = autoSequence;
        autoSequence = false;

        if (callback != null) {
            runCompletion(callback);
            return;
        }

        if (shouldResumeFarming && MacroStateManager.isMacroRunning()) {
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
        } else if (!wasAuto) {
            ClientUtils.sendMessage(client, "\u00A7aAuto Composter finished.", false);
        }
    }

    private static void markAutoComplete() {
        lastAutoRunCompletedMs = System.currentTimeMillis();
    }

    private static void runCompletionIfAuto() {
        Runnable callback = completionCallback;
        completionCallback = null;
        autoSequence = false;
        runCompletion(callback);
    }

    private static void runCompletion(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private record Purchase(String itemName, int amount) {
    }

    private record Resource(int current, int max) {
        static Resource unknown() {
            return new Resource(-1, -1);
        }

        boolean known() {
            return current >= 0 && max > 0;
        }
    }
}
