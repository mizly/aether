package dev.aether.ui;

import dev.aether.macro.ReconnectScheduler;
import dev.aether.util.AetherLang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class FunnyDynamicRestScreen extends DisconnectedScreen {
    private final long restEndTimeMs;
    private long lastRemainingSeconds;

    public FunnyDynamicRestScreen(long restEndTimeMs) {
        super(
                new CancelDynamicRestParentScreen(),
                Component.literal(AetherLang.localize("Failed to connect to the server")),
                buildReason(restEndTimeMs),
                Component.literal(AetherLang.localize("Back to Server List")));
        this.restEndTimeMs = restEndTimeMs;
        this.lastRemainingSeconds = remainingSeconds(restEndTimeMs);
    }

    @Override
    public void tick() {
        long remainingSeconds = remainingSeconds(restEndTimeMs);
        if (remainingSeconds != lastRemainingSeconds && minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(new FunnyDynamicRestScreen(restEndTimeMs));
        }
    }

    private static Component buildReason(long restEndTimeMs) {
        return Component.empty()
                .append(Component.literal(AetherLang.localize("You are temporarily banned for ")).withStyle(ChatFormatting.RED))
                .append(Component.literal(formatRemainingDuration(restEndTimeMs)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(AetherLang.localize(" from this server!")).withStyle(ChatFormatting.RED))
                .append("\n\n")
                .append(Component.literal(AetherLang.localize("Reason: ")).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(AetherLang.localize("Cheating through the use of unfair game advantages.")).withStyle(ChatFormatting.WHITE))
                .append("\n")
                .append(Component.literal(AetherLang.localize("Find out more: ")).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(AetherLang.localize("https://www.hypixel.net/appeal"))
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE))
                .append("\n\n")
                .append(Component.literal(AetherLang.localize("Ban ID: ")).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(AetherLang.localize("#AETHERSB")).withStyle(ChatFormatting.WHITE))
                .append("\n")
                .append(Component.literal(AetherLang.localize("Sharing your Ban ID may affect the processing of your appeal!"))
                        .withStyle(ChatFormatting.GRAY));
    }

    private static long remainingSeconds(long restEndTimeMs) {
        return Math.max(0L, (restEndTimeMs - System.currentTimeMillis() + 999L) / 1000L);
    }

    private static String formatRemainingDuration(long restEndTimeMs) {
        long seconds = remainingSeconds(restEndTimeMs);
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder builder = new StringBuilder();
        if (days > 0L) {
            builder.append(days).append('d').append(' ');
        }
        if (hours > 0L || days > 0L) {
            builder.append(hours).append('h').append(' ');
        }
        if (minutes > 0L || hours > 0L || days > 0L) {
            builder.append(minutes).append('m').append(' ');
        }
        builder.append(seconds).append('s');
        return builder.toString();
    }

    private static final class CancelDynamicRestParentScreen extends Screen {
        private CancelDynamicRestParentScreen() {
            super(Component.empty());
        }

        @Override
        protected void init() {
            ReconnectScheduler.cancel();
            if (minecraft != null) {
                minecraft.setScreen(new TitleScreen());
            }
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }
    }
}
