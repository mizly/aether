package dev.aether.bootstrap;

import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.experiments.ExperimentsManager;
import dev.aether.modules.visitor.VisitorManager;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

public final class AetherScreenHooks {
    private static String lastScannedVisitorTitle = null;

    private AetherScreenHooks() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen)) {
                return;
            }

            ScreenKeyboardEvents.afterKeyPress(screen).register((keyedScreen, keyEvent) -> {
                if (keyedScreen instanceof AbstractContainerScreen<?> containerScreen
                        && AetherKeybindRegistry.getExperimentClickKey().matches(keyEvent)) {
                    ExperimentsManager.onGuiKeybind(client, containerScreen);
                }
            });

            ScreenEvents.afterExtract(screen).register((renderedScreen, graphics, mouseX, mouseY, tickDelta) -> {
                if (!(renderedScreen instanceof AbstractContainerScreen containerScreen)) {
                    return;
                }

                String title = containerScreen.getTitle().getString().trim();
                if (title.equals(lastScannedVisitorTitle) || !MacroStateManager.isMacroRunning()) {
                    return;
                }

                if (containerScreen.getMenu().slots.size() <= 29) {
                    return;
                }

                Slot slot = containerScreen.getMenu().getSlot(29);
                if (slot == null || !slot.hasItem()) {
                    return;
                }

                String itemName = slot.getItem().getHoverName().getString();
                if (!itemName.contains("Accept Offer")) {
                    return;
                }

                lastScannedVisitorTitle = title;
                MacroWorkerThread.getInstance().submit("VisitorGui-Scan",
                        () -> VisitorManager.scanVisitorGui(Minecraft.getInstance(), containerScreen));
            });
        });
    }

}

