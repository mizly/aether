package dev.aether.ui.gui.nav;

import dev.aether.config.ConfigProfileManager;
import dev.aether.config.ThemeProfileManager;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;
import dev.aether.util.AetherLang;

import java.util.List;

// every config and theme profile action, with the toasts the old menu shows; both menus call these so a
// rewrite can never export a webhook url or skip the profile-loaded hook
public final class ProfileActions {

    public enum Kind { CONFIG, THEME }

    public enum ImportResult { IMPORTED, EMPTY_CLIPBOARD, NEEDS_CONFIRM }

    public static final String DEFAULT_IMPORT_NAME = "imported";

    private ProfileActions() {
    }

    public static List<String> list(Kind kind) {
        return kind == Kind.CONFIG ? ConfigProfileManager.list() : ThemeProfileManager.list();
    }

    public static boolean exists(Kind kind, String name) {
        return kind == Kind.CONFIG ? ConfigProfileManager.exists(name) : ThemeProfileManager.exists(name);
    }

    public static boolean save(Kind kind, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (kind == Kind.CONFIG) {
            ConfigProfileManager.save(name);
        } else {
            ThemeProfileManager.save(name);
        }
        NotificationManager.success(title(kind, "Config Saved", "Theme Saved"), quoted(name) + " saved successfully");
        return true;
    }

    // config loads go through ConfigProfileManager.load, which fires onConfigProfileLoaded and rebuilds the registry
    public static boolean load(Kind kind, String name) {
        if (kind == Kind.CONFIG) {
            if (!ConfigProfileManager.load(name)) {
                NotificationManager.error(AetherLang.localize("Config Load Failed"), quoted(name) + " could not be loaded");
                return false;
            }
            NotificationManager.success(AetherLang.localize("Config Loaded"), quoted(name) + " loaded successfully");
            return true;
        }
        ThemeProfileManager.load(name);
        NotificationManager.success(AetherLang.localize("Theme Loaded"), quoted(name) + " loaded successfully");
        return true;
    }

    // config exports always blank the webhook url, bot token, coop names, custom username and server nick
    public static String exportJson(Kind kind, String name) {
        return kind == Kind.CONFIG ? ConfigProfileManager.exportJsonSanitized(name) : ThemeProfileManager.exportJson(name);
    }

    public static void export(Kind kind, String name, Clipboard clipboard) {
        clipboard.write(exportJson(kind, name));
        NotificationManager.info(AetherLang.localize("Copied to Clipboard"), quoted(name) + " exported");
    }

    public static String importName(String typedName) {
        return typedName == null || typedName.isBlank() ? DEFAULT_IMPORT_NAME : typedName;
    }

    // NEEDS_CONFIRM when a profile of that name exists: ask, then call again with overwrite
    public static ImportResult importFromClipboard(Kind kind, String typedName, Clipboard clipboard, boolean overwrite) {
        String json = clipboard.read();
        if (json == null || json.isBlank()) {
            return ImportResult.EMPTY_CLIPBOARD;
        }
        String name = importName(typedName);
        if (!overwrite && exists(kind, name)) {
            return ImportResult.NEEDS_CONFIRM;
        }
        if (kind == Kind.CONFIG) {
            ConfigProfileManager.importFromClipboard(name, json);
        } else {
            ThemeProfileManager.importJson(name, json);
        }
        NotificationManager.success(title(kind, "Config Imported", "Theme Imported"), quoted(name) + " imported");
        return ImportResult.IMPORTED;
    }

    public static void delete(Kind kind, String name) {
        if (kind == Kind.CONFIG) {
            ConfigProfileManager.delete(name);
        } else {
            ThemeProfileManager.delete(name);
        }
        NotificationManager.warning(title(kind, "Config Deleted", "Theme Deleted"), quoted(name) + " was deleted");
    }

    // a blank new name cancels without a toast, as the old rename field does
    public static boolean rename(Kind kind, String oldName, String newName) {
        String target = newName == null ? "" : newName.trim();
        if (target.isBlank()) {
            return false;
        }
        boolean renamed = kind == Kind.CONFIG
                ? ConfigProfileManager.rename(oldName, target)
                : ThemeProfileManager.rename(oldName, target);
        if (renamed) {
            NotificationManager.success(title(kind, "Config Renamed", "Theme Renamed"),
                    quoted(oldName) + " renamed to " + quoted(target));
        } else {
            NotificationManager.error(title(kind, "Config Rename Failed", "Theme Rename Failed"),
                    quoted(target) + " could not be used");
        }
        return renamed;
    }

    public static void applyPreset(ThemePreset preset) {
        preset.apply();
        Theme.saveTheme();
    }

    public static void defaultColours() {
        Theme.resetColorsToDefaults();
        Theme.saveTheme();
    }

    private static String title(Kind kind, String config, String theme) {
        return AetherLang.localize(kind == Kind.CONFIG ? config : theme);
    }

    private static String quoted(String name) {
        return "\"" + name + "\"";
    }
}
