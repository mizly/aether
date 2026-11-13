package dev.aether.mixin

import net.minecraft.client.gui.components.events.AbstractContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

// stops null becoming the focused widget, which npes in focus navigation
@Mixin(AbstractContainerEventHandler::class)
abstract class MixinContainerEventHandler {
    @Inject(at = [At("HEAD")], method = ["setFocused(Lnet/minecraft/client/gui/components/events/GuiEventListener;)V"], cancellable = true)
    private fun `aether$skipNullFocused`(child: GuiEventListener?, ci: CallbackInfo) {
        if (child == null) ci.cancel()
    }
}
