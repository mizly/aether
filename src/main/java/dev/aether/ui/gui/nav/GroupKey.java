package dev.aether.ui.gui.nav;

// a group by page id, raw name and its index among same-named groups on that page: stable across language
// switches and in-place group rebuilds, so it serves as a hit/focus id and a persisted path
public record GroupKey(String pageId, String groupRawName, int ordinal) {
    static final char SEP = '\u001f';

    public String anchor() {
        return "g" + SEP + pageId + SEP + groupRawName + SEP + ordinal;
    }

    public static GroupKey fromAnchor(String anchor) {
        if (anchor == null || !anchor.startsWith("g" + SEP)) {
            return null;
        }
        String[] parts = anchor.split(String.valueOf(SEP), -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new GroupKey(parts[1], parts[2], Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
