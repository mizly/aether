package dev.aether.macro;

import dev.aether.config.ConfigHelpers;
import dev.aether.config.UnflyMode;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;
import dev.aether.util.ProgrammaticMovementTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Keeps macro trackers and raw key state in sync. */
public final class MacroInput {

    private MacroInput() {
    }

    public static void set(KeyMapping mapping, boolean held) {
        ProgrammaticMovementTracker.set(mapping, held);
        ClientUtils.setKeyMappingState(mapping, held);
    }

    public static void setAttack(KeyMapping attackKey, boolean held) {
        ProgrammaticAttackTracker.setHeld(attackKey, held);
        ClientUtils.setKeyMappingState(attackKey, held);
    }

    public static void unfly(Minecraft mc, int tick) {
        boolean doubleTap = ConfigHelpers.getUnflyMode() == UnflyMode.DOUBLE_TAP_SPACE;
        int phase = Math.floorMod(tick, 10);
        set(mc.options.keyShift, !doubleTap);
        set(mc.options.keyJump, doubleTap && (phase < 2 || phase >= 5 && phase < 7));
    }

    public static void releaseMovement(Minecraft mc) {
        var opts = mc.options;
        ProgrammaticMovementTracker.set(opts.keyLeft, false);
        ProgrammaticMovementTracker.set(opts.keyRight, false);
        ProgrammaticMovementTracker.set(opts.keyUp, false);
        ProgrammaticMovementTracker.set(opts.keyDown, false);
        ProgrammaticMovementTracker.set(opts.keyShift, false);
        ProgrammaticMovementTracker.set(opts.keySprint, false);
        ProgrammaticMovementTracker.set(opts.keyJump, false);
        ClientUtils.forceReleaseMovementKeys();
    }
}
