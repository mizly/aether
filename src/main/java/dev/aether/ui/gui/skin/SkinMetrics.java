package dev.aether.ui.gui.skin;

// layout numbers a skin hands to controls and SettingsList, in layout units. built through Builder so
// numbers can be added later without breaking skins that set their own
public final class SkinMetrics {
    private static final SkinMetrics REFERENCE = new Builder().build();

    private final float rowHeight;
    private final float rowPadX;
    private final float rowPadY;
    private final float controlHeight;
    private final float smallControlHeight;
    private final float fieldRadius;
    private final float surfaceRadius;
    private final float windowRadius;
    private final float buttonRadius;
    private final float gap;
    private final float groupHeaderHeight;
    private final float sectionHeaderHeight;
    private final float toggleWidth;
    private final float toggleHeight;
    private final float checkboxSize;
    private final float sliderTrackHeight;
    private final float sliderKnobSize;
    private final float sliderWidth;
    private final float valueFieldWidth;
    private final float dropdownWidth;
    private final float textFieldWidth;
    private final float keybindWidth;
    private final float swatchSize;
    private final float chipHeight;
    private final float iconSize;
    private final float scrollbarWidth;
    private final float menuRowHeight;
    private final float tooltipMaxWidth;

    private SkinMetrics(Builder b) {
        rowHeight = b.rowHeight;
        rowPadX = b.rowPadX;
        rowPadY = b.rowPadY;
        controlHeight = b.controlHeight;
        smallControlHeight = b.smallControlHeight;
        fieldRadius = b.fieldRadius;
        surfaceRadius = b.surfaceRadius;
        windowRadius = b.windowRadius;
        buttonRadius = b.buttonRadius;
        gap = b.gap;
        groupHeaderHeight = b.groupHeaderHeight;
        sectionHeaderHeight = b.sectionHeaderHeight;
        toggleWidth = b.toggleWidth;
        toggleHeight = b.toggleHeight;
        checkboxSize = b.checkboxSize;
        sliderTrackHeight = b.sliderTrackHeight;
        sliderKnobSize = b.sliderKnobSize;
        sliderWidth = b.sliderWidth;
        valueFieldWidth = b.valueFieldWidth;
        dropdownWidth = b.dropdownWidth;
        textFieldWidth = b.textFieldWidth;
        keybindWidth = b.keybindWidth;
        swatchSize = b.swatchSize;
        chipHeight = b.chipHeight;
        iconSize = b.iconSize;
        scrollbarWidth = b.scrollbarWidth;
        menuRowHeight = b.menuRowHeight;
        tooltipMaxWidth = b.tooltipMaxWidth;
    }

    public static SkinMetrics reference() {
        return REFERENCE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.rowHeight = rowHeight;
        b.rowPadX = rowPadX;
        b.rowPadY = rowPadY;
        b.controlHeight = controlHeight;
        b.smallControlHeight = smallControlHeight;
        b.fieldRadius = fieldRadius;
        b.surfaceRadius = surfaceRadius;
        b.windowRadius = windowRadius;
        b.buttonRadius = buttonRadius;
        b.gap = gap;
        b.groupHeaderHeight = groupHeaderHeight;
        b.sectionHeaderHeight = sectionHeaderHeight;
        b.toggleWidth = toggleWidth;
        b.toggleHeight = toggleHeight;
        b.checkboxSize = checkboxSize;
        b.sliderTrackHeight = sliderTrackHeight;
        b.sliderKnobSize = sliderKnobSize;
        b.sliderWidth = sliderWidth;
        b.valueFieldWidth = valueFieldWidth;
        b.dropdownWidth = dropdownWidth;
        b.textFieldWidth = textFieldWidth;
        b.keybindWidth = keybindWidth;
        b.swatchSize = swatchSize;
        b.chipHeight = chipHeight;
        b.iconSize = iconSize;
        b.scrollbarWidth = scrollbarWidth;
        b.menuRowHeight = menuRowHeight;
        b.tooltipMaxWidth = tooltipMaxWidth;
        return b;
    }

    // a one-line setting row, before text grows it
    public float rowHeight() {
        return rowHeight;
    }

    public float rowPadX() {
        return rowPadX;
    }

    public float rowPadY() {
        return rowPadY;
    }

    // fields, buttons and dropdowns inside a row
    public float controlHeight() {
        return controlHeight;
    }

    public float smallControlHeight() {
        return smallControlHeight;
    }

    public float fieldRadius() {
        return fieldRadius;
    }

    public float surfaceRadius() {
        return surfaceRadius;
    }

    public float windowRadius() {
        return windowRadius;
    }

    public float buttonRadius() {
        return buttonRadius;
    }

    public float gap() {
        return gap;
    }

    public float groupHeaderHeight() {
        return groupHeaderHeight;
    }

    public float sectionHeaderHeight() {
        return sectionHeaderHeight;
    }

