package dev.aether.modules.failsafe

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Executors

// linux shells out to notify-send, which is what mako/dunst/swaync implement, so wayland works out of the box
// macos uses osascript and windows a powershell toast as best-effort fallbacks
object DesktopNotificationManager {
    private const val APP_NAME = "Aether"
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Aether-DesktopNotification").apply { isDaemon = true } }
    @Volatile private var notifySendPathHolder: Array<String?>? = null

    @JvmStatic fun notify(title: String?, body: String?, critical: Boolean) {
        val safeTitle = title ?: APP_NAME
        val safeBody = body ?: ""
        executor.execute { dispatch(safeTitle, safeBody, critical) }
    }
    private fun dispatch(title: String, body: String, critical: Boolean) {
        try {
            val command = buildCommand(title, body, critical) ?: return
            ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) { System.err.println("[Aether] Failed to send desktop notification: ${e.message}") }
    }
    private fun buildCommand(title: String, body: String, critical: Boolean): List<String>? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        if (os.contains("win")) return listOf("powershell", "-NoProfile", "-Command", buildWindowsToastScript(title, body))
        if (os.contains("mac") || os.contains("darwin")) {
            val script = "display notification ${quoteAppleScript(body)} with title ${quoteAppleScript(title)}"
            return listOf("osascript", "-e", script)
        }
        val notifySend = resolveNotifySend()
        if (notifySend == null) {
            System.err.println("[Aether] Desktop notifications need 'notify-send' (libnotify) and a notification daemon (mako/dunst/swaync on Hyprland). Skipping.")
            return null
        }
        return listOf(notifySend, "--app-name=$APP_NAME", "--urgency=${if (critical) "critical" else "normal"}", "--expire-time=${if (critical) "0" else "8000"}", title, body)
    }
    private fun resolveNotifySend(): String? {
        notifySendPathHolder?.let { return it[0] }
        val resolved = findNotifySend()
        notifySendPathHolder = arrayOf<String?>(resolved)
        return resolved
    }
    private fun findNotifySend(): String? {
        val candidates = ArrayList<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.filterTo(candidates) { it.isNotBlank() }?.replaceAll { "$it/notify-send" }
        System.getProperty("user.home")?.let { candidates.add("$it/.nix-profile/bin/notify-send"); candidates.add("$it/.local/bin/notify-send") }
        System.getProperty("user.name")?.let { candidates.add("/etc/profiles/per-user/$it/bin/notify-send") }
        candidates.addAll(listOf("/run/current-system/sw/bin/notify-send", "/usr/bin/notify-send", "/usr/local/bin/notify-send", "/bin/notify-send"))
        return candidates.firstOrNull { File(it).let { file -> file.isFile && file.canExecute() } }
    }
    private fun buildWindowsToastScript(title: String, body: String) = "Add-Type -AssemblyName System.Windows.Forms;" +
        "\$n=New-Object System.Windows.Forms.NotifyIcon;" + "\$n.Icon=[System.Drawing.SystemIcons]::Information;" +
        "\$n.Visible=\$true;" + "\$n.ShowBalloonTip(8000,${quotePowerShell(title)},${quotePowerShell(body)},[System.Windows.Forms.ToolTipIcon]::Info);" + "Start-Sleep -Seconds 9;\$n.Dispose();"
    private fun quoteAppleScript(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun quotePowerShell(value: String) = "'${value.replace("'", "''")}'"
}
