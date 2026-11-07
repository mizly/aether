package dev.aether.util;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.system.Platform;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WindowFocusHelper {
    private static final int FOCUS_WAIT_MS = 500;
    private static final int WINDOW_ACTION_WAIT_MS = 1000;
    private static final int ALT_TAB_SETTLE_MS = 250;

    private static final ExecutorService FOCUS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Aether-WindowFocus");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean FOCUS_IN_PROGRESS = new AtomicBoolean();

    private WindowFocusHelper() {
    }

    public static void focusMinecraftWindow(String debugPrefix) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            debug(null, debugPrefix, "client is null");
            return;
        }

        if (client.getWindow() == null) {
            debug(client, debugPrefix, "window is null");
            return;
        }

        client.execute(() -> prepareFocusRequest(client, debugPrefix));
    }

    private static void prepareFocusRequest(Minecraft client, String debugPrefix) {
        if (client.getWindow() == null) {
            debug(client, debugPrefix, "window became unavailable");
            return;
        }

        long sdlWindow = client.getWindow().handle();
        long nativeWindow = getNativeWindow(sdlWindow);
        debug(client, debugPrefix, "requested handle=" + sdlWindow
                + " nativeHandle=" + nativeWindow
                + " platform=" + Platform.get());
        if (sdlWindow == 0L) {
            debug(client, debugPrefix, "SDL handle is 0");
            return;
        }

        if (isWindowFocused(client, nativeWindow)) {
            debug(client, debugPrefix, "window already focused");
            return;
        }

        if (!FOCUS_IN_PROGRESS.compareAndSet(false, true)) {
            debug(client, debugPrefix, "focus request already in progress");
            return;
        }

        FOCUS_EXECUTOR.execute(() -> {
            try {
                focusMinecraftWindow(client, sdlWindow, nativeWindow, debugPrefix);
            } finally {
                FOCUS_IN_PROGRESS.set(false);
            }
        });
    }

    private static void focusMinecraftWindow(
            Minecraft client,
            long sdlWindow,
            long nativeWindow,
            String debugPrefix
    ) {
        debug(client, debugPrefix, "focus worker started");
        boolean focused = false;
        if (Platform.get() == Platform.WINDOWS) {
            focused = bringWindowToFrontUsingWinApi(client, nativeWindow, debugPrefix);
            debug(client, debugPrefix, "WinAPI result=" + focused);
            if (!focused) {
                focused = bringWindowToFrontUsingWinApiAltUnlock(client, nativeWindow, debugPrefix);
                debug(client, debugPrefix, "WinAPI alt-unlock result=" + focused);
            }
            if (!focused) {
                focused = bringWindowToFrontUsingThreadInput(client, nativeWindow, debugPrefix);
                debug(client, debugPrefix, "WinAPI thread-input result=" + focused);
            }
        } else {
            debug(client, debugPrefix, "skipping WinAPI on platform=" + Platform.get());
        }
        if (!focused) {
            debug(client, debugPrefix, "trying SDL focus fallback");
            focused = bringWindowToFrontUsingSdl(client, sdlWindow, nativeWindow, debugPrefix);
            debug(client, debugPrefix, "SDL result=" + focused);
        }
        if (!focused) {
            debug(client, debugPrefix, "trying Robot alt-tab fallback");
            focused = bringWindowToFrontUsingRobot(client, nativeWindow, debugPrefix);
            debug(client, debugPrefix, "Robot result=" + focused);
        }
        if (!focused) {
            ClientUtils.sendDebugMessage(debugPrefix + ": window focus failed");
        } else {
            debug(client, debugPrefix, "window focused");
        }
    }

    private static boolean bringWindowToFrontUsingWinApi(Minecraft client, long nativeWindow, String debugPrefix) {
        try {
            debug(client, debugPrefix, "WinAPI hwnd=" + nativeWindow);
            if (nativeWindow == 0L) {
                debug(client, debugPrefix, "WinAPI hwnd is 0");
                return false;
            }

            User32 user32 = User32.INSTANCE;
            WinDef.HWND hwnd = hwnd(nativeWindow);
            if (!user32.IsWindow(hwnd)) {
                debug(client, debugPrefix, "WinAPI IsWindow=false");
                return false;
            }

            WindowShowState showState = getWindowShowState(user32, hwnd);
            boolean restoreResult = false;
            if (showState.minimized) {
                restoreResult = user32.ShowWindow(hwnd, WinUser.SW_RESTORE);
            }
            boolean showResult = user32.ShowWindow(hwnd, WinUser.SW_SHOW);
            boolean bringToTopResult = user32.BringWindowToTop(hwnd);
            boolean foregroundResult = user32.SetForegroundWindow(hwnd);
            boolean focused = waitForFocus(client, nativeWindow, FOCUS_WAIT_MS, "WinAPI", debugPrefix);
            debug(client, debugPrefix, "WinAPI showCmd=" + showState.showCmd
                    + " minimized=" + showState.minimized
                    + " restore=" + restoreResult
                    + " show=" + showResult
                    + " bringToTop=" + bringToTopResult
                    + " foreground=" + foregroundResult
                    + " focused=" + focused);
            return focused;
        } catch (Throwable t) {
            debug(client, debugPrefix, "WinAPI exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean bringWindowToFrontUsingWinApiAltUnlock(
            Minecraft client,
            long nativeWindow,
            String debugPrefix
    ) {
        try {
            if (nativeWindow == 0L) {
                debug(client, debugPrefix, "alt-unlock hwnd is 0");
                return false;
            }

            User32 user32 = User32.INSTANCE;
            WinDef.HWND hwnd = hwnd(nativeWindow);
            WinDef.DWORD sentInputs = sendAltKeyPress(user32);
            boolean bringToTopResult = user32.BringWindowToTop(hwnd);
            boolean foregroundResult = user32.SetForegroundWindow(hwnd);
            boolean focused = waitForFocus(client, nativeWindow, FOCUS_WAIT_MS, "WinAPI alt-unlock", debugPrefix);
            debug(client, debugPrefix, "alt-unlock sentInputs=" + sentInputs.longValue()
                    + " bringToTop=" + bringToTopResult
                    + " foreground=" + foregroundResult
                    + " focused=" + focused);
            return focused;
        } catch (Throwable t) {
            debug(client, debugPrefix, "alt-unlock exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean bringWindowToFrontUsingThreadInput(
            Minecraft client,
            long nativeWindow,
            String debugPrefix
    ) {
        try {
            if (nativeWindow == 0L) {
                debug(client, debugPrefix, "thread-input hwnd is 0");
                return false;
            }

            CompletableFuture<ThreadInputResult> action = new CompletableFuture<>();
            client.execute(() -> {
                try {
                    action.complete(activateFromWindowThread(nativeWindow));
                } catch (Throwable t) {
                    action.completeExceptionally(t);
                }
            });
            ThreadInputResult result = action.get(WINDOW_ACTION_WAIT_MS, TimeUnit.MILLISECONDS);
            boolean focused = waitForFocus(
                    client,
                    nativeWindow,
                    FOCUS_WAIT_MS,
                    "WinAPI thread-input",
                    debugPrefix);
            debug(client, debugPrefix, "thread-input currentThread=" + result.currentThread
                    + " foregroundThread=" + threadValue(result.foregroundThread)
                    + " windowThread=" + result.windowThread
                    + " attachedForeground=" + result.attachedForeground
                    + " attachedWindow=" + result.attachedWindow
                    + " bringToTop=" + result.bringToTop
                    + " foreground=" + result.foreground
                    + " setFocusReturned=" + result.setFocusReturned
                    + " focused=" + focused);
            return focused;
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            debug(client, debugPrefix, "thread-input exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static ThreadInputResult activateFromWindowThread(long nativeWindow) {
        User32 user32 = User32.INSTANCE;
        WinDef.HWND hwnd = hwnd(nativeWindow);
        WinDef.DWORD currentThread = new WinDef.DWORD(Kernel32.INSTANCE.GetCurrentThreadId());
        WinDef.HWND foregroundWindow = user32.GetForegroundWindow();
        WinDef.DWORD foregroundThread = foregroundWindow == null
                ? null
                : new WinDef.DWORD(user32.GetWindowThreadProcessId(foregroundWindow, null));
        long windowThread = user32.GetWindowThreadProcessId(hwnd, null);
        boolean attachedForeground = false;
        boolean attachedWindow = false;

        try {
            if (foregroundThread != null && foregroundThread.longValue() != currentThread.longValue()) {
                attachedForeground = user32.AttachThreadInput(currentThread, foregroundThread, true);
            }
            if (windowThread != 0L && windowThread != currentThread.longValue()) {
                attachedWindow = user32.AttachThreadInput(
                        currentThread,
                        new WinDef.DWORD(windowThread),
                        true);
            }

            WindowShowState showState = getWindowShowState(user32, hwnd);
            if (showState.minimized) {
                user32.ShowWindow(hwnd, WinUser.SW_RESTORE);
            }
            user32.ShowWindow(hwnd, WinUser.SW_SHOW);
            boolean bringToTop = user32.BringWindowToTop(hwnd);
            boolean foreground = user32.SetForegroundWindow(hwnd);
            WinDef.HWND previousFocus = user32.SetFocus(hwnd);
            return new ThreadInputResult(
                    currentThread.longValue(),
                    foregroundThread,
                    windowThread,
                    attachedForeground,
                    attachedWindow,
                    bringToTop,
                    foreground,
                    previousFocus != null);
        } finally {
            if (attachedWindow) {
                user32.AttachThreadInput(currentThread, new WinDef.DWORD(windowThread), false);
            }
            if (attachedForeground && foregroundThread != null) {
                user32.AttachThreadInput(currentThread, foregroundThread, false);
            }
        }
    }

    private static boolean bringWindowToFrontUsingSdl(
            Minecraft client,
            long sdlWindow,
            long nativeWindow,
            String debugPrefix
    ) {
        try {
            client.execute(() -> {
                if ((SDLVideo.SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_MINIMIZED) != 0) {
                    SDLVideo.SDL_RestoreWindow(sdlWindow);
                }
                SDLVideo.SDL_ShowWindow(sdlWindow);
                SDLVideo.SDL_RaiseWindow(sdlWindow);
            });
            return waitForFocus(client, nativeWindow, FOCUS_WAIT_MS, "SDL", debugPrefix);
        } catch (Throwable t) {
            debug(client, debugPrefix, "SDL exception=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean bringWindowToFrontUsingRobot(
            Minecraft client,
            long nativeWindow,
            String debugPrefix
    ) {
        int modifierKey = isMac() ? KeyEvent.VK_META : KeyEvent.VK_ALT;
        Robot robot = null;
        boolean modifierPressed = false;
        boolean tabPressed = false;

        try {
            robot = new Robot();
            for (int i = 1; i <= 25 && !isWindowFocused(client, nativeWindow); i++) {
                debug(client, debugPrefix, "Robot alt-tab attempt=" + i);
                robot.keyPress(modifierKey);
                modifierPressed = true;
                for (int j = 0; j < i; j++) {
                    robot.keyPress(KeyEvent.VK_TAB);
                    tabPressed = true;
                    robot.delay(100);
                    robot.keyRelease(KeyEvent.VK_TAB);
                    tabPressed = false;
                }
                robot.keyRelease(modifierKey);
                modifierPressed = false;
                robot.delay(ALT_TAB_SETTLE_MS);
            }
            return isWindowFocused(client, nativeWindow);
        } catch (AWTException | SecurityException e) {
            debug(client, debugPrefix, "Robot exception=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (robot != null) {
                if (tabPressed) {
                    robot.keyRelease(KeyEvent.VK_TAB);
                }
                if (modifierPressed) {
                    robot.keyRelease(modifierKey);
                }
            }
        }
    }

    private static boolean waitForFocus(
            Minecraft client,
            long nativeWindow,
            long timeoutMs,
            String method,
            String debugPrefix
    ) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isWindowFocused(client, nativeWindow)) {
                debug(client, debugPrefix, method + " focus detected");
                return true;
            }

            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                debug(client, debugPrefix, method + " wait interrupted");
                return false;
            }
        }
        boolean focused = isWindowFocused(client, nativeWindow);
        if (!focused) {
            debug(client, debugPrefix, method + " focus wait timed out");
        }
        return focused;
    }

    private static boolean isWindowFocused(Minecraft client, long nativeWindow) {
        if (Platform.get() == Platform.WINDOWS && nativeWindow != 0L) {
            WinDef.HWND foregroundWindow = User32.INSTANCE.GetForegroundWindow();
            return foregroundWindow != null
                    && Pointer.nativeValue(foregroundWindow.getPointer()) == nativeWindow;
        }
        return client.isWindowActive();
    }

    private static long getNativeWindow(long sdlWindow) {
        if (sdlWindow == 0L || Platform.get() != Platform.WINDOWS) {
            return 0L;
        }
        return SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow),
                SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
    }

    private static WinDef.HWND hwnd(long nativeWindow) {
        return new WinDef.HWND(Pointer.createConstant(nativeWindow));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static WindowShowState getWindowShowState(User32 user32, WinDef.HWND hwnd) {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        placement.length = placement.size();
        boolean hasPlacement = user32.GetWindowPlacement(hwnd, placement).booleanValue();
        int showCmd = hasPlacement ? placement.showCmd : -1;
        boolean minimized = showCmd == WinUser.SW_SHOWMINIMIZED
                || showCmd == WinUser.SW_MINIMIZE
                || showCmd == WinUser.SW_SHOWMINNOACTIVE;
        return new WindowShowState(showCmd, minimized);
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

    private static void debug(Minecraft client, String debugPrefix, String message) {
        if (client != null) {
            ClientUtils.sendDebugMessage(debugPrefix + ": " + message);
        }
    }

    private record WindowShowState(int showCmd, boolean minimized) {
    }

    private record ThreadInputResult(
            long currentThread,
            WinDef.DWORD foregroundThread,
            long windowThread,
            boolean attachedForeground,
            boolean attachedWindow,
            boolean bringToTop,
            boolean foreground,
            boolean setFocusReturned
    ) {
    }
}
