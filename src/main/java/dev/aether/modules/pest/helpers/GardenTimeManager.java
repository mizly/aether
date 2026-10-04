package dev.aether.modules.pest.helpers;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class GardenTimeManager {

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
        if (!client.isSameThread()) {
            return PestClientThread.call(client, () -> sidebarContains(client, marker), false);
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
            ClientUtils.sendCommand("/islandtime");

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

            // Previously this returned right after scheduling the close, without confirming
            // it actually happened - harmless for callers that do nothing else afterward, but
            // a caller that opens a second GUI immediately (e.g. a loadout swap right after
            // this) could have that request race the server still processing this container's
            // close, which can cut the switch off before it fully registers. Confirming the
            // screen is actually gone (or timing out) guarantees this method doesn't return
            // until the server's had a real chance to process both the click and the close.
            if (!waitForContainerClosed(client, 2000L)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: garden time GUI did not close in time.");
            }

            return true;
        } finally {
            switchingGardenTime = false;
        }
    }

    private static boolean waitForContainerClosed(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            boolean closed = PestClientThread.call(client,
                    () -> !(client.screen instanceof AbstractContainerScreen<?>),
                    true);
            if (closed) {
                return true;
            }

            if (!MacroWorkerThread.sleep(50)) {
                return false;
            }
        }

        return false;
    }

    private static boolean waitForScreenTitle(Minecraft client, String expectedFragment, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String expected = expectedFragment.toLowerCase();

        while (System.currentTimeMillis() < deadline) {
            boolean open = PestClientThread.call(client,
                    () -> client.screen instanceof AbstractContainerScreen<?> screen
                            && screen.getTitle().getString().toLowerCase().contains(expected),
                    false);
            if (open) {
                return true;
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
