package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.DropdownListSetting
import dev.aether.ui.settings.InfoSetting
import dev.aether.ui.settings.MultiDropdownSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingType
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import java.util.ArrayList

final class MainGUITextLayout {
    fun settingHeight(setting: Setting, rowW: Float): Float {
        val extraLines = Math.max(0, wrappedSettingLabelLineCount(setting, rowW) - 1)
        return when (setting.getType()) {
            SettingType.SECTION -> 46f
            SettingType.TEXT -> {
                val textSetting = setting as TextSetting
                if (!textSetting.isMultiline()) 50f + extraLines * 14f else {
                    val labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting)
                    val fieldH = MainGUI.TEXT_MULTI_PAD_Y * 2f + textSetting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f
                    26f + labelBlockH + fieldH
                }
            }
            SettingType.INFO -> {
                val labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting)
                val valueFontSize = 12f
                val valueLineStep = valueFontSize + 4f
                var valueLines = 1
                if ((setting as InfoSetting).isMultiline()) {
                    val innerWidth = Math.max(1f, rowW - 48f)
                    valueLines = estimateWrappedLineCount(setting.getValue(), innerWidth, valueFontSize)
                }
                21f + labelBlockH + Math.max(28f, valueLines * valueLineStep + 12f)
            }
            SettingType.LIST -> {
                val labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting)
                val rowCount = Math.max(1, (setting as ListSetting).getValues().size)
                9f + labelBlockH + 6f + rowCount * MainGUI.LIST_ITEM_H + (rowCount - 1) * MainGUI.LIST_ITEM_GAP + 15f
            }
            SettingType.DROPDOWN_LIST -> {
                val labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting)
                val rowCount = (setting as DropdownListSetting).getValues().size + 1
                9f + labelBlockH + 6f + rowCount * MainGUI.LIST_ITEM_H + (rowCount - 1) * MainGUI.LIST_ITEM_GAP + 15f
            }
            SettingType.ACTION -> 50f
            SettingType.MULTI_DROPDOWN -> {
                val innerWidth = Math.max(1f, rowW - 32f)
                val labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting)
                val rows = (setting as MultiDropdownSetting).rowCount(innerWidth)
                12f + labelBlockH + 8f + rows * MultiDropdownSetting.CHIP_H + (rows - 1) * MultiDropdownSetting.CHIP_GAP + 12f
            }
            else -> 50f + extraLines * 14f
        }
    }

    fun wrappedSettingLabelLineCount(setting: Setting, rowW: Float): Int =
        estimateWrappedLineCount(AetherLang.localize(setting.getName()), settingLabelMaxWidth(setting, rowW), settingLabelFontSize(setting))

    fun settingLabelFontSize(setting: Setting): Float = if (setting.getType() == SettingType.POSITION) 13f else 13.5f
    fun settingLabelLineStep(setting: Setting): Float = settingLabelFontSize(setting) + 3f

    fun settingLabelMaxWidth(setting: Setting, rowW: Float): Float {
        val innerWidth = Math.max(0f, rowW - 32f)
        return when (setting.getType()) {
            SettingType.TOGGLE -> Math.max(80f, innerWidth - 50f)
            SettingType.SLIDER, SettingType.RANGE_SLIDER -> Math.max(80f, innerWidth - innerWidth / 3f - 12f)
            SettingType.TEXT -> {
                val textSetting = setting as TextSetting
                if (textSetting.isMultiline()) Math.max(80f, innerWidth - 16f)
                else Math.max(80f, innerWidth - textInlineFieldWidth(innerWidth) - MainGUI.TEXT_INLINE_FIELD_GAP)
            }
            SettingType.INFO -> Math.max(80f, innerWidth - 16f)
            SettingType.LIST, SettingType.DROPDOWN_LIST -> Math.max(80f, innerWidth)
            SettingType.DROPDOWN -> Math.max(80f, innerWidth - (MainGUI.DROPDOWN_FIELD_W + dropdownActionStripWidth(setting as DropdownSetting) + 12f))
            SettingType.MULTI_DROPDOWN -> Math.max(80f, innerWidth - 16f)
            SettingType.COLOR -> Math.max(80f, innerWidth - 96f)
            SettingType.POSITION -> Math.max(80f, innerWidth - (320f + positionActionStripWidth(setting as PositionSetting)))
            SettingType.KEYBIND -> Math.max(80f, innerWidth - 220f)
            else -> Math.max(80f, innerWidth - 16f)
        }
    }

    fun dropdownActionStripWidth(dropdown: DropdownSetting): Float {
        val count = dropdown.getIconActions().size
        if (count == 0) return 0f
        return MainGUI.DROPDOWN_ACTION_GAP + count * MainGUI.DROPDOWN_ACTION_W + (count - 1) * MainGUI.DROPDOWN_ACTION_GAP
    }
    fun dropdownActionStartX(dropdown: DropdownSetting, fieldX: Float): Float = fieldX - dropdownActionStripWidth(dropdown)

    private fun positionActionStripWidth(setting: PositionSetting): Float {
        val count = setting.getActionButtons().size
        if (count == 0) return 0f
        return count * MainGUI.LIST_ACTION_W + count * MainGUI.LIST_ITEM_GAP
    }

    fun dropdownActionIndexAt(dropdown: DropdownSetting, fieldX: Float, fieldY: Float, fieldH: Float, mx: Float, my: Float): Int {
        val startX = dropdownActionStartX(dropdown, fieldX)
        if (startX >= fieldX) return -1
        for (i in 0 until dropdown.getIconActions().size) {
            val actionX = startX + i * (MainGUI.DROPDOWN_ACTION_W + MainGUI.DROPDOWN_ACTION_GAP)
            if (mx >= actionX && mx <= actionX + MainGUI.DROPDOWN_ACTION_W && my >= fieldY && my <= fieldY + fieldH) return i
        }
        return -1
    }
    fun textInlineFieldWidth(innerWidth: Float): Float = Math.min(130f, innerWidth)

    fun inlineTextScrollOffset(nvg: NVGRenderer, font: String, text: String?, cursor: Int, fontSize: Float, visibleWidth: Float): Float {
        if (text == null || text.isEmpty() || visibleWidth <= 0f) return 0f
        val totalWidth = nvg.textWidth(font, text, fontSize)
        if (totalWidth <= visibleWidth) return 0f
        val safeCursor = Math.max(0, Math.min(cursor, text.length))
        val cursorWidth = nvg.textWidth(font, text.substring(0, safeCursor), fontSize)
        val caretPadding = Math.min(18f, visibleWidth * 0.35f)
        val maxOffset = totalWidth - visibleWidth
        return Math.max(0f, Math.min(maxOffset, cursorWidth - (visibleWidth - caretPadding)))
    }

    fun inlineTextScrollOffsetApprox(text: String?, cursor: Int, fontSize: Float, visibleWidth: Float): Float {
        if (text == null || text.isEmpty() || visibleWidth <= 0f) return 0f
        val avgCharWidth = fontSize * 0.52f
        val totalWidth = text.length * avgCharWidth
        if (totalWidth <= visibleWidth) return 0f
        val safeCursor = Math.max(0, Math.min(cursor, text.length))
        val cursorWidth = safeCursor * avgCharWidth
        val caretPadding = Math.min(18f, visibleWidth * 0.35f)
        val maxOffset = totalWidth - visibleWidth
        return Math.max(0f, Math.min(maxOffset, cursorWidth - (visibleWidth - caretPadding)))
    }

    fun estimateWrappedLineCount(text: String?, maxWidth: Float, fontSize: Float): Int {
        if (text == null || text.isBlank() || maxWidth <= 0f) return 1
        val avgCharWidth = fontSize * 0.52f
        val maxChars = Math.max(1, Math.floor((maxWidth / avgCharWidth).toDouble()).toInt())
        var lines = 1
        var current = 0
        for (word in text.trim().split(Regex("\\s+"))) {
            val wordLength = word.length
            if (current == 0) {
                current = wordLength
                lines += Math.max(0, (wordLength - 1) / maxChars)
                current = ((wordLength - 1) % maxChars) + 1
            } else if (current + 1 + wordLength <= maxChars) current += 1 + wordLength else {
                lines++
                lines += Math.max(0, (wordLength - 1) / maxChars)
                current = ((wordLength - 1) % maxChars) + 1
            }
        }
        return Math.max(1, lines)
    }

    fun wrapSettingLabel(nvg: NVGRenderer, setting: Setting, rowW: Float): List<String> =
        wrapTextToWidth(nvg, AetherLang.localize(setting.getName()), Fonts.REGULAR, settingLabelFontSize(setting), settingLabelMaxWidth(setting, rowW))

    fun wrapTextToWidth(nvg: NVGRenderer, text: String?, fontName: String, fontSize: Float, maxWidth: Float): List<String> {
        val lines: ArrayList<String> = ArrayList()
        if (text == null || text.isBlank()) {
            lines.add("")
            return lines
        }
        if (maxWidth <= 0f) {
            lines.add(text)
            return lines
        }
        var current = ""
        for (word in text.trim().split(Regex("\\s+"))) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || nvg.textWidth(fontName, candidate, fontSize) <= maxWidth) {
                current = candidate
                continue
            }
            lines.add(current)
            current = word
            while (nvg.textWidth(fontName, current, fontSize) > maxWidth && current.length > 1) {
                val split = fittingPrefixLength(nvg, current, fontName, fontSize, maxWidth)
                lines.add(current.substring(0, split))
                current = current.substring(split)
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return if (lines.isEmpty()) listOf(text) else lines
    }

    fun fittingPrefixLength(nvg: NVGRenderer, text: String, fontName: String, fontSize: Float, maxWidth: Float): Int {
        var low = 1
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (nvg.textWidth(fontName, text.substring(0, mid), fontSize) <= maxWidth) low = mid else high = mid - 1
        }
        return Math.max(1, low)
    }
}
