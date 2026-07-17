package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }

        boolean freecam = AetherBootstrapHooks.isFreecamEnabled();
        boolean macroForward = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyUp);
        boolean macroBackward = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyDown);
        boolean macroLeft = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyLeft);
        boolean macroRight = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyRight);
        boolean macroJump = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyJump);
        boolean macroShift = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyShift);
        boolean macroSprint = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keySprint);
        boolean hasMacroInput = macroForward || macroBackward || macroLeft || macroRight
            || macroJump || macroShift || macroSprint;
        if (!freecam && !hasMacroInput) {
            return;
        }

        boolean forward = macroForward || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyUp)
            : this.keyPresses.forward());
        boolean backward = macroBackward || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyDown)
            : this.keyPresses.backward());
        boolean left = macroLeft || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyLeft)
            : this.keyPresses.left());
        boolean right = macroRight || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyRight)
            : this.keyPresses.right());
        boolean jump = macroJump || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyJump)
            : this.keyPresses.jump());
        boolean shift = macroShift || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyShift)
            : this.keyPresses.shift());
        boolean sprint = macroSprint || (freecam
            ? AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keySprint)
            : this.keyPresses.sprint());

        this.keyPresses = new Input(
            forward,
            backward,
            left,
            right,
            jump,
            shift,
            sprint
        );
        this.moveVector = new Vec2(calculateImpulse(left, right), calculateImpulse(forward, backward)).normalized();
    }

    private static float calculateImpulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0f;
        }
        return positive ? 1.0f : -1.0f;
    }
}

