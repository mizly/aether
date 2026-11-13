package dev.aether.renderer

import net.minecraft.client.gui.screens.CreditsAndAttributionScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen

class AetherBackgroundScreens private constructor() {
    companion object {
        @JvmStatic fun matches(screen: Screen): Boolean = screen is TitleScreen || screen is SelectWorldScreen || screen is JoinMultiplayerScreen || screen is OptionsScreen || screen is CreditsAndAttributionScreen
    }
}
