package dev.aether.ui.gui.widget;

// a setting row's stable id: raw names plus ordinals among same-named siblings, never object identity,
// because the farming, rewarp and pet groups are rebuilt with new objects on ordinary edits
public record RowKey(String pageId, String group, int groupOrdinal, String setting, int ordinal) {
    public GroupKey groupKey() {
        return new GroupKey(pageId, group, groupOrdinal);
    }
}
