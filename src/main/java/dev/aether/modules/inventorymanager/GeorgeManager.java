package dev.aether.modules.inventorymanager;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class GeorgeManager {
    private static final long GEORGE_COOLDOWN_MS = 30000;

    public static volatile boolean isSelling = false;
    public static volatile boolean isPreparingToSell = false;
    public static volatile int interactionStage = 0;
    public static volatile long interactionTime = 0;
    public static volatile int confirmationCount = 0;

    private static long lastGeorgeSellAttemptTime = 0;
    private static long lastGeorgeGuiCloseTime = 0;
    private static long lastGeorgeSequenceActionTime = 0;

    private static MacroState.State getPrepRequiredState() {
        return AetherConfig.FARM_WHILE_CALLING_GEORGE.get()
                ? MacroState.State.FARMING
                : MacroState.State.GEORGE;
    }

    private static void pauseFarmingForGeorgeIfNeeded() {
        if (!AetherConfig.FARM_WHILE_CALLING_GEORGE.get()
                && MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
            MacroStateManager.setCurrentState(MacroState.State.GEORGE);
        }
    }

    private static void resumeFarmingAfterGeorgeWait(Minecraft client, String debugMessage) {
        if (AetherConfig.FARM_WHILE_CALLING_GEORGE.get()
                && MacroStateManager.getCurrentState() == MacroState.State.GEORGE) {
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            ClientUtils.sendDebugMessage(debugMessage);
        }
    }

    public static void reset() {
        isSelling = false;
        isPreparingToSell = false;
        interactionStage = 0;
        interactionTime = 0;
        confirmationCount = 0;
        lastGeorgeSellAttemptTime = 0;
        lastGeorgeGuiCloseTime = 0;
        lastGeorgeSequenceActionTime = 0;
    }

    public static void onCallGeorgeSent() {
        if (!shouldUseGeorgeAutomation() && !isPreparingToSell && !isSelling) {
            return;
        }

        isSelling = true;
        interactionStage = 0;
        interactionTime = System.currentTimeMillis();
        confirmationCount = 0;
        lastGeorgeGuiCloseTime = 0;
        lastGeorgeSequenceActionTime = 0;
    }

    public static void handleGeorgeMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return;
        }

        if (!shouldUseGeorgeAutomation()) {
            reset();
            return;
        }

        String title = stripFormatting(screen.getTitle().getString());
        boolean isGeorgeGUI = isGeorgeScreen(title);
        boolean isGeorgeInteractionGUI = isGeorgeInteractionScreen(title);

        if (!isSelling && !isPreparingToSell) {
            if (!MacroStateManager.isMacroRunning()) {
                return;
            }

            if (isGeorgeGUI) {
                ClientUtils.sendMessage("\u00A7cUnexpected George GUI detected. Closing and returning to sequence...", false);
                client.player.closeContainer();

                MacroWorkerThread.getInstance().submit("George-UnexpectedGUI-Failsafe", () -> {
                    if (MacroWorkerThread.shouldAbortTask(client)) {
                        return;
                    }
                    client.execute(() -> FarmingMacroManager.disable(client));
                    MacroWorkerThread.sleep(1000);
                    if (MacroWorkerThread.shouldAbortTask(client)) {
                        return;
                    }
                    ClientUtils.sendDebugMessage("Restarting macro after unexpected George GUI closure");
                    client.execute(() -> FarmingMacroManager.enable(client,
                            FarmingMacroManager.createMacroFromConfig()));
                });
            }
            return;
        }

        lastGeorgeGuiCloseTime = 0;

        if (!isGeorgeInteractionGUI) {
            return;
        }

        if (isSelling && isGeorgeGUI
                && MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
            MacroStateManager.setCurrentState(MacroState.State.GEORGE);
            ClientUtils.sendMessage("\u00A7eGeorge GUI detected. Pausing farming.", true);
        }

        long now = System.currentTimeMillis();
        if (now - interactionTime < ClientUtils.getGuiClickDelayMs(interactionStage == 0)) {
            return;
        }

        if (title.contains("offer pets") && interactionStage < 1) {
            interactionStage = 1;
            ClientUtils.sendDebugMessage("Stage 1: Offer pets menu detected");
            interactionTime = now;
        } else if ((title.contains("confirm") || title.contains("are you sure")) && interactionStage < 3) {
            interactionStage = 3;
            ClientUtils.sendDebugMessage("Stage 3: confirm sale gui screen");
            interactionTime = now;
        }

        switch (interactionStage) {
            case 0:
                if (title.contains("george") || title.contains("sell pets") || title.contains("pet collector")) {
                    int petSlotIdx = findPetSlotIdx(screen);
                    if (petSlotIdx != -1) {
                        String petName = screen.getMenu().slots.get(petSlotIdx).getItem().getHoverName().getString();
                        ClientUtils.sendMessage("\u00A7aSelling pet: " + petName, true);
                        ClientUtils.performSlotClick(client, screen, petSlotIdx, 0, ContainerInput.QUICK_MOVE);
                        interactionTime = now;
                        lastGeorgeSequenceActionTime = now;
                    } else if (countPetsInInventory(client) == 0) {
                        finishSelling(client);
                    }
                }
                break;

            case 1:
                Slot slot13 = screen.getMenu().slots.size() > 13 ? screen.getMenu().slots.get(13) : null;
                if (slot13 != null && slot13.hasItem() && isRatOrSlug(slot13.getItem())) {
                    interactionStage = 2;
                    ClientUtils.sendDebugMessage("Stage 2: rat/slug pet in slot 13");
                    interactionTime = now;
                } else {
                    int petSlotIdx = findPetSlotIdx(screen);
                    if (petSlotIdx != -1 && petSlotIdx != 13) {
                        ClientUtils.performSlotClick(client, screen, petSlotIdx, 0, ContainerInput.QUICK_MOVE);
                        interactionTime = now;
                        lastGeorgeSequenceActionTime = now;
                    }
                }
                break;

            case 2:
                if (title.contains("offer pets")) {
                    int sellButtonIdx = findButtonSlot(screen, "sell pet", "accept", "confirm", "offer");
                    if (sellButtonIdx != -1 && client.gameMode != null) {
                        ClientUtils.performSlotClick(client, screen, sellButtonIdx, 0, ContainerInput.PICKUP);
                        interactionTime = now;
                        lastGeorgeSequenceActionTime = now;
                    }
                }
                break;

            case 3:
                int confirmSlotIdx = findButtonSlot(screen, "confirm", "yes", "accept", "click to accept");
                if (confirmSlotIdx != -1) {
                    ClientUtils.performSlotClick(client, screen, confirmSlotIdx, 0, ContainerInput.PICKUP);
                    interactionTime = now + 500;
                    lastGeorgeSequenceActionTime = now;
                }
                break;

            default:
                break;
        }
    }

    private static boolean isPriorityEventActive(Minecraft client) {
        MacroState.State state = MacroStateManager.getCurrentState();
        if (state != MacroState.State.FARMING && state != MacroState.State.GEORGE) {
            return true;
        }
        return PestManager.isCleaningInProgress
                || PestPrepSwapManager.prepSwappedForCurrentPestCycle
                || BookCombineManager.isCombining
                || BookCombineManager.isPreparingToCombine
                || JunkManager.isDropping
                || JunkManager.isPreparingToDrop
                || AutoSellManager.isSelling
                || AutoSellManager.isPreparingToSell;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!shouldUseGeorgeAutomation() || client.player == null) {
            if (isSelling || isPreparingToSell) {
                reset();
            }
            return;
        }

        if (System.currentTimeMillis() - lastGeorgeSellAttemptTime < GEORGE_COOLDOWN_MS) {
            return;
        }

        if (isPreparingToSell) {
            if (isPriorityEventActive(client)) {
                isPreparingToSell = false;
                if (MacroStateManager.getCurrentState() == MacroState.State.GEORGE) {
                    MacroStateManager.setCurrentState(MacroState.State.FARMING);
                }
                lastGeorgeSellAttemptTime = System.currentTimeMillis();
                ClientUtils.sendMessage("\u00A7cAborting George prep due to priority event.", false);
            }
            return;
        }

        if (isSelling) {
            if (isPriorityEventActive(client)) {
                isSelling = false;
                lastGeorgeGuiCloseTime = 0;
                lastGeorgeSequenceActionTime = 0;
                if (MacroStateManager.getCurrentState() == MacroState.State.GEORGE) {
                    MacroStateManager.setCurrentState(MacroState.State.FARMING);
                }
                ClientUtils.sendMessage("\u00A7cAborting George sell due to priority event.", false);
                lastGeorgeSellAttemptTime = System.currentTimeMillis();
                return;
            }

            if (client.screen == null) {
                resumeFarmingAfterGeorgeWait(client, "George GUI closed. Resuming farming until next re-call.");

                if (interactionStage > 0) {
                    if (interactionStage >= 3) {
                        ClientUtils.sendDebugMessage("Stage 4: gui closed");
                    }
                    interactionStage = 0;
                }

                long now = System.currentTimeMillis();
                if (lastGeorgeGuiCloseTime == 0) {
                    lastGeorgeGuiCloseTime = now;
                    return;
                }

                long recallDelayMs = ConfigHelpers.getRandomizedDelay(
                        AetherConfig.GEORGE_POST_SELL_DELAY_MIN_MS.get(),
                        AetherConfig.GEORGE_POST_SELL_DELAY_MAX_MS.get());
                long recallReadyAt = Math.max(lastGeorgeGuiCloseTime, lastGeorgeSequenceActionTime);
                if (now - recallReadyAt < recallDelayMs) {
                    return;
                }

                if (now - interactionTime > 1500) {
                    int remaining = countPetsInInventory(client);
                    if (remaining > 0) {
                        ClientUtils.sendMessage("\u00A77GUI closed, but " + remaining + " pets remain. Re-calling George...", false);
                        interactionTime = now;
                        interactionStage = 0;
                        confirmationCount = 0;
                        lastGeorgeSequenceActionTime = 0;
                        ClientUtils.sendCommand(client, "/call george");
                    } else {
                        finishSelling(client);
                    }
                }
            }
            return;
        }

        if (isPriorityEventActive(client)
                || LoadoutManager.isSwappingLoadout) {
            return;
        }

        int petCount = countPetsInInventory(client);
        if (petCount >= AetherConfig.GEORGE_SELL_THRESHOLD.get()) {
            triggerAutomaticSell(client, petCount);
        }
    }

    private static void finishSelling(Minecraft client) {
        if (client.player != null && client.screen != null) {
            client.player.closeContainer();
        }
        isSelling = false;
        isPreparingToSell = false;
        lastGeorgeGuiCloseTime = 0;
        lastGeorgeSequenceActionTime = 0;
        if (MacroStateManager.getCurrentState() == MacroState.State.GEORGE) {
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
        }
        lastGeorgeSellAttemptTime = System.currentTimeMillis();
        ClientUtils.sendMessage("\u00A7aGeorge autosell finished. Resuming tasks...", true);
        client.execute(() -> GearManager.swapToFarmingTool(client));
    }

    public static int getSellablePetCount(Minecraft client) {
        if (client == null || client.player == null) {
            return 0;
        }
        return countPetsInInventory(client);
    }

    public static long getGeorgeCooldownRemainingMs() {
        return Math.max(0L, GEORGE_COOLDOWN_MS - (System.currentTimeMillis() - lastGeorgeSellAttemptTime));
    }

    private static int countPetsInInventory(Minecraft client) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = stripFormatting(stack.getHoverName().getString());
                if ((name.contains("rat") || name.contains("slug")) && name.contains("[lvl 1]")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int findPetSlotIdx(AbstractContainerScreen<?> screen) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (slot.hasItem() && isRatOrSlug(slot.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isRatOrSlug(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String name = stripFormatting(stack.getHoverName().getString());
        return (name.contains("rat") || name.contains("slug")) && name.contains("[lvl 1]");
    }

    private static int findButtonSlot(AbstractContainerScreen<?> screen, String... keywords) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            String itemName = stack.getHoverName().getString().toLowerCase();
            String itemId = stack.getItem().toString().toLowerCase();

            for (String keyword : keywords) {
                if (itemName.contains(keyword)
                        && (itemId.contains("lime")
                                || itemId.contains("green")
                                || itemId.contains("emerald")
                                || itemId.contains("terracotta"))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void triggerAutomaticSell(Minecraft client, int count) {
        if (AetherConfig.FARM_WHILE_CALLING_GEORGE.get()) {
            ClientUtils.sendMessage("\u00A7eGeorge sell threshold met. Starting prep while farming...", false);
        } else {
            ClientUtils.sendMessage("\u00A7eGeorge sell threshold met. Pausing farming while calling George...", false);
            pauseFarmingForGeorgeIfNeeded();
        }

        isPreparingToSell = true;
        isSelling = false;
        lastGeorgeGuiCloseTime = 0;
        lastGeorgeSequenceActionTime = 0;

        MacroWorkerThread.getInstance().submit("George-TriggerSell", () -> {
            try {
                for (int i = 3; i > 0; i--) {
                    if (!isPreparingToSell
                            || MacroWorkerThread.shouldAbortTask(client, getPrepRequiredState())) {
                        break;
                    }
                    ClientUtils.sendMessage("\u00A7eCalling George in " + i + "s...", true);
                    MacroWorkerThread.sleep(1000);
                }

                if (isPreparingToSell) {
                    isPreparingToSell = false;
                    isSelling = true;
                    interactionStage = 0;
                    interactionTime = System.currentTimeMillis();
                    confirmationCount = 0;
                    lastGeorgeGuiCloseTime = 0;
                    lastGeorgeSequenceActionTime = 0;
                    ClientUtils.sendCommand(client, "/call george");
                    if (AetherConfig.FARM_WHILE_CALLING_GEORGE.get()) {
                        ClientUtils.sendMessage("\u00A7aGeorge called. Continuing farming until GUI opens.", false);
                    } else {
                        ClientUtils.sendMessage("\u00A7aGeorge called. Waiting for the GUI to open.", false);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static boolean isGeorgeScreen(String title) {
        return title.contains("confirm sale")
                || title.contains("offer pets");
    }

    private static boolean isGeorgeInteractionScreen(String title) {
        return isGeorgeScreen(title)
                || title.contains("george")
                || title.contains("sell pets")
                || title.contains("pet collector");
    }

    private static boolean shouldUseGeorgeAutomation() {
        MacroState.State state = MacroStateManager.getCurrentState();
        return AetherConfig.AUTO_GEORGE_SELL.get()
                && (state == MacroState.State.FARMING
                        || state == MacroState.State.GEORGE);
    }

    private static String stripFormatting(String text) {
        return text.replaceAll("(?i)\u00A7.", "").toLowerCase();
    }
}
