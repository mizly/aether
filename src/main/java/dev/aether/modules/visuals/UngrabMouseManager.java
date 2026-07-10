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

    public static void toggle() {
        setEnabled(!isEnabled());
    }

    public static void setEnabled(boolean shouldEnable) {
        Minecraft client = Minecraft.getInstance();
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
            client.execute(() -> setEnabled(shouldEnable));
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
