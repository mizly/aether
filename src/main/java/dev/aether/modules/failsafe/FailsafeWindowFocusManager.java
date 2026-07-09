package dev.aether.modules.failsafe;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorWindow;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FailsafeWindowFocusManager {
    private static final int FOCUS_WAIT_MS = 500;

    private static final ExecutorService FOCUS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Aether-FailsafeWindowFocus");
        thread.setDaemon(true);
        return thread;
    });

    private FailsafeWindowFocusManager() {
    }

    public static void bringWindowToFront() {
        Minecraft client = Minecraft.getInstance();
        if (!AetherConfig.FAILSAFE_AUTO_ALT_TAB.get()) {
            debug(client, "FailsafeWindowFocus: auto alt-tab disabled");
            return;
        }

        if (client == null) {
            debug(null, "FailsafeWindowFocus: client is null");
            return;
        }

        if (client.getWindow() == null) {
            debug(client, "FailsafeWindowFocus: window is null");
            return;
        }

        long glfwWindow = ((AccessorWindow) (Object) client.getWindow()).getHandle();
        debug(client, "FailsafeWindowFocus: requested handle=" + glfwWindow + " platform=" + Platform.get());
        if (glfwWindow == 0L) {
            debug(client, "FailsafeWindowFocus: GLFW handle is 0");
            return;
        }

        if (isWindowFocused(glfwWindow)) {
            debug(client, "FailsafeWindowFocus: window already focused");
            return;
        }

        FOCUS_EXECUTOR.execute(() -> {
            debug(client, "FailsafeWindowFocus: focus worker started");
            boolean focused = false;
            if (Platform.get() == Platform.WINDOWS) {
                focused = bringWindowToFrontUsingWinApi(client, glfwWindow);
                debug(client, "FailsafeWindowFocus: WinAPI result=" + focused);
                if (!focused) {
                    focused = bringWindowToFrontUsingWinApiAltUnlock(client, glfwWindow);
                    debug(client, "FailsafeWindowFocus: WinAPI alt-unlock result=" + focused);
                }
                if (!focused) {
                    focused = bringWindowToFrontUsingThreadInput(client, glfwWindow);
                    debug(client, "FailsafeWindowFocus: WinAPI thread-input result=" + focused);
                }
            } else {
                debug(client, "FailsafeWindowFocus: skipping WinAPI on platform=" + Platform.get());
            }
            if (!focused) {
                debug(client, "FailsafeWindowFocus: trying GLFW focus fallback");
                focused = bringWindowToFrontUsingGlfw(client, glfwWindow);
                debug(client, "FailsafeWindowFocus: GLFW result=" + focused);
            }
            if (!focused) {
                if (Platform.get() == Platform.WINDOWS) {
                    debug(client, "FailsafeWindowFocus: skipping Robot fallback on Windows");
                } else {
                    debug(client, "FailsafeWindowFocus: trying Robot alt-tab fallback");
                    focused = bringWindowToFrontUsingRobot(glfwWindow);
                    debug(client, "FailsafeWindowFocus: Robot result=" + focused);
                }
            }
            if (!focused) {
                ClientUtils.sendDebugMessage(client, "Failsafe window focus failed");
            } else {
                debug(client, "FailsafeWindowFocus: window focused");
            }
        });
    }

    private static boolean bringWindowToFrontUsingWinApi(Minecraft client, long glfwWindow) {
        try {
            long win32Window = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            debug(client, "FailsafeWindowFocus: WinAPI hwnd=" + win32Window);
            if (win32Window == 0L) {
                debug(client, "FailsafeWindowFocus: WinAPI hwnd is 0");
                return false;
            }

            User32 user32 = User32.INSTANCE;
            WinDef.HWND hwnd = new WinDef.HWND(Pointer.createConstant(win32Window));
            if (!user32.IsWindow(hwnd)) {
                debug(client, "FailsafeWindowFocus: WinAPI IsWindow=false");
                return false;
            }

            WindowShowState showState = getWindowShowState(user32, hwnd);
            boolean restoreResult = false;
            if (showState.minimized) {
                restoreResult = user32.ShowWindow(hwnd, WinUser.SW_RESTORE);
            }
            boolean showResult = user32.ShowWindow(hwnd, WinUser.SW_SHOW);
            boolean foregroundResult = user32.SetForegroundWindow(hwnd);
            WinDef.HWND previousFocus = user32.SetFocus(hwnd);
            boolean focused = waitForFocus(client, glfwWindow, FOCUS_WAIT_MS, "WinAPI");
            debug(client, "FailsafeWindowFocus: WinAPI showCmd=" + showState.showCmd
                    + " minimized=" + showState.minimized
                    + " restore=" + restoreResult
                    + " show=" + showResult
                    + " foreground=" + foregroundResult
                    + " setFocusReturned=" + (previousFocus != null)
                    + " focused=" + focused);
            return focused;
        } catch (Throwable t) {
            debug(client, "FailsafeWindowFocus: WinAPI exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean bringWindowToFrontUsingWinApiAltUnlock(Minecraft client, long glfwWindow) {
        try {
            long win32Window = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            if (win32Window == 0L) {
                debug(client, "FailsafeWindowFocus: alt-unlock hwnd is 0");
                return false;
            }

            User32 user32 = User32.INSTANCE;
            WinDef.HWND hwnd = new WinDef.HWND(Pointer.createConstant(win32Window));
            WinDef.DWORD sentInputs = sendAltKeyPress(user32);
            boolean bringToTopResult = user32.BringWindowToTop(hwnd);
            boolean foregroundResult = user32.SetForegroundWindow(hwnd);
            boolean focused = waitForFocus(client, glfwWindow, FOCUS_WAIT_MS, "WinAPI alt-unlock");
            debug(client, "FailsafeWindowFocus: alt-unlock sentInputs=" + sentInputs.longValue()
                    + " bringToTop=" + bringToTopResult
                    + " foreground=" + foregroundResult
                    + " focused=" + focused);
            return focused;
        } catch (Throwable t) {
            debug(client, "FailsafeWindowFocus: alt-unlock exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean bringWindowToFrontUsingThreadInput(Minecraft client, long glfwWindow) {
        User32 user32 = User32.INSTANCE;
        WinDef.HWND hwnd = null;
        WinDef.DWORD currentThread = new WinDef.DWORD(Kernel32.INSTANCE.GetCurrentThreadId());
        WinDef.DWORD foregroundThread = null;
        WinDef.DWORD targetThread = null;
        boolean attachedForeground = false;
        boolean attachedTarget = false;

        try {
            long win32Window = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            if (win32Window == 0L) {
                debug(client, "FailsafeWindowFocus: thread-input hwnd is 0");
                return false;
            }

            hwnd = new WinDef.HWND(Pointer.createConstant(win32Window));
            WinDef.HWND foregroundWindow = user32.GetForegroundWindow();
            foregroundThread = foregroundWindow == null
                    ? null
                    : new WinDef.DWORD(user32.GetWindowThreadProcessId(foregroundWindow, null));
            targetThread = new WinDef.DWORD(user32.GetWindowThreadProcessId(hwnd, null));

            if (foregroundThread != null && foregroundThread.longValue() != currentThread.longValue()) {
                attachedForeground = user32.AttachThreadInput(currentThread, foregroundThread, true);
            }
            if (targetThread != null && targetThread.longValue() != currentThread.longValue()) {
                attachedTarget = user32.AttachThreadInput(currentThread, targetThread, true);
            }

            boolean bringToTopResult = user32.BringWindowToTop(hwnd);
            boolean foregroundResult = user32.SetForegroundWindow(hwnd);
            WinDef.HWND previousFocus = user32.SetFocus(hwnd);
            boolean focused = waitForFocus(client, glfwWindow, FOCUS_WAIT_MS, "WinAPI thread-input");
            debug(client, "FailsafeWindowFocus: thread-input currentThread=" + currentThread.longValue()
                    + " foregroundThread=" + threadValue(foregroundThread)
                    + " targetThread=" + threadValue(targetThread)
                    + " attachedForeground=" + attachedForeground
                    + " attachedTarget=" + attachedTarget
                    + " bringToTop=" + bringToTopResult
                    + " foreground=" + foregroundResult
                    + " setFocusReturned=" + (previousFocus != null)
                    + " focused=" + focused);
            return focused;
        } catch (Throwable t) {
            debug(client, "FailsafeWindowFocus: thread-input exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        } finally {
            if (attachedTarget && targetThread != null) {
                user32.AttachThreadInput(currentThread, targetThread, false);
            }
            if (attachedForeground && foregroundThread != null) {
                user32.AttachThreadInput(currentThread, foregroundThread, false);
            }
        }
    }

    private static boolean bringWindowToFrontUsingGlfw(Minecraft client, long glfwWindow) {
        try {
            client.execute(() -> {
                if (GLFW.glfwGetWindowAttrib(glfwWindow, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
                    GLFW.glfwRestoreWindow(glfwWindow);
                }
                GLFW.glfwShowWindow(glfwWindow);
                GLFW.glfwFocusWindow(glfwWindow);
            });
            return waitForFocus(client, glfwWindow, FOCUS_WAIT_MS, "GLFW");
        } catch (Throwable t) {
            debug(client, "FailsafeWindowFocus: GLFW exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean bringWindowToFrontUsingRobot(long glfwWindow) {
        int modifierKey = isMac() ? KeyEvent.VK_META : KeyEvent.VK_ALT;

        try {
            Robot robot = new Robot();
            for (int i = 1; i <= 25 && !isWindowFocused(glfwWindow); i++) {
                Minecraft client = Minecraft.getInstance();
                debug(client, "FailsafeWindowFocus: Robot alt-tab attempt=" + i);
                robot.keyPress(modifierKey);
                for (int j = 0; j < i; j++) {
                    robot.keyPress(KeyEvent.VK_TAB);
                    robot.delay(100);
                    robot.keyRelease(KeyEvent.VK_TAB);
                }
                robot.keyRelease(modifierKey);
                robot.delay(100);
            }
            return isWindowFocused(glfwWindow);
        } catch (AWTException | SecurityException e) {
            Minecraft client = Minecraft.getInstance();
            debug(client, "FailsafeWindowFocus: Robot exception=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean waitForFocus(Minecraft client, long glfwWindow, long timeoutMs, String method) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isWindowFocused(glfwWindow)) {
                debug(client, "FailsafeWindowFocus: " + method + " focus detected");
                return true;
            }

            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                debug(client, "FailsafeWindowFocus: " + method + " wait interrupted");
                return false;
            }
        }
        boolean focused = isWindowFocused(glfwWindow);
        if (!focused) {
            debug(client, "FailsafeWindowFocus: " + method + " focus wait timed out");
        }
        return focused;
    }

    private static boolean isWindowFocused(long glfwWindow) {
        return GLFW.glfwGetWindowAttrib(glfwWindow, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static WindowShowState getWindowShowState(User32 user32, WinDef.HWND hwnd) {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        placement.length = placement.size();
        boolean hasPlacement = user32.GetWindowPlacement(hwnd, placement).booleanValue();
        int showCmd = hasPlacement ? placement.showCmd : -1;
        return new WindowShowState(showCmd, showCmd == WinUser.SW_SHOWMINIMIZED);
    }

    private static WinDef.DWORD sendAltKeyPress(User32 user32) {
        WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
        setKeyboardInput(inputs[0], WinUser.VK_MENU, 0);
        setKeyboardInput(inputs[1], WinUser.VK_MENU, WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
        return user32.SendInput(new WinDef.DWORD(inputs.length), inputs, inputs[0].size());
    }

    private static void setKeyboardInput(WinUser.INPUT input, int virtualKey, int flags) {
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType(WinUser.KEYBDINPUT.class);
        input.input.ki = new WinUser.KEYBDINPUT();
        input.input.ki.wVk = new WinDef.WORD(virtualKey);
        input.input.ki.wScan = new WinDef.WORD(0);
        input.input.ki.dwFlags = new WinDef.DWORD(flags);
        input.input.ki.time = new WinDef.DWORD(0);
        input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        input.write();
    }

    private static String threadValue(WinDef.DWORD threadId) {
        return threadId == null ? "null" : Long.toString(threadId.longValue());
    }

    private static void debug(Minecraft client, String message) {
        if (client != null) {
            ClientUtils.sendDebugMessage(client, message);
        }
    }

    private record WindowShowState(int showCmd, boolean minimized) {
    }
}
