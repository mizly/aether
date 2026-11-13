package dev.aether.modules.visuals

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.platform.InputConstants
import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorKeyMapping
import dev.aether.mixin.AccessorWindow
import dev.aether.mixin.MixinMinecraft
import dev.aether.modules.farming.UngrabMouse
import dev.aether.util.ClientUtils
import dev.aether.util.ProgrammaticAttackTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec3
import org.lwjgl.sdl.SDLMouse

class FreecamManager private constructor() {
    companion object {
        private const val OBSERVED_PLAYER_UPDATE_INTERVAL = 2
        private const val OBSERVED_PLAYER_INTERPOLATION_STEPS = 3
        private const val MACRO_MOVEMENT_GRACE_MS = 200L
        private var registered = false
        private var enabled = false
        private var toggleKeyWasDown = false
        private var teleportKeyWasDown = false
        private var cameraEntity: RemotePlayer? = null
        private var previousCameraEntity: Entity? = null
        private var previousCameraType: CameraType? = null
        private var observedRenderPos: Vec3? = null
        private var observedRenderPrevPos: Vec3? = null
        private var observedRenderTargetPos: Vec3? = null
        private var observedRenderInterpolationSteps = 0
        private var observedRenderTargetTick = 0
        private var macroMovementGraceUntilMs = 0L

        @JvmStatic fun register() {
            if (registered) return
            registered = true
            ClientTickEvents.END_CLIENT_TICK.register(FreecamManager::tick)
            LevelRenderEvents.END_EXTRACTION.register(FreecamManager::appendAnchoredPlayerRenderState)
        }
        @JvmStatic fun isEnabled() = enabled
        @JvmStatic fun isFeatureEnabled() = AetherConfig.FREECAM_ENABLED.get()
        @JvmStatic fun setFeatureEnabled(shouldEnable: Boolean) {
            AetherConfig.FREECAM_ENABLED.set(shouldEnable)
            AetherConfig.save()
            if (!shouldEnable) setEnabled(false)
        }
        @JvmStatic fun turnCamera(yRot: Double, xRot: Double): Boolean {
            val camera = cameraEntity
            if (!enabled || camera == null) return false
            camera.turn(yRot, xRot)
            syncRotationState(camera, camera.yRot, camera.xRot)
            return true
        }
        @JvmStatic fun shouldSuppressMacroMovementDetection(player: LocalPlayer?): Boolean = player != null && System.currentTimeMillis() < macroMovementGraceUntilMs
        @JvmStatic fun isProgrammaticKeyDown(mapping: KeyMapping?): Boolean {
            val client = Minecraft.getInstance()
            return mapping != null && mapping.isDown && !isKeyDown(client, mapping)
        }
        @JvmStatic fun toggle() {
            val client = Minecraft.getInstance()
            if (!isFeatureEnabled()) {
                if (client != null) ClientUtils.sendMessage("Turn the Freecam module on to use the freecam keybind.", false)
                return
            }
            setEnabled(!enabled)
        }
        @JvmStatic fun teleportCameraToPlayer() {
            val client = Minecraft.getInstance()
            val player = client.player
            val camera = cameraEntity
            if (!enabled || client == null || player == null || camera == null) return
            camera.setOldPosAndRot(player.position(), player.yRot, player.xRot)
            camera.setPos(player.position())
            syncRotationState(camera, player.yRot, player.xRot)
            camera.setDeltaMovement(Vec3.ZERO)
            ClientUtils.sendMessage("Teleported to player!", false)
        }
        @JvmStatic fun setEnabled(shouldEnable: Boolean) {
            val client = Minecraft.getInstance()
            if (shouldEnable && StreamerModeManager.isEnabled()) return
            if (client == null) {
                enabled = false
                previousCameraType = null
                macroMovementGraceUntilMs = 0L
                return
            }
            if (!client.isSameThread) { client.execute { setEnabled(shouldEnable) }; return }
            if (shouldEnable) enable(client) else disable(client, false)
        }
        private fun enable(client: Minecraft) {
            val player = client.player
            val level = client.level
            if (enabled || player == null || level == null) return
            enabled = true
            previousCameraEntity = client.cameraEntity
            previousCameraType = client.options.cameraType
            UngrabMouse.suspendForFreecam()
            cameraEntity = createCameraEntity(level, player)
            observedRenderPos = player.position()
            observedRenderPrevPos = observedRenderPos
            observedRenderTargetPos = observedRenderPos
            observedRenderInterpolationSteps = 0
            observedRenderTargetTick = player.tickCount
            startMacroMovementGrace()
            client.setCameraEntity(cameraEntity)
            enforceFirstPerson(client)
            clearLatchedInputState(client, player)
            ClientUtils.reapplyProgrammaticKeyStates(client)
            clearMissTime(client)
            ClientUtils.sendDebugMessage("[FC] enabled: attackDown=" + client.options.keyAttack.isDown + " tracker=" + ProgrammaticAttackTracker.isHeld())
            ClientUtils.sendMessage("Freecam enabled!", false)
        }
        private fun disable(client: Minecraft?, dueToMissingWorld: Boolean) {
            if (!enabled && cameraEntity == null) return
            enabled = false
            val player = client?.player
            if (client != null && player != null) {
                startMacroMovementGrace()
                clearLatchedInputState(client, player)
                ClientUtils.reapplyProgrammaticKeyStates(client)
                ClientUtils.sendDebugMessage("[FC] disabled: attackDown=" + client.options.keyAttack.isDown + " tracker=" + ProgrammaticAttackTracker.isHeld())
                var restore = previousCameraEntity
                if (restore == null || restore.isRemoved) restore = player
                client.setCameraEntity(restore)
                if (!dueToMissingWorld) ClientUtils.sendMessage("Freecam disabled!", false)
            }
            restoreCameraType(client)
            UngrabMouse.resumeAfterFreecam()
            clearMissTime(client)
            cameraEntity = null
            observedRenderPos = null
            observedRenderPrevPos = null
            observedRenderTargetPos = null
            observedRenderInterpolationSteps = 0
            observedRenderTargetTick = 0
            previousCameraEntity = null
            previousCameraType = null
        }
        private fun startMacroMovementGrace() { macroMovementGraceUntilMs = System.currentTimeMillis() + MACRO_MOVEMENT_GRACE_MS }
        private fun clearMissTime(client: Minecraft?) {
            if (client == null) return
            client.execute { (client as MixinMinecraft).`aether$setMissTime`(0) }
        }
        private fun pollKeybinds(client: Minecraft?) {
            if (client == null || client.player == null || client.level == null || client.gui.screen() != null) {
                toggleKeyWasDown = false; teleportKeyWasDown = false; return
            }
            val toggleDown = isKeyDown(client, AetherKeybindRegistry.getFreecamKey())
            if (toggleDown && !toggleKeyWasDown) toggle()
            toggleKeyWasDown = toggleDown
            val teleportDown = isKeyDown(client, AetherKeybindRegistry.getFreecamTeleportToPlayerKey())
            if (teleportDown && !teleportKeyWasDown && enabled) teleportCameraToPlayer()
            teleportKeyWasDown = teleportDown
        }
        private fun tick(client: Minecraft) {
            pollKeybinds(client)
            if (!enabled) return
            if (StreamerModeManager.isEnabled()) { disable(client, true); return }
            val player = client.player
            val level = client.level
            if (player == null || level == null) { disable(client, true); return }
            if (cameraEntity == null || client.cameraEntity !== cameraEntity) {
                cameraEntity = createCameraEntity(level, player)
                client.setCameraEntity(cameraEntity)
            }
            enforceFirstPerson(client)
            moveCamera(client)
            updateObservedRenderState(player)
        }
        private fun appendAnchoredPlayerRenderState(context: LevelExtractionContext) {
            if (!enabled || StreamerModeManager.isEnabled()) return
            val client = Minecraft.getInstance()
            val player = client.player ?: return
            if (context.level() != player.level()) return
            if (isPlayerAlreadyQueued(context, player.id)) return
            val partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(!player.level().tickRateManager().isEntityFrozen(player))
            val renderState = client.entityRenderDispatcher.extractEntity(player, partialTick)
            val pos = observedRenderPos
            val prev = observedRenderPrevPos
            if (pos != null && prev != null) {
                renderState.x = Mth.lerp(partialTick.toDouble(), prev.x, pos.x)
                renderState.y = Mth.lerp(partialTick.toDouble(), prev.y, pos.y)
                renderState.z = Mth.lerp(partialTick.toDouble(), prev.z, pos.z)
            }
            context.levelState().entityRenderStates.add(renderState)
            if (renderState.appearsGlowing()) context.levelState().shouldShowEntityOutlines = true
        }
        private fun createCameraEntity(level: ClientLevel, player: LocalPlayer): RemotePlayer {
            val camera = RemotePlayer(level, GameProfile(player.uuid, player.name.string))
            camera.setInvisible(true); camera.noPhysics = true; camera.setNoGravity(true)
            camera.setOldPosAndRot(player.position(), player.yRot, player.xRot)
            camera.setPos(player.position()); syncRotationState(camera, player.yRot, player.xRot); camera.setDeltaMovement(Vec3.ZERO)
            return camera
        }
        private fun isPlayerAlreadyQueued(context: LevelExtractionContext, playerId: Int): Boolean {
            for (renderState in context.levelState().entityRenderStates) if (renderState is AvatarRenderState && renderState.id == playerId) return true
            return false
        }
        private fun clearLatchedInputState(client: Minecraft, player: LocalPlayer) {
            if (client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyUp, false); ClientUtils.setKeyMappingState(client.options.keyDown, false)
                ClientUtils.setKeyMappingState(client.options.keyLeft, false); ClientUtils.setKeyMappingState(client.options.keyRight, false)
                ClientUtils.setKeyMappingState(client.options.keyJump, false); ClientUtils.setKeyMappingState(client.options.keyShift, false)
                ClientUtils.setKeyMappingState(client.options.keySprint, false); ClientUtils.setKeyMappingState(client.options.keyAttack, false)
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
            }
            player.setJumping(false); player.setShiftKeyDown(false); player.setSprinting(false)
            player.input.keyPresses = Input(false, false, false, false, false, false, false)
        }
        private fun updateObservedRenderState(player: LocalPlayer) {
            val currentPos = player.position()
            var pos = observedRenderPos
            var prev = observedRenderPrevPos
            var target = observedRenderTargetPos
            if (pos == null || prev == null || target == null) {
                observedRenderPos = currentPos; observedRenderPrevPos = currentPos; observedRenderTargetPos = currentPos
                observedRenderInterpolationSteps = 0; observedRenderTargetTick = player.tickCount; return
            }
            observedRenderPrevPos = pos
            if (target.distanceToSqr(currentPos) > 1.0E-6 && player.tickCount - observedRenderTargetTick >= OBSERVED_PLAYER_UPDATE_INTERVAL) {
                target = currentPos; observedRenderTargetPos = currentPos
                observedRenderInterpolationSteps = OBSERVED_PLAYER_INTERPOLATION_STEPS; observedRenderTargetTick = player.tickCount
            }
            if (observedRenderInterpolationSteps > 0) {
                val progress = 1.0 / observedRenderInterpolationSteps
                observedRenderPos = Vec3(Mth.lerp(progress, pos.x, target.x), Mth.lerp(progress, pos.y, target.y), Mth.lerp(progress, pos.z, target.z))
                observedRenderInterpolationSteps--; return
            }
            observedRenderPos = target
        }
        private fun moveCamera(client: Minecraft) {
            val camera = cameraEntity ?: return
            var speed = AetherConfig.FREECAM_SPEED.get()
            if (isKeyDown(client, client.options.keySprint)) speed *= 2.0f
            val look = camera.lookAngle
            var forward = Vec3(look.x, 0.0, look.z)
            if (forward.lengthSqr() < 1.0E-6) {
                val yawRad = Math.toRadians(camera.yRot.toDouble())
                forward = Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad))
            }
            forward = forward.normalize()
            val right = Vec3(-forward.z, 0.0, forward.x)
            var motion = Vec3.ZERO
            if (isKeyDown(client, client.options.keyUp)) motion = motion.add(forward)
            if (isKeyDown(client, client.options.keyDown)) motion = motion.subtract(forward)
            if (isKeyDown(client, client.options.keyLeft)) motion = motion.subtract(right)
            if (isKeyDown(client, client.options.keyRight)) motion = motion.add(right)
            if (isKeyDown(client, client.options.keyJump)) motion = motion.add(0.0, 1.0, 0.0)
            if (isKeyDown(client, client.options.keyShift)) motion = motion.add(0.0, -1.0, 0.0)
            camera.setOldPosAndRot(camera.position(), camera.yRot, camera.xRot)
            if (motion.lengthSqr() > 1.0E-6) camera.setPos(camera.position().add(motion.normalize().scale(speed.toDouble())))
            syncRotationState(camera, camera.yRot, camera.xRot); camera.setDeltaMovement(Vec3.ZERO)
        }
        private fun syncRotationState(entity: LivingEntity, yaw: Float, pitch: Float) {
            entity.setYRot(yaw); entity.setXRot(pitch); entity.yRotO = yaw; entity.xRotO = pitch
            entity.setYHeadRot(yaw); entity.yHeadRotO = yaw; entity.setYBodyRot(yaw); entity.yBodyRotO = yaw
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
        private fun enforceFirstPerson(client: Minecraft) {
            if (client.options.cameraType != CameraType.FIRST_PERSON) client.options.setCameraType(CameraType.FIRST_PERSON)
        }
        private fun restoreCameraType(client: Minecraft?) {
            val cameraType = previousCameraType ?: return
            if (client == null) return
            client.options.setCameraType(cameraType)
        }
    }
}
