package dev.aether.bootstrap;

import com.mojang.authlib.GameProfile;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.MainGUI;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;

public final class AetherBootstrapHooks {
    public interface FeatureHooks {
        default void onConfigProfileLoaded(File profileFile) {
        }

        default void onUnexpectedDisconnect() {
        }

        default Screen maybeCreateConfirmScreen(BooleanConsumer callback, Component title, Component message) {
            return null;
        }

        default Screen maybeCreateDirectJoinScreen(Screen lastScreen, BooleanConsumer callback, ServerData serverData) {
            return null;
        }

        default Screen maybeCreateMultiplayerScreen(Screen lastScreen) {
            return null;
        }

        default Screen maybeCreateManageServerScreen(Screen lastScreen, Component title, BooleanConsumer callback, ServerData serverData) {
            return null;
        }

        default Screen maybeCreateTitleScreen() {
            return null;
        }

        default Screen maybeCreateHudEditScreen() {
            return null;
        }

        default void onGameRenderStart(Minecraft minecraft) {
        }

        default void onGameRenderEnd() {
        }

        default void renderFailsafeColourFlash() {
        }

        default void onUserInput() {
        }

        default boolean shouldSuppressVanillaHud(Screen screen) {
            return false;
        }

        default void renderConfigScreenOverlay(NVGRenderer renderer, float width, float height, float deltaTime) {
        }

        default Component transformOverlayMessage(Component component) {
            return component;
        }

        default Component transformDisplayComponent(Component component) {
            return component;
        }

        default String transformDisplayString(String text) {
            return text;
        }

        default boolean shouldHidePlayerSkin(GameProfile profile) {
            return false;
        }

        default boolean shouldHideFilteredChatMessage(Component message) {
            return false;
        }

        default boolean isFreecamEnabled() {
            return false;
        }

        default boolean isFreecamProgrammaticKeyDown(Minecraft client, KeyMapping keyMapping) {
            return false;
        }

        default boolean isProgrammaticMovementKeyDown(KeyMapping keyMapping) {
            return false;
        }

        default boolean turnFreecamCamera(double yRot, double xRot) {
            return false;
        }

        default boolean isFreelookActive() {
            return false;
        }

        default boolean turnFreelookCamera(double yRot, double xRot) {
            return false;
        }

        default float getFreelookYaw() {
            return 0.0f;
        }

        default float getFreelookPitch() {
            return 0.0f;
        }

        default boolean shouldCancelMouseTurn() {
            return false;
        }

        default boolean isMouseUngrabbed() {
            return false;
        }

        default boolean hasCustomScreenBackground(Screen screen) {
            return false;
        }

        default void renderCustomScreenBackground(int width, int height, int mouseX, int mouseY) {
        }

        default void onBackgroundLeftClick(Minecraft minecraft, Screen screen, double mouseX, double mouseY) {
        }

        default void onBlockBreak() {
        }

        default void onBlockBreak(BlockPos pos) {
            onBlockBreak();
        }

        default void onBlockBreakClick(Minecraft minecraft, BlockPos pos) {
        }

        default void onBlockChanged(Minecraft minecraft, BlockPos pos, BlockState oldState, BlockState newState) {
        }

        default void tickFailsafes(Minecraft minecraft) {
        }

        default void resetFailsafes() {
        }

        default void resetFailsafeRuntimeState() {
        }

        default void addRotationGracePeriod(long durationMs) {
        }

        default void selectHotbarSlot(Minecraft minecraft, int slot) {
        }

        default boolean isMuted() {
            return false;
        }

        default float getMuteVolume() {
            return 0.0f;
        }

        default boolean areParticlesDisabled() {
            return false;
        }

        default void onParticlePacket(Minecraft minecraft, ClientboundLevelParticlesPacket packet) {
        }

        default void onStatsPacketReceived() {
        }
    }

    private static final ThreadLocal<Integer> DISPLAY_TRANSFORM_SUSPEND_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final FeatureHooks NOOP = new FeatureHooks() {
    };

    private static volatile FeatureHooks hooks = NOOP;

    private AetherBootstrapHooks() {
    }

    public static void install(FeatureHooks featureHooks) {
        hooks = featureHooks == null ? NOOP : featureHooks;
    }

    public static void reset() {
        hooks = NOOP;
    }

    public static void onUnexpectedDisconnect() {
        hooks.onUnexpectedDisconnect();
    }

    public static void onConfigProfileLoaded(File profileFile) {
        hooks.onConfigProfileLoaded(profileFile);
    }

    public static Screen maybeCreateConfirmScreen(BooleanConsumer callback, Component title, Component message) {
        return hooks.maybeCreateConfirmScreen(callback, title, message);
    }

    public static Screen maybeCreateDirectJoinScreen(Screen lastScreen, BooleanConsumer callback, ServerData serverData) {
        return hooks.maybeCreateDirectJoinScreen(lastScreen, callback, serverData);
    }

    public static Screen maybeCreateMultiplayerScreen(Screen lastScreen) {
        return hooks.maybeCreateMultiplayerScreen(lastScreen);
    }

    public static Screen maybeCreateManageServerScreen(Screen lastScreen, Component title, BooleanConsumer callback, ServerData serverData) {
        return hooks.maybeCreateManageServerScreen(lastScreen, title, callback, serverData);
    }

    public static Screen maybeCreateTitleScreen() {
        return hooks.maybeCreateTitleScreen();
    }

    public static Screen maybeCreateHudEditScreen() {
        return hooks.maybeCreateHudEditScreen();
    }

    public static void onGameRenderStart(Minecraft minecraft) {
        hooks.onGameRenderStart(minecraft);
    }

    public static void onGameRenderEnd() {
        hooks.onGameRenderEnd();
    }

