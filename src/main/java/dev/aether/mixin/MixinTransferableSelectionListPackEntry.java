package dev.aether.mixin;

import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import net.minecraft.server.packs.repository.PackCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Force incompatible packs to render/select as compatible in the resource pack list,
// removing the warning styling and the "designed for a different version" confirm prompt.
@Mixin(TransferableSelectionList.PackEntry.class)
public class MixinTransferableSelectionListPackEntry {

    @Redirect(method = "extractContent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/repository/PackCompatibility;isCompatible()Z"))
    private boolean aether$forceCompatibleOnExtract(PackCompatibility compatibility) {
        return true;
    }

    @Redirect(method = "handlePackSelection", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/repository/PackCompatibility;isCompatible()Z"))
    private boolean aether$forceCompatibleOnSelect(PackCompatibility compatibility) {
        return true;
    }
}
