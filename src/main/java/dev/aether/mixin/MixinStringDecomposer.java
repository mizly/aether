package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(StringDecomposer.class)
public class MixinStringDecomposer {

    @ModifyArg(method = "iterateFormatted(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", 
               at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", ordinal = 0), 
               index = 0)
    private static String aether$modifyIterateFormatted(String text) {
        return AetherBootstrapHooks.transformDisplayString(text);
    }
}

