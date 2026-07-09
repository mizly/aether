package dev.aether.modules.farming;

import net.minecraft.client.Minecraft;

/**
 * Releases (un-grabs) the mouse cursor while the farming macro is active,
 * so the player can freely interact with other applications.
 *
 * <p>Works by calling {@link net.minecraft.client.MouseHandler#releaseMouse()}
 * and setting a flag that {@link dev.aether.mixin.MixinMouseHandler} watches to
 * prevent the game from re-grabbing the cursor automatically.
 */
public final class UngrabMouse {
    private UngrabMouse() {}

    /** {@code true} while the mouse is intentionally un-grabbed by the macro. */
    private static volatile boolean mouseUngrabbed = false;
    /** {@code true} while a macro wants the cursor released. */
    private static volatile boolean macroRequested = false;
    /** {@code true} while the visuals module wants the cursor released. */
    private static volatile boolean visualRequested = false;
    /** {@code true} while freecam temporarily owns cursor grab behavior. */
    private static volatile boolean freecamSuspend = false;
    /** {@code true} while freelook temporarily owns cursor grab behavior. */
    private static volatile boolean freelookSuspend = false;
    /** Remembers whether ungrab was active before suspension, or was requested during it. */
    private static volatile boolean restoreOnResume = false;

    /** {@code true} while any overlay (freecam / freelook) needs the cursor grabbed. */
    private static boolean isSuspended() {
        return freecamSuspend || freelookSuspend;
    }

    public static boolean isMouseUngrabbed() {
        return mouseUngrabbed && !isSuspended();
    }

    public static boolean isVisualUngrabEnabled() {
        return visualRequested;
    }

    /**
     * Releases the mouse cursor. Safe to call even when already released.
     * Must be called on the main client thread (or via {@code mc.execute(...)}).
     */
    public static void ungrabMouse() {
        requestMacroUngrab();
    }

    /**
     * Re-grabs the mouse cursor if it was previously un-grabbed by the macro.
     * Must be called on the main client thread (or via {@code mc.execute(...)}).
     */
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
            }
        });
    }

    private static boolean isUngrabRequested() {
        return macroRequested || visualRequested;
    }
}
