package dev.aether.modules.visuals;

import dev.aether.modules.farming.UngrabMouse;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

public final class UngrabMouseManager {
    private UngrabMouseManager() {
    }

    public static boolean isEnabled() {
        return UngrabMouse.isVisualUngrabEnabled();
    }

    public static void toggle(Minecraft client) {
        setEnabled(client, !isEnabled());
    }

    public static void setEnabled(boolean shouldEnable) {
        setEnabled(Minecraft.getInstance(), shouldEnable);
    }

    public static void setEnabled(Minecraft client, boolean shouldEnable) {
        if (shouldEnable && StreamerModeManager.isEnabled()) {
            return;
        }
        if (client == null) {
            if (shouldEnable) {
                UngrabMouse.requestVisualUngrab();
            } else {
                UngrabMouse.clearVisualUngrab();
            }
            return;
        }
        if (!client.isSameThread()) {
            client.execute(() -> setEnabled(client, shouldEnable));
            return;
        }
        if (isEnabled() == shouldEnable) {
            return;
        }

        if (shouldEnable) {
            UngrabMouse.requestVisualUngrab();
        } else {
            UngrabMouse.clearVisualUngrab();
        }

        ClientUtils.sendMessage(shouldEnable ? "§aUngrab mouse enabled." : "§cUngrab mouse disabled.");
    }
}
