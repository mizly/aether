package dev.aether.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MixinMinecraft {
    @Invoker("startAttack")
    boolean aether$startAttack();

    @Invoker("continueAttack")
    void aether$continueAttack(boolean attacking);

    @Invoker("startUseItem")
    void aether$startUseItem();

    @Invoker("pickBlockOrEntity")
    void aether$pickBlockOrEntity();

    // vanilla grabMouse() slams missTime to 10000 and it only decrements 1/tick, so freelook has to clear it after grabbing or the macro's continuous attack never resets it and breaking stays suppressed for ~500s
    @Accessor("missTime")
    void aether$setMissTime(int missTime);
}
