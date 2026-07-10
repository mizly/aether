package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.discord.DiscordStatusManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

public final class FailsafeManager {
    public enum InventorySlotState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum InventoryGuiState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum BpsState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum RotationState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum GhostBlockState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum DirtCheckState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private FailsafeManager() {}

    public static void reset() {
        InventorySlotFailsafe.reset();
        UnexpectedInventoryFailsafe.reset();
        BpsFailsafe.reset();
        GhostBlockFailsafe.reset();
        DirtFailsafe.reset();
        RotationFailsafe.reset();
        FailsafeTestManager.reset();
    }

    public static void resetRuntimeState() {
        InventorySlotFailsafe.reset();
        UnexpectedInventoryFailsafe.reset();
        BpsFailsafe.reset();
        GhostBlockFailsafe.reset();
        DirtFailsafe.reset();
        RotationFailsafe.reset();
    }

    public static void syncSelectedSlotFromClient(Minecraft client) {
        InventorySlotFailsafe.syncSelectedSlotFromClient(client);
    }

    public static void expectSelectedHotbarSlot(int slot) {
        InventorySlotFailsafe.expectSelectedHotbarSlot(slot);
    }

    public static void selectHotbarSlot(Minecraft client, int slot) {
        InventorySlotFailsafe.selectHotbarSlot(client, slot);
    }

    public static void tick(Minecraft client) {
        FailsafeTestManager.tick(client);
        InventorySlotFailsafe.tick(client);
        UnexpectedInventoryFailsafe.tick(client);
        BpsFailsafe.tick(client);
        GhostBlockFailsafe.tick(client);
        DirtFailsafe.tick(client);
        RotationFailsafe.tick(client);
    }

    public static void onBlockBreak() {
        BpsFailsafe.onBlockBreak();
    }

    public static void onBlockBreak(BlockPos pos) {
        BpsFailsafe.onBlockBreak();
        DirtFailsafe.onBlockBreak(pos);
    }

    public static void onBlockChanged(Minecraft client, BlockPos pos, BlockState oldState, BlockState newState) {
        DirtFailsafe.onBlockChanged(client, pos, oldState, newState);
    }

    public static void expectRotation(float yaw, float pitch) {
        RotationFailsafe.expectRotation(yaw, pitch);
    }

    public static void syncExpectedRotationFromClient(Minecraft client) {
        RotationFailsafe.syncExpectedRotationFromClient(client);
    }

    public static void addRotationGracePeriod(long durationMs) {
        RotationFailsafe.addGracePeriod(durationMs);
    }

    public static int getExpectedSelectedSlot() {
        return InventorySlotFailsafe.getExpectedSelectedSlot();
    }

    public static int getCurrentSelectedSlot(Minecraft client) {
        return InventorySlotFailsafe.getCurrentSelectedSlot(client);
    }

    public static InventorySlotState getInventorySlotState(Minecraft client) {
        return switch (InventorySlotFailsafe.getState(client)) {
            case IDLE -> InventorySlotState.IDLE;
            case WAIT -> InventorySlotState.WAIT;
            case TRIGGERED -> InventorySlotState.TRIGGERED;
        };
    }

    public static long getInventorySlotTriggerRemainingMs() {
        return InventorySlotFailsafe.getTriggerRemainingMs();
    }

    public static InventoryGuiState getInventoryGuiState(Minecraft client) {
        return switch (UnexpectedInventoryFailsafe.getState(client)) {
            case IDLE -> InventoryGuiState.IDLE;
            case WAIT -> InventoryGuiState.WAIT;
            case TRIGGERED -> InventoryGuiState.TRIGGERED;
        };
    }

    public static long getInventoryGuiTriggerRemainingMs() {
        return UnexpectedInventoryFailsafe.getTriggerRemainingMs();
    }

    public static BpsState getBpsState(Minecraft client) {
        return switch (BpsFailsafe.getState(client)) {
            case IDLE -> BpsState.IDLE;
            case WAIT -> BpsState.WAIT;
            case TRIGGERED -> BpsState.TRIGGERED;
        };
    }

    public static int getBpsBreakCount() {
        return BpsFailsafe.getBreakCount();
    }

    public static double getBpsWindowSeconds() {
        return BpsFailsafe.getWindowSeconds();
    }

    public static double getCurrentBps() {
        return BpsFailsafe.getCurrentBps();
    }

    public static double getExpectedBps() {
        return AetherConfig.FAILSAFE_BPS_THRESHOLD.get();
    }

    public static long getBpsTriggerRemainingMs() {
        return BpsFailsafe.getTriggerRemainingMs();
    }

    public static GhostBlockState getGhostBlockState(Minecraft client) {
        return switch (GhostBlockFailsafe.getState(client)) {
            case IDLE -> GhostBlockState.IDLE;
            case WAIT -> GhostBlockState.WAIT;
            case TRIGGERED -> GhostBlockState.TRIGGERED;
        };
    }

    public static double getGhostBlockWindowSeconds() {
        return GhostBlockFailsafe.getMissingWindowSeconds();
    }

    public static double getExpectedGhostBlockWindowSeconds() {
        return AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.get();
    }

    public static boolean isGhostBlockFarmingTextVisible(Minecraft client) {
        return GhostBlockFailsafe.isFarmingTextVisible(client);
    }

    public static long getGhostBlockTriggerRemainingMs() {
        return GhostBlockFailsafe.getTriggerRemainingMs();
    }

    public static DirtCheckState getDirtCheckState(Minecraft client) {
        return switch (DirtFailsafe.getState(client)) {
            case IDLE -> DirtCheckState.IDLE;
            case WAIT -> DirtCheckState.WAIT;
            case TRIGGERED -> DirtCheckState.TRIGGERED;
        };
    }

