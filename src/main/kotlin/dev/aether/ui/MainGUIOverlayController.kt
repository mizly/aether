package dev.aether.ui

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts

internal class MainGUIOverlayController(private val owner: MainGUI) {

    fun renderDropdownOverlay(nvg: NVGRenderer, mx: Float, my: Float) {
        val dropdown = owner.currentOpenDropdown()
        if (dropdown == null) {
            return
        }

        val options = dropdown.getOptions()
        val overlayH = options.size * MainGUI.DD_ITEM_H + 6f
        var overlayY = if (owner.dropdownOverlayY() + overlayH > owner.panelY() + owner.panelH() - 4f) owner.dropdownOverlayY() - overlayH - owner.dropdownButtonHeight() else owner.dropdownOverlayY()
        overlayY = Math.max(owner.panelY() + 4f, overlayY)

        val slideOffset = (1f - owner.dropdownAnimAmount()) * 5f
        nvg.save()
        nvg.globalAlpha(owner.dropdownAnimAmount())
        nvg.translate(0f, slideOffset)

        nvg.shadow(owner.dropdownOverlayX(), overlayY, owner.dropdownOverlayW(), overlayH, 7f, 16f, Theme.withAlpha(0xFF000000.toInt(), 0.5f))
        nvg.roundedRect(owner.dropdownOverlayX(), overlayY, owner.dropdownOverlayW(), overlayH, 7f, Theme.CARD_BG)
        nvg.rectOutline(owner.dropdownOverlayX(), overlayY, owner.dropdownOverlayW(), overlayH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f))

