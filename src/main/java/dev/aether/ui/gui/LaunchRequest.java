package dev.aether.ui.gui;

// where the gui opens: a page id, or a module by raw name as the old deep links give it, optionally down
// to a group and a setting; null fields are unspecified
public record LaunchRequest(String pageId, String moduleRawName, String groupRawName, String settingRawName) {
}
