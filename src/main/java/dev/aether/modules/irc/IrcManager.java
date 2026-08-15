package dev.aether.modules.irc;

import dev.aether.Aether;
import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.telemetry.AetherApiClient;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.telemetry.AetherTokenStore;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class IrcManager {
    private static final String CHAT_PREFIX = "§8[§caether §7#irc§8] ";

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "Aether IRC");
        thread.setDaemon(true);
        return thread;
    });

    private static final AetherIrcSocket SOCKET = new AetherIrcSocket(SCHEDULER, new SocketHandler());

    private static volatile boolean running;
    private static volatile boolean redirecting;

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
        SCHEDULER.shutdownNow();
    }

    public static void onAuthenticated() {
        syncFromConfig();
    }

    public static void onLoggedOut() {
        stop();
    }

    // rotation retires the old token, so the socket has to be rebuilt around the new one.
    public static void onTokenRotated() {
        synchronized (LOCK) {
            if (!running) {
                return;
            }
        }
        SOCKET.stop();
        SOCKET.start(AetherTokenStore.getToken());
    }

    public static boolean isConnected() {
        return SOCKET.isConnected();
    }

    public static boolean isRedirecting() {
        return redirecting;
    }

    public static void toggleRedirect() {
        if (redirecting) {
            redirecting = false;
            ClientUtils.sendMessage("§echat is back to normal", false);
            return;
        }

        if (!AetherAuthService.isAuthenticated() || AetherTokenStore.getToken().isEmpty()) {
            ClientUtils.sendMessage("§clink your account dude", false);
            return;
        }

        if (!isEnabled()) {
            ClientUtils.sendMessage("§eirc is off turn it on", false);
            return;
        }

        redirecting = true;
        ClientUtils.sendMessage("§aall chat goes to irc now - §f/aether irc§a to stop", false);
    }

    /** Returns true when the message was taken over, so the caller cancels the outgoing chat. */
    public static boolean interceptChat(String message) {
        if (!redirecting) {
            return false;
        }

        // never swallow a message that cannot be delivered; fall back to normal chat instead.
        if (!isEnabled() || !AetherAuthService.isAuthenticated()) {
            redirecting = false;
            ClientUtils.sendMessage("§echat is back to normal", false);
            return false;
        }

        sendChat(message);
        return true;
    }

    public static void sendChat(String message) {
        if (message == null || message.isBlank()) {
            ClientUtils.sendMessage("§cUsage: /aether irc chat <message>", false);
            return;
        }

        if (!AetherAuthService.isAuthenticated() || AetherTokenStore.getToken().isEmpty()) {
            ClientUtils.sendMessage("§clink your account dude", false);
            return;
        }

        if (!isEnabled()) {
            ClientUtils.sendMessage("§eirc is off turn it on", false);
            return;
        }

        if (!SOCKET.send(message)) {
            ClientUtils.sendMessage("§ereconnecting - try that again in a sec", false);
        }
    }

    private static void start() {
        synchronized (LOCK) {
            // stay fully dormant while unlinked; onAuthenticated() starts the socket later.
            if (running || !AetherAuthService.isAuthenticated()) {
                return;
            }
            running = true;
        }
        SOCKET.start(AetherTokenStore.getToken());
    }

    private static void stop() {
        redirecting = false;
        synchronized (LOCK) {
            if (!running) {
                return;
            }
            running = false;
        }
        SOCKET.stop();
    }

    // the backend strips these too, but the client must not let relayed text restyle its own chat line.
    private static String stripFormatting(String value) {
        return value == null ? "" : value.replace('§', ' ');
    }

    private static void display(AetherApiClient.IrcMessage message) {
        if (StreamerModeManager.isEnabled()) {
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
            client.player.sendSystemMessage(Component.literal(
                    CHAT_PREFIX + "§b" + stripFormatting(message.author())
                            + "§7: §f" + stripFormatting(message.content())));
        });
    }

    private static final class SocketHandler implements AetherIrcSocket.Handler {
        @Override
        public void onMessage(AetherApiClient.IrcMessage message) {
            display(message);
        }

        @Override
        public void onConnected() {
            Aether.LOGGER.debug("[aether] Aether IRC connected");
        }

        @Override
        public void onUnauthorized() {
            stop();
            ClientUtils.sendMessage("§clogin expired pls relink", false);
        }

        @Override
        public void onRejected(String reason) {
            if ("too_many_connections".equals(reason)) {
                ClientUtils.sendMessage("§eirc is already open on another game", false);
            }
            stop();
        }

        @Override
        public void onSendFailed(String error, long retryAfterSeconds) {
            switch (error) {
                case "blocked_content" -> ClientUtils.sendMessage("§ewatch your language", false);
                case "rate_limited" -> ClientUtils.sendMessage("§eslow down buddy", false);
                case "empty_message" -> {
                }
                case "blocked" -> ClientUtils.sendMessage("§cur banned", false);
                default -> {
                    ClientUtils.sendMessage("§cwhoops cant deliver ur msg", false);
                    Aether.LOGGER.warn("[aether] Aether IRC send failed: {}", error);
                }
            }
        }
    }
}
