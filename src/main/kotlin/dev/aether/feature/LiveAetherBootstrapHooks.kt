package dev.aether.feature

import com.mojang.authlib.GameProfile
import dev.aether.Aether
import dev.aether.config.AetherConfig
import dev.aether.hud.HudEditScreen
import dev.aether.hud.HudRegistry
import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.macro.MacroStateManager
import dev.aether.macro.ReconnectScheduler
import dev.aether.modules.failsafe.FailsafeColourFlashManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.farming.UngrabMouse
import dev.aether.modules.pathfinding.rotation.RotationExecutor
import dev.aether.modules.performance.MuteManager
import dev.aether.modules.performance.PerformanceModeManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.VacuumParticleDebug
import dev.aether.modules.pest.helpers.PestTrackerAbility
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.visuals.FreecamManager
import dev.aether.modules.visuals.FreelookManager
import dev.aether.modules.visuals.PestEspManager
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.renderer.AetherBackground
import dev.aether.renderer.AetherBackgroundScreens
import dev.aether.renderer.NVGRenderer
import dev.aether.telemetry.ban.AetherBanService
import dev.aether.telemetry.ban.BanDisconnectContext
import dev.aether.telemetry.ban.PlaySessionTracker
import dev.aether.ui.AetherConfirmScreen
import dev.aether.ui.AetherDirectJoinScreen
import dev.aether.ui.AetherManageServerScreen
import dev.aether.ui.AetherMultiplayerScreen
import dev.aether.ui.AetherTitleScreen
import dev.aether.ui.MainGUI
import dev.aether.util.BpsTracker
import dev.aether.util.DelayedBlockBreakTracker
import dev.aether.util.NickHiderUtils
import dev.aether.util.PingTracker
import dev.aether.util.ProgrammaticMovementTracker
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import java.io.File
import java.util.function.Consumer

