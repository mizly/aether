package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.KeyboardInput
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec2
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(KeyboardInput::class)
abstract class MixinKeyboardInput : ClientInput() {
    @Inject(method = ["tick"], at = [At("TAIL")])
    private fun onTick(ci: CallbackInfo) {
        val client = Minecraft.getInstance()
        if (client.options == null) return

        val freecam = AetherBootstrapHooks.isFreecamEnabled()
        val macroForward = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyUp)
        val macroBackward = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyDown)
        val macroLeft = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyLeft)
        val macroRight = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyRight)
        val macroJump = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyJump)
        val macroShift = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyShift)
        val macroSprint = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keySprint)
        val hasMacroInput = macroForward || macroBackward || macroLeft || macroRight || macroJump || macroShift || macroSprint
        if (!freecam && !hasMacroInput) return

        val forward = macroForward || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyUp) else keyPresses.forward()
        val backward = macroBackward || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyDown) else keyPresses.backward()
        val left = macroLeft || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyLeft) else keyPresses.left()
        val right = macroRight || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyRight) else keyPresses.right()
        val jump = macroJump || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyJump) else keyPresses.jump()
        val shift = macroShift || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyShift) else keyPresses.shift()
        val sprint = macroSprint || if (freecam) AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keySprint) else keyPresses.sprint()

        keyPresses = Input(forward, backward, left, right, jump, shift, sprint)
        moveVector = Vec2(aetherModCalculateImpulse(left, right), aetherModCalculateImpulse(forward, backward)).normalized()
    }

    @Unique
    private fun aetherModCalculateImpulse(positive: Boolean, negative: Boolean): Float {
        if (positive == negative) return 0.0f
        return if (positive) 1.0f else -1.0f
    }
}
