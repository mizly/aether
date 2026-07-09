package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import org.lwjgl.glfw.GLFW;

import java.util.List;

final class MainGUIOverlayController {
    private final MainGUI owner;

    MainGUIOverlayController(MainGUI owner) {
        this.owner = owner;
    }

    void renderDropdownOverlay(NVGRenderer nvg, float mx, float my) {
        DropdownSetting dropdown = owner.currentOpenDropdown();
        if (dropdown == null) {
            return;
        }

        List<String> options = dropdown.getOptions();
        float overlayH = options.size() * MainGUI.DD_ITEM_H + 6f;
        float overlayY = (owner.dropdownOverlayY() + overlayH > owner.panelY() + owner.panelH() - 4f)
                ? owner.dropdownOverlayY() - overlayH - owner.dropdownButtonHeight()
                : owner.dropdownOverlayY();
        overlayY = Math.max(owner.panelY() + 4f, overlayY);

        float slideOffset = (1f - owner.dropdownAnimAmount()) * 5f;
        nvg.save();
        nvg.globalAlpha(owner.dropdownAnimAmount());
        nvg.translate(0, slideOffset);

        nvg.shadow(owner.dropdownOverlayX(), overlayY, owner.dropdownOverlayW(), overlayH, 7f, 16f,
                Theme.withAlpha(0xFF000000, 0.5f));
        nvg.roundedRect(owner.dropdownOverlayX(), overlayY, owner.dropdownOverlayW(), overlayH, 7f, Theme.CARD_BG);
        nvg.rectOutline(owner.dropdownOverlayX(), overlayY, owner.dropdownOverlayW(), overlayH, 7f, 1f,
                Theme.withAlpha(0xFFFFFFFF, 0.06f));

        float itemY = overlayY + 3f;
        for (int i = 0; i < options.size(); i++) {
            boolean selected = dropdown.getSelectedIndex() == i;
            boolean hovered = mx >= owner.dropdownOverlayX() && mx <= owner.dropdownOverlayX() + owner.dropdownOverlayW()
                    && my >= itemY && my <= itemY + MainGUI.DD_ITEM_H;

            if (selected) {
                nvg.roundedRect(owner.dropdownOverlayX() + 4f, itemY, owner.dropdownOverlayW() - 8f, MainGUI.DD_ITEM_H, 5f,
                        Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f));
            } else if (hovered) {
                nvg.roundedRect(owner.dropdownOverlayX() + 4f, itemY, owner.dropdownOverlayW() - 8f, MainGUI.DD_ITEM_H, 5f,
                        Theme.withAlpha(0xFFFFFFFF, 0.05f));
            }

            if (selected) {
                nvg.circle(owner.dropdownOverlayX() + 14f, itemY + MainGUI.DD_ITEM_H / 2f, 3f, Theme.ACCENT_PRIMARY);
                nvg.text(Fonts.REGULAR, options.get(i), owner.dropdownOverlayX() + 24f,
                        itemY + (MainGUI.DD_ITEM_H - 12.5f) / 2f, 12.5f, Theme.ACCENT_PRIMARY);
            } else {
                nvg.text(Fonts.REGULAR, options.get(i), owner.dropdownOverlayX() + 14f,
                        itemY + (MainGUI.DD_ITEM_H - 12.5f) / 2f, 12.5f,
                        hovered ? Theme.TEXT_LABEL : Theme.TEXT_VALUE);
            }
            itemY += MainGUI.DD_ITEM_H;
        }

