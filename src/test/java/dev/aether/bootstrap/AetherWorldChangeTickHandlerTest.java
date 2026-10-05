package dev.aether.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AetherWorldChangeTickHandlerTest {

    @Test
    void theAlertSaysTheMacroStoppedWhenFishingCannotRestart() {
        assertEquals("Restart route started.", AetherWorldChangeTickHandler.worldChangeActionDone(true, null));
        assertEquals("Macro stopped.", AetherWorldChangeTickHandler.worldChangeActionDone(true,
                "Restart route has no warp, macro stopped."));
        assertEquals("Macro stopped and world change recovery started.",
                AetherWorldChangeTickHandler.worldChangeActionDone(false, null));
    }
}
