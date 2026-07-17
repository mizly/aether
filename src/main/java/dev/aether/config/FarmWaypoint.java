package dev.aether.config;

import java.util.Locale;

public record FarmWaypoint(
        double x,
        double y,
        double z,
        boolean forward,
        boolean left,
        boolean back,
        boolean right,
        boolean highlighted
) {
    public static FarmWaypoint emptyAt(double x, double y, double z) {
        return new FarmWaypoint(x, y, z, false, false, false, false, true);
    }

    public static FarmWaypoint parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.trim().split(":", -1);
        String[] coords = parts[0].split(",");
        if (coords.length < 3) {
            return null;
        }

        try {
            double x = Double.parseDouble(coords[0].trim());
            double y = Double.parseDouble(coords[1].trim());
            double z = Double.parseDouble(coords[2].trim());
            String keys = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
            return new FarmWaypoint(
                    x,
                    y,
                    z,
                    keys.contains("W"),
                    keys.contains("A"),
                    keys.contains("S"),
                    keys.contains("D"),
                    parts.length < 3 || Boolean.parseBoolean(parts[2]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public String encode() {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f:%s:%s", x, y, z, movementLabel(), highlighted);
    }

    public String movementLabel() {
        StringBuilder builder = new StringBuilder();
        appendKey(builder, forward, "W");
        appendKey(builder, left, "A");
        appendKey(builder, back, "S");
        appendKey(builder, right, "D");
        return builder.length() == 0 ? "None" : builder.toString();
    }

    public FarmWaypoint withPosition(double x, double y, double z) {
        return new FarmWaypoint(x, y, z, forward, left, back, right, highlighted);
    }

    public FarmWaypoint withHighlighted(boolean highlighted) {
        return new FarmWaypoint(x, y, z, forward, left, back, right, highlighted);
    }

    public int movementMask() {
        int mask = 0;
        if (forward) mask |= 1;
        if (left) mask |= 2;
        if (back) mask |= 4;
        if (right) mask |= 8;
        return mask;
    }

    public FarmWaypoint withMovementMask(int mask) {
        return new FarmWaypoint(
                x,
                y,
                z,
                (mask & 1) != 0,
                (mask & 2) != 0,
                (mask & 4) != 0,
                (mask & 8) != 0,
                highlighted);
    }

    public FarmWaypoint toggleKey(char key) {
        return switch (Character.toUpperCase(key)) {
            case 'W' -> new FarmWaypoint(x, y, z, !forward, left, back, right, highlighted);
            case 'A' -> new FarmWaypoint(x, y, z, forward, !left, back, right, highlighted);
            case 'S' -> new FarmWaypoint(x, y, z, forward, left, !back, right, highlighted);
            case 'D' -> new FarmWaypoint(x, y, z, forward, left, back, !right, highlighted);
            default -> this;
        };
    }

    public boolean isKeySelected(char key) {
        return switch (Character.toUpperCase(key)) {
            case 'W' -> forward;
            case 'A' -> left;
            case 'S' -> back;
            case 'D' -> right;
            default -> false;
        };
    }

    private static void appendKey(StringBuilder builder, boolean selected, String key) {
        if (!selected) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('/');
        }
        builder.append(key);
    }
}
