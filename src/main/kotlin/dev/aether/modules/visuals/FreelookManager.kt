package dev.aether.modules.visuals

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.config.ConfigHelpers
import dev.aether.config.FreelookMode
import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorKeyMapping
import dev.aether.mixin.MixinMinecraft
import dev.aether.mixin.AccessorWindow
import dev.aether.modules.farming.UngrabMouse
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.sdl.SDLMouse
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth

// the mouse orbits the camera while the bind is held, without touching the player's real yaw/pitch, so the body keeps facing and farming where it was
// unlike freecam the camera entity never detaches, so picking stays anchored to the player and nothing new is sent to the server
class FreelookManager private constructor() {
    companion object {
        private var registered = false
        private var active = false
        private var keyWasDown = false
        private var yaw = 0f
        private var pitch = 0f
        private var previousCameraType: CameraType? = null
        @JvmStatic fun register() {
            if (registered) return
            registered = true
            ClientTickEvents.END_CLIENT_TICK.register(FreelookManager::tick)
        }
        @JvmStatic fun isActive(): Boolean = active
        @JvmStatic fun getYaw(): Float = yaw
        @JvmStatic fun getPitch(): Float = pitch
        // matches vanilla turn feel (0.15 scale, pitch clamped to +-90)
        // true when the delta was consumed, so the caller should not turn the player
        @JvmStatic fun turn(yRot: Double, xRot: Double): Boolean {
            if (!active) return false
            yaw += (yRot * 0.15).toFloat()
            pitch += (xRot * 0.15).toFloat()
            pitch = Mth.clamp(pitch, -90.0f, 90.0f)
            return true
        }
        private fun tick(client: Minecraft) {
            val available = isAvailable(client)
            val keyDown = available && isKeyDown(client, AetherKeybindRegistry.getFreelookKey())
            if (!available) { if (active) disable(client); keyWasDown = keyDown; return }
            if (ConfigHelpers.getFreelookMode() == FreelookMode.TOGGLE) {
                if (keyDown && !keyWasDown) { if (active) disable(client) else enable(client) }
            } else {
                if (keyDown && !active) enable(client) else if (!keyDown && active) disable(client)
            }
            if (active) clearMissTime(client)
            keyWasDown = keyDown
        }
        private fun clearMissTime(client: Minecraft?) {
            if (client == null) return
            client.execute { (client as MixinMinecraft).`aether$setMissTime`(0) }
        }
        private fun isAvailable(client: Minecraft): Boolean {
            if (client.player == null || client.level == null || client.gui.screen() != null) return false
            if (FreecamManager.isEnabled() || StreamerModeManager.isEnabled()) return false
            return AetherConfig.FREELOOK_ENABLED.get()
        }
        private fun enable(client: Minecraft) {
            val player: LocalPlayer = client.player ?: return
            if (client.options == null) return
            active = true
            yaw = player.yRot
            pitch = player.xRot
            previousCameraType = client.options.cameraType
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK)
            UngrabMouse.suspendForFreelook()
            clearMissTime(client)
        }
        private fun disable(client: Minecraft?) {
            active = false
            UngrabMouse.resumeAfterFreelook()
            if (client != null && client.options != null && previousCameraType != null) client.options.setCameraType(previousCameraType!!)
            previousCameraType = null
        }
        private fun isKeyDown(client: Minecraft, mapping: KeyMapping?): Boolean {
            if (mapping == null) return false
            val key = (mapping as AccessorKeyMapping).getKey() ?: return false
            if (key == InputConstants.UNKNOWN) return false
            return when (key.type) {
                InputConstants.Type.KEYBOARD -> InputConstants.isKeyDown(key.value)
                InputConstants.Type.MOUSE -> (SDLMouse.SDL_GetMouseState(null, null) and (1 shl (key.value - 1))) != 0
                else -> false
            }
        }
    }
}
