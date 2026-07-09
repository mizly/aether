package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.ComposterManager;
import dev.aether.modules.SupercraftManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.inventorymanager.BookCombineManager;
import dev.aether.modules.inventorymanager.GeorgeManager;
import dev.aether.modules.pest.helpers.PestExchangeManager;
import dev.aether.modules.pest.helpers.PestTrapManager;
import dev.aether.modules.visitor.VisitorsMacro;
import dev.aether.notification.NotificationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

final class UnexpectedInventoryFailsafe {
    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static volatile long inventoryOpenSince = 0L;
    private static volatile long inventoryOpenRandomDelayMs = 0L;
    private static volatile boolean triggered = false;

    private UnexpectedInventoryFailsafe() {
    }

    static void reset() {
        inventoryOpenSince = 0L;
        inventoryOpenRandomDelayMs = 0L;
        triggered = false;
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        if (!AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()) {
            inventoryOpenSince = 0L;
            inventoryOpenRandomDelayMs = 0L;
            triggered = false;
            return;
        }

        MacroState.State state = MacroStateManager.getCurrentState();
        boolean shouldMonitor = state == MacroState.State.FARMING || state == MacroState.State.CLEANING;
        if (!shouldMonitor) {
            inventoryOpenSince = 0L;
            inventoryOpenRandomDelayMs = 0L;
            triggered = false;
            return;
        }

        if (!ClientUtils.isInventoryScreenOpen(client)) {
            inventoryOpenSince = 0L;
            inventoryOpenRandomDelayMs = 0L;
            return;
        }

        if (isExpectedInventoryGuiOpen()) {
            inventoryOpenSince = 0L;
            inventoryOpenRandomDelayMs = 0L;
            triggered = false;
            return;
        }

        long now = System.currentTimeMillis();
        if (inventoryOpenSince == 0L) {
            inventoryOpenSince = now;
            inventoryOpenRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            return;
        }

        long triggerDelayMs = Math.round(AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() * 1000.0f)
                + inventoryOpenRandomDelayMs;
        if (now - inventoryOpenSince < triggerDelayMs) {
            return;
        }

        trigger(client, state);
    }

    static State getState(Minecraft client) {
        if (triggered) {
            return State.TRIGGERED;
        }
        if (client == null || client.player == null || !AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()) {
            return State.IDLE;
        }

        MacroState.State state = MacroStateManager.getCurrentState();
        if ((state != MacroState.State.FARMING && state != MacroState.State.CLEANING)
                || !ClientUtils.isInventoryScreenOpen(client)
                || isExpectedInventoryGuiOpen()) {
            return State.IDLE;
        }

        return inventoryOpenSince == 0L ? State.IDLE : State.WAIT;
    }

    static long getTriggerRemainingMs() {
        if (inventoryOpenSince == 0L) {
            return 0L;
        }

        long triggerDelayMs = Math.round(AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() * 1000.0f)
                + inventoryOpenRandomDelayMs;
        long elapsedMs = System.currentTimeMillis() - inventoryOpenSince;
        return Math.max(0L, triggerDelayMs - elapsedMs);
    }

    private static void trigger(Minecraft client, MacroState.State state) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getUnexpectedInventoryGuiAction();
        triggered = true;
        NotificationManager.error(
                FailsafeManager.getNotificationTitle(action),
                "Unexpected inventory GUI detected.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.GUI_OPENED,
                "unexpected inventory GUI detected during " + state.name().toLowerCase(java.util.Locale.ROOT) + ".",
                "UnexpectedInventoryFailsafe: unexpected inventory GUI detected during " + state.name());
        reset();
    }

    private static boolean isExpectedInventoryGuiOpen() {
        return LoadoutManager.isSwappingLoadout
                || PestExchangeManager.isExchanging
                || PestTrapManager.isRunning
                || ComposterManager.isRunning()
                || SupercraftManager.isRunning()
                || BookCombineManager.isPreparingToCombine
                || BookCombineManager.isCombining
                || isPestTrapGuiOpen()
                || VisitorsMacro.isRunning
                || GeorgeManager.isPreparingToSell
                || GeorgeManager.isSelling;
    }

    private static boolean isPestTrapGuiOpen() {
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }

        String title = screen.getTitle().getString().toLowerCase(java.util.Locale.ROOT);
        return title.contains("trap");
    }
}
