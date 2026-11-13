package dev.aether.bootstrap

import com.mojang.authlib.GameProfile
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.MainGUI
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.level.block.state.BlockState
import java.io.File
import java.util.function.Consumer

class AetherBootstrapHooks private constructor() {
    interface FeatureHooks {
        fun isAttackSuppressed(): Boolean = false
        fun onAttack(minecraft: Minecraft) {}
        fun onConfigProfileLoaded(profileFile: File) {}
        fun onUnexpectedDisconnect() {}
        fun onPlayStageDisconnect(serverAddress: String?, reason: Component?) {}
        fun maybeCreateConfirmScreen(callback: BooleanConsumer, title: Component, message: Component): Screen? = null
        fun maybeCreateDirectJoinScreen(lastScreen: Screen?, callback: BooleanConsumer, serverData: ServerData): Screen? = null
        fun maybeCreateMultiplayerScreen(lastScreen: Screen): Screen? = null
        fun maybeCreateManageServerScreen(lastScreen: Screen?, title: Component, callback: BooleanConsumer, serverData: ServerData): Screen? = null
        fun maybeCreateTitleScreen(): Screen? = null
        fun maybeCreateHudEditScreen(): Screen? = null
        fun onGameRenderStart(minecraft: Minecraft) {}
        fun onGameRenderEnd() {}
        fun renderFailsafeColourFlash() {}
        fun pestOutlineColor(entity: net.minecraft.world.entity.Entity): Int = 0
        fun renderPestEspTracerOverlay() {}
        fun onUserInput() {}
        fun shouldSuppressVanillaHud(screen: Screen?): Boolean = false
        fun extractScoreboardSidebar(graphics: GuiGraphicsExtractor, vanilla: Consumer<GuiGraphicsExtractor>) { vanilla.accept(graphics) }
        fun renderConfigScreenOverlay(renderer: NVGRenderer, width: Float, height: Float, deltaTime: Float) {}
        fun transformOverlayMessage(component: Component): Component = component
        fun transformDisplayComponent(component: Component): Component = component
        fun transformDisplayString(text: String): String = text
        fun shouldHidePlayerSkin(profile: GameProfile): Boolean = false
        fun shouldHideFilteredChatMessage(message: Component): Boolean = false
        fun isFreecamEnabled(): Boolean = false
        fun isFreecamProgrammaticKeyDown(client: Minecraft, keyMapping: KeyMapping): Boolean = false
        fun isProgrammaticMovementKeyDown(keyMapping: KeyMapping): Boolean = false
        fun turnFreecamCamera(yRot: Double, xRot: Double): Boolean = false
        fun isFreelookActive(): Boolean = false
        fun turnFreelookCamera(yRot: Double, xRot: Double): Boolean = false
        fun getFreelookYaw(): Float = 0.0f
        fun getFreelookPitch(): Float = 0.0f
        fun shouldCancelMouseTurn(): Boolean = false
        fun isMouseUngrabbed(): Boolean = false
        fun hasCustomScreenBackground(screen: Screen): Boolean = false
        fun renderCustomScreenBackground(width: Int, height: Int, mouseX: Int, mouseY: Int) {}
        fun onBackgroundLeftClick(minecraft: Minecraft, screen: Screen, mouseX: Double, mouseY: Double) {}
        fun onBlockBreak() {}
        fun onBlockBreak(pos: BlockPos) { onBlockBreak() }
        fun onBlockBreakClick(minecraft: Minecraft, pos: BlockPos) {}
        fun onBlockChanged(minecraft: Minecraft, pos: BlockPos, oldState: BlockState, newState: BlockState) {}
        fun tickFailsafes(minecraft: Minecraft) {}
        fun resetFailsafes() {}
        fun resetFailsafeRuntimeState() {}
        fun addRotationGracePeriod(durationMs: Long) {}
        fun selectHotbarSlot(minecraft: Minecraft, slot: Int) {}
        fun isMuted(): Boolean = false
        fun getMuteVolume(): Float = 0.0f
        fun areParticlesDisabled(): Boolean = false
        fun onParticlePacket(minecraft: Minecraft, packet: ClientboundLevelParticlesPacket) {}
        fun onStatsPacketReceived() {}
    }

