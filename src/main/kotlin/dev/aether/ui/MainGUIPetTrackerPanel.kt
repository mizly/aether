package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang

final class MainGUIPetTrackerPanel(private val owner: MainGUI) {
    fun renderGroup(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        nvg.rect(x, y, w, 1f, Theme.BORDER_DEFAULT)
        val fields = resolveFields(group) ?: return
        val layout = Layout(x, y, w)
        renderCompactTextField(nvg, fields.petName, layout.nameX, layout.row1Y, layout.nameW, MainGUI.petFieldHeight())
        renderCompactDropdownField(nvg, fields.maxLevel, layout.maxLevelX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight())
        renderCompactDropdownField(nvg, fields.rarity, layout.rarityX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight())
        renderCompactTextField(nvg, fields.level1Price, layout.level1X, layout.row2Y, layout.halfW, MainGUI.petFieldHeight())
        renderCompactTextField(nvg, fields.maxLevelPrice, layout.maxLevelX2, layout.row2Y, layout.halfW, MainGUI.petFieldHeight())
    }

    fun handleGroupClick(group: SettingGroup, mx: Float, my: Float, x: Float, y: Float, w: Float): Boolean {
        val fields = resolveFields(group) ?: return false
        val layout = Layout(x, y, w)
        val fieldY1 = compactFieldBodyY(layout.row1Y)
        val fieldY2 = compactFieldBodyY(layout.row2Y)
        val fieldH = compactFieldBodyH(MainGUI.petFieldHeight())
        if (my >= fieldY1 && my <= fieldY1 + fieldH) {
            if (mx >= layout.nameX && mx <= layout.nameX + layout.nameW) { handleCompactTextFieldClick(fields.petName, mx, my, layout.nameX, layout.row1Y, layout.nameW, MainGUI.petFieldHeight()); return true }
            if (mx >= layout.maxLevelX && mx <= layout.maxLevelX + layout.quarterW) { handleCompactDropdownFieldClick(fields.maxLevel, layout.maxLevelX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight()); return true }
            if (mx >= layout.rarityX && mx <= layout.rarityX + layout.quarterW) { handleCompactDropdownFieldClick(fields.rarity, layout.rarityX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight()); return true }
        }
        if (my >= fieldY2 && my <= fieldY2 + fieldH) {
            if (mx >= layout.level1X && mx <= layout.level1X + layout.halfW) { handleCompactTextFieldClick(fields.level1Price, mx, my, layout.level1X, layout.row2Y, layout.halfW, MainGUI.petFieldHeight()); return true }
            if (mx >= layout.maxLevelX2 && mx <= layout.maxLevelX2 + layout.halfW) { handleCompactTextFieldClick(fields.maxLevelPrice, mx, my, layout.maxLevelX2, layout.row2Y, layout.halfW, MainGUI.petFieldHeight()); return true }
        }
        return false
    }

    private fun handleCompactTextFieldClick(setting: TextSetting, mx: Float, my: Float, x: Float, y: Float, w: Float, h: Float) {
        owner.commitColor()
        val sameActive = owner.isActiveTextEditor(setting)
        if (!sameActive) { owner.commitText(); owner.activateTextSettingEditor(setting) }
        val fieldY = compactFieldBodyY(y)
        val fieldH = compactFieldBodyH(h)
        val textPad = 6f
        val fieldTextSize = 11f
        val value = owner.currentTextBuffer()
        val visibleTextW = w - textPad * 2f
        val textOffset = if (sameActive) owner.inlineTextScrollOffsetApproxFor(value, owner.currentTextCursor(), fieldTextSize, visibleTextW) else 0f
        val startX = x + textPad - textOffset
        owner.beginInlineFieldCaretPress(x, fieldY, w, fieldH, value, fieldTextSize, startX, mx, my)
    }

    private fun handleCompactDropdownFieldClick(setting: DropdownSetting, x: Float, y: Float, w: Float, h: Float) {
        owner.commitText(); owner.commitColor()
        val fieldY = compactFieldBodyY(y)
        val fieldH = compactFieldBodyH(h)
        if (owner.isOpenDropdown(setting)) { owner.closeOpenDropdown(); return }
        owner.openDropdownOverlayFor(setting, x, fieldY + fieldH, w, fieldH)
    }

    private fun resolveFields(group: SettingGroup): Fields? {
        val petName = findSetting(group, "Pet Name")
        val maxLevel = findSetting(group, "Max Level")
        val rarity = findSetting(group, "Rarity")
        val level1Price = findSetting(group, "Level 1 Price")
        val maxLevelPrice = findSetting(group, "Max Level Price")
        if (petName !is TextSetting || maxLevel !is DropdownSetting || rarity !is DropdownSetting || level1Price !is TextSetting || maxLevelPrice !is TextSetting) return null
        return Fields(petName, maxLevel, rarity, level1Price, maxLevelPrice)
    }