    public static boolean isTouchingDirtBlock(Minecraft client) {
        return DirtFailsafe.isTouchingDirtBlock(client);
    }

    public static int getDirtCheckTrackedBlockCount(Minecraft client) {
        return DirtFailsafe.getTrackedBlockCount(client);
    }

    public static long getDirtCheckTriggerRemainingMs() {
        return DirtFailsafe.getTriggerRemainingMs();
    }

    public static void observeGhostBlockOverlayMessage(Component component) {
        GhostBlockFailsafe.observeOverlayMessage(component);
    }

    public static RotationState getRotationState(Minecraft client) {
        return switch (RotationFailsafe.getState(client)) {
            case IDLE -> RotationState.IDLE;
            case WAIT -> RotationState.WAIT;
            case TRIGGERED -> RotationState.TRIGGERED;
        };
    }

    public static float getExpectedYaw() {
        return RotationFailsafe.getExpectedYaw();
    }

    public static float getExpectedPitch() {
        return RotationFailsafe.getExpectedPitch();
    }

    public static long getRotationTriggerRemainingMs() {
        return RotationFailsafe.getTriggerRemainingMs();
    }

    public static boolean shouldSuppressPestCleanerRotation(Minecraft client) {
        return RotationFailsafe.shouldSuppressPestCleanerRotation(client);
    }

    public static long sampleAdditionalTriggerDelayMs() {
        float maxAdditionalDelaySeconds = AetherConfig.FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS.get();
        if (maxAdditionalDelaySeconds <= 0.0f) {
            return 0L;
        }

        long maxAdditionalDelayMs = Math.round(maxAdditionalDelaySeconds * 1000.0f);
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(maxAdditionalDelayMs + 1L);
    }

    public static void onFailsafeTriggered(FailsafeAction action) {
        FailsafeColourFlashManager.trigger();
        FailsafeSoundManager.playConfiguredSound(action);
        FailsafeWindowFocusManager.bringWindowToFront();
    }

    public static FailsafeAction getInventorySlotChangedAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get());
    }

    public static FailsafeAction getUnexpectedInventoryGuiAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get());
    }

    public static FailsafeAction getBpsAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_BPS_ACTION.get());
    }

    public static FailsafeAction getGhostBlockAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.get());
    }

    public static FailsafeAction getDirtCheckAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.get());
    }

    public static FailsafeAction getRotationAction(boolean pestCleanerRotation) {
        return FailsafeAction.fromConfig(
                pestCleanerRotation
                        ? AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.get()
                        : AetherConfig.FAILSAFE_ROTATION_ACTION.get());
    }

    public static FailsafeAction getWorldChangeAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.get());
    }

    public static boolean shouldStopMacroOnTrigger(FailsafeAction action) {
        return action == FailsafeAction.STOP;
    }

    public static String getNotificationTitle(FailsafeAction action) {
        return shouldStopMacroOnTrigger(action) ? "Failsafe Triggered" : "Failsafe Detected";
    }

    public static void handleConfiguredAction(Minecraft client, FailsafeAction action, String details, String debugReason) {
        handleConfiguredAction(client, action, null, details, debugReason);
    }

    public static void handleConfiguredAction(
            Minecraft client,
            FailsafeAction action,
            FailsafeCustomReplayManager.FailsafeReplayType replayType,
            String details,
            String debugReason
    ) {
        handleConfiguredAction(client, action, replayType, details, debugReason, getDefaultActionDone(action));
    }

    public static void handleConfiguredAction(
            Minecraft client,
            FailsafeAction action,
            FailsafeCustomReplayManager.FailsafeReplayType replayType,
            String details,
            String debugReason,
            String actionDone
    ) {
        onFailsafeTriggered(action);
        if (AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) {
            DesktopNotificationManager.notify(
                    getNotificationTitle(action),
                    (details + " " + actionDone).trim(),
                    shouldStopMacroOnTrigger(action));
        }
        DiscordStatusManager.sendFailsafeAlert(details, actionDone);

        if (shouldStopMacroOnTrigger(action)) {
            ClientUtils.sendMessage("\u00A7cFailsafe triggered: " + details + " Macro stopped.", false);
            MacroStateManager.stopMacro(client, debugReason, false);
            return;
        }

        if (action == FailsafeAction.CUSTOM) {
            ClientUtils.sendMessage("\u00A7eFailsafe triggered: " + details + " " + AetherLang.localize("Running custom replay."),
                    false);
            FailsafeCustomReplayManager.playConfiguredReplay(client, replayType);
            ClientUtils.sendDebugMessage(debugReason + " (custom replay)");
            return;
        }

        ClientUtils.sendMessage("\u00A7eFailsafe detected (ignored): " + details, false);
        ClientUtils.sendDebugMessage(debugReason + " (ignored)");
    }

    private static String getDefaultActionDone(FailsafeAction action) {
        return switch (action) {
            case STOP -> "Macro stopped.";
            case CUSTOM -> "Custom replay started.";
            case IGNORE -> "Ignored.";
        };
    }

    @Deprecated
    public static FailsafeAction getConfiguredAction() {
        return FailsafeAction.fromConfig(AetherConfig.FAILSAFE_ACTION.get());
    }

    @Deprecated
    public static boolean shouldStopMacroOnTrigger() {
        return getConfiguredAction() == FailsafeAction.STOP;
    }

    @Deprecated
    public static void handleConfiguredAction(Minecraft client, String details, String debugReason) {
        handleConfiguredAction(client, getConfiguredAction(), details, debugReason);
    }
}