    class DisplayTransformScope internal constructor() : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            resumeDisplayTransforms()
        }
    }

    companion object {
        private val DISPLAY_TRANSFORM_SUSPEND_DEPTH = ThreadLocal.withInitial { 0 }
        private val NOOP = object : FeatureHooks {}
        @Volatile private var hooks: FeatureHooks = NOOP

        @JvmStatic fun install(featureHooks: FeatureHooks?) { hooks = featureHooks ?: NOOP }
        @JvmStatic fun reset() { hooks = NOOP }
        @JvmStatic fun isAttackSuppressed(): Boolean = hooks.isAttackSuppressed()
        @JvmStatic fun onAttack(minecraft: Minecraft) { hooks.onAttack(minecraft) }
        @JvmStatic fun onUnexpectedDisconnect() = hooks.onUnexpectedDisconnect()
        @JvmStatic fun onPlayStageDisconnect(serverAddress: String?, reason: Component?) = hooks.onPlayStageDisconnect(serverAddress, reason)
        @JvmStatic fun onConfigProfileLoaded(profileFile: File) = hooks.onConfigProfileLoaded(profileFile)
        @JvmStatic fun maybeCreateConfirmScreen(callback: BooleanConsumer, title: Component, message: Component): Screen? = hooks.maybeCreateConfirmScreen(callback, title, message)
        @JvmStatic fun maybeCreateDirectJoinScreen(lastScreen: Screen?, callback: BooleanConsumer, serverData: ServerData): Screen? = hooks.maybeCreateDirectJoinScreen(lastScreen, callback, serverData)
        @JvmStatic fun maybeCreateMultiplayerScreen(lastScreen: Screen) = hooks.maybeCreateMultiplayerScreen(lastScreen)
        @JvmStatic fun maybeCreateManageServerScreen(lastScreen: Screen?, title: Component, callback: BooleanConsumer, serverData: ServerData) = hooks.maybeCreateManageServerScreen(lastScreen, title, callback, serverData)
        @JvmStatic fun maybeCreateTitleScreen() = hooks.maybeCreateTitleScreen()
        @JvmStatic fun maybeCreateHudEditScreen() = hooks.maybeCreateHudEditScreen()
        @JvmStatic fun onGameRenderStart(minecraft: Minecraft) = hooks.onGameRenderStart(minecraft)
        @JvmStatic fun onGameRenderEnd() = hooks.onGameRenderEnd()
        @JvmStatic fun renderFailsafeColourFlash() = hooks.renderFailsafeColourFlash()
        @JvmStatic fun pestOutlineColor(entity: net.minecraft.world.entity.Entity) = hooks.pestOutlineColor(entity)
        @JvmStatic fun renderPestEspTracerOverlay() = hooks.renderPestEspTracerOverlay()
        @JvmStatic fun onUserInput() = hooks.onUserInput()
        @JvmStatic fun shouldSuppressVanillaHud(screen: Screen?) = isBootstrapConfigScreen(screen) || hooks.shouldSuppressVanillaHud(screen)
        @JvmStatic fun extractScoreboardSidebar(graphics: GuiGraphicsExtractor, vanilla: Consumer<GuiGraphicsExtractor>) = hooks.extractScoreboardSidebar(graphics, vanilla)
        @JvmStatic fun isBootstrapConfigScreen(screen: Screen?) = screen is MainGUI
        @JvmStatic fun renderConfigScreenOverlay(renderer: NVGRenderer, width: Float, height: Float, deltaTime: Float) = hooks.renderConfigScreenOverlay(renderer, width, height, deltaTime)
        @JvmStatic fun transformOverlayMessage(component: Component) = hooks.transformOverlayMessage(component)
        @JvmStatic fun transformDisplayComponent(component: Component): Component = if (areDisplayTransformsSuspended()) component else hooks.transformDisplayComponent(component)
        @JvmStatic fun transformDisplayString(text: String): String = if (areDisplayTransformsSuspended()) text else hooks.transformDisplayString(text)
        @JvmStatic fun suspendDisplayTransforms(): DisplayTransformScope {
            DISPLAY_TRANSFORM_SUSPEND_DEPTH.set(DISPLAY_TRANSFORM_SUSPEND_DEPTH.get() + 1)
            return DisplayTransformScope()
        }
        @JvmStatic fun areDisplayTransformsSuspended() = DISPLAY_TRANSFORM_SUSPEND_DEPTH.get() > 0
        private fun resumeDisplayTransforms() {
            val depth = DISPLAY_TRANSFORM_SUSPEND_DEPTH.get()
            if (depth <= 1) DISPLAY_TRANSFORM_SUSPEND_DEPTH.remove() else DISPLAY_TRANSFORM_SUSPEND_DEPTH.set(depth - 1)
        }
        @JvmStatic fun shouldHidePlayerSkin(profile: GameProfile) = hooks.shouldHidePlayerSkin(profile)
        @JvmStatic fun shouldHideFilteredChatMessage(message: Component) = hooks.shouldHideFilteredChatMessage(message)
        @JvmStatic fun isFreecamEnabled() = hooks.isFreecamEnabled()
        @JvmStatic fun isFreecamProgrammaticKeyDown(client: Minecraft, keyMapping: KeyMapping) = hooks.isFreecamProgrammaticKeyDown(client, keyMapping)
        @JvmStatic fun isProgrammaticMovementKeyDown(keyMapping: KeyMapping) = hooks.isProgrammaticMovementKeyDown(keyMapping)
        @JvmStatic fun turnFreecamCamera(yRot: Double, xRot: Double) = hooks.turnFreecamCamera(yRot, xRot)
        @JvmStatic fun isFreelookActive() = hooks.isFreelookActive()
        @JvmStatic fun turnFreelookCamera(yRot: Double, xRot: Double) = hooks.turnFreelookCamera(yRot, xRot)
        @JvmStatic fun getFreelookYaw() = hooks.getFreelookYaw()
        @JvmStatic fun getFreelookPitch() = hooks.getFreelookPitch()
        @JvmStatic fun shouldCancelMouseTurn() = hooks.shouldCancelMouseTurn()
        @JvmStatic fun isMouseUngrabbed() = hooks.isMouseUngrabbed()
        @JvmStatic fun hasCustomScreenBackground(screen: Screen) = hooks.hasCustomScreenBackground(screen)
        @JvmStatic fun renderCustomScreenBackground(width: Int, height: Int, mouseX: Int, mouseY: Int) = hooks.renderCustomScreenBackground(width, height, mouseX, mouseY)
        @JvmStatic fun onBackgroundLeftClick(minecraft: Minecraft, screen: Screen, mouseX: Double, mouseY: Double) = hooks.onBackgroundLeftClick(minecraft, screen, mouseX, mouseY)
        @JvmStatic fun onBlockBreak() = hooks.onBlockBreak()
        @JvmStatic fun onBlockBreak(pos: BlockPos) = hooks.onBlockBreak(pos)
        @JvmStatic fun onBlockBreakClick(minecraft: Minecraft, pos: BlockPos) = hooks.onBlockBreakClick(minecraft, pos)
        @JvmStatic fun onBlockChanged(minecraft: Minecraft, pos: BlockPos, oldState: BlockState, newState: BlockState) = hooks.onBlockChanged(minecraft, pos, oldState, newState)
        @JvmStatic fun tickFailsafes(minecraft: Minecraft) = hooks.tickFailsafes(minecraft)
        @JvmStatic fun resetFailsafes() = hooks.resetFailsafes()
        @JvmStatic fun resetFailsafeRuntimeState() = hooks.resetFailsafeRuntimeState()
        @JvmStatic fun addRotationGracePeriod(durationMs: Long) = hooks.addRotationGracePeriod(durationMs)
        @JvmStatic fun selectHotbarSlot(minecraft: Minecraft, slot: Int) = hooks.selectHotbarSlot(minecraft, slot)
        @JvmStatic fun isMuted() = hooks.isMuted()
        @JvmStatic fun getMuteVolume() = hooks.getMuteVolume()
        @JvmStatic fun areParticlesDisabled() = hooks.areParticlesDisabled()
        @JvmStatic fun onParticlePacket(minecraft: Minecraft, packet: ClientboundLevelParticlesPacket) = hooks.onParticlePacket(minecraft, packet)
        @JvmStatic fun onStatsPacketReceived() = hooks.onStatsPacketReceived()
    }
}