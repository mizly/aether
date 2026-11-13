package dev.aether.util

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import dev.aether.mixin.AccessorWindow
import net.minecraft.client.Minecraft
import org.lwjgl.system.Platform
import java.awt.AWTException
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WindowFocusHelper private constructor() {
    companion object {
        private const val FOCUS_WAIT_MS = 500
        private const val WINDOW_ACTION_WAIT_MS = 1000
        private const val ALT_TAB_SETTLE_MS = 250
        private val FOCUS_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "Aether-WindowFocus").apply { isDaemon = true } }
        private val FOCUS_IN_PROGRESS = AtomicBoolean()

        @JvmStatic
        fun focusMinecraftWindow(debugPrefix: String) {
            val client = Minecraft.getInstance()
            if (client == null) { debug(null, debugPrefix, "client is null"); return }
            if (client.getWindow() == null) { debug(client, debugPrefix, "window is null"); return }
            client.execute { prepareFocusRequest(client, debugPrefix) }
        }

        private fun prepareFocusRequest(client: Minecraft, debugPrefix: String) {
            if (client.getWindow() == null) { debug(client, debugPrefix, "window became unavailable"); return }
            val glfwWindow = (client.getWindow() as AccessorWindow).getHandle()
            val nativeWindow = getNativeWindow(glfwWindow)
            debug(client, debugPrefix, "requested handle=$glfwWindow nativeHandle=$nativeWindow platform=${Platform.get()}")
            if (glfwWindow == 0L) { debug(client, debugPrefix, "GLFW handle is 0"); return }
            if (isWindowFocused(client, nativeWindow)) { debug(client, debugPrefix, "window already focused"); return }
            if (!FOCUS_IN_PROGRESS.compareAndSet(false, true)) { debug(client, debugPrefix, "focus request already in progress"); return }
            FOCUS_EXECUTOR.execute {
                try { focusMinecraftWindow(client, glfwWindow, nativeWindow, debugPrefix) }
                finally { FOCUS_IN_PROGRESS.set(false) }
            }
        }

        private fun focusMinecraftWindow(client: Minecraft, glfwWindow: Long, nativeWindow: Long, debugPrefix: String) {
            debug(client, debugPrefix, "focus worker started")
            var focused = false
            if (Platform.get() == Platform.WINDOWS) {
                focused = bringWindowToFrontUsingWinApi(client, nativeWindow, debugPrefix)
                debug(client, debugPrefix, "WinAPI result=$focused")
                if (!focused) { focused = bringWindowToFrontUsingWinApiAltUnlock(client, nativeWindow, debugPrefix); debug(client, debugPrefix, "WinAPI alt-unlock result=$focused") }
                if (!focused) { focused = bringWindowToFrontUsingThreadInput(client, nativeWindow, debugPrefix); debug(client, debugPrefix, "WinAPI thread-input result=$focused") }
            } else debug(client, debugPrefix, "skipping WinAPI on platform=${Platform.get()}")
            if (!focused) { debug(client, debugPrefix, "trying GLFW focus fallback"); focused = bringWindowToFrontUsingGlfw(client, glfwWindow, nativeWindow, debugPrefix); debug(client, debugPrefix, "GLFW result=$focused") }
            if (!focused) { debug(client, debugPrefix, "trying Robot alt-tab fallback"); focused = bringWindowToFrontUsingRobot(client, nativeWindow, debugPrefix); debug(client, debugPrefix, "Robot result=$focused") }
            if (!focused) ClientUtils.sendDebugMessage("$debugPrefix: window focus failed") else debug(client, debugPrefix, "window focused")
        }

        private fun bringWindowToFrontUsingWinApi(client: Minecraft, nativeWindow: Long, debugPrefix: String): Boolean {
            try {
                debug(client, debugPrefix, "WinAPI hwnd=$nativeWindow")
                if (nativeWindow == 0L) { debug(client, debugPrefix, "WinAPI hwnd is 0"); return false }
                val user32 = User32.INSTANCE
                val hwnd = hwnd(nativeWindow)
                if (!user32.IsWindow(hwnd)) { debug(client, debugPrefix, "WinAPI IsWindow=false"); return false }
                val showState = getWindowShowState(user32, hwnd)
                var restoreResult = false
                if (showState.minimized) restoreResult = user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
                val showResult = user32.ShowWindow(hwnd, WinUser.SW_SHOW)
                val bringToTopResult = user32.BringWindowToTop(hwnd)
                val foregroundResult = user32.SetForegroundWindow(hwnd)
                val focused = waitForFocus(client, nativeWindow, FOCUS_WAIT_MS.toLong(), "WinAPI", debugPrefix)
                debug(client, debugPrefix, "WinAPI showCmd=${showState.showCmd} minimized=${showState.minimized} restore=$restoreResult show=$showResult bringToTop=$bringToTopResult foreground=$foregroundResult focused=$focused")
                return focused
            } catch (t: Throwable) { debug(client, debugPrefix, "WinAPI exception=${t.javaClass.simpleName}: ${t.message}"); return false }
        }

        private fun bringWindowToFrontUsingWinApiAltUnlock(client: Minecraft, nativeWindow: Long, debugPrefix: String): Boolean {
            try {
                if (nativeWindow == 0L) { debug(client, debugPrefix, "alt-unlock hwnd is 0"); return false }
                val user32 = User32.INSTANCE
                val hwnd = hwnd(nativeWindow)
                val sentInputs = sendAltKeyPress(user32)
                val bringToTopResult = user32.BringWindowToTop(hwnd)
                val foregroundResult = user32.SetForegroundWindow(hwnd)
                val focused = waitForFocus(client, nativeWindow, FOCUS_WAIT_MS.toLong(), "WinAPI alt-unlock", debugPrefix)
                debug(client, debugPrefix, "alt-unlock sentInputs=${sentInputs} bringToTop=$bringToTopResult foreground=$foregroundResult focused=$focused")
                return focused
            } catch (t: Throwable) { debug(client, debugPrefix, "alt-unlock exception=${t.javaClass.simpleName}: ${t.message}"); return false }
        }

        private fun bringWindowToFrontUsingThreadInput(client: Minecraft, nativeWindow: Long, debugPrefix: String): Boolean {
            try {
                if (nativeWindow == 0L) { debug(client, debugPrefix, "thread-input hwnd is 0"); return false }
                val action = CompletableFuture<ThreadInputResult>()
                client.execute {
                    try { action.complete(activateFromWindowThread(nativeWindow)) }
                    catch (t: Throwable) { action.completeExceptionally(t) }
                }
                val result = action.get(WINDOW_ACTION_WAIT_MS.toLong(), TimeUnit.MILLISECONDS)
                val focused = waitForFocus(client, nativeWindow, FOCUS_WAIT_MS.toLong(), "WinAPI thread-input", debugPrefix)
                debug(client, debugPrefix, "thread-input currentThread=${result.currentThread} foregroundThread=${threadValue(result.foregroundThread)} windowThread=${result.windowThread} attachedForeground=${result.attachedForeground} attachedWindow=${result.attachedWindow} bringToTop=${result.bringToTop} foreground=${result.foreground} setFocusReturned=${result.setFocusReturned} focused=$focused")
                return focused
            } catch (t: Throwable) {
                if (t is InterruptedException) Thread.currentThread().interrupt()
                debug(client, debugPrefix, "thread-input exception=${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        }

        private fun activateFromWindowThread(nativeWindow: Long): ThreadInputResult {
            val user32 = User32.INSTANCE
            val hwnd = hwnd(nativeWindow)
            val currentThread = WinDef.DWORD(Kernel32.INSTANCE.GetCurrentThreadId().toLong())
            val foregroundWindow = user32.GetForegroundWindow()
            val foregroundThread = if (foregroundWindow == null) null else WinDef.DWORD(user32.GetWindowThreadProcessId(foregroundWindow, null).toLong())
            val windowThread = user32.GetWindowThreadProcessId(hwnd, null).toLong()
            var attachedForeground = false
            var attachedWindow = false
            try {
                if (foregroundThread != null && foregroundThread.toLong() != currentThread.toLong()) attachedForeground = user32.AttachThreadInput(currentThread, foregroundThread, true)
                if (windowThread != 0L && windowThread != currentThread.toLong()) attachedWindow = user32.AttachThreadInput(currentThread, WinDef.DWORD(windowThread), true)
                val showState = getWindowShowState(user32, hwnd)
                if (showState.minimized) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
                user32.ShowWindow(hwnd, WinUser.SW_SHOW)
                val bringToTop = user32.BringWindowToTop(hwnd)
                val foreground = user32.SetForegroundWindow(hwnd)
                val previousFocus = user32.SetFocus(hwnd)
                return ThreadInputResult(currentThread.toLong(), foregroundThread, windowThread, attachedForeground, attachedWindow, bringToTop, foreground, previousFocus != null)
            } finally {
                if (attachedWindow) user32.AttachThreadInput(currentThread, WinDef.DWORD(windowThread), false)
                if (attachedForeground && foregroundThread != null) user32.AttachThreadInput(currentThread, foregroundThread, false)
            }
        }

        private fun bringWindowToFrontUsingGlfw(client: Minecraft, glfwWindow: Long, nativeWindow: Long, debugPrefix: String): Boolean {
            try {
                client.execute {
                    org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow(glfwWindow)
                    org.lwjgl.sdl.SDLVideo.SDL_ShowWindow(glfwWindow)
                    org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(glfwWindow)
                }
                return waitForFocus(client, nativeWindow, FOCUS_WAIT_MS.toLong(), "GLFW", debugPrefix)
            } catch (t: Throwable) { debug(client, debugPrefix, "GLFW exception=${t.javaClass.simpleName}: ${t.message}"); return false }
        }

        private fun bringWindowToFrontUsingRobot(client: Minecraft, nativeWindow: Long, debugPrefix: String): Boolean {
            val modifierKey = if (isMac()) KeyEvent.VK_META else KeyEvent.VK_ALT
            var robot: Robot? = null
            var modifierPressed = false
            var tabPressed = false
            try {
                robot = Robot()
                for (i in 1..25) {
                    if (isWindowFocused(client, nativeWindow)) break
                    debug(client, debugPrefix, "Robot alt-tab attempt=$i")
                    robot.keyPress(modifierKey); modifierPressed = true
                    for (j in 0 until i) {
                        robot.keyPress(KeyEvent.VK_TAB); tabPressed = true
                        robot.delay(100)
                        robot.keyRelease(KeyEvent.VK_TAB); tabPressed = false
                    }
                    robot.keyRelease(modifierKey); modifierPressed = false
                    robot.delay(ALT_TAB_SETTLE_MS)
                }
                return isWindowFocused(client, nativeWindow)
            } catch (e: AWTException) { debug(client, debugPrefix, "Robot exception=${e.javaClass.simpleName}: ${e.message}"); return false }
            catch (e: SecurityException) { debug(client, debugPrefix, "Robot exception=${e.javaClass.simpleName}: ${e.message}"); return false }
            finally {
                if (robot != null) {
                    if (tabPressed) robot.keyRelease(KeyEvent.VK_TAB)
                    if (modifierPressed) robot.keyRelease(modifierKey)
                }
            }
        }

        private fun waitForFocus(client: Minecraft, nativeWindow: Long, timeoutMs: Long, method: String, debugPrefix: String): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (isWindowFocused(client, nativeWindow)) { debug(client, debugPrefix, "$method focus detected"); return true }
                try { Thread.sleep(25L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); debug(client, debugPrefix, "$method wait interrupted"); return false }
            }
            val focused = isWindowFocused(client, nativeWindow)
            if (!focused) debug(client, debugPrefix, "$method focus wait timed out")
            return focused
        }

        private fun isWindowFocused(client: Minecraft, nativeWindow: Long): Boolean {
            if (Platform.get() == Platform.WINDOWS && nativeWindow != 0L) {
                val foregroundWindow = User32.INSTANCE.GetForegroundWindow()
                return foregroundWindow != null && Pointer.nativeValue(foregroundWindow.pointer) == nativeWindow
            }
            return client.isWindowActive()
        }

        private fun getNativeWindow(glfwWindow: Long): Long {
            if (glfwWindow == 0L || Platform.get() != Platform.WINDOWS) return 0L
            return 0L
        }
        private fun hwnd(nativeWindow: Long): WinDef.HWND = WinDef.HWND(Pointer.createConstant(nativeWindow))
        private fun isMac(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")

        private fun getWindowShowState(user32: User32, hwnd: WinDef.HWND): WindowShowState {
            val placement = WinUser.WINDOWPLACEMENT()
            placement.length = placement.size().toInt()
            val hasPlacement = user32.GetWindowPlacement(hwnd, placement).booleanValue()
            val showCmd = if (hasPlacement) placement.showCmd.toInt() else -1
            val minimized = showCmd == WinUser.SW_SHOWMINIMIZED || showCmd == WinUser.SW_MINIMIZE || showCmd == WinUser.SW_SHOWMINNOACTIVE
            return WindowShowState(showCmd, minimized)
        }

        private fun sendAltKeyPress(user32: User32): WinDef.DWORD {
            val inputs = WinUser.INPUT().toArray(2) as Array<WinUser.INPUT>
            setKeyboardInput(inputs[0], WinUser.VK_MENU, 0)
            setKeyboardInput(inputs[1], WinUser.VK_MENU, WinUser.KEYBDINPUT.KEYEVENTF_KEYUP)
            return user32.SendInput(WinDef.DWORD(inputs.size.toLong()), inputs, inputs[0].size())
        }

        private fun setKeyboardInput(input: WinUser.INPUT, virtualKey: Int, flags: Int) {
            input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
            input.input.setType(WinUser.KEYBDINPUT::class.java)
            input.input.ki = WinUser.KEYBDINPUT()
            input.input.ki.wVk = WinDef.WORD(virtualKey.toLong())
            input.input.ki.wScan = WinDef.WORD(0L)
            input.input.ki.dwFlags = WinDef.DWORD(flags.toLong())
            input.input.ki.time = WinDef.DWORD(0L)
            input.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0L)
            input.write()
        }

        private fun threadValue(threadId: WinDef.DWORD?): String = if (threadId == null) "null" else java.lang.Long.toString(threadId.toLong())
        private fun debug(client: Minecraft?, debugPrefix: String, message: String) { if (client != null) ClientUtils.sendDebugMessage("$debugPrefix: $message") }

        private class WindowShowState(val showCmd: Int, val minimized: Boolean)
        private class ThreadInputResult(val currentThread: Long, val foregroundThread: WinDef.DWORD?, val windowThread: Long, val attachedForeground: Boolean, val attachedWindow: Boolean, val bringToTop: Boolean, val foreground: Boolean, val setFocusReturned: Boolean)
    }
}
