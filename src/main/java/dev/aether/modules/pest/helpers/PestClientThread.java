package dev.aether.modules.pest.helpers;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class PestClientThread {
    private static final long DEFAULT_TIMEOUT_MS = 2_000L;

    private PestClientThread() {
    }

    static void run(Minecraft client, Runnable action) {
        call(client, () -> {
            action.run();
            return null;
        }, null);
    }

    static <T> T call(Minecraft client, Supplier<T> action, T fallback) {
        if (client == null) {
            return fallback;
        }
        if (client.isSameThread()) {
            return action.get();
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable guardedAction = MacroWorkerThread.getInstance().cancellable(() -> result.complete(action.get()));
        client.execute(() -> {
            try {
                guardedAction.run();
                result.complete(fallback);
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        try {
            return result.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception error) {
            ClientUtils.sendDebugMessage("Pest client-thread operation failed: " + error.getMessage());
            return fallback;
        }
    }
}
