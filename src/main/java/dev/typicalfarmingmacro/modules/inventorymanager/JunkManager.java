package dev.typicalfarmingmacro.modules.inventorymanager;

import dev.typicalfarmingmacro.config.ConfigHelpers;
import dev.typicalfarmingmacro.config.TfmConfig;

import dev.typicalfarmingmacro.macro.MacroState;
import dev.typicalfarmingmacro.macro.MacroStateManager;
import dev.typicalfarmingmacro.macro.MacroWorkerThread;
import dev.typicalfarmingmacro.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import dev.typicalfarmingmacro.modules.gear.GearManager;
import dev.typicalfarmingmacro.modules.gear.helpers.LoadoutManager;
import dev.typicalfarmingmacro.modules.pest.PestManager;
import dev.typicalfarmingmacro.modules.pest.helpers.PestPrepSwapManager;
import dev.typicalfarmingmacro.modules.visitor.VisitorManager;

public class JunkManager {
    public static volatile boolean isDropping = false;
    public static volatile boolean isPreparingToDrop = false;
    public static volatile long interactionTime = 0;
    public static volatile long noJunkStartTime = 0;
    private static long lastDropTime = 0;
    private static final long DROP_COOLDOWN_MS = 30000;

    public static void reset() {
        isDropping = false;
        isPreparingToDrop = false;
        interactionTime = 0;
        noJunkStartTime = 0;
        lastDropTime = 0;
    }

    private static boolean isPriorityEventActive(Minecraft client) {
        return MacroStateManager.getCurrentState() != MacroState.State.FARMING ||
                PestManager.isCleaningInProgress ||
                PestPrepSwapManager.prepSwappedForCurrentPestCycle ||
                (TfmConfig.AUTO_VISITOR.get() && VisitorManager.getVisitorCount(client) >= TfmConfig.VISITOR_THRESHOLD.get()) ||
                BookCombineManager.isCombining ||
                BookCombineManager.isPreparingToCombine ||
                GeorgeManager.isSelling ||
                GeorgeManager.isPreparingToSell ||
                AutoSellManager.isSelling ||
                AutoSellManager.isPreparingToSell;
    }

    public static void update(Minecraft client) {
        if (!TfmConfig.AUTO_DROP_JUNK.get() || client.player == null)
            return;

        if (System.currentTimeMillis() - lastDropTime < DROP_COOLDOWN_MS)
            return;

        if (isPreparingToDrop) {
            if (isPriorityEventActive(client)) {
                isPreparingToDrop = false;
                lastDropTime = System.currentTimeMillis();
                dev.typicalfarmingmacro.util.ClientUtils.sendMessage(client, "\u00A7cAborting Junk Drop prep due to priority event.", false);
            }
            return;
        }

        if (isDropping) {
            if (MacroStateManager.getCurrentState() != MacroState.State.DROPPING_JUNK ||
                    PestManager.isCleaningInProgress || PestPrepSwapManager.prepSwappedForCurrentPestCycle) {
                isDropping = false;
                if (MacroStateManager.getCurrentState() == MacroState.State.DROPPING_JUNK) {
                    MacroStateManager.setCurrentState(MacroState.State.FARMING);
                }
                dev.typicalfarmingmacro.util.ClientUtils.sendMessage(client, "\u00A7cAborting Junk Drop due to priority event.", false);
                lastDropTime = System.currentTimeMillis();
                return;
            }

            // If screen closed unexpectedly, we might need to re-open or finish
            if (client.screen == null && System.currentTimeMillis() - interactionTime > 1500) {
                if (countJunkItems(client) > 0) {
                    interactionTime = System.currentTimeMillis();
                    noJunkStartTime = 0;
                    client.execute(() -> client.setScreen(new InventoryScreen(client.player)));
                } else {
                    if (noJunkStartTime == 0) {
                        noJunkStartTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - noJunkStartTime >= 2000) {
                        finishDropping(client);
                        noJunkStartTime = 0;
                    }
                }
            }
            return;
        }

        if (isPriorityEventActive(client) ||
                LoadoutManager.isSwappingLoadout)
            return;

        int junkCount = countJunkItems(client);
        if (junkCount >= TfmConfig.JUNK_THRESHOLD.get()) {
            triggerAutomaticDrop(client, junkCount);
        }
    }

