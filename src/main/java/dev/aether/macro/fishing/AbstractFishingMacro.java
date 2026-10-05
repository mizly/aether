package dev.aether.macro.fishing;

import dev.aether.macro.AbstractMacro;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

// the manager tells every fishing macro where home is, and can make it let go of its keys without stopping it
abstract class AbstractFishingMacro extends AbstractMacro {
    private BlockPos home;

    final void setHome(BlockPos home) {
        this.home = home;
    }

    // with no route to end on, the macro fishes wherever it was started
    protected final BlockPos home(Minecraft mc) {
        return home != null ? home : mc.player.blockPosition();
    }

    public abstract void releaseAll(Minecraft mc);

    void onChat(String plain) {
    }
}
