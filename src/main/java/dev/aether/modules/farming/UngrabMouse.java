package dev.aether.modules.farming;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

// releases the cursor while the macro farms, and sets the flag MixinMouseHandler watches so the game cannot re-grab it
public final class UngrabMouse {
    private UngrabMouse() {}

    private static volatile boolean mouseUngrabbed = false;
    private static volatile boolean macroRequested = false;
    private static volatile boolean visualRequested = false;
    private static volatile boolean freecamSuspend = false;
    private static volatile boolean freelookSuspend = false;
    // remembers whether ungrab was active before suspension, or was asked for during it
    private static volatile boolean restoreOnResume = false;

    private static boolean isSuspended() {
        return freecamSuspend || freelookSuspend;
    }

    public static boolean isMouseUngrabbed() {
        Minecraft mc = Minecraft.getInstance();
        return mouseUngrabbed
                && !isSuspended()
                && mc.player != null
                && mc.level != null
                && mc.screen == null;
    }

    public static boolean isVisualUngrabEnabled() {
        return visualRequested;
    }

    // main client thread only, or via mc.execute
    public static void ungrabMouse() {
        requestMacroUngrab();
    }

    // main client thread only, or via mc.execute
    public static void regrabMouse() {
        clearMacroUngrab();
    }

    public static void requestMacroUngrab() {
        macroRequested = true;
        syncRequestedState();
    }

    public static void clearMacroUngrab() {
        macroRequested = false;
        syncRequestedState();
    }

    public static void requestVisualUngrab() {
        visualRequested = true;
        syncRequestedState();
    }

    public static void clearVisualUngrab() {
        visualRequested = false;
        syncRequestedState();
    }

    public static void suspendForFreecam() {
        suspend(true);
    }

    public static void resumeAfterFreecam() {
        resume(true);
    }

    public static void suspendForFreelook() {
        suspend(false);
    }

    public static void resumeAfterFreelook() {
        resume(false);
    }

    private static void suspend(boolean freecam) {
        boolean wasSuspended = isSuspended();
        if (freecam) {
            freecamSuspend = true;
        } else {
            freelookSuspend = true;
        }
        if (wasSuspended) {
            // Already grabbed by the other overlay; nothing more to change.
            return;
        }
        restoreOnResume = mouseUngrabbed || isUngrabRequested() || restoreOnResume;
        boolean wasUngrabbed = mouseUngrabbed;
        mouseUngrabbed = false;
        if (!wasUngrabbed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!mc.mouseHandler.isMouseGrabbed() && mc.screen == null) {
                mc.mouseHandler.grabMouse();
                ClientUtils.reapplyProgrammaticKeyStates(mc);
            }
        });
    }

    private static void resume(boolean freecam) {
        if (freecam) {
            freecamSuspend = false;
        } else {
            freelookSuspend = false;
        }
        if (isSuspended()) {
            // The other overlay still needs the cursor grabbed.
            return;
        }
        boolean shouldRestore = restoreOnResume;
        restoreOnResume = false;
        if (shouldRestore) {
            syncRequestedState();
        }
    }

    private static void syncRequestedState() {
        boolean shouldUngrab = isUngrabRequested();
        if (isSuspended()) {
            restoreOnResume = shouldUngrab;
            return;
        }
        if (mouseUngrabbed == shouldUngrab) {
            return;
        }

        mouseUngrabbed = shouldUngrab;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (shouldUngrab) {
                if (mc.mouseHandler.isMouseGrabbed()) {
                    mc.mouseHandler.releaseMouse();
                }
                return;
            }

            if (!mc.mouseHandler.isMouseGrabbed() && mc.screen == null) {
                mc.mouseHandler.grabMouse();
                ClientUtils.reapplyProgrammaticKeyStates(mc);
            }
        });
    }

    private static boolean isUngrabRequested() {
        return macroRequested || visualRequested;
    }
}
