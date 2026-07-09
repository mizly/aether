package dev.aether.mixin;

import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Inventory.class)
public interface AccessorInventory {
    @Accessor("selected")
    void setSelected(int selected);

    @Accessor("selected")
    int getSelected();
}
