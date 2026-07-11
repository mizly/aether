package dev.aether.feature;

import com.mojang.authlib.GameProfile;
import dev.aether.config.AetherConfig;
import dev.aether.hud.HudEditScreen;
import dev.aether.hud.HudRegistry;
import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.ReconnectScheduler;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.modules.performance.MuteManager;
import dev.aether.modules.performance.PerformanceModeManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.VacuumParticleDebug;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.modules.visuals.FreecamManager;
import dev.aether.modules.visuals.FreelookManager;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.renderer.AetherBackground;
import dev.aether.renderer.AetherBackgroundScreens;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.AetherConfirmScreen;
import dev.aether.ui.AetherDirectJoinScreen;
import dev.aether.ui.AetherManageServerScreen;
import dev.aether.ui.AetherMultiplayerScreen;
import dev.aether.ui.AetherTitleScreen;
import dev.aether.ui.MainGUI;
import dev.aether.util.BpsTracker;
import dev.aether.util.DelayedBlockBreakTracker;
import dev.aether.util.NickHiderUtils;
import dev.aether.util.PingTracker;
import dev.aether.util.ProgrammaticMovementTracker;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

import java.io.File;

public final class LiveAetherBootstrapHooks implements AetherBootstrapHooks.FeatureHooks {
    @Override
    public void onConfigProfileLoaded(File profileFile) {
        ClientFeatureBootstrap.onConfigProfileLoaded(profileFile);
    }

    @Override
    public void onUnexpectedDisconnect() {
        if (MacroStateManager.isMacroRunning() && !MacroStateManager.isIntentionalDisconnect()) {
            long delay = 30 + (long) (Math.random() * 30);
            ReconnectScheduler.scheduleReconnect(delay, true);
        }
    }

