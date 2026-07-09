package dev.aether.modules.inventorymanager;

import dev.aether.config.AetherConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class BoosterCookieManager {
    public static volatile long interactionTime = 0;
    public static volatile int interactionStage = 0;

    public static void handleBoosterCookieMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!AetherConfig.AUTOSELL_PASSIVE.get() || screen == null || client.player == null)
            return;

        String title = screen.getTitle().getString();
        String lowerTitle = title.toLowerCase();

        if (!lowerTitle.equals("booster cookie") && !lowerTitle.equals("trades"))
            return;

        if (interactionTime == 0L) {
            interactionTime = System.currentTimeMillis();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - interactionTime < dev.aether.util.ClientUtils.getGuiClickDelayMs(interactionStage == 0))
            return;

        // Stage 0: Search inventory and click matching items
        if (interactionStage == 0) {
            int foundSlotIdx = -1;

            int totalSlots = screen.getMenu().slots.size();
            int inventoryStart = totalSlots - 36;

            for (int i = inventoryStart; i < totalSlots; i++) {
                Slot slot = screen.getMenu().slots.get(i);
                if (!slot.hasItem())
                    continue;

                ItemStack stack = slot.getItem();
                String name = stack.getHoverName().getString().replaceAll("(?i)\\u00A7.", "").toLowerCase();

                for (String target : AetherConfig.AUTO_SELL_ITEMS.get()) {
                    if (target.isBlank()) continue;
                    if (name.contains(target.toLowerCase())) {
                        foundSlotIdx = i;
                        break;
                    }
                }
                if (foundSlotIdx != -1)
                    break;
            }

            if (foundSlotIdx != -1) {
                dev.aether.util.ClientUtils.performSlotClick(client, screen, foundSlotIdx, 0, ContainerInput.QUICK_MOVE);
                interactionTime = now;
            }
        }
    }
}
