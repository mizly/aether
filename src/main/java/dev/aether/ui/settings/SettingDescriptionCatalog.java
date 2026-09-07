package dev.aether.ui.settings;

import dev.aether.util.AetherLang;

import java.util.Map;

/**
 * Central help text for ClickGUI settings. Every interactive setting gets a
 * tooltip: important/specialized controls have explicit descriptions, while
 * uncommon settings fall back to a type-aware explanation instead of showing
 * nothing.
 */
final class SettingDescriptionCatalog {
    private static final Map<String, String> EXPLICIT = Map.ofEntries(
            Map.entry("Target Lock", "Keeps the currently selected pest committed until it dies, disappears, becomes invalid, or is deferred. Prevents moving pests from causing route flip-flops."),
            Map.entry("One Tap Pests", "Uses kill-confidence signals to hand off quickly: a new damage popup can confirm immediately, a disappearing pest marker can combine with sustained vacuum contact, and a 0.5-second aimed vacuum hold remains the fallback. Surviving pests are rechecked before the run can finish."),
            Map.entry("AOTV Between Distant Pests", "Allows Pest Destroyer to use an Aspect of the Void between pests when normal travel would be slower."),
            Map.entry("Smart AOTV Routing", "Chooses whether another AOTV hop is worthwhile using distance, vertical travel, line of sight, and the configured start/stop distances."),
            Map.entry("AOTV Start Distance (Blocks)", "Minimum travel distance at which Smart AOTV Routing begins considering AOTV between pests."),
            Map.entry("AOTV Stop Distance (Blocks)", "Distance from the target where Smart AOTV Routing stops chaining teleports and hands movement back to normal pest approach."),
            Map.entry("Confirm AOTV Between Pests", "Requires player movement to confirm that an AOTV click actually teleported before continuing the route."),
            Map.entry("Etherwarp Near Pest", "For far pests, attempts a safe Etherwarp to a valid block one to two blocks around the target. When another pest is known, it favors landing on the side that shortens the next route leg before falling back to normal AOTV routing."),
            Map.entry("Etherwarp Minimum Distance (Blocks)", "Minimum pest distance required before the direct near-pest Etherwarp shortcut is considered."),
            Map.entry("Final Scan Max Duration (Seconds)", "Maximum time Pest Destroyer will stay still for final verification. The adaptive scan can finish much earlier as soon as the server pest count confirms the run is clear."),
            Map.entry("Pest Turn Speed Limit", "Caps how quickly the camera tracks a pest during normal vacuum combat. Higher values turn faster."),
            Map.entry("Next Pest Turn Speed", "Caps the faster handoff rotation used when switching to a newly selected pest or lining up pest-to-pest teleport movement. Higher values turn faster."),
            Map.entry("Pest FOV Range", "Controls the angular tolerance used by Pest Destroyer when deciding whether the camera is sufficiently aimed at a pest."),
            Map.entry("Pest Above Aim Pitch", "Sets the allowed pitch range used by pest-related aiming behavior when the player is above a target."),
            Map.entry("Pest Threshold", "Number of pests required before Pest Destroyer is allowed to start automatically."),
            Map.entry("Estimate Pest Destroyer Completion", "Uses client-side pest tracking to estimate when a cleaning run has finished, while final scans still verify remaining pests."),
            Map.entry("Skip while Crop Fever Active", "Delays Pest Destroyer while Crop Fever is active so the farming bonus is not interrupted."),
            Map.entry("Trigger Only After Rewarp", "Only allows Pest Destroyer to start after the farming macro reaches a rewarp point."),
            Map.entry("Plot TP for Current Plot", "Uses the plot teleport command even when Pest Destroyer believes you are already on the target plot."),
            Map.entry("Leave One Pest Alive", "Preserves one pest on selected plots instead of fully clearing them."),
            Map.entry("Leave One Pest Plots", "Lists the Garden plots where Leave One Pest Alive should reserve a pest."),
            Map.entry("Sunset Pests", "Enables the client\'s special Sunset pest handling rules during Pest Destroyer runs."),
            Map.entry("Optimized Route ESP", "Draws the planned Pest Destroyer route between the current target and the predicted next pests."),
            Map.entry("Optimized Route Color", "Sets the color used for the optimized Pest Destroyer route overlay."),
            Map.entry("Highlight", "Draws the Pest ESP highlight around detected pests."),
            Map.entry("Highlight Color", "Sets the color used by Pest ESP highlights."),
            Map.entry("Tracer", "Draws a screen-space tracer toward detected pests."),
            Map.entry("Tracer Color", "Sets the color used by Pest ESP tracer lines."),
            Map.entry("Vacuum Stun Before Lasso", "Uses the vacuum to stun a lasso-routed pest before trying to catch it."),
            Map.entry("Vacuum Pest Blacklist", "Selects pest types that should use the vacuum instead of the lasso while Pest Hunting is enabled."),
            Map.entry("Follow Distance", "Preferred distance to maintain from a pest while following it during lasso hunting."),
            Map.entry("Max Leash Distance", "Maximum allowed distance from a lassoed pest before the client tries harder to close the gap."),
            Map.entry("Max Lasso Throws", "Maximum number of lasso throws allowed for one pest before the hunt gives up or retries."),
            Map.entry("Catch Timeout", "Maximum time the client will spend trying to catch one pest before abandoning that attempt."),
            Map.entry("Activation Mode", "Chooses whether the feature stays active after one key press or only remains active while its key is held."),
            Map.entry("Freelook Keybind", "Changes the key used to activate Freelook perspective mode."),
            Map.entry("Rotation Time", "Base duration used by automated camera rotations. Larger values generally produce slower, smoother turns."),
            Map.entry("Rotation Ease In", "Makes automated rotations accelerate gradually instead of starting at full speed."),
            Map.entry("Rotation Ease Out", "Makes automated rotations decelerate gradually as they approach the target angle."),
            Map.entry("Ease In Factor", "Controls how strongly rotation speed ramps up at the beginning of an eased turn."),
            Map.entry("Ease Out Factor", "Controls how strongly rotation speed slows down near the end of an eased turn."),
            Map.entry("Tracking Noise Min", "Minimum small aim variation that can be applied while automated tracking is active."),
            Map.entry("Tracking Noise Max", "Maximum small aim variation that can be applied while automated tracking is active."),
            Map.entry("UI Scale", "Changes the overall size of the Aether menu interface."),
            Map.entry("Text Scale", "Changes text size in the Aether interface without changing the full menu scale."),
            Map.entry("Animation Time", "Controls how long supported interface animations take to complete."),
            Map.entry("Limit FPS", "Caps Minecraft frame rate while the associated condition is active."),
            Map.entry("Max FPS", "Sets the frame-rate cap used when Limit FPS is enabled."),
            Map.entry("Limit Chunk Distance", "Allows Aether to reduce the client render distance under the configured conditions."),
            Map.entry("Chunk Distance", "Sets the render-distance value used when Limit Chunk Distance is active."),
            Map.entry("Mute Game", "Temporarily mutes normal game audio while this feature is active."),
            Map.entry("Game Volume", "Sets the game-volume level used by Aether when it manages Minecraft audio."),
            Map.entry("Play Failsafe Sound", "Plays the selected alert sound when a failsafe is triggered."),
            Map.entry("Failsafe Volume", "Sets the playback volume of Aether failsafe sounds."),
            Map.entry("Failsafe Additional Random Delay", "Adds extra randomized waiting time to supported failsafe reactions."),
            Map.entry("Colour Flash on Failsafe Trigger", "Flashes the screen with the configured colors when a failsafe activates."),
            Map.entry("Flash Opacity", "Controls how opaque the failsafe color flash appears."),
            Map.entry("Auto Reconnect", "Automatically attempts to reconnect after supported disconnects."),
            Map.entry("Auto Alt-Tab", "Lets Aether automatically change window focus when the related automation requests it."),
            Map.entry("Ungrab Mouse", "Releases the Minecraft mouse grab so the cursor can leave the game window while automation continues."),
            Map.entry("Keep Focus", "Keeps the game window treated as focused for supported automation behavior."),
            Map.entry("Show FPS", "Shows current frames per second in the selected HUD."),
            Map.entry("Show Ping", "Shows current network latency in the selected HUD."),
            Map.entry("Show Time", "Shows the current time in the selected HUD."),
            Map.entry("Show Username", "Shows the configured/current username in the selected HUD."),
            Map.entry("Show Macro Status", "Shows whether the main macro is currently running and its current state."),
            Map.entry("Only Show While Macro Running", "Hides the selected HUD unless the macro is currently active."),
            Map.entry("Only Show HUDs In Garden", "Restricts supported HUD elements to the Garden."),
            Map.entry("Show Task HUDs Outside Garden", "Allows task-specific HUD elements to remain visible outside the Garden."),
            Map.entry("Render In First Person", "Allows the selected visual element to render while Minecraft is in first-person view."),
            Map.entry("Show Player Model", "Shows the player model in the related HUD or visual panel."),
            Map.entry("Show Armor", "Shows equipped armor in the related player/HUD display."),
            Map.entry("Hide Skin", "Hides the player skin in the related player-model display."),
            Map.entry("Enable Username Spoof", "Displays a custom username in supported Aether UI/HUD areas instead of the real one."),
            Map.entry("Custom Username", "Sets the display name used when Username Spoof is enabled."),
            Map.entry("Edit HUD Layout", "Opens the HUD editor so Aether overlays can be moved and resized."),
            Map.entry("Export Theme (Copy)", "Copies the current Aether theme configuration so it can be shared or backed up."),
            Map.entry("Import Theme (Paste)", "Imports an Aether theme configuration from the clipboard."),
            Map.entry("Reset Theme", "Restores the current theme settings to their defaults."),
            Map.entry("Refresh Language Packs", "Reloads available Aether language-pack data and refreshes translated UI text."),
            Map.entry("Language", "Chooses the language used by the Aether interface."),
            Map.entry("Preset", "Chooses which saved preset or configuration should be used by this feature."),
            Map.entry("Save Preset", "Saves the current values as a reusable preset."),
            Map.entry("Run Now", "Runs this automation immediately instead of waiting for its normal trigger."),
            Map.entry("Harvest Now", "Starts the associated harvesting action immediately."),
            Map.entry("Reset Session", "Clears the current session statistics and starts their counters over."),
            Map.entry("Reset Daily Timer", "Resets the current daily timer/counter."),
            Map.entry("Reset Lifetime Profit", "Clears the stored lifetime-profit total."),
            Map.entry("Connect Discord", "Connects the configured Discord integration using the supplied settings."),
            Map.entry("Disconnect", "Disconnects the currently active integration or connection."),
            Map.entry("Webhook URL", "Sets the Discord/webhook destination used by supported notifications."),
            Map.entry("Bot Token", "Sets the Discord bot token used by the configured integration. Keep this value private."),
            Map.entry("Channel ID", "Sets the Discord channel identifier used by the configured integration."),
            Map.entry("Server ID", "Sets the Discord server identifier used by the configured integration."),
            Map.entry("Auto Sell (Passive)", "Allows automatic selling to run passively when its normal conditions are met."),
            Map.entry("Bazaar Autosell", "Sells supported items through the Bazaar when automatic selling runs."),
            Map.entry("NPC Autosell", "Sells supported items to an NPC when automatic selling runs."),
            Map.entry("Auto Sell Items", "Lists the items that automatic selling is allowed to sell."),
            Map.entry("Junk Items", "Lists items treated as junk by supported inventory-management features."),
            Map.entry("Inventory Threshold", "Sets how full the inventory can become before the related inventory action triggers."),
            Map.entry("Inventory Full Time", "Sets how long the inventory must remain full before the related response triggers."),
            Map.entry("Minimum Purse", "Minimum purse balance required before the associated purchase or automation is allowed."),
            Map.entry("Max Visitor Purchase", "Maximum amount Aether may spend on one visitor request."),
            Map.entry("Visitor Threshold", "Controls the visitor-count condition used to trigger visitor automation."),
            Map.entry("Visitor Loadout Slot", "Wardrobe/loadout slot used for visitor-related automation."),
            Map.entry("Farming Loadout Slot", "Wardrobe/loadout slot used for normal farming."),
            Map.entry("Pest Spawn Loadout Slot", "Loadout slot selected when handling pest spawning behavior."),
            Map.entry("Pest Kill Loadout Slot", "Loadout slot selected while actively clearing pests."),
            Map.entry("Pathfinder Max Jump Height", "Maximum vertical step/jump the pathfinder is allowed to plan through."),
            Map.entry("Movement Speed", "Adjusts the movement speed used by the related automated movement behavior."),
            Map.entry("Stuck Timeout", "How long movement may make insufficient progress before Aether treats it as stuck and retries or recovers."),
            Map.entry("Warp Grace Period", "Short period after a warp during which supported safety/position checks are relaxed to let the teleport settle.")
    );

