package dev.aether.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Screen.class)
public interface AccessorScreen {
    @Invoker("defaultHandleGameClickEvent")
    static void aether$defaultHandleGameClickEvent(ClickEvent clickEvent, Minecraft minecraft, Screen screen) {
        throw new AssertionError();
    }
}
