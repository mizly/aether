package dev.aether.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Hud.class)
public interface GuiAccessor {
    @Invoker("extractScoreboardSidebar")
    void aether$extractScoreboardSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker);
}