    private fun findSetting(group: SettingGroup, name: String): Setting? {
        for (setting in group.getSettings()) if (setting.getRawName() == name) return setting
        return null
    }

    private fun compactFieldBodyY(y: Float): Float = y + 18f
    private fun compactFieldBodyH(h: Float): Float = h - 18f

    private fun renderCompactTextField(nvg: NVGRenderer, setting: TextSetting, x: Float, y: Float, w: Float, h: Float) {
        val active = owner.isActiveTextEditor(setting)
        nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getName()), x, y + 5f, 10f, Theme.TEXT_SECONDARY)
        val fieldY = compactFieldBodyY(y)
        val fieldH = compactFieldBodyH(h)
        nvg.roundedRect(x, fieldY, w, fieldH, 4f, Theme.BG_FIELD)
        nvg.rectOutline(x, fieldY, w, fieldH, 4f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        if (active) owner.setEditorBounds(x, fieldY, w, fieldH)
        val textPad = 6f
        val value = if (active) owner.currentTextBuffer() else setting.getValue()
        val visibleTextW = w - textPad * 2f
        val textOffset = if (active) owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, value, owner.currentTextCursor(), 11f, visibleTextW) else 0f
        val drawX = x + textPad - textOffset
        val clipped = owner.pushContentLocalScissor(nvg, x + textPad, fieldY + 1f, visibleTextW, fieldH - 2f)
        if (active && owner.textHasSelection()) {
            val selectionStart = owner.currentTextSelectionStart()
            val selectionEnd = owner.currentTextSelectionEnd()
            val selectionX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(value, 0, selectionStart), 11f)
            val selectionW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(value, selectionStart, selectionEnd), 11f)
            nvg.roundedRect(selectionX, fieldY + 4f, Math.max(1f, selectionW), fieldH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
        }
        if (value.isEmpty() && !active) nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), x + textPad, fieldY + 5f, 11f, Theme.TEXT_DIM) else nvg.text(Fonts.REGULAR, value, drawX, fieldY + 5f, 11f, Theme.TEXT_PRIMARY)
        if (active && (System.currentTimeMillis() / 530) % 2 == 0L) {
            val prefix = owner.safeTextSlice(owner.currentTextBuffer(), 0, owner.currentTextCursor())
            val caretX = drawX + nvg.textWidth(Fonts.REGULAR, prefix, 11f)
            nvg.rect(caretX, fieldY + 4f, 1f, fieldH - 8f, Theme.TEXT_PRIMARY)
        }
        owner.popContentLocalScissor(nvg, clipped)
    }

    private fun renderCompactDropdownField(nvg: NVGRenderer, setting: DropdownSetting, x: Float, y: Float, w: Float, h: Float) {
        val open = owner.isOpenDropdown(setting)
        nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getName()), x, y + 5f, 10f, Theme.TEXT_SECONDARY)
        val fieldY = compactFieldBodyY(y)
        val fieldH = compactFieldBodyH(h)
        nvg.roundedRect(x, fieldY, w, fieldH, 4f, Theme.BG_FIELD)
        nvg.rectOutline(x, fieldY, w, fieldH, 4f, 1f, if (open) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        val clipped = owner.pushContentLocalScissor(nvg, x + 7f, fieldY, w - 24f, fieldH)
        nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getSelectedOption()), x + 7f, fieldY + (fieldH - 13f) / 2f, 12f, Theme.TEXT_PRIMARY)
        owner.popContentLocalScissor(nvg, clipped)
        DropdownChevron.renderDown(nvg, x + w - 24f, fieldY, 24f, fieldH, Theme.TEXT_SECONDARY)
        if (open) owner.setDropdownOverlayBounds(x, fieldY + fieldH, w, fieldH)
    }

    private class Fields(val petName: TextSetting, val maxLevel: DropdownSetting, val rarity: DropdownSetting, val level1Price: TextSetting, val maxLevelPrice: TextSetting)

    private class Layout(x: Float, y: Float, w: Float) {
        val row1Y: Float
        val row2Y: Float
        val nameW: Float
        val quarterW: Float
        val nameX: Float = x
        val maxLevelX: Float
        val rarityX: Float
        val halfW: Float
        val level1X: Float = x
        val maxLevelX2: Float
        init {
            val halfGap = MainGUI.petColGap() / 2f
            row1Y = y + 6f
            row2Y = row1Y + MainGUI.petFieldHeight() + MainGUI.petRowGap()
            nameW = (w - MainGUI.petColGap() * 2f) * 0.5f + halfGap
            quarterW = (w - MainGUI.petColGap() * 2f - nameW) / 2f
            maxLevelX = nameX + nameW + MainGUI.petColGap()
            rarityX = maxLevelX + quarterW + MainGUI.petColGap()
            halfW = (w - MainGUI.petColGap()) / 2f
            maxLevelX2 = x + halfW + MainGUI.petColGap()
        }
    }
}
