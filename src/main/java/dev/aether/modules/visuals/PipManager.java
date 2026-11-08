package dev.aether.modules.visuals;

import com.mojang.blaze3d.platform.Window;
import dev.aether.config.AetherConfig;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import org.lwjgl.sdl.SDLVideo;

public final class PipManager {
    private static WindowState appliedState;
    private static WindowAttributes previousAttributes;
    private static boolean enabled;

    private PipManager() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static void setEnabled(boolean shouldEnable) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            enabled = false;
            appliedState = null;
            previousAttributes = null;
            return;
        }
        if (!client.isSameThread()) {
            client.execute(() -> setEnabled(shouldEnable));
            return;
        }

        if (enabled == shouldEnable) {
            if (enabled) {
                applyPipMode(client);
            }
            return;
        }

        enabled = shouldEnable;
        if (enabled) {
            applyPipMode(client);
        } else {
            restoreWindowMode(client);
        }

        ClientUtils.sendMessage(enabled ? "§aPiP mode enabled." : "§cPiP mode disabled.");
    }

    public static void render() {
        Minecraft client = Minecraft.getInstance();
        if (enabled && client != null && client.isSameThread()) {
            applyPipMode(client);
        }
    }

    private static void applyPipMode(Minecraft client) {
        Window window = client.getWindow();
        if (window == null) {
            return;
        }

        long handle = window.handle();
        if (previousAttributes == null) {
            previousAttributes = WindowAttributes.capture(handle);
        }

        WindowState targetState = WindowState.pip();
        if (targetState.equals(appliedState)) {
            return;
        }

        window.setWindowed(targetState.width, targetState.height);
        SDLVideo.SDL_SetWindowAlwaysOnTop(handle, targetState.floating);
        SDLVideo.SDL_SetWindowBordered(handle, targetState.decorated);
        appliedState = targetState;
    }

    private static void restoreWindowMode(Minecraft client) {
        Window window = client.getWindow();
        if (window != null) {
            WindowAttributes attributes = previousAttributes != null
                    ? previousAttributes
                    : WindowAttributes.defaultWindowed();
            attributes.apply(window.handle());
        }

        appliedState = null;
        previousAttributes = null;
    }

    private record WindowAttributes(
            boolean floating,
            boolean decorated
    ) {
        private static WindowAttributes capture(long handle) {
            return new WindowAttributes(
                    (SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_ALWAYS_ON_TOP) != 0,
                    (SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_BORDERLESS) == 0
            );
        }

        private static WindowAttributes defaultWindowed() {
            return new WindowAttributes(false, true);
        }

        private void apply(long handle) {
            SDLVideo.SDL_SetWindowAlwaysOnTop(handle, floating);
            SDLVideo.SDL_SetWindowBordered(handle, decorated);
        }
    }

    private record WindowState(
            int width,
            int height,
            boolean floating,
            boolean decorated
    ) {
        private static WindowState pip() {
            return new WindowState(
                    AetherConfig.PIP_WINDOW_WIDTH.get(),
                    AetherConfig.PIP_WINDOW_HEIGHT.get(),
                    AetherConfig.PIP_START_FLOATING.get(),
                    AetherConfig.PIP_START_DECORATED.get()
            );
        }
    }
}