    private SettingDescriptionCatalog() {
    }

    static String describe(Setting setting) {
        if (setting == null) {
            return "";
        }
        String raw = setting.getRawName() == null ? setting.getName() : setting.getRawName();
        String explicit = EXPLICIT.get(raw);
        if (explicit != null) {
            return AetherLang.localize(explicit);
        }
        return AetherLang.localize(fallback(setting.getType(), raw));
    }

    private static String fallback(SettingType type, String raw) {
        String subject = raw == null || raw.isBlank() ? "this setting" : raw;
        return switch (type) {
            case TOGGLE -> toggleDescription(subject);
            case SLIDER -> "Adjusts " + lowerFirst(subject) + ". Move the slider or type a value to fine-tune the behavior.";
            case RANGE_SLIDER -> "Sets the minimum and maximum values used for " + lowerFirst(subject) + ".";
            case DROPDOWN -> "Chooses which mode or option is used for " + lowerFirst(subject) + ".";
            case DROPDOWN_LIST -> "Sets the ordered choices used for " + lowerFirst(subject) + ".";
            case MULTI_DROPDOWN -> "Selects which entries are included in " + lowerFirst(subject) + ". Multiple choices can be active at once.";
            case LIST -> "Manages the saved entries used by " + lowerFirst(subject) + ".";
            case TEXT -> "Sets the text or identifier used for " + lowerFirst(subject) + ".";
            case COLOR -> "Sets the color used for " + lowerFirst(subject) + ".";
            case POSITION -> "Sets the in-world position used for " + lowerFirst(subject) + ".";
            case KEYBIND -> "Changes the keyboard key used for " + lowerFirst(subject) + ".";
            case ACTION -> "Runs the “" + subject + "” action immediately.";
            case INFO -> "Displays current information for " + lowerFirst(subject) + ".";
            case SECTION -> "Settings related to " + lowerFirst(subject) + ".";
        };
    }

