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

public final class VinylManager {

    private VinylManager() {}

    // no-ops when the right vinyl is already playing; blocks until done or failed
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
        PestClientThread.run(client, ClientUtils::performShiftLeftClick);
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
        if (!client.isSameThread()) {
            return PestClientThread.call(client, () -> isTargetVinylPlaying(client, targetVinyl), false);
        }
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
        StereoSelection selection = PestClientThread.call(
                client, () -> inspectStereoGui(client, targetVinyl), StereoSelection.NOT_FOUND);
        if (selection.alreadyPlaying()) {
            ClientUtils.sendDebugMessage("VinylManager: '" + targetVinyl + "' already playing, skipping.");
            return true;
        }
        if (selection.slot() < 0) {
            ClientUtils.sendMessage("\u00A7cVinyl '" + targetVinyl + "' not found in Stereo Harmony.");
            return false;
        }
        PestClientThread.run(client, () -> {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                ClientUtils.performSlotClick(screen, selection.slot(), 0, ContainerInput.PICKUP);
            }
        });
        MacroWorkerThread.sleep(guiDelay);
        return true;
    }

    private static boolean isStereoGuiOpen(Minecraft client) {
        if (!client.isSameThread()) {
            return PestClientThread.call(client, () -> isStereoGuiOpen(client), false);
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return false;
        return TablistUtils.stripColors(screen.getTitle().getString()).toLowerCase().contains("stereo");
    }

    private static boolean holdVacuum(Minecraft client) {
        if (client.player == null) return false;
        int slot = PestClientThread.call(client, () -> findVacuumSlot(client), -1);
        if (slot < 0) {
            return false;
        }
        PestClientThread.run(client, () -> {
            if (client.player != null) {
                FailsafeManager.selectHotbarSlot(client, slot);
            }
        });
        MacroWorkerThread.sleep(150);
        return true;
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

    private static int findVacuumSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty() && isVacuumStack(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static StereoSelection inspectStereoGui(Minecraft client, String targetVinyl) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return StereoSelection.NOT_FOUND;
        }
        String targetLower = targetVinyl.toLowerCase();
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            String name = TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase();
            if (!name.contains(targetLower)) {
                continue;
            }
            boolean playing = stack.getTooltipLines(
                            net.minecraft.world.item.Item.TooltipContext.EMPTY,
                            client.player,
                            TooltipFlag.NORMAL)
                    .stream()
                    .map(Component::getString)
                    .map(TablistUtils::stripColors)
                    .anyMatch(line -> line.contains("PLAYING"));
            return new StereoSelection(i, playing);
        }
        return StereoSelection.NOT_FOUND;
    }

    private static boolean isVacuumStack(ItemStack stack) {
        String name = TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase();
        return name.contains("vacuum");
    }

    private record StereoSelection(int slot, boolean alreadyPlaying) {
        private static final StereoSelection NOT_FOUND = new StereoSelection(-1, false);
    }
}
