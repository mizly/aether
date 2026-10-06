package dev.aether.ui.gui.skin;

// how SettingsList lays a page out. a class with withers rather than a record, so fields can be added
// later without breaking skins that build one
public final class ListStyle {
    private static final ListStyle REFERENCE = new ListStyle(true, true, false, false,
            DescriptionPlacement.INLINE, 16f, true);

    private final boolean groupContainers;
    private final boolean rowDividers;
    private final boolean stickyHeaders;
    private final boolean singleGroup;
    private final DescriptionPlacement descriptionPlacement;
    private final float groupGap;
    private final boolean sectionAnchors;

    private ListStyle(boolean groupContainers, boolean rowDividers, boolean stickyHeaders, boolean singleGroup,
                      DescriptionPlacement descriptionPlacement, float groupGap, boolean sectionAnchors) {
        this.groupContainers = groupContainers;
        this.rowDividers = rowDividers;
        this.stickyHeaders = stickyHeaders;
        this.singleGroup = singleGroup;
        this.descriptionPlacement = descriptionPlacement;
        this.groupGap = groupGap;
        this.sectionAnchors = sectionAnchors;
    }

    // rounded group containers, hairline dividers, inline explicit descriptions
    public static ListStyle reference() {
        return REFERENCE;
    }

    // each group in a rounded container with its header inside
    public boolean groupContainers() {
        return groupContainers;
    }

    public boolean rowDividers() {
        return rowDividers;
    }

    // group and section headers stick to the top of the viewport while their rows scroll under them
    public boolean stickyHeaders() {
        return stickyHeaders;
    }

    // one group at a time behind tabs; SettingsList then draws only SettingsList.selectGroup's group
    public boolean singleGroup() {
        return singleGroup;
    }

    public DescriptionPlacement descriptionPlacement() {
        return descriptionPlacement;
    }

    public float groupGap() {
        return groupGap;
    }

    // sections become scroll anchors next to groups (anchor pills, terminal tree)
    public boolean sectionAnchors() {
        return sectionAnchors;
    }

    public ListStyle withGroupContainers(boolean value) {
        return new ListStyle(value, rowDividers, stickyHeaders, singleGroup, descriptionPlacement, groupGap, sectionAnchors);
    }

    public ListStyle withRowDividers(boolean value) {
        return new ListStyle(groupContainers, value, stickyHeaders, singleGroup, descriptionPlacement, groupGap, sectionAnchors);
    }

    public ListStyle withStickyHeaders(boolean value) {
        return new ListStyle(groupContainers, rowDividers, value, singleGroup, descriptionPlacement, groupGap, sectionAnchors);
    }

    public ListStyle withSingleGroup(boolean value) {
        return new ListStyle(groupContainers, rowDividers, stickyHeaders, value, descriptionPlacement, groupGap, sectionAnchors);
    }

    public ListStyle withDescriptionPlacement(DescriptionPlacement value) {
        return new ListStyle(groupContainers, rowDividers, stickyHeaders, singleGroup, value, groupGap, sectionAnchors);
    }

    public ListStyle withGroupGap(float value) {
        return new ListStyle(groupContainers, rowDividers, stickyHeaders, singleGroup, descriptionPlacement, value, sectionAnchors);
    }

    public ListStyle withSectionAnchors(boolean value) {
        return new ListStyle(groupContainers, rowDividers, stickyHeaders, singleGroup, descriptionPlacement, groupGap, value);
    }
}
