package dev.aether.modules.irc;

import dev.aether.Aether;
import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.telemetry.AetherApiClient;
import dev.aether.telemetry.AetherApiException;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.telemetry.AetherTokenStore;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class IrcManager {
    private static final String CHAT_PREFIX = "§8[§caether §7#irc§8] ";
    private static final int POLL_WAIT_SECONDS = 25;
    private static final long RETRY_DELAY_SECONDS = 15L;
    private static final long BACKOFF_MAX_SECONDS = 120L;

    private static final Object LOCK = new Object();
    // two threads so an outgoing message never queues behind the 25s long poll.
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "Aether IRC");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean running;
    private static volatile int generation;
    private static volatile String cursor = "";
    private static long backoffSeconds = RETRY_DELAY_SECONDS;

    private IrcManager() {
    }

    public static boolean isEnabled() {
        return AetherConfig.IRC_ENABLED.get();
    }

    public static void initialize() {
        syncFromConfig();
    }

    public static void syncFromConfig() {
        if (isEnabled()) {
            start();
        } else {
            stop();
        }
    }

    public static void setEnabled(boolean enabled) {
        AetherConfig.IRC_ENABLED.set(enabled);
        AetherConfig.save();
        syncFromConfig();
    }

    public static void shutdown() {
        stop();
    }

    public static void sendChat(String message) {
        if (message == null || message.isBlank()) {
            ClientUtils.sendMessage("§cUsage: /aether irc chat <message>", false);
            return;
        }

        if (!AetherAuthService.isAuthenticated()) {
            ClientUtils.sendMessage("§clink your account dude", false);
            return;
        }

        if (!isEnabled()) {
            ClientUtils.sendMessage("§eirc is off turn it on", false);
            return;
        }

        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            ClientUtils.sendMessage("§clink your account dude", false);
            return;
        }

        submit(() -> {
            try {
                AetherApiClient.sendIrc(token, message);
            } catch (AetherApiException e) {
                reportSendFailure(e);
            }
        });
    }

    private static void reportSendFailure(AetherApiException e) {
        if (e.isUnauthorized()) {
            ClientUtils.sendMessage("§clogin expired pls relink", false);
            return;
        }
        if (e.statusCode() == 422) {
            ClientUtils.sendMessage("§ewatch your language", false);
            return;
        }
        if (e.statusCode() == 429) {
            ClientUtils.sendMessage("§eslow down buddy", false);
            return;
        }
        if (e.statusCode() == 403) {
            ClientUtils.sendMessage("§cur banned", false);
            return;
        }
        ClientUtils.sendMessage("§cwhoops cant deliver ur msg", false);
        Aether.LOGGER.warn("[aether] Aether IRC send failed: {}", e.getMessage());
    }

    private static void start() {
        int gen;
        synchronized (LOCK) {
            if (running) {
                return;
            }
            running = true;
            backoffSeconds = RETRY_DELAY_SECONDS;
            gen = ++generation;
        }
        // an empty cursor makes the first poll return the head id only, so joining never replays backlog.
        cursor = "";
        submit(() -> poll(gen));
    }

    private static void stop() {
        synchronized (LOCK) {
            if (!running) {
                return;
            }
            running = false;
            generation++;
        }
        cursor = "";
    }

    private static void poll(int gen) {
        if (gen != generation || !running) {
            return;
        }

        if (!AetherAuthService.isAuthenticated()) {
            reschedule(gen, RETRY_DELAY_SECONDS);
            return;
        }

        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            reschedule(gen, RETRY_DELAY_SECONDS);
            return;
        }

        try {
            AetherApiClient.IrcPoll result = AetherApiClient.pollIrc(token, cursor, POLL_WAIT_SECONDS);
            if (gen != generation || !running) {
                return;
            }

            boolean hadCursor = !cursor.isEmpty();
            cursor = result.latestId();
            backoffSeconds = RETRY_DELAY_SECONDS;

            if (hadCursor) {
                display(result.messages());
            }
            submit(() -> poll(gen));
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                // the auth service demotes the token on its own /me check; just idle until it does.
                reschedule(gen, RETRY_DELAY_SECONDS);
                return;
            }
            Aether.LOGGER.debug("[aether] Aether IRC poll failed: {}", e.getMessage());
            long delay = backoffSeconds;
            backoffSeconds = Math.min(backoffSeconds * 2L, BACKOFF_MAX_SECONDS);
            reschedule(gen, delay);
        }
    }

    private static void display(List<AetherApiClient.IrcMessage> messages) {
        if (messages.isEmpty() || StreamerModeManager.isEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            for (AetherApiClient.IrcMessage message : messages) {
                client.player.sendSystemMessage(Component.literal(
                        CHAT_PREFIX + "§b" + message.author() + "§7: §f" + message.content()));
            }
        });
    }

    private static void reschedule(int gen, long delaySeconds) {
        try {
            SCHEDULER.schedule(() -> poll(gen), delaySeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            Aether.LOGGER.debug("[aether] Aether IRC poll rejected: {}", e.getClass().getSimpleName());
        }
    }

    private static void submit(Runnable task) {
        try {
            SCHEDULER.execute(task);
        } catch (RuntimeException e) {
            Aether.LOGGER.debug("[aether] Aether IRC task rejected: {}", e.getClass().getSimpleName());
        }
    }
}
