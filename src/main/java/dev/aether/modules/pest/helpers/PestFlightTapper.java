package dev.aether.modules.pest.helpers;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

/**
 * Tick-counted jump double-tap for entering flight. Vanilla's toggle window is
 * 7 client ticks, so counting ticks stays valid when the client tick rate dips.
 */
final class PestFlightTapper {
    /** Phase 0 releases first, so a jump already held on entry still yields a rising edge. */
    private static final int CYCLE_TICKS = 8;
    private static final int FIRST_PRESS_PHASE = 1;
    private static final int SECOND_PRESS_PHASE = 3;

    static final int TIMEOUT_TICKS = 60;

    private PestFlightTapper() {
    }

    /** Drives one client tick of the repeating double-tap using a caller-owned counter. */
    static void tick(Minecraft client, int tickCount) {
        if (client.options == null) {
            return;
        }
        int phase = Math.floorMod(tickCount, CYCLE_TICKS);
        boolean down = phase == FIRST_PRESS_PHASE || phase == SECOND_PRESS_PHASE;
        ClientUtils.setKeyMappingState(client.options.keyJump, down);
    }

    static void release(Minecraft client) {
        if (client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
        }
    }
}
