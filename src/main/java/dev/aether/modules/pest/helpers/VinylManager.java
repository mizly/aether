package dev.aether.modules.pest.helpers;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class VinylManager {

    private VinylManager() {}

    /**
     * Opens the Stereo Harmony GUI and plays the vinyl matching targetVinyl (item name substring).
     * No-ops if the correct vinyl is already playing. Blocks until done or failed.
     * Returns true if the correct vinyl is playing after this call.
     */
    public static boolean setVinyl(Minecraft client, String targetVinyl) {
        if (client.player == null || targetVinyl == null) return false;

        long guiDelay = ClientUtils.getGuiClickDelayMs(true);

        if (isTargetVinylPlaying(client, targetVinyl)) {
            ClientUtils.sendDebugMessage("VinylManager: '" + targetVinyl + "' already playing, skipping.");
            return true;
        }

        if (!holdVacuum(client)) {
            ClientUtils.sendMessage("§cVacuum not found in hotbar. Cannot set vinyl.");
            return false;
        }

        ClientUtils.sendDebugMessage("VinylManager: holding shift");
        ClientUtils.performShiftLeftClick();
        ClientUtils.sendDebugMessage("VinylManager: left click sent");

        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (isStereoGuiOpen(client)) break;
            MacroWorkerThread.sleep(100);
        }

        // Release shift only after GUI is confirmed open - releasing earlier causes
        // Hypixel to close the container in response to the RELEASE_SHIFT_KEY packet.
        ClientUtils.releaseShiftKey();
        MacroWorkerThread.sleep(guiDelay);
        ClientUtils.sendDebugMessage("VinylManager: shift released");

        if (!isStereoGuiOpen(client)) {
            ClientUtils.sendMessage("§cStereo Harmony GUI did not open.");
            return false;
        }

        ClientUtils.sendDebugMessage("VinylManager: GUI open confirmed");

        MacroWorkerThread.sleep(guiDelay);

        boolean result = handleStereoGui(client, targetVinyl, guiDelay);

        client.execute(() -> {
            if (client.screen != null && client.player != null) {
                client.player.closeContainer();
            }
        });
        MacroWorkerThread.sleep(200);

        return result;
    }

    public static boolean isTargetVinylPlaying(Minecraft client, String targetVinyl) {
        if (client == null || client.player == null || targetVinyl == null) return false;
        return isVinylPlayingFromHotbarLore(client, targetVinyl);
    }

    private static boolean isVinylPlayingFromHotbarLore(Minecraft client, String targetVinyl) {
        ItemStack vacuum = findVacuumStack(client);
        if (vacuum == null) return false;

        String targetLower = targetVinyl.toLowerCase();
        try {
            return vacuum.getTooltipLines(
                            net.minecraft.world.item.Item.TooltipContext.EMPTY,
                            client.player,
                            TooltipFlag.NORMAL)
                    .stream()
                    .map(c -> TablistUtils.stripColors(c.getString()))
                    .map(line -> line.replace('\u00A0', ' ').trim())
                    .filter(line -> line.toLowerCase().startsWith("now playing:"))
                    .anyMatch(line -> line.toLowerCase().contains(targetLower));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean handleStereoGui(Minecraft client, String targetVinyl, long guiDelay) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return false;

        String targetLower = targetVinyl.toLowerCase();

        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) continue;

            ItemStack stack = slot.getItem();
            String name = TablistUtils.stripColors(stack.getHoverName().getString());
            if (!name.toLowerCase().contains(targetLower)) continue;

            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    client.player,
                    TooltipFlag.NORMAL);

            boolean alreadyPlaying = tooltip.stream()
                    .map(c -> TablistUtils.stripColors(c.getString()))
                    .anyMatch(line -> line.contains("PLAYING"));

            if (alreadyPlaying) {
                ClientUtils.sendDebugMessage("VinylManager: '" + targetVinyl + "' already playing, skipping.");
                return true;
            }

            int slotIdx = i;
            client.execute(() -> ClientUtils.performSlotClick(screen, slotIdx, 0, ContainerInput.PICKUP));
            MacroWorkerThread.sleep(guiDelay);
            return true;
        }

        ClientUtils.sendMessage("§cVinyl '" + targetVinyl + "' not found in Stereo Harmony.");
        return false;
    }

    private static boolean isStereoGuiOpen(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return false;
        return TablistUtils.stripColors(screen.getTitle().getString()).toLowerCase().contains("stereo");
    }

    private static boolean holdVacuum(Minecraft client) {
        if (client.player == null) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            if (isVacuumStack(stack)) {
                int slot = i;
                client.execute(() -> {
                    if (client.player != null) FailsafeManager.selectHotbarSlot(client, slot);
                });
                MacroWorkerThread.sleep(150);
                return true;
            }
        }
        return false;
    }

    private static ItemStack findVacuumStack(Minecraft client) {
        if (client.player == null) return null;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            if (isVacuumStack(stack)) return stack;
        }
        return null;
    }

    private static boolean isVacuumStack(ItemStack stack) {
        String name = TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase();
        return name.contains("vacuum");
    }
}