    public float toggleWidth() {
        return toggleWidth;
    }

    public float toggleHeight() {
        return toggleHeight;
    }

    public float checkboxSize() {
        return checkboxSize;
    }

    public float sliderTrackHeight() {
        return sliderTrackHeight;
    }

    public float sliderKnobSize() {
        return sliderKnobSize;
    }

    // track plus value field of a slider row
    public float sliderWidth() {
        return sliderWidth;
    }

    public float valueFieldWidth() {
        return valueFieldWidth;
    }

    public float dropdownWidth() {
        return dropdownWidth;
    }

    public float textFieldWidth() {
        return textFieldWidth;
    }

    public float keybindWidth() {
        return keybindWidth;
    }

    public float swatchSize() {
        return swatchSize;
    }

    public float chipHeight() {
        return chipHeight;
    }

    public float iconSize() {
        return iconSize;
    }

    public float scrollbarWidth() {
        return scrollbarWidth;
    }

    public float menuRowHeight() {
        return menuRowHeight;
    }

    public float tooltipMaxWidth() {
        return tooltipMaxWidth;
    }

    public static final class Builder {
        private float rowHeight = 44f;
        private float rowPadX = 14f;
        private float rowPadY = 9f;
        private float controlHeight = 30f;
        private float smallControlHeight = 24f;
        private float fieldRadius = 8f;
        private float surfaceRadius = 12f;
        private float windowRadius = 16f;
        private float buttonRadius = 8f;
        private float gap = 8f;
        private float groupHeaderHeight = 48f;
        private float sectionHeaderHeight = 34f;
        private float toggleWidth = 36f;
        private float toggleHeight = 20f;
        private float checkboxSize = 18f;
        private float sliderTrackHeight = 4f;
        private float sliderKnobSize = 16f;
        private float sliderWidth = 210f;
        private float valueFieldWidth = 62f;
        private float dropdownWidth = 184f;
        private float textFieldWidth = 200f;
        private float keybindWidth = 128f;
        private float swatchSize = 22f;
        private float chipHeight = 26f;
        private float iconSize = 16f;
        private float scrollbarWidth = 6f;
        private float menuRowHeight = 30f;
        private float tooltipMaxWidth = 300f;

        private Builder() {
        }

        public Builder rowHeight(float value) {
            rowHeight = value;
            return this;
        }

        public Builder rowPadX(float value) {
            rowPadX = value;
            return this;
        }

        public Builder rowPadY(float value) {
            rowPadY = value;
            return this;
        }

        public Builder controlHeight(float value) {
            controlHeight = value;
            return this;
        }

        public Builder smallControlHeight(float value) {
            smallControlHeight = value;
            return this;
        }

        public Builder fieldRadius(float value) {
            fieldRadius = value;
            return this;
        }

        public Builder surfaceRadius(float value) {
            surfaceRadius = value;
            return this;
        }

        public Builder windowRadius(float value) {
            windowRadius = value;
            return this;
        }

        public Builder buttonRadius(float value) {
            buttonRadius = value;
            return this;
        }

        public Builder gap(float value) {
            gap = value;
            return this;
        }

        public Builder groupHeaderHeight(float value) {
            groupHeaderHeight = value;
            return this;
        }

        public Builder sectionHeaderHeight(float value) {
            sectionHeaderHeight = value;
            return this;
        }

        public Builder toggleWidth(float value) {
            toggleWidth = value;
            return this;
        }

        public Builder toggleHeight(float value) {
            toggleHeight = value;
            return this;
        }

        public Builder checkboxSize(float value) {
            checkboxSize = value;
            return this;
        }

        public Builder sliderTrackHeight(float value) {
            sliderTrackHeight = value;
            return this;
        }

        public Builder sliderKnobSize(float value) {
            sliderKnobSize = value;
            return this;
        }

        public Builder sliderWidth(float value) {
            sliderWidth = value;
            return this;
        }

        public Builder valueFieldWidth(float value) {
            valueFieldWidth = value;
            return this;
        }

        public Builder dropdownWidth(float value) {
            dropdownWidth = value;
            return this;
        }

        public Builder textFieldWidth(float value) {
            textFieldWidth = value;
            return this;
        }

        public Builder keybindWidth(float value) {
            keybindWidth = value;
            return this;
        }

        public Builder swatchSize(float value) {
            swatchSize = value;
            return this;
        }

        public Builder chipHeight(float value) {
            chipHeight = value;
            return this;
        }

        public Builder iconSize(float value) {
            iconSize = value;
            return this;
        }

        public Builder scrollbarWidth(float value) {
            scrollbarWidth = value;
            return this;
        }

        public Builder menuRowHeight(float value) {
            menuRowHeight = value;
            return this;
        }

        public Builder tooltipMaxWidth(float value) {
            tooltipMaxWidth = value;
            return this;
        }

        public SkinMetrics build() {
            return new SkinMetrics(this);
        }
    }
}