    private static String toggleDescription(String subject) {
        if (subject.startsWith("Show ")) {
            return "Shows " + lowerFirst(subject.substring(5)) + " when enabled.";
        }
        if (subject.startsWith("Hide ")) {
            return "Hides " + lowerFirst(subject.substring(5)) + " when enabled.";
        }
        if (subject.startsWith("Disable ")) {
            return "Disables " + lowerFirst(subject.substring(8)) + " while this option is enabled.";
        }
        if (subject.startsWith("Enable ")) {
            return "Enables " + lowerFirst(subject.substring(7)) + ".";
        }
        if (subject.startsWith("Auto ")) {
            return "Automatically manages " + lowerFirst(subject.substring(5)) + " when enabled.";
        }
        if (subject.startsWith("Only ")) {
            return "Restricts the related feature so it follows the “" + subject + "” condition.";
        }
        if (subject.startsWith("Skip ")) {
            return "Skips " + lowerFirst(subject.substring(5)) + " when this condition is active.";
        }
        if (subject.startsWith("Hold ")) {
            return "Keeps " + lowerFirst(subject.substring(5)) + " held while the related automation is active.";
        }
        if (subject.startsWith("Trigger ")) {
            return "Controls whether the related automation will " + lowerFirst(subject) + ".";
        }
        return "Turns " + lowerFirst(subject) + " on or off.";
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "this setting";
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