class LiveAetherBootstrapHooks : AetherBootstrapHooks.FeatureHooks {
    override fun isAttackSuppressed() = PestDestroyer.isCatchInProgress()
    override fun onAttack(minecraft: Minecraft) { SqueakyMousematManager.onAttack(minecraft); PestTrackerAbility.onAttack(minecraft) }
    override fun onConfigProfileLoaded(profileFile: File) { ClientFeatureBootstrap.onConfigProfileLoaded(profileFile) }
    override fun onUnexpectedDisconnect() {
        if (AetherConfig.AUTO_RECONNECT.get() && MacroStateManager.isMacroRunning() && !MacroStateManager.isIntentionalDisconnect()) ReconnectScheduler.scheduleReconnect(30 + (Math.random() * 30).toLong(), true)
    }
    override fun onPlayStageDisconnect(serverAddress: String?, reason: Component?) {
        val context = BanDisconnectContext.capture(MacroStateManager.isMacroRunning())
        val established = PlaySessionTracker.isPlaySessionEstablished()
        val intentional = MacroStateManager.isIntentionalDisconnect()
        val address = if (serverAddress.isNullOrBlank()) PlaySessionTracker.getServerAddress() else serverAddress
        PlaySessionTracker.markSessionEnded()
        try { AetherBanService.onPlayDisconnect(address, reason?.string ?: "", context, established, intentional) }
        catch (e: RuntimeException) { Aether.LOGGER.warn("[aether] Ban detection failed: {}", e.javaClass.simpleName) }
    }
    override fun maybeCreateConfirmScreen(callback: BooleanConsumer, title: Component, message: Component): Screen? = if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) null else AetherConfirmScreen(callback, title, message)
    override fun maybeCreateDirectJoinScreen(lastScreen: Screen?, callback: BooleanConsumer, serverData: ServerData): Screen? = if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) null else AetherDirectJoinScreen(lastScreen!!, callback, serverData)
    override fun maybeCreateMultiplayerScreen(lastScreen: Screen): Screen? = if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) null else AetherMultiplayerScreen(lastScreen)
    override fun maybeCreateManageServerScreen(lastScreen: Screen?, title: Component, callback: BooleanConsumer, serverData: ServerData): Screen? = if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) null else AetherManageServerScreen(lastScreen!!, title, callback, serverData)
    override fun maybeCreateTitleScreen(): Screen? = if (StreamerModeManager.isEnabled() || !AetherConfig.CUSTOM_UI_ENABLED.get()) null else AetherTitleScreen()
    override fun maybeCreateHudEditScreen(): Screen? = HudEditScreen()
    override fun onGameRenderStart(minecraft: Minecraft) { if (minecraft.player != null) { RotationManager.update(); RotationExecutor.update() } }
    override fun onGameRenderEnd() { HudRegistry.onGuiGraphicsClosed() }
    override fun renderFailsafeColourFlash() { FailsafeColourFlashManager.render() }
    override fun pestOutlineColor(entity: net.minecraft.world.entity.Entity) = PestEspManager.outlineColor(entity)
    override fun renderPestEspTracerOverlay() { PestEspManager.renderTracerOverlay() }
    override fun onUserInput() { FailsafeColourFlashManager.dismiss() }
    override fun shouldSuppressVanillaHud(screen: Screen?) = AetherBootstrapHooks.isBootstrapConfigScreen(screen) || screen is MainGUI || screen is HudEditScreen
    override fun extractScoreboardSidebar(graphics: GuiGraphicsExtractor, vanilla: Consumer<GuiGraphicsExtractor>) { val hud = HudRegistry.scoreboardHud; if (hud == null) vanilla.accept(graphics) else hud.extract(graphics, vanilla) }
    override fun renderConfigScreenOverlay(renderer: NVGRenderer, width: Float, height: Float, deltaTime: Float) { HudRegistry.renderConfigTransition(renderer) }
    override fun transformOverlayMessage(component: Component): Component { FailsafeManager.observeGhostBlockOverlayMessage(component); dev.aether.modules.profit.helpers.FarmingXpTracker.onActionBar(component); return transformDisplayComponent(component) }
    override fun transformDisplayComponent(component: Component): Component {
        if (!AetherConfig.NICK_HIDER_ENABLED.get() && !AetherConfig.COOP_HIDER_ENABLED.get() && !AetherConfig.HIDE_SERVER_ID.get()) return component
        return NickHiderUtils.transformComponent(component) ?: component
    }
    override fun transformDisplayString(text: String) = if (!AetherConfig.NICK_HIDER_ENABLED.get() && !AetherConfig.COOP_HIDER_ENABLED.get() && !AetherConfig.HIDE_SERVER_ID.get()) text else NickHiderUtils.transformString(text)
    override fun shouldHidePlayerSkin(profile: GameProfile) = AetherConfig.NICK_HIDER_ENABLED.get() && AetherConfig.HIDE_SKIN.get() && profile.name == Minecraft.getInstance().user.name
    override fun shouldHideFilteredChatMessage(message: Component) = AetherConfig.HIDE_FILTERED_CHAT.get() && message.string.contains("for killing a")
    override fun isFreecamEnabled() = FreecamManager.isEnabled()
    override fun isFreecamProgrammaticKeyDown(client: Minecraft, keyMapping: KeyMapping) = FreecamManager.isProgrammaticKeyDown(keyMapping)
    override fun isProgrammaticMovementKeyDown(keyMapping: KeyMapping) = ProgrammaticMovementTracker.isDown(keyMapping)
    override fun turnFreecamCamera(yRot: Double, xRot: Double) = FreecamManager.turnCamera(yRot, xRot)
    override fun isFreelookActive() = FreelookManager.isActive()
    override fun turnFreelookCamera(yRot: Double, xRot: Double) = FreelookManager.turn(yRot, xRot)
    override fun getFreelookYaw() = FreelookManager.getYaw()
    override fun getFreelookPitch() = FreelookManager.getPitch()
    override fun shouldCancelMouseTurn() = RotationManager.isRotating() && !FreecamManager.isEnabled() && !FreelookManager.isActive()
    override fun isMouseUngrabbed() = UngrabMouse.isMouseUngrabbed()
    override fun hasCustomScreenBackground(screen: Screen) = !StreamerModeManager.isEnabled() && AetherConfig.CUSTOM_UI_ENABLED.get() && AetherBackgroundScreens.matches(screen)
    override fun renderCustomScreenBackground(width: Int, height: Int, mouseX: Int, mouseY: Int) { AetherBackground.INSTANCE.render(width, height, mouseX, mouseY) }
    override fun onBackgroundLeftClick(minecraft: Minecraft, screen: Screen, mouseX: Double, mouseY: Double) { if (hasCustomScreenBackground(screen)) AetherBackground.INSTANCE.addRipple(mouseX.toFloat(), mouseY.toFloat()) }
    override fun onBlockBreak() { BpsTracker.onBlockBreak(); FailsafeManager.onBlockBreak() }
    override fun onBlockBreak(pos: net.minecraft.core.BlockPos) { DelayedBlockBreakTracker.onImmediateBlockBreak(pos); BpsTracker.onBlockBreak(); FailsafeManager.onBlockBreak(pos) }
    override fun onBlockBreakClick(minecraft: Minecraft, pos: net.minecraft.core.BlockPos) { DelayedBlockBreakTracker.onBlockBreakClick(minecraft, pos) }
    override fun onBlockChanged(minecraft: Minecraft, pos: net.minecraft.core.BlockPos, oldState: net.minecraft.world.level.block.state.BlockState, newState: net.minecraft.world.level.block.state.BlockState) { DelayedBlockBreakTracker.onBlockChanged(minecraft, pos, newState); FailsafeManager.onBlockChanged(minecraft, pos, oldState, newState) }
    override fun tickFailsafes(minecraft: Minecraft) { dev.aether.util.RefocusInputGuard.tick(minecraft); DelayedBlockBreakTracker.tick(minecraft); FailsafeManager.tick(minecraft) }
    override fun resetFailsafes() { dev.aether.util.RefocusInputGuard.reset(); DelayedBlockBreakTracker.reset(); FailsafeManager.reset() }
    override fun resetFailsafeRuntimeState() { DelayedBlockBreakTracker.reset(); FailsafeManager.resetRuntimeState() }
    override fun addRotationGracePeriod(durationMs: Long) { FailsafeManager.addRotationGracePeriod(durationMs) }
    override fun selectHotbarSlot(minecraft: Minecraft, slot: Int) { FailsafeManager.selectHotbarSlot(minecraft, slot) }
    override fun isMuted() = MuteManager.isMuted()
    override fun getMuteVolume() = MuteManager.getVolume()
    override fun areParticlesDisabled() = PerformanceModeManager.isParticlesDisabled()
    override fun onParticlePacket(minecraft: Minecraft, packet: ClientboundLevelParticlesPacket) { if (!minecraft.isSameThread) return; VacuumParticleDebug.onParticlePacket(packet); PestTrackerAbility.onParticlePacket(minecraft, packet) }
    override fun onStatsPacketReceived() { PingTracker.onStatsReceived() }
}
