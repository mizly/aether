package dev.aether.modules.gear.helpers;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class BudgetAutopetManager {
    private BudgetAutopetManager() {
    }

    public static void equipPetByName(Minecraft client, String petName, String triggerLabel) throws InterruptedException {
        if (client == null || client.player == null) {
            return;
        }

        if (petName == null || petName.isBlank()) {
            ClientUtils.sendDebugMessage("BudgetAutopet: skipped " + triggerLabel + " because no pet name is set.");
            return;
        }

        waitForGearMenuCleanup(client, triggerLabel);
        if (MacroWorkerThread.shouldAbortTask(client) || client.player == null) {
            return;
        }

        ClientUtils.sendDebugMessage("BudgetAutopet: opening Pets GUI for " + triggerLabel + " pet '" + petName + "'.");
        ClientUtils.sendCommand(client, "/pets");
        MacroWorkerThread.sleep(500);

        long deadline = System.currentTimeMillis() + 3000;
        AbstractContainerScreen<?> petsScreen = null;
        while (System.currentTimeMillis() < deadline && !MacroWorkerThread.shouldAbortTask(client)) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                String title = screen.getTitle().getString().toLowerCase();
                if (title.contains("pets")) {
                    petsScreen = screen;
                    break;
                }
            }
            MacroWorkerThread.sleep(100);
        }

        if (petsScreen == null) {
            if (!MacroWorkerThread.shouldAbortTask(client)) {
                ClientUtils.sendDebugMessage("BudgetAutopet: could not open Pets GUI.");
            }
            return;
        }

        String needle = petName.toLowerCase();
        int petSlot = -1;
        boolean alreadyEquipped = false;

        for (int i = 0; i < petsScreen.getMenu().slots.size(); i++) {
            Slot slot = petsScreen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString().toLowerCase();
            if (!name.contains(needle)) {
                continue;
            }

            petSlot = i;
            List<Component> lore = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    client.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL);
            for (Component line : lore) {
                if (line.getString().toLowerCase().contains("click to despawn!")) {
                    alreadyEquipped = true;
                    break;
                }
            }
            break;
        }

        if (petSlot == -1) {
            ClientUtils.sendDebugMessage("BudgetAutopet: could not find pet matching '" + petName + "' in Pets GUI.");
            closePetsScreen(client);
            return;
        }

        if (alreadyEquipped) {
            ClientUtils.sendDebugMessage("BudgetAutopet: '" + petName + "' is already equipped for " + triggerLabel + ".");
            closePetsScreen(client);
            return;
        }

        ClientUtils.sendDebugMessage("BudgetAutopet: equipping '" + petName + "' from slot " + petSlot + " for " + triggerLabel + ".");
        final int slotToClick = petSlot;
        final AbstractContainerScreen<?> finalPetsScreen = petsScreen;
        client.execute(() -> ClientUtils.performSlotClick(client, finalPetsScreen, slotToClick, 0, ContainerInput.PICKUP));
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
        closePetsScreen(client);
    }

    private static void closePetsScreen(Minecraft client) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.closeContainer();
            }
        });
    }

    private static void waitForGearMenuCleanup(Minecraft client, String triggerLabel) throws InterruptedException {
        long start = System.currentTimeMillis();
        long deadline = start + 2500L;
        boolean waited = false;

        while (System.currentTimeMillis() < deadline && !MacroWorkerThread.shouldAbortTask(client)) {
            if (!LoadoutManager.isSwappingLoadout
                    && LoadoutManager.loadoutCleanupTicks <= 0
                    && client.screen == null
                    && !hasServerContainerOpen(client)) {
                if (waited) {
                    ClientUtils.sendDebugMessage("BudgetAutopet: gear menu cleanup finished after "
                                    + (System.currentTimeMillis() - start) + "ms for " + triggerLabel + ".");
                }
                return;
            }

            waited = true;
            MacroWorkerThread.sleep(50);
        }

        if (waited && !MacroWorkerThread.shouldAbortTask(client)) {
            ClientUtils.sendDebugMessage("BudgetAutopet: continuing after gear menu cleanup wait timed out for " + triggerLabel + ".");
        }
    }

    private static boolean hasServerContainerOpen(Minecraft client) {
        return client.player != null
                && client.player.containerMenu != null
                && client.player.inventoryMenu != null
                && client.player.containerMenu.containerId != client.player.inventoryMenu.containerId;
    }
}
