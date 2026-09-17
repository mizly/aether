package dev.aether.util;

import dev.aether.macro.MacroStateManager;
import dev.aether.mixin.AccessorMouseHandler;
import net.minecraft.client.Minecraft;

// drops the mouse delta that piles up while the window is unfocused
public final class RefocusInputGuard {
    private static boolean wasFocused = true;

    private RefocusInputGuard() {}

    public static void reset() {
        wasFocused = true;
    }

    public static void tick(Minecraft client) {
        if (client == null || client.getWindow() == null) return;

        boolean focused = client.getWindow().isFocused();
        boolean regained = focused && !wasFocused;
        wasFocused = focused;

        if (!regained || !MacroStateManager.isMacroRunning()) return;

        // Vanilla only discards the stale delta inside grabMouse, which the macro
        // suppresses while the cursor is ungrabbed - so it lands as a view snap.
        AccessorMouseHandler handler = (AccessorMouseHandler) client.mouseHandler;
        handler.aether$setAccumulatedDX(0.0);
        handler.aether$setAccumulatedDY(0.0);
        handler.aether$setIgnoreFirstMove(true);
    }
}
