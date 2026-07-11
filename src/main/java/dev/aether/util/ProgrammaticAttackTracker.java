package dev.aether.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public final class ProgrammaticAttackTracker {
    private static volatile boolean attackHeld = false;

    private ProgrammaticAttackTracker() {
    }

    public static void setHeld(KeyMapping mapping, boolean held) {
        attackHeld = held && mapping != null;
    }

    public static boolean isHeld() {
        return attackHeld;
    }

    public static boolean shouldTreatMouseAsGrabbed(Minecraft client) {
        return attackHeld
                && client != null
                && client.options != null
                && client.screen == null
                && client.options.keyAttack.isDown();
    }
}
