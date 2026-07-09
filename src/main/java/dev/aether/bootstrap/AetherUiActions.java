package dev.aether.bootstrap;

import dev.aether.Aether;
import dev.aether.ui.MainGUI;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

public final class AetherUiActions {
    private AetherUiActions() {
    }

    public static void toggleMainGui(Minecraft client) {
        if (client.screen instanceof MainGUI) {
            client.setScreen(null);
        } else {
            openMainGui(client);
        }
    }

    public static void openMainGui(Minecraft client) {
        if (client == null) {
            return;
        }

        try {
            MainGUIRegistry.refresh();
            client.execute(() -> {
                try {
                    client.setScreen(new MainGUI());
                } catch (RuntimeException | LinkageError e) {
                    Aether.LOGGER.error("Failed to open Aether GUI from queued client task", e);
                    ClientUtils.sendMessage(client, "\u00A7cFailed to open the Aether GUI. Check the client log.", false);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            Aether.LOGGER.error("Failed to open Aether GUI", e);
            ClientUtils.sendMessage(client, "\u00A7cFailed to open the Aether GUI. Check the client log.", false);
        }
    }
}
