package dev.aether.ui.gui.nav;

// a setting by its group key, raw name and index among same-named settings in that group
public record SettingKey(GroupKey group, String settingRawName, int ordinal) {

    public String pageId() {
        return group.pageId();
    }

    public String anchor() {
        return "s" + GroupKey.SEP + group.pageId() + GroupKey.SEP + group.groupRawName() + GroupKey.SEP
                + group.ordinal() + GroupKey.SEP + settingRawName + GroupKey.SEP + ordinal;
    }

    public static SettingKey fromAnchor(String anchor) {
        if (anchor == null || !anchor.startsWith("s" + GroupKey.SEP)) {
            return null;
        }
        String[] parts = anchor.split(String.valueOf(GroupKey.SEP), -1);
        if (parts.length != 6) {
            return null;
        }
        try {
            return new SettingKey(new GroupKey(parts[1], parts[2], Integer.parseInt(parts[3])), parts[4],
                    Integer.parseInt(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
