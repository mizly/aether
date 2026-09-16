package dev.aether.macro.fishing;

import dev.aether.config.ConfigHelpers;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

// tick() has to be wired to END_CLIENT_TICK
public final class FishingMacroManager {

    private static final int START_DELAY_MIN_TICKS = 1;
    private static final int START_DELAY_MAX_TICKS = 10;

    private static StriderFishingMacro activeMacro;
    private static int pendingEnableTicks;

    private FishingMacroManager() {
    }

    // main client thread only
    public static void enable(Minecraft mc) {
        disable(mc);
        ClientUtils.forceReleaseKeys();
        activeMacro = new StriderFishingMacro();
        pendingEnableTicks = ConfigHelpers.getRandomizedDelay(START_DELAY_MIN_TICKS, START_DELAY_MAX_TICKS);
    }

    // main client thread only
    public static void disable(Minecraft mc) {
        if (activeMacro != null) {
            activeMacro.onDisable(mc);
            activeMacro = null;
            pendingEnableTicks = 0;
        }
    }

    public static boolean isActive() {
        return activeMacro != null;
    }

    public static StriderFishingMacro getActiveMacro() {
        return activeMacro;
    }

    // releases the macro's keys without disabling it
    public static void releaseInputs(Minecraft mc) {
        if (activeMacro != null && mc != null && mc.options != null) {
            activeMacro.releaseAll(mc);
        }
    }

    public static void tick(Minecraft mc) {
        if (activeMacro == null || mc.player == null) {
            return;
        }

        if (pendingEnableTicks > 0) {
            if (--pendingEnableTicks > 0) {
                return;
            }
            activeMacro.onEnable(mc);
            return;
        }

        activeMacro.onTick(mc);
    }
}
