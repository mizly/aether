package dev.aether.modules.pest.helpers;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class GardenTimeManager {

    private static final int TIME_MENU_SLOT = 50;
    private static final int DAYTIME_SLOT = 11;
    private static final int NIGHTTIME_SLOT = 13;
    private static final char DAYTIME_MARKER = '\u2600';
    private static final char NIGHTTIME_MARKER = '\u263D';
    private static volatile boolean switchingGardenTime = false;

    private GardenTimeManager() {}

    public static boolean switchToDaytime(Minecraft client) {
        if (isDaytime(client)) {
            ClientUtils.sendDebugMessage("GardenTimeManager: daytime already active, skipping.");
            return true;
        }
        return switchGardenTime(client, DAYTIME_SLOT, "daytime");
    }

    public static boolean switchToNightTime(Minecraft client) {
        if (isNightTime(client)) {
            ClientUtils.sendDebugMessage("GardenTimeManager: night time already active, skipping.");
            return true;
        }
        return switchGardenTime(client, NIGHTTIME_SLOT, "night time");
    }

    public static boolean isDaytime(Minecraft client) {
        return sidebarContains(client, DAYTIME_MARKER);
    }

    public static boolean isNightTime(Minecraft client) {
        return sidebarContains(client, NIGHTTIME_MARKER);
    }

    public static boolean isSwitchingGardenTime() {
        return switchingGardenTime;
    }

    private static boolean sidebarContains(Minecraft client, char marker) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return false;
        }

        for (String line : ClientUtils.getSidebarLines()) {
            if (line.indexOf(marker) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean switchGardenTime(Minecraft client, int timeSlot, String label) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return false;
        }

        ClientUtils.sendDebugMessage("GardenTimeManager: switching garden time to " + label);
        switchingGardenTime = true;
        try {
            ClientUtils.sendCommand("/desk");

            if (!waitForScreenTitle(client, "desk", 5000L)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: desk GUI did not open in time.");
                return false;
            }

            if (!MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true))) {
                return false;
            }

            if (!clickSlot(client, TIME_MENU_SLOT)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: failed to click desk slot " + TIME_MENU_SLOT);
                return false;
            }

            if (!waitForScreenTitle(client, "garden time", 5000L)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: garden time GUI did not open in time.");
                return false;
            }

            if (!MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true))) {
                return false;
            }

            if (!clickSlot(client, timeSlot)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: failed to click time slot " + timeSlot);
                return false;
            }

            if (!MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false))) {
                return false;
            }

            client.execute(() -> {
                if (client.player != null) {
                    client.player.closeContainer();
                }
            });

            return true;
        } finally {
            switchingGardenTime = false;
        }
    }

    private static boolean waitForScreenTitle(Minecraft client, String expectedFragment, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String expected = expectedFragment.toLowerCase();

        while (System.currentTimeMillis() < deadline) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                String title = screen.getTitle().getString().toLowerCase();
                if (title.contains(expected)) {
                    return true;
                }
            }

            if (!MacroWorkerThread.sleep(50)) {
                return false;
            }
        }

        return false;
    }

    private static boolean clickSlot(Minecraft client, int slotId) {
        if (client.isSameThread()) {
            return clickSlotOnClientThread(client, slotId);
        }

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] clicked = { false };
        client.execute(() -> {
            try {
                clicked[0] = clickSlotOnClientThread(client, slotId);
            } finally {
                latch.countDown();
            }
        });

        try {
            return latch.await(1000L, TimeUnit.MILLISECONDS) && clicked[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean clickSlotOnClientThread(Minecraft client, int slotId) {
        if (client.screen instanceof AbstractContainerScreen<?> screen
                && slotId >= 0
                && slotId < screen.getMenu().slots.size()) {
            ClientUtils.performSlotClick(screen, slotId, 0, ContainerInput.PICKUP);
            return true;
        }
        return false;
    }
}
