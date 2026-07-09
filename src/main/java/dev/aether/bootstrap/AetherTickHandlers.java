package dev.aether.bootstrap;

import dev.aether.modules.visuals.FreecamManager;
import dev.aether.modules.visuals.FreelookManager;
import dev.aether.modules.movement.MovementPlaybackManager;

public final class AetherTickHandlers {
    private AetherTickHandlers() {
    }

    public static void register() {
        AetherKeybindHandler.register();
        AetherReconnectTickHandler.register();
        AetherUpdateTickHandler.register();
        AetherWorldChangeTickHandler.register();
        AetherAutomationTickHandler.register();
        MovementPlaybackManager.register();
        FreecamManager.register();
        FreelookManager.register();
    }

    public static void setPickingUpStash(boolean pickingUpStash) {
        AetherAutomationTickHandler.setPickingUpStash(pickingUpStash);
    }
}
