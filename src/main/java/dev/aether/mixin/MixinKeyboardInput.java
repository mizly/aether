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
        if (!AetherBootstrapHooks.isFreecamEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }

        boolean forward = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyUp)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyUp);
        boolean backward = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyDown)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyDown);
        boolean left = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyLeft)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyLeft);
        boolean right = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyRight)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyRight);
        boolean jump = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyJump)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyJump);
        boolean shift = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keyShift)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keyShift);
        boolean sprint = AetherBootstrapHooks.isProgrammaticMovementKeyDown(client.options.keySprint)
            || AetherBootstrapHooks.isFreecamProgrammaticKeyDown(client, client.options.keySprint);

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

