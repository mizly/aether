package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

public final class UngrabMouseManager {
    private static volatile boolean restoreOnMacroStart = false;

    private UngrabMouseManager() {
    }

    public static boolean isEnabled() {
        return UngrabMouse.isVisualUngrabEnabled();
    }

    // Smart Ungrab Mouse: drop the cursor release while the macro is stopped so it
    // stays grabbed between runs, then put it back exactly as the user left it.
    public static void onMacroStopped() {
        if (!AetherConfig.SMART_UNGRAB_MOUSE.get() || !isEnabled()) {
            return;
        }
        restoreOnMacroStart = true;
        setEnabled(false);
    }

    public static void onMacroStarted() {
        if (!restoreOnMacroStart) {
            return;
        }
        restoreOnMacroStart = false;
        if (AetherConfig.SMART_UNGRAB_MOUSE.get()) {
            setEnabled(true);
        }
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
