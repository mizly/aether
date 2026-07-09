package dev.aether.renderer;

import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

/** Single source of truth for which screens show the animated background. */
public final class AetherBackgroundScreens {
    private AetherBackgroundScreens() {}

    public static boolean matches(Screen screen) {
        return screen instanceof TitleScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof JoinMultiplayerScreen
                || screen instanceof OptionsScreen
                || screen instanceof CreditsAndAttributionScreen;
    }
}