    public static void renderFailsafeColourFlash() {
        hooks.renderFailsafeColourFlash();
    }

    public static void onUserInput() {
        hooks.onUserInput();
    }

    public static boolean shouldSuppressVanillaHud(Screen screen) {
        return isBootstrapConfigScreen(screen) || hooks.shouldSuppressVanillaHud(screen);
    }

    public static boolean isBootstrapConfigScreen(Screen screen) {
        return screen instanceof MainGUI;
    }

    public static void renderConfigScreenOverlay(NVGRenderer renderer, float width, float height, float deltaTime) {
        hooks.renderConfigScreenOverlay(renderer, width, height, deltaTime);
    }

    public static Component transformOverlayMessage(Component component) {
        return hooks.transformOverlayMessage(component);
    }

    public static Component transformDisplayComponent(Component component) {
        if (areDisplayTransformsSuspended()) {
            return component;
        }
        return hooks.transformDisplayComponent(component);
    }

    public static String transformDisplayString(String text) {
        if (areDisplayTransformsSuspended()) {
            return text;
        }
        return hooks.transformDisplayString(text);
    }

    public static DisplayTransformScope suspendDisplayTransforms() {
        DISPLAY_TRANSFORM_SUSPEND_DEPTH.set(DISPLAY_TRANSFORM_SUSPEND_DEPTH.get() + 1);
        return new DisplayTransformScope();
    }

    public static boolean areDisplayTransformsSuspended() {
        return DISPLAY_TRANSFORM_SUSPEND_DEPTH.get() > 0;
    }

    private static void resumeDisplayTransforms() {
        int depth = DISPLAY_TRANSFORM_SUSPEND_DEPTH.get();
        if (depth <= 1) {
            DISPLAY_TRANSFORM_SUSPEND_DEPTH.remove();
        } else {
            DISPLAY_TRANSFORM_SUSPEND_DEPTH.set(depth - 1);
        }
    }

    public static final class DisplayTransformScope implements AutoCloseable {
        private boolean closed;

        private DisplayTransformScope() {
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            resumeDisplayTransforms();
        }
    }

    public static boolean shouldHidePlayerSkin(GameProfile profile) {
        return hooks.shouldHidePlayerSkin(profile);
    }

    public static boolean shouldHideFilteredChatMessage(Component message) {
        return hooks.shouldHideFilteredChatMessage(message);
    }

    public static boolean isFreecamEnabled() {
        return hooks.isFreecamEnabled();
    }

    public static boolean isFreecamProgrammaticKeyDown(Minecraft client, KeyMapping keyMapping) {
        return hooks.isFreecamProgrammaticKeyDown(client, keyMapping);
    }

    public static boolean isProgrammaticMovementKeyDown(KeyMapping keyMapping) {
        return hooks.isProgrammaticMovementKeyDown(keyMapping);
    }

    public static boolean turnFreecamCamera(double yRot, double xRot) {
        return hooks.turnFreecamCamera(yRot, xRot);
    }

    public static boolean isFreelookActive() {
        return hooks.isFreelookActive();
    }

    public static boolean turnFreelookCamera(double yRot, double xRot) {
        return hooks.turnFreelookCamera(yRot, xRot);
    }

    public static float getFreelookYaw() {
        return hooks.getFreelookYaw();
    }

    public static float getFreelookPitch() {
        return hooks.getFreelookPitch();
    }

    public static boolean shouldCancelMouseTurn() {
        return hooks.shouldCancelMouseTurn();
    }

    public static boolean isMouseUngrabbed() {
        return hooks.isMouseUngrabbed();
    }

    public static boolean hasCustomScreenBackground(Screen screen) {
        return hooks.hasCustomScreenBackground(screen);
    }

    public static void renderCustomScreenBackground(int width, int height, int mouseX, int mouseY) {
        hooks.renderCustomScreenBackground(width, height, mouseX, mouseY);
    }

    public static void onBackgroundLeftClick(Minecraft minecraft, Screen screen, double mouseX, double mouseY) {
        hooks.onBackgroundLeftClick(minecraft, screen, mouseX, mouseY);
    }

    public static void onBlockBreak() {
        hooks.onBlockBreak();
    }

    public static void onBlockBreak(BlockPos pos) {
        hooks.onBlockBreak(pos);
    }

    public static void onBlockBreakClick(Minecraft minecraft, BlockPos pos) {
        hooks.onBlockBreakClick(minecraft, pos);
    }

    public static void onBlockChanged(Minecraft minecraft, BlockPos pos, BlockState oldState, BlockState newState) {
        hooks.onBlockChanged(minecraft, pos, oldState, newState);
    }

    public static void tickFailsafes(Minecraft minecraft) {
        hooks.tickFailsafes(minecraft);
    }

    public static void resetFailsafes() {
        hooks.resetFailsafes();
    }

    public static void resetFailsafeRuntimeState() {
        hooks.resetFailsafeRuntimeState();
    }

    public static void addRotationGracePeriod(long durationMs) {
        hooks.addRotationGracePeriod(durationMs);
    }

    public static void selectHotbarSlot(Minecraft minecraft, int slot) {
        hooks.selectHotbarSlot(minecraft, slot);
    }

    public static boolean isMuted() {
        return hooks.isMuted();
    }

    public static float getMuteVolume() {
        return hooks.getMuteVolume();
    }

    public static boolean areParticlesDisabled() {
        return hooks.areParticlesDisabled();
    }

    public static void onParticlePacket(Minecraft minecraft, ClientboundLevelParticlesPacket packet) {
        hooks.onParticlePacket(minecraft, packet);
    }

    public static void onStatsPacketReceived() {
        hooks.onStatsPacketReceived();
    }
}
