package dev.aether.modules.visuals

import com.mojang.blaze3d.platform.Window
import dev.aether.config.AetherConfig
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowAlwaysOnTop
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowBordered
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_BORDERLESS
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_UTILITY

class PipManager private constructor() {
    companion object {
        private var appliedState: WindowState? = null
        private var previousAttributes: WindowAttributes? = null
        private var enabled = false

        @JvmStatic
        fun isEnabled(): Boolean {
            return enabled
        }

        @JvmStatic
        fun toggle() {
            setEnabled(!enabled)
        }

        @JvmStatic
        fun setEnabled(shouldEnable: Boolean) {
            val client = Minecraft.getInstance()
            if (client == null) {
                enabled = false
                appliedState = null
                previousAttributes = null
                return
            }
            if (!client.isSameThread()) {
                client.execute { setEnabled(shouldEnable) }
                return
            }

            if (enabled == shouldEnable) {
                if (enabled) {
                    applyPipMode(client)
                }
                return
            }

            enabled = shouldEnable
            if (enabled) {
                applyPipMode(client)
            } else {
                restoreWindowMode(client)
            }

            ClientUtils.sendMessage(if (enabled) "§aPiP mode enabled." else "§cPiP mode disabled.")
        }

        @JvmStatic
        fun render() {
            val client = Minecraft.getInstance()
            if (enabled && client != null && client.isSameThread()) {
                applyPipMode(client)
            }
        }

        private fun applyPipMode(client: Minecraft) {
            val window = client.window
            if (window == null) {
                return
            }

            val handle = window.handle()
            if (previousAttributes == null) {
                previousAttributes = WindowAttributes.capture(handle)
            }

            val targetState = WindowState.pip()
            if (targetState == appliedState) {
                return
            }

            window.setWindowed(targetState.width, targetState.height)
            SDL_SetWindowAlwaysOnTop(handle, targetState.floating)
            SDL_SetWindowBordered(handle, targetState.decorated)
            appliedState = targetState
        }

        private fun restoreWindowMode(client: Minecraft) {
            val window = client.window
            if (window != null) {
                val attributes = previousAttributes ?: WindowAttributes.defaultWindowed()
                attributes.apply(window.handle())
            }

            appliedState = null
            previousAttributes = null
        }

        private class WindowAttributes(
            @JvmField val floating: Boolean,
            @JvmField val decorated: Boolean
        ) {
            fun apply(handle: Long) {
                SDL_SetWindowAlwaysOnTop(handle, floating)
                SDL_SetWindowBordered(handle, decorated)
            }

            companion object {
                fun capture(handle: Long): WindowAttributes {
                    val flags = SDL_GetWindowFlags(handle)
                    return WindowAttributes(
                        (flags and SDL_WINDOW_UTILITY) != 0L,
                        (flags and SDL_WINDOW_BORDERLESS) == 0L
                    )
                }

                fun defaultWindowed(): WindowAttributes {
                    return WindowAttributes(false, true)
                }
            }
        }

        private class WindowState(
            @JvmField val width: Int,
            @JvmField val height: Int,
            @JvmField val floating: Boolean,
            @JvmField val decorated: Boolean
        ) {
            override fun equals(other: Any?): Boolean {
                return other is WindowState && width == other.width && height == other.height &&
                    floating == other.floating && decorated == other.decorated
            }

            override fun hashCode(): Int {
                return java.util.Objects.hash(width, height, floating, decorated)
            }

            companion object {
                fun pip(): WindowState {
                    return WindowState(
                        AetherConfig.PIP_WINDOW_WIDTH.get(),
                        AetherConfig.PIP_WINDOW_HEIGHT.get(),
                        AetherConfig.PIP_START_FLOATING.get(),
                        AetherConfig.PIP_START_DECORATED.get()
                    )
                }
            }
        }
    }
}
