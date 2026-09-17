package dev.aether.renderer;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.StreamerModeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

public final class AetherCape {
    public static final ClientAsset.Texture TEXTURE = new ClientAsset.ResourceTexture(
            Identifier.fromNamespaceAndPath("aether", "cosmetic/aether_cape"));

    private AetherCape() {}

    public static ClientAsset.Texture textureFor(int playerId, ClientAsset.Texture original) {
        var player = Minecraft.getInstance().player;
        return player != null && player.getId() == playerId && AetherConfig.CAPE_ENABLED.get()
                && !StreamerModeManager.isEnabled() ? TEXTURE : original;
    }
}