    @Override
    public Screen maybeCreateConfirmScreen(BooleanConsumer callback, Component title, Component message) {
        if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) {
            return null;
        }
        return new AetherConfirmScreen(callback, title, message);
    }

    @Override
    public Screen maybeCreateDirectJoinScreen(Screen lastScreen, BooleanConsumer callback, ServerData serverData) {
        if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) {
            return null;
        }
        return new AetherDirectJoinScreen(lastScreen, callback, serverData);
    }

    @Override
    public Screen maybeCreateMultiplayerScreen(Screen lastScreen) {
        if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) {
            return null;
        }
        return new AetherMultiplayerScreen(lastScreen);
    }

    @Override
    public Screen maybeCreateManageServerScreen(Screen lastScreen, Component title, BooleanConsumer callback, ServerData serverData) {
        if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) {
            return null;
        }
        return new AetherManageServerScreen(lastScreen, title, callback, serverData);
    }

    @Override
    public Screen maybeCreateTitleScreen() {
        if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) {
            return null;
        }
        return new AetherTitleScreen();
    }

    @Override
    public Screen maybeCreateHudEditScreen() {
        return new HudEditScreen();
    }

    @Override
    public void onGameRenderStart(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        RotationManager.update();
        RotationExecutor.update();
    }

    @Override
    public void onGameRenderEnd() {
        HudRegistry.onGuiGraphicsClosed();
    }

    @Override
    public void renderFailsafeColourFlash() {
        FailsafeColourFlashManager.render();
    }

    @Override
    public void onUserInput() {
        FailsafeColourFlashManager.dismiss();
    }

    @Override
    public boolean shouldSuppressVanillaHud(Screen screen) {
        return AetherBootstrapHooks.isBootstrapConfigScreen(screen) || screen instanceof MainGUI || screen instanceof HudEditScreen;
    }

    @Override
    public void renderConfigScreenOverlay(NVGRenderer renderer, float width, float height, float deltaTime) {
        HudRegistry.renderConfigTransition(renderer);
    }

    @Override
    public Component transformOverlayMessage(Component component) {
        FailsafeManager.observeGhostBlockOverlayMessage(component);
        dev.aether.modules.profit.helpers.FarmingXpTracker.onActionBar(component);
        return transformDisplayComponent(component);
    }

    @Override
    public Component transformDisplayComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (!AetherConfig.NICK_HIDER_ENABLED.get() && !AetherConfig.COOP_HIDER_ENABLED.get() && !AetherConfig.HIDE_SERVER_ID.get()) {
            return component;
        }
        Component transformed = NickHiderUtils.transformComponent(component);
        return transformed != null ? transformed : component;
    }

    @Override
    public String transformDisplayString(String text) {
        if (!AetherConfig.NICK_HIDER_ENABLED.get() && !AetherConfig.COOP_HIDER_ENABLED.get() && !AetherConfig.HIDE_SERVER_ID.get()) {
            return text;
        }
        return NickHiderUtils.transformString(text);
    }

    @Override
    public boolean shouldHidePlayerSkin(GameProfile profile) {
        return profile != null
                && AetherConfig.NICK_HIDER_ENABLED.get()
                && AetherConfig.HIDE_SKIN.get()
                && profile.name().equals(Minecraft.getInstance().getUser().getName());
    }

    @Override
    public boolean shouldHideFilteredChatMessage(Component message) {
        if (!AetherConfig.HIDE_FILTERED_CHAT.get() || message == null) {
            return false;
        }
        return message.getString().contains("for killing a");
    }

    @Override
    public boolean isFreecamEnabled() {
        return FreecamManager.isEnabled();
    }

    @Override
    public boolean isFreecamProgrammaticKeyDown(Minecraft client, KeyMapping keyMapping) {
        return FreecamManager.isProgrammaticKeyDown(keyMapping);
    }

    @Override
    public boolean isProgrammaticMovementKeyDown(KeyMapping keyMapping) {
        return ProgrammaticMovementTracker.isDown(keyMapping);
    }

    @Override
    public boolean turnFreecamCamera(double yRot, double xRot) {
        return FreecamManager.turnCamera(yRot, xRot);
    }

    @Override
    public boolean isFreelookActive() {
        return FreelookManager.isActive();
    }

    @Override
    public boolean turnFreelookCamera(double yRot, double xRot) {
        return FreelookManager.turn(yRot, xRot);
    }

    @Override
    public float getFreelookYaw() {
        return FreelookManager.getYaw();
    }

    @Override
    public float getFreelookPitch() {
        return FreelookManager.getPitch();
    }

    @Override
    public boolean shouldCancelMouseTurn() {
        return RotationManager.isRotating() && !FreecamManager.isEnabled() && !FreelookManager.isActive();
    }

    @Override
    public boolean isMouseUngrabbed() {
        return UngrabMouse.isMouseUngrabbed();
    }

    @Override
    public boolean hasCustomScreenBackground(Screen screen) {
        return !StreamerModeManager.isEnabled()
                && AetherConfig.CUSTOM_UI_ENABLED.get()
                && screen != null
                && AetherBackgroundScreens.matches(screen);
    }

    @Override
    public void renderCustomScreenBackground(int width, int height, int mouseX, int mouseY) {
        AetherBackground.INSTANCE.render(width, height, mouseX, mouseY);
    }

    @Override
    public void onBackgroundLeftClick(Minecraft minecraft, Screen screen, double mouseX, double mouseY) {
        if (hasCustomScreenBackground(screen)) {
            AetherBackground.INSTANCE.addRipple((float) mouseX, (float) mouseY);
        }
    }

    @Override
    public void onBlockBreak() {
        BpsTracker.onBlockBreak();
        FailsafeManager.onBlockBreak();
    }

    @Override
    public void onBlockBreak(net.minecraft.core.BlockPos pos) {
        DelayedBlockBreakTracker.onImmediateBlockBreak(pos);
        BpsTracker.onBlockBreak();
        FailsafeManager.onBlockBreak(pos);
    }

    @Override
    public void onBlockBreakClick(Minecraft minecraft, net.minecraft.core.BlockPos pos) {
        DelayedBlockBreakTracker.onBlockBreakClick(minecraft, pos);
    }

    @Override
    public void onBlockChanged(Minecraft minecraft, net.minecraft.core.BlockPos pos,
                               net.minecraft.world.level.block.state.BlockState oldState,
                               net.minecraft.world.level.block.state.BlockState newState) {
        DelayedBlockBreakTracker.onBlockChanged(minecraft, pos, newState);
        FailsafeManager.onBlockChanged(minecraft, pos, oldState, newState);
    }

    @Override
    public void tickFailsafes(Minecraft minecraft) {
        DelayedBlockBreakTracker.tick(minecraft);
        FailsafeManager.tick(minecraft);
    }

    @Override
    public void resetFailsafes() {
        DelayedBlockBreakTracker.reset();
        FailsafeManager.reset();
    }

    @Override
    public void resetFailsafeRuntimeState() {
        DelayedBlockBreakTracker.reset();
        FailsafeManager.resetRuntimeState();
    }

    @Override
    public void addRotationGracePeriod(long durationMs) {
        FailsafeManager.addRotationGracePeriod(durationMs);
    }

    @Override
    public void selectHotbarSlot(Minecraft minecraft, int slot) {
        FailsafeManager.selectHotbarSlot(minecraft, slot);
    }

    @Override
    public boolean isMuted() {
        return MuteManager.isMuted();
    }

    @Override
    public float getMuteVolume() {
        return MuteManager.getVolume();
    }

    @Override
    public boolean areParticlesDisabled() {
        return PerformanceModeManager.isParticlesDisabled();
    }

    @Override
    public void onParticlePacket(Minecraft minecraft, ClientboundLevelParticlesPacket packet) {
        VacuumParticleDebug.onParticlePacket(packet);
        if (packet.getParticle().getType() == ParticleTypes.ANGRY_VILLAGER) {
            PestDestroyer.onFireworkParticle(packet.getX(), packet.getY(), packet.getZ());
        }
    }

    @Override
    public void onStatsPacketReceived() {
        PingTracker.onStatsReceived();
    }
}
