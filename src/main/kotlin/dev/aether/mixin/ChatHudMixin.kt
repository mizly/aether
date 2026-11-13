package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.client.multiplayer.chat.GuiMessageTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MessageSignature
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ChatComponent::class)
class ChatHudMixin {
    @Inject(
        method = ["addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun onAddMessage(message: Component, signature: MessageSignature, source: GuiMessageSource, tag: GuiMessageTag, ci: CallbackInfo) {
        if (AetherBootstrapHooks.shouldHideFilteredChatMessage(message)) {
            ci.cancel()
        }
    }
}
