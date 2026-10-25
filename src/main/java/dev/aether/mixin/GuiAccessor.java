package dev.aether.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// 26.2 moved the scoreboard sidebar from Gui to Hud
@Mixin(Hud.class)
public interface GuiAccessor {
    @Invoker("extractScoreboardSidebar")
    void aether$extractScoreboardSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker);
}
