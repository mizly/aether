package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import java.util.IdentityHashMap

internal class MainGUIRenderPrimitives(private val owner: MainGUI, private val textLayout: MainGUITextLayout) {
    private val pillAnim: IdentityHashMap<Any, Float> = IdentityHashMap()

    fun renderSectionHeader(nvg: NVGRenderer, name: String, x: Float, y: Float, w: Float) {
        val localized = AetherLang.localize(name)
        val textW = nvg.textWidth(Fonts.BOLD, localized, 11f)
        nvg.text(Fonts.BOLD, localized, x, y + (MainGUI.SECT_H - 11f) / 2f, 11f, Theme.TEXT_MUTED)
        val lineX = x + textW + 10f
        val lineY = y + MainGUI.SECT_H / 2f
        nvg.rect(lineX, lineY, w - textW - 10f, 1f, Theme.SEPARATOR)
    }

    fun renderModuleCard(nvg: NVGRenderer, subTab: ModulesTab.SubTab, x: Float, y: Float, w: Float, hover: Float, mx: Float, my: Float) {
        val height = MainGUI.CARD_H
        val lift = hover * 4f
        val enabled = subTab.isEnabled()

        nvg.save()
        nvg.translate(0f, -lift)

        if (hover > 0.01f) {
            nvg.shadow(x, y, w, height, 7f, 14f, Theme.withAlpha(Theme.ACCENT_PRIMARY, hover * 0.18f))
        }

        nvg.roundedRect(x, y, w, height, 7f, Theme.CARD_BG)
        if (enabled) {
            nvg.roundedRect(x, y, w, height, 7f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.12f))
            nvg.rectOutlineSolid(x, y, w, height, 7f, 1f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.4f))
        } else {
            val border = if (hover > 0.01f) Theme.blend(Theme.SEPARATOR, Theme.BORDER_HOVER, hover) else Theme.SEPARATOR
            nvg.rectOutlineSolid(x, y, w, height, 7f, 1f, border)
            if (hover > 0.01f) {
                nvg.roundedRect(x, y, w, height, 7f, Theme.withAlpha(0xFFFFFFFF.toInt(), hover * 0.04f))
            }
        }

        val pillX = x + w - 52f
        val pillY = y + (height - MainGUI.PILL_H) / 2f
        if (subTab.hasToggle()) {
            renderPill(nvg, pillX, pillY, enabled, subTab)
        }

        val textX = x + 16f
        val nameColor = if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_VALUE
        val descriptionColor = if (enabled) Theme.TEXT_SECONDARY else Theme.TEXT_MUTED
        val textMaxW = Math.max(80f, w - 32f - (if (subTab.hasToggle()) 62f else 0f))
        val nameLines = textLayout.wrapTextToWidth(nvg, AetherLang.localize(subTab.name()), Fonts.BOLD, 13f, textMaxW)
        val descriptionLines = textLayout.wrapTextToWidth(nvg, AetherLang.localize(subTab.description()), Fonts.REGULAR, 10f, textMaxW)
        val descriptionY = y + 38f + Math.max(0, Math.min(2, nameLines.size) - 1) * 12f
        for (i in 0 until Math.min(2, nameLines.size)) {
            nvg.text(Fonts.BOLD, nameLines[i], textX, y + 19f + i * 15f, 13f, nameColor)
        }
        for (i in 0 until Math.min(2, descriptionLines.size)) {
            nvg.text(Fonts.REGULAR, descriptionLines[i], textX, descriptionY + i * 12f, 10f, descriptionColor)
        }

        nvg.restore()

        owner.offerHoverHelp("module:" + subTab.name(), AetherLang.localize(subTab.name()), AetherLang.localize(subTab.description()), x, y, w, height, mx, my)
        if (subTab.hasToggle()) {
            owner.addClickArea(pillX - 6f, pillY - 6f, 50f, MainGUI.PILL_H + 12f, Runnable { subTab.toggle() })
        }
        owner.addClickArea(x, y, w - 56f, height, Runnable { owner.openModuleDetailFromCard(subTab) })
    }

    fun renderListActionButton(nvg: NVGRenderer, x: Float, y: Float, w: Float, label: String, hovered: Boolean, enabled: Boolean) {
        val bg = if (!enabled) {
            Theme.withAlpha(Theme.BG_FIELD, 0.7f)
        } else if (hovered) {
            Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.16f)
        } else {
            Theme.BG_FIELD
        }
        val border = if (!enabled) {
            Theme.withAlpha(Theme.BORDER_DEFAULT, 0.6f)
        } else if (hovered) {
            Theme.ACCENT_PRIMARY
        } else {
            Theme.BORDER_DEFAULT
        }
        val text = if (!enabled) {
            Theme.TEXT_DIM
        } else if (hovered) {
            Theme.ACCENT_PRIMARY
        } else {
            Theme.TEXT_SECONDARY
        }

        nvg.roundedRect(x, y, w, MainGUI.LIST_ITEM_H, 3f, bg)
        nvg.rectOutline(x, y, w, MainGUI.LIST_ITEM_H, 3f, 1f, border)
        nvg.textCentered(Fonts.BOLD, label, x, y + 0.5f, w, MainGUI.LIST_ITEM_H, 12f, text)
    }

    fun renderPill(nvg: NVGRenderer, x: Float, y: Float, on: Boolean, key: Any) {
        val t = pillAnimValue(key, on)
        val boxX = x + 10f
        val boxY = y + (MainGUI.PILL_H - 18f) / 2f
        nvg.roundedRect(boxX, boxY, 18f, 18f, 4f, Theme.blend(Theme.BG_FIELD, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.18f), t))
        nvg.rectOutline(boxX, boxY, 18f, 18f, 4f, 1f, Theme.blend(Theme.BORDER_HOVER, Theme.ACCENT_PRIMARY, t))
        if (t > 0.01f) {
            val color = Theme.withAlpha(Theme.ACCENT_PRIMARY, t)
            nvg.line(boxX + 4f, boxY + 9f, boxX + 8f, boxY + 13f, 1.8f, color)
            nvg.line(boxX + 8f, boxY + 13f, boxX + 14f, boxY + 5f, 1.8f, color)
        }
    }

    fun renderDropdownActionButtons(nvg: NVGRenderer, dropdown: DropdownSetting, fieldX: Float, fieldY: Float, fieldH: Float, mx: Float, my: Float) {
        val startX = textLayout.dropdownActionStartX(dropdown, fieldX)
        if (startX >= fieldX) {
            return
        }

        val iconSize = 16f
        val iconActions = dropdown.getIconActions()
        for (i in iconActions.indices) {
            val action = iconActions[i]
            val actionX = startX + i * (MainGUI.DROPDOWN_ACTION_W + MainGUI.DROPDOWN_ACTION_GAP)
            val hovered = mx >= actionX && mx <= actionX + MainGUI.DROPDOWN_ACTION_W && my >= fieldY && my <= fieldY + fieldH
            val bg = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.12f) else Theme.ELEMENT_BG
            val border = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.4f) else Theme.BORDER_DEFAULT
            val iconColor = if (hovered) Theme.ACCENT_PRIMARY else Theme.TEXT_MUTED

            nvg.roundedRect(actionX, fieldY, MainGUI.DROPDOWN_ACTION_W, fieldH, 7f, bg)
            nvg.rectOutline(actionX, fieldY, MainGUI.DROPDOWN_ACTION_W, fieldH, 7f, 1f, border)
            nvg.renderSVG(action.iconPath(), actionX + (MainGUI.DROPDOWN_ACTION_W - iconSize) / 2f, fieldY + (fieldH - iconSize) / 2f, iconSize, iconSize, iconColor)
        }
    }

    private fun pillAnimValue(key: Any, on: Boolean): Float {
        val target = if (on) 1f else 0f
        var current = pillAnim.getOrDefault(key, target)
        val animationFactor = Theme.animationFactor()
        current += (target - current) * Math.min(1f, animationFactor * 3f)
        pillAnim.put(key, current)
        return current
    }
}
