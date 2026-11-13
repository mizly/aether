package dev.aether.modules.pest.helpers

import dev.aether.macro.MacroWorkerThread
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

internal class PestClientThread private constructor() {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 2_000L

        @JvmStatic fun run(client: Minecraft?, action: Runnable) {
            call(client, Supplier { action.run(); null }, null)
        }

        @JvmStatic fun <T> call(client: Minecraft?, action: Supplier<T?>, fallback: T?): T? {
            if (client == null) return fallback
            if (client.isSameThread) return action.get()

            val result = CompletableFuture<T?>()
            val guardedAction = MacroWorkerThread.getInstance().cancellable(Runnable { result.complete(action.get()) })
            client.execute(Runnable {
                try {
                    guardedAction.run()
                    result.complete(fallback)
                } catch (error: RuntimeException) {
                    result.completeExceptionally(error)
                }
            })
            try {
                return result.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return fallback
            } catch (error: Exception) {
                ClientUtils.sendDebugMessage("Pest client-thread operation failed: " + error.message)
                return fallback
            }
        }
    }
}
