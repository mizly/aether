package dev.aether.ui.theme;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// jdk-only so it also runs as a single-file program while palettes are tuned:
// java ThemeSwatchSheet.java <out.png> <themes dir> <fonts dir> <id>...
public final class ThemeSwatchSheet {
    private static final int CARD_W = 560;
    private static final int CARD_H = 330;
    private static final int GAP = 18;
    private static final int COLUMNS = 3;
    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([0-9A-Fa-f]{8})\"");

    private final Map<String, Font> fonts = new LinkedHashMap<>();

    private ThemeSwatchSheet(Path fontDir) {
        load(fontDir, "regular", "Inter-Regular.otf", Font.PLAIN);
        load(fontDir, "medium", "Inter-Medium.otf", Font.PLAIN);
        load(fontDir, "semibold", "Inter-SemiBold.otf", Font.BOLD);
        load(fontDir, "bold", "Inter-Bold.otf", Font.BOLD);
        load(fontDir, "mono", "JetBrainsMono-Regular.ttf", Font.PLAIN);
    }

    public static void main(String[] args) throws IOException {
        render(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), List.of(args).subList(3, args.length));
    }

    public static void render(Path out, Path themeDir, Path fontDir, List<String> ids) throws IOException {
        new ThemeSwatchSheet(fontDir).write(out, themeDir, ids);
    }

    private void load(Path dir, String role, String file, int fallbackStyle) {
        Font font;
        try {
            font = Font.createFont(Font.TRUETYPE_FONT, dir.resolve(file).toFile());
        } catch (Exception e) {
            font = new Font(role.equals("mono") ? Font.MONOSPACED : Font.SANS_SERIF, fallbackStyle, 12);
        }
        fonts.put(role, font);
    }

    private Font font(String role, float size) {
        return fonts.get(role).deriveFont(size);
    }

    private void write(Path out, Path themeDir, List<String> ids) throws IOException {
        int rows = (ids.size() + COLUMNS - 1) / COLUMNS;
        int width = COLUMNS * CARD_W + (COLUMNS + 1) * GAP;
        int height = rows * CARD_H + (rows + 1) * GAP;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(new Color(0x5B6B4E));
        g.fillRect(0, 0, width, height);
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Integer> theme = parse(Files.readString(themeDir.resolve(ids.get(i) + ".json")));
            int x = GAP + (i % COLUMNS) * (CARD_W + GAP);
            int y = GAP + (i / COLUMNS) * (CARD_H + GAP);
            Graphics2D card = (Graphics2D) g.create(x, y, CARD_W, CARD_H);
            card(card, ids.get(i), theme);
            card.dispose();
        }
        g.dispose();
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(image, "png", out.toFile());
    }

    static Map<String, Integer> parse(String json) {
        Map<String, Integer> values = new LinkedHashMap<>();
        Matcher m = ENTRY.matcher(json);
        while (m.find()) values.put(m.group(1), (int) Long.parseLong(m.group(2), 16));
        return values;
    }

    private void card(Graphics2D g, String id, Map<String, Integer> t) {
        int sidebarW = 128;
        fill(g, t.get("Main Panel BG"), 0, 0, CARD_W, CARD_H, 14);
        g.setClip(new RoundRectangle2D.Float(0, 0, CARD_W, CARD_H, 28, 28));
        fill(g, t.get("Sidebar BG"), 0, 0, sidebarW, CARD_H, 0);
        g.setClip(null);
        stroke(g, t.get("Border"), 0.5f, 0.5f, CARD_W - 1, CARD_H - 1, 14, 1f);
        fill(g, t.get("Separator"), sidebarW, 0, 1, CARD_H, 0);

        dot(g, t.get("Accent"), 18, 22, 9);
        text(g, "bold", 15, t.get("Text"), title(id), 34, 27);
        text(g, "medium", 9.5f, t.get("Text Muted"), "FARMING", 16, 56);
        String[] nav = {"Farming Macro", "Pest Destroyer", "Garden", "Failsafes", "Appearance"};
        for (int i = 0; i < nav.length; i++) {
            int ny = 64 + i * 28;
            if (i == 1) {
                fill(g, withAlpha(t.get("Accent"), 0x40), 8, ny, sidebarW - 16, 24, 7);
                text(g, "semibold", 11.5f, t.get("Accent"), nav[i], 18, ny + 16);
            } else if (i == 3) {
                fill(g, t.get("Hover BG"), 8, ny, sidebarW - 16, 24, 7);
                text(g, "medium", 11.5f, t.get("Text Value"), nav[i], 18, ny + 16);
            } else {
                text(g, "medium", 11.5f, t.get("Text Muted"), nav[i], 18, ny + 16);
            }
        }
        fill(g, t.get("Panel BG"), 8, CARD_H - 52, sidebarW - 16, 42, 8);
        stroke(g, t.get("Separator"), 8.5f, CARD_H - 51.5f, sidebarW - 17, 41, 8, 1f);
        text(g, "semibold", 10.5f, t.get("Text Label"), "Profile", 16, CARD_H - 34);
        text(g, "regular", 10f, t.get("Text Dim"), "default · 1h12m", 16, CARD_H - 19);

        int cx = sidebarW + 16;
        int cw = CARD_W - sidebarW - 32;
        text(g, "bold", 16, t.get("Text"), "Pest Destroyer", cx, 30);
        text(g, "regular", 11, t.get("Text Dim"), "Kills pests between rows and returns to farm.", cx, 47);
        int pillX = cx + cw - 70;
        fill(g, t.get("Accent"), pillX, 16, 70, 22, 11);
        text(g, "semibold", 11, onColour(t.get("Accent")), "Resume", pillX + 15, 31);

        int gy = 60;
        int gh = 168;
        fill(g, t.get("Card BG"), cx, gy, cw, gh, 10);
        stroke(g, t.get("Separator"), cx + 0.5f, gy + 0.5f, cw - 1, gh - 1, 10, 1f);
        fill(g, t.get("Panel BG"), cx, gy, cw, 26, 0);
        g.setClip(new RoundRectangle2D.Float(cx, gy, cw, gh, 20, 20));
        fill(g, t.get("Panel BG"), cx, gy, cw, 26, 0);
        g.setClip(null);
        text(g, "semibold", 10f, t.get("Group Active"), "TARGETING", cx + 12, gy + 17);
        text(g, "regular", 10f, t.get("Text Muted"), "4 settings", cx + cw - 64, gy + 17);

        int rx = cx + 12;
        int rw = cw - 24;
        int ry = gy + 44;
        text(g, "medium", 12, t.get("Text Label"), "Auto Target", rx, ry);
        toggle(g, t, rx + rw - 34, ry - 13, true);
        line(g, t.get("Separator"), rx, ry + 9, rw);
        ry += 30;
        text(g, "medium", 12, t.get("Text Label"), "Prefer Closest", rx, ry);
        toggle(g, t, rx + rw - 34, ry - 13, false);
        line(g, t.get("Separator"), rx, ry + 9, rw);
        ry += 30;
        text(g, "medium", 12, t.get("Text Label"), "Reach", rx, ry);
        int sx = rx + 110;
        int sw = rw - 110 - 58;
        fill(g, t.get("[Toggle] Track"), sx, ry - 6, sw, 4, 2);
        gradient(g, t.get("[Slider] Left"), t.get("Accent"), sx, ry - 6, (int) (sw * 0.62f), 4);
        knob(g, t.get("[Toggle] Knob"), sx + sw * 0.62f, ry - 4, 6.5f);
        fill(g, t.get("Element BG"), rx + rw - 48, ry - 14, 48, 20, 6);
        text(g, "mono", 11, t.get("Text Value"), "4.50", rx + rw - 40, ry);
        line(g, t.get("Separator"), rx, ry + 9, rw);
        ry += 30;
        text(g, "medium", 12, t.get("Text Label"), "Weapon", rx, ry);
        fill(g, t.get("[Dropdown] BG"), rx + rw - 132, ry - 15, 132, 22, 6);
        stroke(g, t.get("Border"), rx + rw - 131.5f, ry - 14.5f, 131, 21, 6, 1f);
        text(g, "regular", 11.5f, t.get("Text Value"), "Fire Veil Wand", rx + rw - 124, ry);
        chevron(g, t.get("Text Muted"), rx + rw - 14, ry - 5);
        text(g, "regular", 10.5f, t.get("Text Muted"), "Hint text in muted", rx, ry + 26);

        int by = gy + gh + 12;
        fill(g, t.get("[Action] BG"), cx, by, 84, 24, 7);
        text(g, "medium", 11, t.get("Text Value"), "Reset", cx + 26, by + 16);
        fill(g, t.get("[Action] Hover"), cx + 92, by, 84, 24, 7);
        text(g, "medium", 11, t.get("Text"), "Hovered", cx + 110, by + 16);
        fill(g, t.get("Input BG"), cx + 184, by, cw - 184 - 150, 24, 7);
        stroke(g, t.get("Accent"), cx + 184.5f, by + 0.5f, cw - 184 - 151, 23, 7, 1.2f);
        text(g, "regular", 11, t.get("Text"), "Search", cx + 194, by + 16);
        fill(g, withAlpha(t.get("Accent"), 0x48), cx + 232, by + 5, 3, 14, 0);

        hud(g, t, cx + cw - 142, by - 4, 142, 70);

        int swatchY = CARD_H - 22;
        String[] keys = {"Sidebar BG", "Main Panel BG", "Panel BG", "Card BG", "Element BG", "Input BG",
                "Hover BG", "Separator", "Border", "[Toggle] Track", "Text Muted", "Text Dim", "Text Value",
                "Text", "[Slider] Left", "Accent", "Accent 2", "HUD Success", "HUD Warning", "HUD Error"};
        int sw2 = 12;
        for (int i = 0; i < keys.length; i++) {
            int x = cx + i * (sw2 + 2);
            fill(g, t.get(keys[i]), x, swatchY, sw2, 12, 3);
        }
        text(g, "regular", 9.5f, t.get("Text Muted"), String.format("min %.1f", minContrast(t)),
                cx + keys.length * (sw2 + 2) + 4, swatchY + 10);
    }

    private void hud(Graphics2D g, Map<String, Integer> t, int x, int y, int w, int h) {
        fill(g, 0x66000000, x, y + 2, w, h, 9);
        fill(g, t.get("HUD Background"), x, y, w, h, 8);
        stroke(g, t.get("HUD Border"), x + 0.4f, y + 0.4f, w - 0.8f, h - 0.8f, 8, 0.8f);
        gradient(g, withAlpha(t.get("HUD Accent"), 0x30), t.get("HUD Accent"), x + 8, y, w - 16, 2);
        text(g, "bold", 10.5f, t.get("HUD Title"), "Macro", x + 8, y + 15);
        text(g, "mono", 9, t.get("HUD Label"), "1h12m", x + w - 40, y + 15);
        fill(g, t.get("HUD Separator"), x + 8, y + 21, w - 16, 1, 0);
        text(g, "regular", 9.5f, t.get("HUD Label"), "Profit/h", x + 8, y + 35);
        text(g, "mono", 9.5f, t.get("HUD Value"), "3.2m", x + w - 36, y + 35);
        text(g, "semibold", 9f, t.get("HUD Success"), "Farming", x + 8, y + 49);
        text(g, "semibold", 9f, t.get("HUD Warning"), "Rest", x + 56, y + 49);
        text(g, "semibold", 9f, t.get("HUD Error"), "Failsafe", x + 86, y + 49);
        fill(g, t.get("HUD Bar BG"), x + 8, y + 57, w - 16, 5, 2);
        fill(g, t.get("HUD Accent"), x + 8, y + 57, (int) ((w - 16) * 0.58f), 5, 2);
    }

    private void toggle(Graphics2D g, Map<String, Integer> t, int x, int y, boolean on) {
        fill(g, on ? t.get("Accent") : t.get("[Toggle] Track"), x, y, 34, 18, 9);
        int knob = on ? onColour(t.get("Accent")) : t.get("[Toggle] Knob");
        knob(g, knob, on ? x + 25 : x + 9, y + 9, 6.5f);
    }

    private static String title(String id) {
        return switch (id) {
            case "tokyonight" -> "Tokyo Night";
            case "rosepine" -> "Rosé Pine";
            default -> Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }

    private static double minContrast(Map<String, Integer> t) {
        double min = 99;
        for (String bg : new String[]{"Main Panel BG", "Card BG", "Sidebar BG", "Element BG"}) {
            for (String fg : new String[]{"Text", "Text Dim", "Text Muted", "Text Label", "Text Value", "Accent"}) {
                min = Math.min(min, contrast(t.get(fg), t.get(bg)));
            }
        }
        return min;
    }

    private static int onColour(int fill) {
        return contrast(fill, 0xFF000000) >= contrast(fill, 0xFFFFFFFF) ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int c) {
        return 0.2126 * linear((c >> 16) & 255) + 0.7152 * linear((c >> 8) & 255) + 0.0722 * linear(c & 255);
    }

    private static double linear(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    private static Color color(int argb) {
        return new Color(argb, true);
    }

    private static void fill(Graphics2D g, int argb, float x, float y, float w, float h, float r) {
        g.setColor(color(argb));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2));
    }

    private static void stroke(Graphics2D g, int argb, float x, float y, float w, float h, float r, float width) {
        g.setColor(color(argb));
        g.setStroke(new BasicStroke(width));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2));
    }

    private static void line(Graphics2D g, int argb, int x, int y, int w) {
        fill(g, argb, x, y, w, 1, 0);
    }

    private static void dot(Graphics2D g, int argb, float cx, float cy, float r) {
        g.setColor(color(argb));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    private static void knob(Graphics2D g, int argb, float cx, float cy, float r) {
        dot(g, 0x40000000, cx, cy + 1f, r + 0.8f);
        dot(g, argb, cx, cy, r);
    }

    private static void gradient(Graphics2D g, int from, int to, float x, float y, float w, float h) {
        g.setPaint(new GradientPaint(x, y, color(from), x + w, y, color(to)));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
    }

    private static void chevron(Graphics2D g, int argb, float x, float y) {
        g.setColor(color(argb));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(new int[]{(int) x - 4, (int) x, (int) x + 4}, new int[]{(int) y - 2, (int) y + 2, (int) y - 2}, 3);
    }

    private void text(Graphics2D g, String role, float size, int argb, String value, float x, float y) {
        g.setFont(font(role, size));
        g.setColor(color(argb));
        g.drawString(value, x, y);
    }
}
