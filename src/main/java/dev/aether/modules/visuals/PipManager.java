package dev.aether.modules.visuals;

import com.mojang.blaze3d.platform.Window;
import dev.aether.config.AetherConfig;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import static org.lwjgl.glfw.GLFW.GLFW_DECORATED;
import static org.lwjgl.glfw.GLFW.GLFW_FLOATING;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwSetWindowAttrib;

public final class PipManager {
    private static WindowState appliedState;
    private static WindowAttributes previousAttributes;
    private static boolean enabled;

    private PipManager() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft client) {
        setEnabled(client, !enabled);
    }

    public static void setEnabled(boolean shouldEnable) {
        setEnabled(Minecraft.getInstance(), shouldEnable);
    }

    public static void setEnabled(Minecraft client, boolean shouldEnable) {
        if (shouldEnable && StreamerModeManager.isEnabled()) {
            return;
        }
        if (client == null) {
            enabled = false;
            appliedState = null;
            previousAttributes = null;
            return;
        }
        if (!client.isSameThread()) {
            client.execute(() -> setEnabled(client, shouldEnable));
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

    public static void render(Minecraft client) {
        if (StreamerModeManager.isEnabled()) {
            if (enabled) {
                setEnabled(client, false);
            }
            return;
        }
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
        glfwSetWindowAttrib(handle, GLFW_FLOATING, targetState.floating ? GLFW_TRUE : GLFW_FALSE);
        glfwSetWindowAttrib(handle, GLFW_DECORATED, targetState.decorated ? GLFW_TRUE : GLFW_FALSE);
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
                    glfwGetWindowAttrib(handle, GLFW_FLOATING) == GLFW_TRUE,
                    glfwGetWindowAttrib(handle, GLFW_DECORATED) == GLFW_TRUE
            );
        }

        private static WindowAttributes defaultWindowed() {
            return new WindowAttributes(false, true);
        }

        private void apply(long handle) {
            glfwSetWindowAttrib(handle, GLFW_FLOATING, floating ? GLFW_TRUE : GLFW_FALSE);
            glfwSetWindowAttrib(handle, GLFW_DECORATED, decorated ? GLFW_TRUE : GLFW_FALSE);
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
