package dev.aether.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class RewarpPointPair {
    private static final String PREFIX_V1 = "v1";
    private static final String PREFIX_V2 = "v2";
    private static final String PREFIX_V3 = "v3";
    private static final String PREFIX_V4 = "v4";

    public String name;
    public double startX;
    public double startY;
    public double startZ;
    public boolean startSet;
    public boolean highlightStart;
    public double endX;
    public double endY;
    public double endZ;
    public boolean endSet;
    public boolean highlightEnd;
    public RewarpMode rewarpMode;
    public String plotTpNumber;
    public boolean holdWUntilWall;
    public boolean aotvAlign;

    public RewarpPointPair(String config, int fallbackIndex) {
        RewarpPointPair fallback = defaultPair(fallbackIndex);
        if (config == null || config.isBlank()) {
            copyFrom(fallback);
            return;
        }

        String[] parts = config.split(":", -1);
        if (parts.length < 12 || (!PREFIX_V1.equals(parts[0]) && !PREFIX_V2.equals(parts[0])
                && !PREFIX_V3.equals(parts[0]) && !PREFIX_V4.equals(parts[0]))) {
            copyFrom(fallback);
            return;
        }

        try {
            this.name = decodeName(parts[1], fallback.name);
            this.startX = Double.parseDouble(parts[2]);
            this.startY = Double.parseDouble(parts[3]);
            this.startZ = Double.parseDouble(parts[4]);
            this.startSet = Boolean.parseBoolean(parts[5]);
            this.highlightStart = Boolean.parseBoolean(parts[6]);
            this.endX = Double.parseDouble(parts[7]);
            this.endY = Double.parseDouble(parts[8]);
            this.endZ = Double.parseDouble(parts[9]);
            this.endSet = Boolean.parseBoolean(parts[10]);
            this.highlightEnd = Boolean.parseBoolean(parts[11]);
            if (PREFIX_V4.equals(parts[0]) && parts.length >= 16) {
                this.rewarpMode = RewarpMode.fromConfig(parts[12]);
                this.plotTpNumber = parts[13];
                this.holdWUntilWall = Boolean.parseBoolean(parts[14]);
                this.aotvAlign = Boolean.parseBoolean(parts[15]);
            } else if (PREFIX_V3.equals(parts[0]) && parts.length >= 16) {
                this.rewarpMode = Boolean.parseBoolean(parts[12]) ? RewarpMode.PLOT_TP : RewarpMode.FLY;
                this.plotTpNumber = parts[13];
                this.holdWUntilWall = Boolean.parseBoolean(parts[14]);
                this.aotvAlign = Boolean.parseBoolean(parts[15]);
            } else if (PREFIX_V2.equals(parts[0]) && parts.length >= 17) {
                this.rewarpMode = Boolean.parseBoolean(parts[12]) ? RewarpMode.PLOT_TP : RewarpMode.FLY;
                this.plotTpNumber = parts[14];
                this.holdWUntilWall = Boolean.parseBoolean(parts[15]);
                this.aotvAlign = Boolean.parseBoolean(parts[16]);
            } else {
                this.rewarpMode = fallback.rewarpMode;
                this.plotTpNumber = fallback.plotTpNumber;
                this.holdWUntilWall = fallback.holdWUntilWall;
                this.aotvAlign = fallback.aotvAlign;
            }
            if (this.name == null || this.name.isBlank()) {
                this.name = fallback.name;
            }
            if (this.rewarpMode == null) {
                this.rewarpMode = fallback.rewarpMode;
            }
            if (this.plotTpNumber == null || this.plotTpNumber.isBlank()) {
                this.plotTpNumber = fallback.plotTpNumber;
            }
        } catch (Exception ignored) {
            copyFrom(fallback);
        }
    }

    public static RewarpPointPair defaultPair(int index) {
        RewarpPointPair pair = new RewarpPointPair();
        pair.name = defaultName(index);
        pair.startX = 0.0;
        pair.startY = 0.0;
        pair.startZ = 0.0;
        pair.startSet = false;
        pair.highlightStart = true;
        pair.endX = 0.0;
        pair.endY = 0.0;
        pair.endZ = 0.0;
        pair.endSet = false;
        pair.highlightEnd = true;
        pair.rewarpMode = RewarpMode.FLY;
        pair.plotTpNumber = "0";
        pair.holdWUntilWall = false;
        pair.aotvAlign = true;
        return pair;
    }

    public static RewarpPointPair fromLegacyConfig() {
        RewarpPointPair pair = defaultPair(0);
        pair.startX = AetherConfig.REWARP_START_X.get();
        pair.startY = AetherConfig.REWARP_START_Y.get();
        pair.startZ = AetherConfig.REWARP_START_Z.get();
        pair.startSet = AetherConfig.REWARP_START_POS_SET.get();
        pair.highlightStart = AetherConfig.REWARP_HIGHLIGHT_START.get();
        pair.endX = AetherConfig.REWARP_END_X.get();
        pair.endY = AetherConfig.REWARP_END_Y.get();
        pair.endZ = AetherConfig.REWARP_END_Z.get();
        pair.endSet = AetherConfig.REWARP_END_POS_SET.get();
        pair.highlightEnd = AetherConfig.REWARP_HIGHLIGHT_END.get();
        pair.rewarpMode = AetherConfig.ENABLE_PLOT_TP_REWARP.get() ? RewarpMode.PLOT_TP : RewarpMode.FLY;
        pair.plotTpNumber = AetherConfig.PLOT_TP_NUMBER.get();
        pair.holdWUntilWall = AetherConfig.HOLD_W_UNTIL_WALL.get();
        pair.aotvAlign = AetherConfig.REWARP_AOTV_ALIGN.get();
        return pair;
    }

    public static String defaultConfig(int index) {
        return defaultPair(index).toString();
    }

    public boolean hasStart() {
        return startSet;
    }

    public boolean hasEnd() {
        return endSet;
    }

    public String displayName() {
        return (name == null || name.isBlank()) ? defaultName(0) : name.trim();
    }

    @Override
    public String toString() {
        return PREFIX_V4
                + ":" + encodeName(displayName())
                + ":" + startX
                + ":" + startY
                + ":" + startZ
                + ":" + startSet
                + ":" + highlightStart
                + ":" + endX
                + ":" + endY
                + ":" + endZ
                + ":" + endSet
                + ":" + highlightEnd
                + ":" + rewarpMode.name()
                + ":" + sanitizePlotNumber(plotTpNumber)
                + ":" + holdWUntilWall
                + ":" + aotvAlign;
    }

    private RewarpPointPair() {
    }

    private void copyFrom(RewarpPointPair other) {
        this.name = other.name;
        this.startX = other.startX;
        this.startY = other.startY;
        this.startZ = other.startZ;
        this.startSet = other.startSet;
        this.highlightStart = other.highlightStart;
        this.endX = other.endX;
        this.endY = other.endY;
        this.endZ = other.endZ;
        this.endSet = other.endSet;
        this.highlightEnd = other.highlightEnd;
        this.rewarpMode = other.rewarpMode;
        this.plotTpNumber = other.plotTpNumber;
        this.holdWUntilWall = other.holdWUntilWall;
        this.aotvAlign = other.aotvAlign;
    }

    private static String defaultName(int index) {
        return "Rewarp " + (index + 1);
    }

    private static String encodeName(String name) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeName(String encoded, String fallback) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sanitizePlotNumber(String value) {
        return value == null || value.isBlank() ? "0" : value.trim().replace(":", "");
    }
}
