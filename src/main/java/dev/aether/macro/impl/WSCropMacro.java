package dev.aether.macro.impl;

public class WSCropMacro extends WSFarmMacro {
    private static final float CROP_LEFT_OFFSET_DEGREES = 26.6f;

    public WSCropMacro() {
        super(CROP_LEFT_OFFSET_DEGREES, "[WSCrop]");
    }
}
