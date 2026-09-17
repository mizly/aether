package dev.aether.macro;

import net.minecraft.client.Minecraft;

public abstract class AbstractMacro {

    public abstract void onEnable(Minecraft mc);

    public abstract void onDisable(Minecraft mc);

    public abstract void onTick(Minecraft mc);
}