        var itemY = overlayY + 3f
        for (i in options.indices) {
            val selected = dropdown.getSelectedIndex() == i
            val hovered = mx >= owner.dropdownOverlayX() && mx <= owner.dropdownOverlayX() + owner.dropdownOverlayW() && my >= itemY && my <= itemY + MainGUI.DD_ITEM_H

            if (selected) {
                nvg.roundedRect(owner.dropdownOverlayX() + 4f, itemY, owner.dropdownOverlayW() - 8f, MainGUI.DD_ITEM_H, 5f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f))
            } else if (hovered) {
                nvg.roundedRect(owner.dropdownOverlayX() + 4f, itemY, owner.dropdownOverlayW() - 8f, MainGUI.DD_ITEM_H, 5f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0.05f))
            }

            if (selected) {
                val checkX = owner.dropdownOverlayX() + owner.dropdownOverlayW() - 22f
                val checkY = itemY + MainGUI.DD_ITEM_H / 2f
                nvg.line(checkX, checkY, checkX + 3f, checkY + 3f, 1.5f, Theme.ACCENT_PRIMARY)
                nvg.line(checkX + 3f, checkY + 3f, checkX + 9f, checkY - 4f, 1.5f, Theme.ACCENT_PRIMARY)
            }
            nvg.pushScissor(owner.dropdownOverlayX() + 14f, itemY, owner.dropdownOverlayW() - 44f, MainGUI.DD_ITEM_H)
            nvg.text(Fonts.REGULAR, options[i], owner.dropdownOverlayX() + 14f, itemY + (MainGUI.DD_ITEM_H - 12.5f) / 2f, 12.5f, if (selected) Theme.ACCENT_PRIMARY else if (hovered) Theme.TEXT_LABEL else Theme.TEXT_VALUE)
            nvg.popScissor()
            itemY += MainGUI.DD_ITEM_H
        }

        nvg.restore()
    }

    fun renderColorOverlay(nvg: NVGRenderer) {
        val pad = 10f
        val svW = 220f
        val svH = 140f
        val barH = 12f
        val hexH = 26f

        val panelW = svW + pad * 2f
        val panelH = pad + svH + 8f + barH + 8f + barH + 10f + hexH + pad

        val panelX = Math.max(owner.panelX() + 4f, Math.min(owner.panelX() + owner.panelW() - panelW - 4f, owner.panelX() + (owner.panelW() - panelW) / 2f))
        val panelY = Math.max(owner.panelY() + 4f, Math.min(owner.panelY() + owner.panelH() - panelH - 4f, owner.panelY() + (owner.panelH() - panelH) / 2f))
        owner.setColorPickerPanelBounds(panelX, panelY, panelW, panelH)

        nvg.shadow(panelX, panelY, panelW, panelH, 8f, 24f, Theme.withAlpha(0xFF000000.toInt(), 0.7f))
        nvg.roundedRect(panelX, panelY, panelW, panelH, 8f, 0xFF181820.toInt())
        nvg.rectOutline(panelX, panelY, panelW, panelH, 8f, 1f, Theme.ACCENT_PRIMARY)

        val svX = panelX + pad
        val svY = panelY + pad
        owner.setColorPickerSvBounds(svX, svY, svW, svH)

        val hueColor = hsvToArgb(owner.colorHue(), 1f, 1f, 255)
        nvg.horizontalGradient(svX, svY, svW, svH, 4f, 0xFFFFFFFF.toInt(), hueColor)
        nvg.linearGradient(svX, svY, svW, svH, 4f, 0x00000000, 0xFF000000.toInt())
        nvg.rectOutline(svX, svY, svW, svH, 4f, 0.5f, Theme.withAlpha(0xFFFFFFFF.toInt(), 30))
        val knobX = svX + owner.colorSaturation() * svW
        val knobY = svY + (1f - owner.colorValue()) * svH
        nvg.circle(knobX, knobY + 1f, 7f, Theme.withAlpha(0xFF000000.toInt(), 120))
        nvg.circle(knobX, knobY, 6f, 0xFFFFFFFF.toInt())
        nvg.circle(knobX, knobY, 4f, hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), 255))

        val hueBarY = svY + svH + 8f
        renderHueBar(nvg, svX, hueBarY, svW, barH)
        val hueIndicator = svX + owner.colorHue() * svW
        nvg.roundedRect(hueIndicator - 2f, hueBarY - 2f, 4f, barH + 4f, 2f, Theme.withAlpha(0xFFFFFFFF.toInt(), 220))

        val alphaBarY = hueBarY + barH + 8f
        val fullColor = hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), 255)
        renderAlphaBar(nvg, svX, alphaBarY, svW, barH, fullColor)
        val alphaIndicator = svX + owner.colorAlpha() * svW
        nvg.roundedRect(alphaIndicator - 2f, alphaBarY - 2f, 4f, barH + 4f, 2f, Theme.withAlpha(0xFFFFFFFF.toInt(), 220))
        owner.setColorPickerBarBounds(hueBarY, alphaBarY, barH)

        val rowY = alphaBarY + barH + 10f
        val hexW = svW - 44f
        owner.setColorPickerHexBounds(svX, rowY, hexW, hexH)

        val currentArgb = hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), (owner.colorAlpha() * 255).toInt())
        nvg.roundedRect(svX, rowY, hexW, hexH, 3f, Theme.BG_FIELD)
        nvg.rectOutline(svX, rowY, hexW, hexH, 3f, 1f, if (owner.isColorHexFocused()) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        nvg.text(Fonts.REGULAR, "#", svX + 6f, rowY + 7f, 11f, Theme.TEXT_SECONDARY)
        val hexText = if (owner.isColorHexFocused()) owner.colorHexBuffer().toString() else String.format("%06X", currentArgb and 0xFFFFFF)
        nvg.text(Fonts.MONO, hexText, svX + 16f, rowY + 7f, 11f, Theme.TEXT_PRIMARY)

        val swatchX = svX + hexW + 8f
        nvg.roundedRect(swatchX, rowY, 36f, hexH, 3f, currentArgb or 0xFF000000.toInt())
        nvg.rectOutline(swatchX, rowY, 36f, hexH, 3f, 1f, Theme.BORDER_DEFAULT)
    }

    fun argbToHsv(argb: Int) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = Math.max(r, Math.max(g, b))
        val min = Math.min(r, Math.min(g, b))
        val delta = max - min

        owner.setColorValue(max)
        owner.setColorSaturation(if (max == 0f) 0f else delta / max)
        owner.setColorAlpha(((argb shr 24) and 0xFF) / 255f)
        if (delta == 0f) {
            owner.setColorHue(0f)
            return
        }

        var hue: Float
        if (max == r) {
            hue = ((g - b) / delta % 6f) / 6f
        } else if (max == g) {
            hue = ((b - r) / delta + 2f) / 6f
        } else {
            hue = ((r - g) / delta + 4f) / 6f
        }
        if (hue < 0f) {
            hue += 1f
        }
        owner.setColorHue(hue)
    }

    fun updateColorFromHsv() {
        val activeColor = owner.activeColorSetting()
        if (activeColor != null) {
            activeColor.setValue(hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), (owner.colorAlpha() * 255).toInt()))
        }
    }

    fun handleColorPickerClick(mx: Float, my: Float): Boolean {
        if (mx >= owner.colorPickerSvX() && mx <= owner.colorPickerSvX() + owner.colorPickerSvW() && my >= owner.colorPickerSvY() && my <= owner.colorPickerSvY() + owner.colorPickerSvH()) {
            owner.setColorSaturation(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())))
            owner.setColorValue(1f - Math.max(0f, Math.min(1f, (my - owner.colorPickerSvY()) / owner.colorPickerSvH())))
            owner.setColorDragMode(MainGUI.CP_SV)
            updateColorFromHsv()
            return true
        }
        if (mx >= owner.colorPickerSvX() && mx <= owner.colorPickerSvX() + owner.colorPickerSvW() && my >= owner.colorPickerHueBarY() && my <= owner.colorPickerHueBarY() + owner.colorPickerBarH()) {
            owner.setColorHue(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())))
            owner.setColorDragMode(MainGUI.CP_HUE)
            updateColorFromHsv()
            return true
        }
        if (mx >= owner.colorPickerSvX() && mx <= owner.colorPickerSvX() + owner.colorPickerSvW() && my >= owner.colorPickerAlphaBarY() && my <= owner.colorPickerAlphaBarY() + owner.colorPickerBarH()) {
            owner.setColorAlpha(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())))
            owner.setColorDragMode(MainGUI.CP_ALPHA)
            updateColorFromHsv()
            return true
        }
        if (mx >= owner.colorPickerHexX() && mx <= owner.colorPickerHexX() + owner.colorPickerHexW() && my >= owner.colorPickerHexY() && my <= owner.colorPickerHexY() + owner.colorPickerHexH()) {
            owner.setColorHexFocused(true)
            owner.colorHexBuffer().setLength(0)
            owner.colorHexBuffer().append(String.format("%06X", hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), 255) and 0xFFFFFF))
            return true
        }
        if (mx >= owner.colorPickerPanelX() && mx <= owner.colorPickerPanelX() + owner.colorPickerPanelW() && my >= owner.colorPickerPanelY() && my <= owner.colorPickerPanelY() + owner.colorPickerPanelH()) {
            owner.setColorHexFocused(false)
            return true
        }
        return false
    }

    fun handleColorPickerDrag(mx: Float, my: Float) {
        when (owner.colorDragMode()) {
            MainGUI.CP_SV -> {
                owner.setColorSaturation(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())))
                owner.setColorValue(1f - Math.max(0f, Math.min(1f, (my - owner.colorPickerSvY()) / owner.colorPickerSvH())))
            }
            MainGUI.CP_HUE -> owner.setColorHue(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())))
            MainGUI.CP_ALPHA -> owner.setColorAlpha(Math.max(0f, Math.min(1f, (mx - owner.colorPickerSvX()) / owner.colorPickerSvW())))
            else -> {
                return
            }
        }
        updateColorFromHsv()
    }

    fun handleDropdownClick(mx: Float, my: Float) {
        val dropdown = owner.currentOpenDropdown()
        if (dropdown == null) {
            return
        }
        val options = dropdown.getOptions()
        val overlayH = options.size * MainGUI.DD_ITEM_H + 4f
        var overlayY = if (owner.dropdownOverlayY() + overlayH > owner.panelY() + owner.panelH() - 4f) owner.dropdownOverlayY() - overlayH - owner.dropdownButtonHeight() else owner.dropdownOverlayY()
        overlayY = Math.max(owner.panelY() + 4f, overlayY)

        if (mx >= owner.dropdownOverlayX() && mx <= owner.dropdownOverlayX() + owner.dropdownOverlayW() && my >= overlayY && my <= overlayY + overlayH) {
            val idx = ((my - overlayY - 2f) / MainGUI.DD_ITEM_H).toInt()
            if (idx >= 0 && idx < options.size) {
                dropdown.setSelectedIndex(idx)
            }
        }
        owner.closeOpenDropdown()
    }

    fun commitColor() {
        val activeColor = owner.activeColorSetting()
        if (activeColor == null) {
            return
        }
        if (owner.isColorHexFocused() && owner.colorHexBuffer().length == 6) {
            try {
                val rgb = java.lang.Long.parseLong(owner.colorHexBuffer().toString(), 16).toInt()
                argbToHsv(0xFF000000.toInt() or rgb)
            } catch (ignored: NumberFormatException) {
            }
        }
        activeColor.setValue(hsvToArgb(owner.colorHue(), owner.colorSaturation(), owner.colorValue(), (owner.colorAlpha() * 255).toInt()))
        owner.clearActiveColorSetting()
        owner.setColorHexFocused(false)
        owner.setColorDragMode(0)
    }

    fun handleHexKeyPressed(key: Int): Boolean {
        if (!owner.isColorHexFocused() || owner.activeColorSetting() == null) {
            return false
        }
        if (key == InputConstants.KEY_BACKSPACE && !owner.colorHexBuffer().isEmpty()) {
            owner.colorHexBuffer().deleteCharAt(owner.colorHexBuffer().length - 1)
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            commitColor()
        }
        return true
    }

    fun handleHexChar(ch: Char): Boolean {
        if (!owner.isColorHexFocused() || owner.activeColorSetting() == null || owner.colorHexBuffer().length >= 6 || !isHex(ch)) {
            return false
        }
        owner.colorHexBuffer().append(ch)
        if (owner.colorHexBuffer().length == 6) {
            try {
                val rgb = java.lang.Long.parseLong(owner.colorHexBuffer().toString(), 16).toInt()
                argbToHsv(0xFF000000.toInt() or rgb)
                updateColorFromHsv()
            } catch (ignored: NumberFormatException) {
            }
        }
        return true
    }

    private fun renderHueBar(nvg: NVGRenderer, x: Float, y: Float, w: Float, h: Float) {
        val stops = intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
        val segW = w / 6f
        for (i in 0 until 6) {
            nvg.horizontalGradient(x + i * segW, y, segW + 0.5f, h, 0f, stops[i], stops[i + 1])
        }
        nvg.rectOutline(x, y, w, h, 2f, 0.5f, Theme.withAlpha(0xFFFFFFFF.toInt(), 20))
    }

    private fun renderAlphaBar(nvg: NVGRenderer, x: Float, y: Float, w: Float, h: Float, solidColor: Int) {
        val sq = h / 2f
        var bx = x
        while (bx < x + w) {
            val s1 = Math.min(sq, x + w - bx)
            val s2 = Math.min(sq, x + w - (bx + sq))
            if (s1 > 0f) nvg.rect(bx, y, s1, sq, 0xFF404040.toInt())
            if (s2 > 0f) nvg.rect(bx + sq, y + sq, s2, sq, 0xFF404040.toInt())
            if (s1 > 0f) nvg.rect(bx, y + sq, s1, sq, 0xFF606060.toInt())
            if (s2 > 0f) nvg.rect(bx + sq, y, s2, sq, 0xFF606060.toInt())
            bx += sq * 2f
        }
        nvg.horizontalGradient(x, y, w, h, 2f, 0x00000000, solidColor)
        nvg.rectOutline(x, y, w, h, 2f, 0.5f, Theme.withAlpha(0xFFFFFFFF.toInt(), 20))
    }

    companion object {
        private fun isHex(c: Char): Boolean {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
        }

        private fun hsvToArgb(h0: Float, s: Float, v: Float, alpha0: Int): Int {
            val h = ((h0 % 1f) + 1f) % 1f
            val i = (h * 6f).toInt()
            val f = h * 6f - i
            val p = v * (1f - s)
            val q = v * (1f - f * s)
            val t = v * (1f - (1f - f) * s)
            val r: Float
            val g: Float
            val b: Float
            when (i % 6) {
                0 -> {
                    r = v
                    g = t
                    b = p
                }
                1 -> {
                    r = q
                    g = v
                    b = p
                }
                2 -> {
                    r = p
                    g = v
                    b = t
                }
                3 -> {
                    r = p
                    g = q
                    b = v
                }
                4 -> {
                    r = t
                    g = p
                    b = v
                }
                else -> {
                    r = v
                    g = p
                    b = q
                }
            }
            val alpha = Math.max(0, Math.min(255, alpha0))
            return (alpha shl 24) or ((r * 255f).toInt() shl 16) or ((g * 255f).toInt() shl 8) or (b * 255f).toInt()
        }
    }
}
