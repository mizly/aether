package dev.aether.renderer

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.StreamerModeManager
import net.minecraft.client.Minecraft
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier

class AetherCape private constructor() {
    companion object {
        @JvmField val TEXTURE: ClientAsset.Texture = ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("aether", "cosmetic/aether_cape"))
        @JvmStatic fun textureFor(playerId: Int, original: ClientAsset.Texture?): ClientAsset.Texture? {
            val player = Minecraft.getInstance().player
            return if (player != null && player.getId() == playerId && AetherConfig.CAPE_ENABLED.get() && !StreamerModeManager.isEnabled()) TEXTURE else original
        }
    }
}
