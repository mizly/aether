package dev.aether.ui

import dev.aether.macro.ReconnectScheduler
import dev.aether.telemetry.ban.AetherBanService
import dev.aether.util.AetherLang
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component

class FunnyDynamicRestScreen : DisconnectedScreen {
    private val restEndTimeMs: Long
    private var lastRemainingSeconds: Long

    constructor(restEndTimeMs: Long) : this(restEndTimeMs, buildReason(restEndTimeMs))

    private constructor(restEndTimeMs: Long, reason: Component) : super(CancelDynamicRestParentScreen(), Component.literal(AetherLang.localize("Failed to connect to the server")), reason, Component.literal(AetherLang.localize("Back to Server List"))) {
        this.restEndTimeMs = restEndTimeMs
        this.lastRemainingSeconds = remainingSeconds(restEndTimeMs)
        AetherBanService.observeSimulatedBanScreen(reason.getString(), restEndTimeMs)
    }

    override fun tick() {
        val remainingSeconds = remainingSeconds(restEndTimeMs)
        if (remainingSeconds != lastRemainingSeconds && minecraft != null && minecraft.gui.screen() == this) {
            minecraft.gui.setScreen(FunnyDynamicRestScreen(restEndTimeMs))
        }
    }

    companion object {
        private fun buildReason(restEndTimeMs: Long): Component = Component.empty()
            .append(Component.literal(AetherLang.localize("You are temporarily banned for ")).withStyle(ChatFormatting.RED))
            .append(Component.literal(formatRemainingDuration(restEndTimeMs)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(AetherLang.localize(" from this server!")).withStyle(ChatFormatting.RED))
            .append("\n\n")
            .append(Component.literal(AetherLang.localize("Reason: ")).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(AetherLang.localize("Cheating through the use of unfair game advantages.")).withStyle(ChatFormatting.WHITE))
            .append("\n")
            .append(Component.literal(AetherLang.localize("Find out more: ")).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(AetherLang.localize("https://www.hypixel.net/appeal")).withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE))
            .append("\n\n")
            .append(Component.literal(AetherLang.localize("Ban ID: ")).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(AetherLang.localize("#AETHERSB")).withStyle(ChatFormatting.WHITE))
            .append("\n")
            .append(Component.literal(AetherLang.localize("Sharing your Ban ID may affect the processing of your appeal!")).withStyle(ChatFormatting.GRAY))

        private fun remainingSeconds(restEndTimeMs: Long): Long = Math.max(0L, (restEndTimeMs - System.currentTimeMillis() + 999L) / 1000L)

        private fun formatRemainingDuration(restEndTimeMs: Long): String {
            var seconds = remainingSeconds(restEndTimeMs)
            val days = seconds / 86400L
            seconds %= 86400L
            val hours = seconds / 3600L
            seconds %= 3600L
            val minutes = seconds / 60L
            seconds %= 60L
            val builder = StringBuilder()
            if (days > 0L) builder.append(days).append('d').append(' ')
            if (hours > 0L || days > 0L) builder.append(hours).append('h').append(' ')
            if (minutes > 0L || hours > 0L || days > 0L) builder.append(minutes).append('m').append(' ')
            builder.append(seconds).append('s')
            return builder.toString()
        }
    }

    private class CancelDynamicRestParentScreen : Screen(Component.empty()) {
        override fun init() {
            ReconnectScheduler.cancel()
            if (minecraft != null) minecraft.gui.setScreen(TitleScreen())
        }

        override fun shouldCloseOnEsc(): Boolean = false
    }
}