        nvg.restore();
    }

    void renderColorOverlay(NVGRenderer nvg) {
        final float pad = 10f;
        final float svW = 220f;
        final float svH = 140f;
        final float barH = 12f;
        final float hexH = 26f;

        float panelW = svW + pad * 2f;
        float panelH = pad + svH + 8f + barH + 8f + barH + 10f + hexH + pad;

        float panelX = Math.max(owner.panelX() + 4f,
                Math.min(owner.panelX() + owner.panelW() - panelW - 4f, owner.panelX() + (owner.panelW() - panelW) / 2f));
        float panelY = Math.max(owner.panelY() + 4f,
                Math.min(owner.panelY() + owner.panelH() - panelH - 4f, owner.panelY() + (owner.panelH() - panelH) / 2f));
        owner.setColorPickerPanelBounds(panelX, panelY, panelW, panelH);

        nvg.shadow(panelX, panelY, panelW, panelH, 8f, 24f, Theme.withAlpha(0xFF000000, 0.7f));
        nvg.roundedRect(panelX, panelY, panelW, panelH, 8f, 0xFF181820);
        nvg.rectOutline(panelX, panelY, panelW, panelH, 8f, 1f, Theme.ACCENT_PRIMARY);

        float svX = panelX + pad;
        float svY = panelY + pad;
        owner.setColorPickerSvBounds(svX, svY, svW, svH);

        int hueColor = hsvToArgb(owner.colorHue(), 1f, 1f, 255);
        nvg.horizontalGradient(svX, svY, svW, svH, 4f, 0xFFFFFFFF, hueColor);
        nvg.linearGradient(svX, svY, svW, svH, 4f, 0x00000000, 0xFF000000);
        nvg.rectOutline(svX, svY, svW, svH, 4f, 0.5f, Theme.withAlpha(0xFFFFFFFF, 30));
        float knobX = svX + owner.colorSaturation() * svW;
        float knobY = svY + (1f - owner.colorValue()) * svH;
        nvg.circle(knobX, knobY + 1f, 7f, Theme.withAlpha(0xFF000000, 120));
        nvg.circle(knobX, knobY, 6f, 0xFFFFFFFF);
        nvg.circle(knobX, knobY, 4f, hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), 255));

        float hueBarY = svY + svH + 8f;
        renderHueBar(nvg, svX, hueBarY, svW, barH);
        float hueIndicator = svX + owner.colorHue() * svW;
        nvg.roundedRect(hueIndicator - 2f, hueBarY - 2f, 4f, barH + 4f, 2f, Theme.withAlpha(0xFFFFFFFF, 220));

        float alphaBarY = hueBarY + barH + 8f;
        int fullColor = hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), 255);
        renderAlphaBar(nvg, svX, alphaBarY, svW, barH, fullColor);
        float alphaIndicator = svX + owner.colorAlpha() * svW;
        nvg.roundedRect(alphaIndicator - 2f, alphaBarY - 2f, 4f, barH + 4f, 2f, Theme.withAlpha(0xFFFFFFFF, 220));
        owner.setColorPickerBarBounds(hueBarY, alphaBarY, barH);

        float rowY = alphaBarY + barH + 10f;
        float hexW = svW - 44f;
        owner.setColorPickerHexBounds(svX, rowY, hexW, hexH);

        int currentArgb = hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), (int) (owner.colorAlpha() * 255));
        nvg.roundedRect(svX, rowY, hexW, hexH, 3f, Theme.BG_FIELD);
        nvg.rectOutline(svX, rowY, hexW, hexH, 3f, 1f,
                owner.isColorHexFocused() ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        nvg.text(Fonts.REGULAR, "#", svX + 6f, rowY + 7f, 11f, Theme.TEXT_SECONDARY);
        String hexText = owner.isColorHexFocused()
                ? owner.colorHexBuffer().toString()
                : String.format("%06X", currentArgb & 0xFFFFFF);
        nvg.text(Fonts.MONO, hexText, svX + 16f, rowY + 7f, 11f, Theme.TEXT_PRIMARY);

        float swatchX = svX + hexW + 8f;
        nvg.roundedRect(swatchX, rowY, 36f, hexH, 3f, currentArgb | 0xFF000000);
        nvg.rectOutline(swatchX, rowY, 36f, hexH, 3f, 1f, Theme.BORDER_DEFAULT);
    }

    void argbToHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        owner.setColorValue(max);
        owner.setColorSaturation(max == 0f ? 0f : delta / max);
        owner.setColorAlpha(((argb >> 24) & 0xFF) / 255f);
        if (delta == 0f) {
            owner.setColorHue(0f);
            return;
        }

        float hue;
        if (max == r) {
            hue = ((g - b) / delta % 6f) / 6f;
        } else if (max == g) {
            hue = ((b - r) / delta + 2f) / 6f;
        } else {
            hue = ((r - g) / delta + 4f) / 6f;
        }
        if (hue < 0f) {
            hue += 1f;
        }
        owner.setColorHue(hue);
    }

    void updateColorFromHsv() {
        ColorSetting activeColor = owner.activeColorSetting();
        if (activeColor != null) {
            activeColor.setValue(hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), (int) (owner.colorAlpha() * 255)));
        }
    }

    boolean handleColorPickerClick(float mx, float my) {
        if (mx >= owner.colorPickerSvX() && mx <= owner.colorPickerSvX() + owner.colorPickerSvW()
                && my >= owner.colorPickerSvY() && my <= owner.colorPickerSvY() + owner.colorPickerSvH()) {
            owner.setColorSaturation(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())));
            owner.setColorValue(1f - Math.max(0f, Math.min(1f, (my - owner.colorPickerSvY()) / owner.colorPickerSvH())));
            owner.setColorDragMode(MainGUI.CP_SV);
            updateColorFromHsv();
            return true;
        }
        if (mx >= owner.colorPickerSvX() && mx <= owner.colorPickerSvX() + owner.colorPickerSvW()
                && my >= owner.colorPickerHueBarY() && my <= owner.colorPickerHueBarY() + owner.colorPickerBarH()) {
            owner.setColorHue(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())));
            owner.setColorDragMode(MainGUI.CP_HUE);
            updateColorFromHsv();
            return true;
        }
        if (mx >= owner.colorPickerSvX() && mx <= owner.colorPickerSvX() + owner.colorPickerSvW()
                && my >= owner.colorPickerAlphaBarY() && my <= owner.colorPickerAlphaBarY() + owner.colorPickerBarH()) {
            owner.setColorAlpha(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())));
            owner.setColorDragMode(MainGUI.CP_ALPHA);
            updateColorFromHsv();
            return true;
        }
        if (mx >= owner.colorPickerHexX() && mx <= owner.colorPickerHexX() + owner.colorPickerHexW()
                && my >= owner.colorPickerHexY() && my <= owner.colorPickerHexY() + owner.colorPickerHexH()) {
            owner.setColorHexFocused(true);
            owner.colorHexBuffer().setLength(0);
            owner.colorHexBuffer().append(String.format("%06X", hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), 255) & 0xFFFFFF));
            return true;
        }
        if (mx >= owner.colorPickerPanelX() && mx <= owner.colorPickerPanelX() + owner.colorPickerPanelW()
                && my >= owner.colorPickerPanelY() && my <= owner.colorPickerPanelY() + owner.colorPickerPanelH()) {
            owner.setColorHexFocused(false);
            return true;
        }
        return false;
    }

    void handleColorPickerDrag(float mx, float my) {
        switch (owner.colorDragMode()) {
            case MainGUI.CP_SV -> {
                owner.setColorSaturation(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())));
                owner.setColorValue(1f - Math.max(0f, Math.min(1f, (my - owner.colorPickerSvY()) / owner.colorPickerSvH())));
            }
            case MainGUI.CP_HUE -> owner.setColorHue(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())));
            case MainGUI.CP_ALPHA -> owner.setColorAlpha(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())));
            default -> {
                return;
            }
        }
        updateColorFromHsv();
    }

    void handleDropdownClick(float mx, float my) {
        DropdownSetting dropdown = owner.currentOpenDropdown();
        if (dropdown == null) {
            return;
        }
        List<String> options = dropdown.getOptions();
        float overlayH = options.size() * MainGUI.DD_ITEM_H + 4f;
        float overlayY = (owner.dropdownOverlayY() + overlayH > owner.panelY() + owner.panelH() - 4f)
                ? owner.dropdownOverlayY() - overlayH - owner.dropdownButtonHeight()
                : owner.dropdownOverlayY();
        overlayY = Math.max(owner.panelY() + 4f, overlayY);

        if (mx >= owner.dropdownOverlayX() && mx <= owner.dropdownOverlayX() + owner.dropdownOverlayW()
                && my >= overlayY && my <= overlayY + overlayH) {
            int idx = (int) ((my - overlayY - 2f) / MainGUI.DD_ITEM_H);
            if (idx >= 0 && idx < options.size()) {
                dropdown.setSelectedIndex(idx);
            }
        }
        owner.closeOpenDropdown();
    }

    void commitColor() {
        ColorSetting activeColor = owner.activeColorSetting();
        if (activeColor == null) {
            return;
        }
        if (owner.isColorHexFocused() && owner.colorHexBuffer().length() == 6) {
            try {
                int rgb = (int) Long.parseLong(owner.colorHexBuffer().toString(), 16);
                argbToHsv(0xFF000000 | rgb);
            } catch (NumberFormatException ignored) {
            }
        }
        activeColor.setValue(hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), (int) (owner.colorAlpha() * 255)));
        owner.clearActiveColorSetting();
        owner.setColorHexFocused(false);
        owner.setColorDragMode(0);
    }

    boolean handleHexKeyPressed(int key) {
        if (!owner.isColorHexFocused() || owner.activeColorSetting() == null) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && !owner.colorHexBuffer().isEmpty()) {
            owner.colorHexBuffer().deleteCharAt(owner.colorHexBuffer().length() - 1);
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            commitColor();
        }
        return true;
    }

    boolean handleHexChar(char ch) {
        if (!owner.isColorHexFocused() || owner.activeColorSetting() == null || owner.colorHexBuffer().length() >= 6 || !isHex(ch)) {
            return false;
        }
        owner.colorHexBuffer().append(ch);
        if (owner.colorHexBuffer().length() == 6) {
            try {
                int rgb = (int) Long.parseLong(owner.colorHexBuffer().toString(), 16);
                argbToHsv(0xFF000000 | rgb);
                updateColorFromHsv();
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    private void renderHueBar(NVGRenderer nvg, float x, float y, float w, float h) {
        int[] stops = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        };
        float segW = w / 6f;
        for (int i = 0; i < 6; i++) {
            nvg.horizontalGradient(x + i * segW, y, segW + 0.5f, h, 0f, stops[i], stops[i + 1]);
        }
        nvg.rectOutline(x, y, w, h, 2f, 0.5f, Theme.withAlpha(0xFFFFFFFF, 20));
    }

    private void renderAlphaBar(NVGRenderer nvg, float x, float y, float w, float h, int solidColor) {
        float sq = h / 2f;
        for (float bx = x; bx < x + w; bx += sq * 2f) {
            float s1 = Math.min(sq, x + w - bx);
            float s2 = Math.min(sq, x + w - (bx + sq));
            if (s1 > 0f) nvg.rect(bx, y, s1, sq, 0xFF404040);
            if (s2 > 0f) nvg.rect(bx + sq, y + sq, s2, sq, 0xFF404040);
            if (s1 > 0f) nvg.rect(bx, y + sq, s1, sq, 0xFF606060);
            if (s2 > 0f) nvg.rect(bx + sq, y, s2, sq, 0xFF606060);
        }
        nvg.horizontalGradient(x, y, w, h, 2f, 0x00000000, solidColor);
        nvg.rectOutline(x, y, w, h, 2f, 0.5f, Theme.withAlpha(0xFFFFFFFF, 20));
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hsvToArgb(float h, float s, float v, int alpha) {
        h = ((h % 1f) + 1f) % 1f;
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> {
                r = v;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = v;
                b = p;
            }
            case 2 -> {
                r = p;
                g = v;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = v;
            }
            case 4 -> {
                r = t;
                g = p;
                b = v;
            }
            default -> {
                r = v;
                g = p;
                b = q;
            }
        }
        alpha = Math.max(0, Math.min(255, alpha));
        return (alpha << 24) | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (b * 255f);
    }
}
