package dev.aether.modules.failsafe;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

public final class FailsafeTestManager {
    private static final int COUNTDOWN_TICKS = 100;
    private static final int TICKS_PER_SECOND = 20;

    private static PendingTest pendingTest = null;
    private static InvisibleContainerScreen activeGuiFlashScreen = null;
    private static int activeGuiFlashTicksRemaining = 0;

    private FailsafeTestManager() {
    }

    public static void scheduleRotation(Minecraft client, float pitchDelta, float yawDelta) {
        if (client == null || client.player == null) {
            return;
        }

        pendingTest = new RotationTest(pitchDelta, yawDelta);
        ClientUtils.sendMessage(client, "\u00A7eRotation failsafe test in 5 seconds.", false);
    }

    public static void scheduleGuiFlash(Minecraft client, int durationTicks) {
        if (client == null || client.player == null) {
            return;
        }

        pendingTest = new GuiFlashTest(Math.max(1, durationTicks));
        ClientUtils.sendMessage(client, "\u00A7eGUI failsafe test in 5 seconds.", false);
    }

    public static void scheduleInventorySlot(Minecraft client, int slot) {
        if (client == null || client.player == null) {
            return;
        }

        int zeroBasedSlot = slot - 1;
        if (zeroBasedSlot < 0 || zeroBasedSlot > 8) {
            return;
        }

        pendingTest = new InventorySlotTest(zeroBasedSlot);
        ClientUtils.sendMessage(client, "\u00A7eInventory slot failsafe test in 5 seconds for slot " + slot + ".", false);
    }

    static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == activeGuiFlashScreen) {
            client.setScreen(null);
        }
        pendingTest = null;
        activeGuiFlashTicksRemaining = 0;
        activeGuiFlashScreen = null;
    }

    static void tick(Minecraft client) {
        tickGuiFlash(client);
        tickPendingTest(client);
    }

    private static void tickPendingTest(Minecraft client) {
        if (pendingTest == null) {
            return;
        }

        if (client == null || client.player == null) {
            pendingTest = null;
            return;
        }

        pendingTest.ticksRemaining--;
        int secondsRemaining = Mth.ceil(pendingTest.ticksRemaining / (float) TICKS_PER_SECOND);
        if (secondsRemaining > 0 && secondsRemaining != pendingTest.lastAnnouncedSecond) {
            pendingTest.lastAnnouncedSecond = secondsRemaining;
            ClientUtils.sendMessage(client, "\u00A7eFailsafe test triggering in " + secondsRemaining + "...", false);
        }

        if (pendingTest.ticksRemaining > 0) {
            return;
        }

        PendingTest test = pendingTest;
        pendingTest = null;
        test.trigger(client);
    }

    private static void tickGuiFlash(Minecraft client) {
        if (activeGuiFlashScreen == null) {
            return;
        }

        if (client == null || client.player == null || client.screen != activeGuiFlashScreen) {
            activeGuiFlashScreen = null;
            activeGuiFlashTicksRemaining = 0;
            return;
        }

        activeGuiFlashTicksRemaining--;
        if (activeGuiFlashTicksRemaining <= 0) {
            client.setScreen(null);
            activeGuiFlashScreen = null;
        }
    }

    private static void rotatePlayer(Minecraft client, float pitchDelta, float yawDelta) {
        if (client.player == null) {
            return;
        }

        float yaw = client.player.getYRot() + yawDelta;
        float pitch = Mth.clamp(client.player.getXRot() + pitchDelta, -90.0f, 90.0f);
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        ClientUtils.sendMessage(client,
                String.format(java.util.Locale.US,
                        "\u00A7eApplied rotation test delta pitch %.1f yaw %.1f.", pitchDelta, yawDelta),
                false);
    }

    private static void openGuiFlash(Minecraft client, int durationTicks) {
        if (client.player == null) {
            return;
        }

        Inventory inventory = client.player.getInventory();
        ChestMenu menu = ChestMenu.threeRows(0, inventory, new SimpleContainer(27));
        InvisibleContainerScreen screen = new InvisibleContainerScreen(menu, inventory);
        activeGuiFlashScreen = screen;
        activeGuiFlashTicksRemaining = Math.max(1, durationTicks);
        client.setScreen(screen);
        ClientUtils.sendMessage(client, "\u00A7eOpened invisible GUI test for " + durationTicks + " ticks.", false);
    }

    private static void switchHotbarSlot(Minecraft client, int zeroBasedSlot) {
        if (client.player == null) {
            return;
        }

        ClientUtils.performHotbarSlotClick(client, zeroBasedSlot);
        ClientUtils.sendMessage(client, "\u00A7eSwitched hotbar to slot " + (zeroBasedSlot + 1) + ".", false);
    }

    private abstract static class PendingTest {
        private int ticksRemaining = COUNTDOWN_TICKS;
        private int lastAnnouncedSecond = 0;

        abstract void trigger(Minecraft client);
    }

    private static final class RotationTest extends PendingTest {
        private final float pitchDelta;
        private final float yawDelta;

        private RotationTest(float pitchDelta, float yawDelta) {
            this.pitchDelta = pitchDelta;
            this.yawDelta = yawDelta;
        }

        @Override
        void trigger(Minecraft client) {
            rotatePlayer(client, pitchDelta, yawDelta);
        }
    }

    private static final class GuiFlashTest extends PendingTest {
        private final int durationTicks;

        private GuiFlashTest(int durationTicks) {
            this.durationTicks = durationTicks;
        }

        @Override
        void trigger(Minecraft client) {
            openGuiFlash(client, durationTicks);
        }
    }

    private static final class InventorySlotTest extends PendingTest {
        private final int zeroBasedSlot;

        private InventorySlotTest(int zeroBasedSlot) {
            this.zeroBasedSlot = zeroBasedSlot;
        }

        @Override
        void trigger(Minecraft client) {
            switchHotbarSlot(client, zeroBasedSlot);
        }
    }

    private static final class InvisibleContainerScreen extends ContainerScreen {
        private InvisibleContainerScreen(ChestMenu menu, Inventory inventory) {
            super(menu, inventory, Component.empty());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        public void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        protected void extractLabels(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(null);
        }

        @Override
        public void removed() {
        }
    }
}
