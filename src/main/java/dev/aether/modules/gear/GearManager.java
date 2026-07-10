package dev.aether.modules.gear;

import dev.aether.config.AetherConfig;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.FarmingTools;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ItemLore;

public class GearManager {
    private static volatile int pendingFinalResumeRetries = 0;
    private static final int MAX_FINAL_RESUME_RETRIES = 3;
    private static final int FINAL_RESUME_RETRY_DELAY_MS = 700;

    public static void reset() {
        LoadoutManager.resetState();
        pendingFinalResumeRetries = 0;
    }

    public static void triggerPrepSwap(Minecraft client) {
    }

    public static void triggerLoadoutSwap(Minecraft client, int slot) {
        LoadoutManager.triggerLoadoutSwap(client, slot);
    }

    public static void ensureLoadoutSlot(Minecraft client, int slot) {
        LoadoutManager.ensureLoadoutSlot(client, slot);
    }

    public static void handleLoadoutMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        LoadoutManager.handleLoadoutMenu(client, screen);
    }

    public static void finalResume(Minecraft client) {
        if (PestManager.isCleaningInProgress)
            return;
        if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
            pendingFinalResumeRetries = 0;
            ClientUtils.sendDebugMessage("Final resume deferred because pest exchange has priority.");
            return;
        }

        if (!hasAnyGearSwapTasksEnabled()) {
            pendingFinalResumeRetries = 0;
            client.execute(() -> {
                if (PestManager.isCleaningInProgress)
                    return;
                dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.FARMING);
                GearManager.swapToFarmingTool(client);
                ClientUtils.sendDebugMessage("Finalizing gear swap. Restarting farming macro...");
                dev.aether.macro.FarmingMacroManager.enable(client,
                        dev.aether.macro.FarmingMacroManager.createMacroFromConfig());
            });
            return;
        }

        ClientUtils.waitForGearAndGui();
        swapToFarmingToolSync(client);

        if (!waitForContainerCloseSync(client, 3500)) {
            ClientUtils.sendDebugMessage("§cFinalizing gear swap aborted: container did not close in time. Not restarting script yet.");
            scheduleFinalResumeRetry(client);
            return;
        }

        pendingFinalResumeRetries = 0;

        if (PestManager.isCleaningInProgress)
            return;

        client.execute(() -> {
            if (PestManager.isCleaningInProgress)
                return;
            dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.FARMING);
            GearManager.swapToFarmingTool(client);
            ClientUtils.sendDebugMessage("Finalizing gear swap. Restarting farming macro...");
            dev.aether.macro.FarmingMacroManager.enable(client, dev.aether.macro.FarmingMacroManager.createMacroFromConfig());
        });
    }

    public static boolean hasAnyGearSwapTasksEnabled() {
        return AetherConfig.AUTO_LOADOUT_PEST.get()
                || AetherConfig.AUTO_LOADOUT_VISITOR.get();
    }

    private static void scheduleFinalResumeRetry(Minecraft client) {
        if (pendingFinalResumeRetries >= MAX_FINAL_RESUME_RETRIES) {
            ClientUtils.sendDebugMessage("§cFinalizing gear swap retry limit reached. Manual restart may be required.");
            return;
        }

        pendingFinalResumeRetries++;
        int attempt = pendingFinalResumeRetries;
        ClientUtils.sendDebugMessage("§eRetrying final resume (" + attempt + "/" + MAX_FINAL_RESUME_RETRIES + ")...");

        MacroWorkerThread.getInstance().submit("GearManager-FinalResumeRetry-" + attempt, () -> {
            MacroWorkerThread.sleep(FINAL_RESUME_RETRY_DELAY_MS);
            if (PestManager.isCleaningInProgress) {
                return;
            }
            finalResume(client);
        });
    }

    private static boolean waitForContainerCloseSync(Minecraft client, long timeoutMs) {
        long start = System.currentTimeMillis();
        long lastForceCloseAttempt = 0;

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (client.player == null) {
                return false;
            }

            boolean hasScreenOpen = client.screen != null;
            boolean hasServerContainerOpen = client.player.containerMenu != null
                    && client.player.inventoryMenu != null
                    && client.player.containerMenu.containerId != client.player.inventoryMenu.containerId;

            if (!hasScreenOpen && !hasServerContainerOpen) {
                return true;
            }

            if (hasServerContainerOpen && System.currentTimeMillis() - lastForceCloseAttempt >= 250) {
                int containerId = client.player.containerMenu.containerId;
                client.execute(() -> {
                    if (client.screen != null) {
                        client.screen.onClose();
                    } else {
                        client.setScreen(null);
                    }
                });
                lastForceCloseAttempt = System.currentTimeMillis();
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                return false;
            }
        }

        return false;
    }

    public static int findAspectOfTheVoidSlot(Minecraft client) {
        if (client.player == null)
            return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty()) {
                String name = stack.getHoverName().getString();
                if (name.contains("Aspect of the Void") || name.contains("Aspect of the End") || 
                    name.contains("AOTV") || name.contains("AOTE")) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static int findEtherwarpAspectOfTheVoidHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !isAspectOfTheVoid(stack) || !hasEtherTransmission(stack)) {
                continue;
            }
            return i;
        }

        return -1;
    }

    public static int findFarmingToolSlot(Minecraft client) {
        if (client.player == null)
            return -1;

        // Restore the original farming tool deterministically by taking the left-most
        // farming tool in the hotbar instead of whatever greenhouse may have equipped.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (FarmingTools.isFarmingTool(stack)) {
                return i;
            }
        }
        return -1;
    }

    public static int findHotbarItemSlot(Minecraft client, String nameFragment) {
        if (client.player == null || nameFragment == null || nameFragment.isBlank()) {
            return -1;
        }

        String needle = nameFragment.toLowerCase();

        ItemStack current = client.player.getMainHandItem();
        if (!current.isEmpty()) {
            String name = current.getHoverName().getString().toLowerCase();
            if (name.contains(needle)) {
                return ((AccessorInventory) client.player.getInventory()).getSelected();
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getHoverName().getString().toLowerCase().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    public static void swapToAOTV(Minecraft client) {
        int slot = findAspectOfTheVoidSlot(client);
        if (slot != -1) {
            client.execute(() -> {
                FailsafeManager.selectHotbarSlot(client, slot);
            });
        }
    }

    public static void swapToAOTVSync(Minecraft client) {
        int slot = findAspectOfTheVoidSlot(client);
        if (slot == -1) return;

        client.execute(() -> FailsafeManager.selectHotbarSlot(client, slot));
        
        // Only sleep if we're on a background thread
        if (!client.isSameThread()) {
            try {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 1500) {
                    if (((AccessorInventory) client.player.getInventory()).getSelected() == slot)
                        break;
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {}
        }
    }

    public static boolean swapToEtherwarpAotvSync(Minecraft client) {
        int slot = findEtherwarpAspectOfTheVoidHotbarSlot(client);
        if (slot == -1) {
            return false;
        }

        client.execute(() -> FailsafeManager.selectHotbarSlot(client, slot));
        if (!client.isSameThread()) {
            try {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 1500) {
                    if (((AccessorInventory) client.player.getInventory()).getSelected() == slot) {
                        break;
                    }
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {
            }
        }
        return true;
    }

    public static void swapToFarmingTool(Minecraft client) {
        int slot = findFarmingToolSlot(client);
        if (slot != -1) {
            String name = client.player.getInventory().getItem(slot).getHoverName().getString();
            FailsafeManager.selectHotbarSlot(client, slot);
            ClientUtils.sendMessage("Equipped farming tool: " + name, true);
        } else {
            ClientUtils.sendDebugMessage("\u00A7c[GearManager] No farming tool found in hotbar!");
        }
    }

    public static boolean swapToFarmingToolSync(Minecraft client) {
        int slot = findFarmingToolSlot(client);
        if (slot == -1) {
            ClientUtils.sendDebugMessage("\u00A7c[GearManager] Sync swap failed: No farming tool found in hotbar!");
            return false;
        }

        if (client.isSameThread()) {
            FailsafeManager.selectHotbarSlot(client, slot);
        } else {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, slot));
        }
        
        if (!client.isSameThread()) {
            try {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 1500) {
                    if (((AccessorInventory) client.player.getInventory()).getSelected() == slot)
                        break;
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {}
        }

        return client.player != null && ((AccessorInventory) client.player.getInventory()).getSelected() == slot;
    }

    public static boolean swapToNamedHotbarItemSync(Minecraft client, String nameFragment) {
        int slot = findHotbarItemSlot(client, nameFragment);
        if (slot == -1) {
            ClientUtils.sendDebugMessage("\u00A7c[GearManager] Sync swap failed: No hotbar item found matching '" + nameFragment + "'!");
            return false;
        }

        client.execute(() -> FailsafeManager.selectHotbarSlot(client, slot));

        if (!client.isSameThread()) {
            try {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 1500) {
                    if (((AccessorInventory) client.player.getInventory()).getSelected() == slot)
                        break;
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {}
        }
        return true;
    }

    public static void cleanupTick() {
        Minecraft client = Minecraft.getInstance();
        if (LoadoutManager.loadoutCleanupTicks > 0) {
            LoadoutManager.loadoutCleanupTicks--;
            if (client.player != null) {
                try {
                    if (client.player.containerMenu != null) {
                        client.player.containerMenu.setCarried(ItemStack.EMPTY);
                        client.player.containerMenu.broadcastChanges();
                    }
                    if (client.player.inventoryMenu != null) {
                        client.player.inventoryMenu.setCarried(ItemStack.EMPTY);
                        client.player.inventoryMenu.broadcastChanges();
                    }
                    client.player.closeContainer();
                } catch (Exception ignored) {
                }
            }
            if (client.mouseHandler != null) {
                client.mouseHandler.releaseMouse();
            }
        }
    }

    private static boolean isAspectOfTheVoid(ItemStack stack) {
        String name = stack.getHoverName().getString();
        return name.contains("Aspect of the Void")
                || name.contains("Aspect of the End")
                || name.contains("AOTV")
                || name.contains("AOTE");
    }

    private static boolean hasEtherTransmission(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }

        for (Component line : lore.lines()) {
            if (line.getString().contains("Ether Transmission")) {
                return true;
            }
        }

        return false;
    }
}

