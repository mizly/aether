package dev.aether.modules.failsafe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// linux shells out to notify-send, which is what mako/dunst/swaync implement, so wayland works out of the box
// macos uses osascript and windows a powershell toast as best-effort fallbacks
public final class DesktopNotificationManager {

    private static final String APP_NAME = "Aether";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Aether-DesktopNotification");
        thread.setDaemon(true);
        return thread;
    });

    // wrapped so null inside the holder means "looked and found nothing" rather than "not resolved yet"
    private static volatile String[] notifySendPathHolder;

    private DesktopNotificationManager() {
    }

    // critical raises the urgency for a stop failsafe
    public static void notify(String title, String body, boolean critical) {
        String safeTitle = title == null ? APP_NAME : title;
        String safeBody = body == null ? "" : body;
        EXECUTOR.execute(() -> dispatch(safeTitle, safeBody, critical));
    }

    private static void dispatch(String title, String body, boolean critical) {
        try {
            List<String> command = buildCommand(title, body, critical);
            if (command == null) {
                return;
            }
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            System.err.println("[Aether] Failed to send desktop notification: " + e.getMessage());
        }
    }

    private static List<String> buildCommand(String title, String body, boolean critical) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("powershell", "-NoProfile", "-Command", buildWindowsToastScript(title, body));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            String script = "display notification " + quoteAppleScript(body)
                    + " with title " + quoteAppleScript(title);
            return List.of("osascript", "-e", script);
        }
        // Linux / *nix -> notify-send (Hyprland: mako / dunst / swaync).
        String notifySend = resolveNotifySend();
        if (notifySend == null) {
            System.err.println("[Aether] Desktop notifications need 'notify-send' (libnotify) and a "
                    + "notification daemon (mako/dunst/swaync on Hyprland). Skipping.");
            return null;
        }
        return List.of(
                notifySend,
                "--app-name=" + APP_NAME,
                "--urgency=" + (critical ? "critical" : "normal"),
                "--expire-time=" + (critical ? "0" : "8000"),
                title,
                body);
    }

    // a gui launcher hands the jvm a minimal PATH that often misses per-user package dirs (nix especially), so search PATH first then the well-known locations
    private static String resolveNotifySend() {
        String[] holder = notifySendPathHolder;
        if (holder != null) {
            return holder[0];
        }
        String resolved = findNotifySend();
        notifySendPathHolder = new String[] {resolved};
        return resolved;
    }

    private static String findNotifySend() {
        List<String> candidates = new ArrayList<>();

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(dir + "/notify-send");
                }
            }
        }

        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        if (home != null) {
            candidates.add(home + "/.nix-profile/bin/notify-send");
            candidates.add(home + "/.local/bin/notify-send");
        }
        if (user != null) {
            candidates.add("/etc/profiles/per-user/" + user + "/bin/notify-send");
        }
        candidates.add("/run/current-system/sw/bin/notify-send");
        candidates.add("/usr/bin/notify-send");
        candidates.add("/usr/local/bin/notify-send");
        candidates.add("/bin/notify-send");

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile() && file.canExecute()) {
                return candidate;
            }
        }
        return null;
    }

    private static String buildWindowsToastScript(String title, String body) {
        // Lightweight balloon tip; avoids WinRT toast registration requirements.
        return "Add-Type -AssemblyName System.Windows.Forms;"
                + "$n=New-Object System.Windows.Forms.NotifyIcon;"
                + "$n.Icon=[System.Drawing.SystemIcons]::Information;"
                + "$n.Visible=$true;"
                + "$n.ShowBalloonTip(8000," + quotePowerShell(title) + "," + quotePowerShell(body)
                + ",[System.Windows.Forms.ToolTipIcon]::Info);"
                + "Start-Sleep -Seconds 9;$n.Dispose();";
    }

    private static String quoteAppleScript(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