    public static int countJunkItems(Minecraft client) {
        if (client.player == null)
            return 0;
        List<String> junk = TfmConfig.JUNK_ITEMS.get();
        if (junk.isEmpty())
            return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (isJunkItem(stack, junk)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isFarmingTool(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        String[] keywords = { "hoe", "dicer", "knife", "chopper", "cutter", "axe" };
        String name = stack.getHoverName().getString().toLowerCase();
        for (String kw : keywords) {
            if (name.contains(kw))
                return true;
        }
        return false;
    }

    private static boolean isJunkItem(ItemStack stack, List<String> junkList) {
        if (stack == null || stack.isEmpty())
            return false;

        // Exclude farming tools - they can be enchanted with junk items
        if (isFarmingTool(stack))
            return false;

        // Check Display Name
        String name = stack.getHoverName().getString().replaceAll("(?i)\\u00A7.", "");
        for (String j : junkList) {
            if (j.isBlank()) continue;
            if (name.contains(j))
                return true;
        }

        // Check Lore
        net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) {
            for (net.minecraft.network.chat.Component line : lore.lines()) {
                String lineText = line.getString().replaceAll("(?i)\\u00A7.", "");
                for (String j : junkList) {
                    if (j.isBlank()) continue;
                    if (lineText.contains(j))
                        return true;
                }
            }
        }

        return false;
    }

    private static void triggerAutomaticDrop(Minecraft client, int count) {
        dev.typicalfarmingmacro.util.ClientUtils.sendMessage(client, "\u00A7eJunk detected (" + count + " items), preparing to drop...", false);
        ClientUtils.forceReleaseKeys(client);
        isPreparingToDrop = true;
        isDropping = false;

        MacroWorkerThread.getInstance().submit("JunkDrop-Trigger", () -> {
            try {
                MacroWorkerThread.sleep(400); // Stabilization delay
                
                if (!isPreparingToDrop)
                    return;

                ClientUtils.sendDebugMessage(client, "Disabling farming macro: Preparing to drop junk");
                client.execute(() -> dev.typicalfarmingmacro.macro.FarmingMacroManager.disable(client));
                MacroWorkerThread.sleep(400); // Small safety delay after stop

                // /setspawn before warping
                dev.typicalfarmingmacro.util.CommandUtils.setSpawn(client);

                // /plottp
                dev.typicalfarmingmacro.util.CommandUtils.plotTp(client, TfmConfig.DROP_JUNK_PLOT_TP.get());
                MacroWorkerThread.sleep(250);

                if (!isPreparingToDrop)
                    return;

                isPreparingToDrop = false;
                MacroStateManager.setCurrentState(MacroState.State.DROPPING_JUNK);
                isDropping = true;
                interactionTime = System.currentTimeMillis();
                noJunkStartTime = 0;

                // Open inventory
                client.execute(() -> client.setScreen(new InventoryScreen(client.player)));

            } catch (Exception e) {
                e.printStackTrace();
                isPreparingToDrop = false;
                isDropping = false;
            }
        });
    }

    public static void handleInventoryMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!isDropping)
            return;
        if (!(screen instanceof InventoryScreen))
            return;

        long now = System.currentTimeMillis();
        if (now - interactionTime < ConfigHelpers.getRandomizedDelay(
                TfmConfig.JUNK_ITEM_DROP_DELAY_MIN.get(),
                TfmConfig.JUNK_ITEM_DROP_DELAY_MAX.get()))
            return;

        int junkSlot = -1;
        List<String> junkList = TfmConfig.JUNK_ITEMS.get();

        // Scan the Slots in the container.
        // In the survival inventory GUI:
        // 0-8: Crafting/Armor (ignored)
        // 9-35: Main inventory
        // 36-44: Hotbar
        for (int i = 9; i <= 44; i++) {
            if (i >= screen.getMenu().slots.size())
                break;
            Slot slot = screen.getMenu().slots.get(i);
            if (isJunkItem(slot.getItem(), junkList)) {
                junkSlot = i;
                break;
            }
        }

        if (junkSlot != -1) {
            ClientUtils.performSlotClick(client, screen, junkSlot, 1, ContainerInput.THROW);
            interactionTime = now;
            noJunkStartTime = 0;
        } else {
            // No more junk
            if (noJunkStartTime == 0) {
                noJunkStartTime = now;
            } else if (now - noJunkStartTime >= 2000) {
                finishDropping(client);
                noJunkStartTime = 0;
            }
        }
    }

    private static void finishDropping(Minecraft client) {
        if (client.player != null && client.screen != null) {
            client.player.closeContainer();
        }
        isDropping = false;
        lastDropTime = System.currentTimeMillis();
        if (MacroStateManager.getCurrentState() == MacroState.State.DROPPING_JUNK) {
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
        }
        dev.typicalfarmingmacro.util.ClientUtils.sendMessage(client, "\u00A7aJunk drop finished. Resuming script...", true);

        MacroWorkerThread.getInstance().submit("JunkDrop-Finish", () -> {
            try {
                dev.typicalfarmingmacro.util.CommandUtils.warpGarden(client);
                MacroWorkerThread.sleep(250);

                ClientUtils.waitForGearAndGui(client);
                if (MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
                    client.execute(() -> {
                        GearManager.swapToFarmingTool(client);
                        ClientUtils.sendDebugMessage(client, "Restarting farming macro after junk drop");
                        dev.typicalfarmingmacro.macro.FarmingMacroManager.enable(client, dev.typicalfarmingmacro.macro.FarmingMacroManager.createMacroFromConfig());
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }
}

