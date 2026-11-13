package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.DropdownListSetting
import dev.aether.ui.settings.InfoSetting
import dev.aether.ui.settings.MultiDropdownSetting
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.RangeSliderSetting
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingType
import dev.aether.ui.settings.SectionSetting
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang

class MainGUISettingRowRenderer(private val owner: MainGUI) {
    fun render(nvg: NVGRenderer, setting: Setting, x: Float, y: Float, w: Float, h: Float, mx: Float, my: Float) {
        if (setting is SectionSetting) { renderSection(nvg, setting, x, y, w, h); return }
        val cardH = h - 5f
        owner.offerHoverHelp("setting:" + setting.getRawName(), setting.getName(), setting.getDescription(), x, y, w, cardH, mx, my)
        nvg.roundedRect(x, y, w, cardH, 7f, Theme.CARD_BG)
        nvg.rectOutlineSolid(x, y, w, cardH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0.12f))
        val innerX = x + 16f
        val innerW = w - 32f
        val midY = y + cardH / 2f
        val labelLines = owner.wrapSettingLabelForRow(nvg, setting, w)
        val labelFontSize = owner.settingLabelFontSizeForRow(setting)
        val labelLineStep = owner.settingLabelLineStepForRow(setting)
        val labelBlockH = labelLines.size * labelLineStep
        when (setting.getType()) {
            SettingType.TOGGLE -> renderToggle(nvg, setting as ToggleSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH)
            SettingType.SLIDER -> renderSlider(nvg, setting as SliderSetting, innerX, innerW, y, cardH, midY, labelLines, labelFontSize, labelLineStep, labelBlockH)
            SettingType.RANGE_SLIDER -> renderRangeSlider(nvg, setting as RangeSliderSetting, innerX, innerW, y, cardH, midY, labelLines, labelFontSize, labelLineStep, labelBlockH)
            SettingType.TEXT -> renderText(nvg, setting as TextSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH)
            SettingType.INFO -> renderInfo(nvg, setting as InfoSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH)
            SettingType.LIST -> renderList(nvg, setting as ListSetting, innerX, innerW, y, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my)
            SettingType.DROPDOWN_LIST -> renderDropdownList(nvg, setting as DropdownListSetting, innerX, innerW, y, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my)
            SettingType.DROPDOWN -> renderDropdown(nvg, setting as DropdownSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my)
            SettingType.MULTI_DROPDOWN -> renderMultiDropdown(nvg, setting as MultiDropdownSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH)
            SettingType.ACTION -> renderAction(nvg, setting as ActionSetting, innerX, innerW, y, cardH, mx, my)
            SettingType.COLOR -> renderColor(nvg, setting as ColorSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my)
            SettingType.POSITION -> renderPosition(nvg, setting as PositionSetting, innerX, innerW, y, cardH, midY, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my)
            SettingType.KEYBIND -> renderKeybind(nvg, setting as KeybindSetting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH)
            else -> Unit
        }
    }

    private fun renderSection(nvg: NVGRenderer, setting: SectionSetting, x: Float, y: Float, w: Float, h: Float) {
        val titleY = y + 7f
        nvg.text(Fonts.BOLD, setting.getName().uppercase(), x + 4f, titleY, 10f, Theme.ACCENT_PRIMARY)
        if (!setting.getDescription().isBlank()) nvg.text(Fonts.REGULAR, setting.getDescription(), x + 4f, titleY + 15f, 11.5f, Theme.TEXT_SECONDARY)
        nvg.rect(x + 4f, y + h - 7f, maxOf(0f, w - 8f), 1f, Theme.SEPARATOR)
    }

    private fun labels(nvg: NVGRenderer, lines: List<String>, x: Float, y: Float, size: Float, step: Float, color: Int) {
        for ((i, line) in lines.withIndex()) nvg.text(Fonts.REGULAR, line, x, y + i * step, size, color)
    }

    private fun renderToggle(nvg: NVGRenderer, setting: ToggleSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        owner.renderPillControl(nvg, x + w - 38f, y + (cardH - MainGUI.PILL_H) / 2f, setting.getValue(), setting)
    }

    private fun renderSlider(nvg: NVGRenderer, setting: SliderSetting, x: Float, w: Float, y: Float, cardH: Float, midY: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        val areaW = w / 3f; val sliderX = x + w - areaW; val boxW = 66f; val boxH = 32f; val boxY = midY - boxH / 2f
        nvg.roundedRect(sliderX, boxY, boxW, boxH, 7f, Theme.ELEMENT_BG)
        val active = owner.isActiveSliderEditor(setting)
        nvg.rectOutline(sliderX, boxY, boxW, boxH, 7f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        if (active) owner.setEditorBounds(sliderX, boxY, boxW, boxH)
        val text = if (active) owner.currentTextBuffer() else owner.formatSliderValue(setting)
        val pad = 6f; val textSize = 12.5f; val drawX = sliderX + (boxW - nvg.textWidth(Fonts.MONO, text, textSize)) / 2f
        val visibleW = boxW - pad * 2f; val clipped = owner.pushContentLocalScissor(nvg, sliderX + pad, boxY + 1f, visibleW, boxH - 2f)
        if (active && owner.textHasSelection()) {
            val start = owner.currentTextSelectionStart(); val end = owner.currentTextSelectionEnd()
            val selX = drawX + nvg.textWidth(Fonts.MONO, owner.safeTextSlice(text, 0, start), textSize)
            val selW = nvg.textWidth(Fonts.MONO, owner.safeTextSlice(text, start, end), textSize)
            nvg.roundedRect(selX, boxY + 4f, maxOf(1f, selW), boxH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
        }
        if (text.isEmpty() && !active) nvg.text(Fonts.MONO, "-", sliderX + pad, boxY + (boxH - textSize) / 2f, textSize, Theme.TEXT_VALUE)
        else nvg.text(Fonts.MONO, text, drawX, boxY + (boxH - textSize) / 2f, textSize, if (active) Theme.TEXT_PRIMARY else Theme.TEXT_VALUE)
        if (active && (System.currentTimeMillis() / 530) % 2L == 0L) {
            val prefix = owner.safeTextSlice(text, 0, owner.currentTextCursor())
            nvg.rect(drawX + nvg.textWidth(Fonts.MONO, prefix, textSize), boxY + 4f, 1f, boxH - 8f, Theme.TEXT_PRIMARY)
        }
        owner.popContentLocalScissor(nvg, clipped)
        val trackX = sliderX + boxW + MainGUI.SLIDER_FIELD_TRACK_GAP; val trackW = areaW - boxW - MainGUI.SLIDER_FIELD_TRACK_GAP
        val fill = ((setting.getValue() - setting.getMin()) / (setting.getMax() - setting.getMin())).coerceIn(0f, 1f)
        nvg.roundedRect(trackX, midY - 2f, trackW, 4f, 2f, Theme.PILL_TRACK)
        if (fill > 0f) nvg.horizontalGradient(trackX, midY - 2f, trackW * fill, 4f, 2f, Theme.SLIDER_LEFT, Theme.ACCENT_PRIMARY)
        nvg.circle(trackX + trackW * fill, midY, 6f, 0xFFFFFFFF.toInt())
    }

    private fun renderRangeSlider(nvg: NVGRenderer, setting: RangeSliderSetting, x: Float, w: Float, y: Float, cardH: Float, midY: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        val areaW = w / 3f; val sliderX = x + w - areaW; val boxW = 120f; val boxH = 32f; val boxY = midY - boxH / 2f
        nvg.roundedRect(sliderX, boxY, boxW, boxH, 7f, Theme.ELEMENT_BG); nvg.rectOutline(sliderX, boxY, boxW, boxH, 7f, 1f, Theme.BORDER_DEFAULT)
        val text = owner.formatRangeSliderValue(setting); val textSize = 12.5f
        nvg.text(Fonts.MONO, text, sliderX + (boxW - nvg.textWidth(Fonts.MONO, text, textSize)) / 2f, boxY + (boxH - textSize) / 2f, textSize, Theme.TEXT_VALUE)
        val trackX = sliderX + boxW + MainGUI.SLIDER_FIELD_TRACK_GAP; val trackW = areaW - boxW - MainGUI.SLIDER_FIELD_TRACK_GAP
        val lower = ((setting.getLowerValue() - setting.getMin()) / (setting.getMax() - setting.getMin())).coerceIn(0f, 1f)
        val upper = ((setting.getUpperValue() - setting.getMin()) / (setting.getMax() - setting.getMin())).coerceIn(0f, 1f)
        val lowerX = trackX + trackW * lower; val upperX = trackX + trackW * upper
        nvg.roundedRect(trackX, midY - 2f, trackW, 4f, 2f, Theme.PILL_TRACK)
        if (upperX > lowerX) nvg.horizontalGradient(lowerX, midY - 2f, upperX - lowerX, 4f, 2f, Theme.SLIDER_LEFT, Theme.ACCENT_PRIMARY)
        nvg.circle(lowerX, midY, 6f, 0xFFFFFFFF.toInt()); nvg.circle(upperX, midY, 6f, 0xFFFFFFFF.toInt())
    }

    private fun renderText(nvg: NVGRenderer, setting: TextSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        val active = owner.isActiveTextEditor(setting)
        if (setting.isMultiline()) { renderMultilineText(nvg, setting, x, w, y, cardH, active, lines, size, step, blockH); return }
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        val fieldW = owner.textInlineFieldWidthForRow(w); val fieldX = x + w - fieldW
        val fieldY = y + (cardH - MainGUI.TEXT_INLINE_FIELD_H) / 2f; val fieldH = MainGUI.TEXT_INLINE_FIELD_H
        nvg.roundedRect(fieldX, fieldY, fieldW, fieldH, 7f, Theme.BG_FIELD)
        nvg.rectOutline(fieldX, fieldY, fieldW, fieldH, 7f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        if (active) owner.setEditorBounds(fieldX, fieldY, fieldW, fieldH)
        val pad = 6f; val textSize = 12.5f; val display = if (active) owner.currentTextBuffer() else setting.getValue()
        val visibleW = fieldW - pad * 2f
        val offset = if (active) owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, display, owner.currentTextCursor(), textSize, visibleW) else 0f
        val drawX = fieldX + pad - offset; val clipped = owner.pushContentLocalScissor(nvg, fieldX + pad, fieldY + 1f, visibleW, fieldH - 2f)
        if (active && owner.textHasSelection()) {
            val start = owner.currentTextSelectionStart(); val end = owner.currentTextSelectionEnd()
            val selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, start), textSize)
            val selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, start, end), textSize)
            nvg.roundedRect(selX, fieldY + 4f, maxOf(1f, selW), fieldH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
        }
        if (display.isEmpty() && !active) nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), fieldX + pad, fieldY + (fieldH - textSize) / 2f, textSize, Theme.TEXT_DIM)
        else nvg.text(Fonts.REGULAR, display, drawX, fieldY + (fieldH - textSize) / 2f, textSize, Theme.TEXT_PRIMARY)
        if (active && (System.currentTimeMillis() / 530) % 2L == 0L) nvg.rect(drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, owner.currentTextCursor()), textSize), fieldY + 5f, 1f, fieldH - 10f, Theme.TEXT_PRIMARY)
        owner.popContentLocalScissor(nvg, clipped)
    }

    private fun renderInfo(nvg: NVGRenderer, setting: InfoSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        val textY = y + 10f; labels(nvg, lines, x, textY, size, step, Theme.TEXT_LABEL)
        val valueY = textY + blockH + 6f; val valueH = maxOf(24f, cardH - (valueY - y) - 10f)
        nvg.roundedRect(x, valueY, w, valueH, 7f, Theme.BG_FIELD); nvg.rectOutlineSolid(x, valueY, w, valueH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0.08f))
        val value = setting.getValue(); val pad = 8f; val fontSize = 12f; val lineStep = fontSize + 4f; val maxWidth = maxOf(1f, w - pad * 2f)
        val values = if (setting.isMultiline()) owner.wrapTextForWidth(nvg, value, Fonts.REGULAR, fontSize, maxWidth) else listOf(value)
        val clipped = owner.pushContentLocalScissor(nvg, x + pad, valueY + 3f, maxWidth, valueH - 6f)
        for ((i, line) in values.withIndex()) nvg.text(Fonts.REGULAR, line, x + pad, valueY + 6f + i * lineStep, fontSize, Theme.TEXT_VALUE)
        owner.popContentLocalScissor(nvg, clipped)
    }

    private fun renderMultilineText(nvg: NVGRenderer, setting: TextSetting, x: Float, w: Float, y: Float, cardH: Float, active: Boolean, labelLines: List<String>, size: Float, step: Float, blockH: Float) {
        val textY = y + 10f; labels(nvg, labelLines, x, textY, size, step, Theme.TEXT_LABEL)
        val fieldY = textY + blockH + 6f; val fieldH = MainGUI.TEXT_MULTI_PAD_Y * 2f + setting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f
        nvg.roundedRect(x, fieldY, w, fieldH, 7f, Theme.BG_FIELD); nvg.rectOutline(x, fieldY, w, fieldH, 7f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        if (active) owner.setEditorBounds(x, fieldY, w, fieldH)
        val display = if (active) owner.currentTextBuffer() else setting.getValue(); val textLines = display.split('\n')
        val start = if (active) owner.getMultilineViewportStart(setting, display, owner.currentTextCursor()) else 0
        val maxLines = maxOf(1, setting.getVisibleLines()); val padX = 6f; val padY = MainGUI.TEXT_MULTI_PAD_Y; val textSize = 12f; val maxWidth = maxOf(1f, w - padX * 2f)
        val clipped = owner.pushContentLocalScissor(nvg, x + padX, fieldY + 1f, maxWidth, fieldH - 2f)
        for (i in 0 until maxLines) { val index = start + i; if (index >= textLines.size) break; nvg.text(Fonts.REGULAR, textLines[index], x + padX, fieldY + padY + i * MainGUI.TEXT_MULTI_LINE_STEP, textSize, Theme.TEXT_PRIMARY) }
        if (display.isEmpty() && !active) nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), x + padX, fieldY + padY, textSize, Theme.TEXT_DIM)
        owner.popContentLocalScissor(nvg, clipped)
    }

    private fun renderList(nvg: NVGRenderer, setting: ListSetting, x: Float, w: Float, y: Float, lines: List<String>, size: Float, step: Float, blockH: Float, mx: Float, my: Float) {
        val textY = y + 9f; labels(nvg, lines, x, textY, size, step, Theme.TEXT_PRIMARY)
        val values = setting.getValues(); val rowCount = maxOf(1, values.size); var rowY = textY + blockH + 6f
        val fieldW = w - MainGUI.LIST_ACTION_W * 2f - MainGUI.LIST_ITEM_GAP * 2f
        for (i in 0 until rowCount) {
            val hasValue = i < values.size; val active = owner.isActiveListEditor(setting, i); val display = if (hasValue) values[i] else ""; val textSize = 12.5f
            nvg.roundedRect(x, rowY, fieldW, MainGUI.LIST_ITEM_H, 7f, Theme.BG_FIELD); nvg.rectOutline(x, rowY, fieldW, MainGUI.LIST_ITEM_H, 7f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
            if (active) owner.setEditorBounds(x, rowY, fieldW, MainGUI.LIST_ITEM_H)
            val pad = 6f; val visibleW = fieldW - pad * 2f; val current = if (active) owner.currentTextBuffer() else display
            val offset = if (active) owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, current, owner.currentTextCursor(), textSize, visibleW) else 0f
            val drawX = x + pad - offset; val clipped = owner.pushContentLocalScissor(nvg, x + pad, rowY + 1f, visibleW, MainGUI.LIST_ITEM_H - 2f)
            if (active) {
                if (owner.textHasSelection()) {
                    val start = owner.currentTextSelectionStart(); val end = owner.currentTextSelectionEnd()
                    val selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(current, 0, start), textSize)
                    val selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(current, start, end), textSize)
                    nvg.roundedRect(selX, rowY + 4f, maxOf(1f, selW), MainGUI.LIST_ITEM_H - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
                }
                nvg.text(Fonts.REGULAR, current, drawX, rowY + (MainGUI.LIST_ITEM_H - textSize) / 2f, textSize, Theme.TEXT_PRIMARY)
                if ((System.currentTimeMillis() / 530) % 2L == 0L) nvg.rect(drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(current, 0, owner.currentTextCursor()), textSize), rowY + 5f, 1f, MainGUI.LIST_ITEM_H - 10f, Theme.TEXT_PRIMARY)
            } else if (display.isEmpty()) nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), x + pad, rowY + (MainGUI.LIST_ITEM_H - textSize) / 2f, textSize, Theme.TEXT_DIM)
            else nvg.text(Fonts.REGULAR, display, x + pad, rowY + (MainGUI.LIST_ITEM_H - textSize) / 2f, textSize, Theme.TEXT_PRIMARY)
            owner.popContentLocalScissor(nvg, clipped)
            val plusX = x + fieldW + MainGUI.LIST_ITEM_GAP; val minusX = plusX + MainGUI.LIST_ACTION_W + MainGUI.LIST_ITEM_GAP
            owner.renderListActionButtonControl(nvg, plusX, rowY, MainGUI.LIST_ACTION_W, "+", mx >= plusX && mx <= plusX + MainGUI.LIST_ACTION_W && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H, true)
            owner.renderListActionButtonControl(nvg, minusX, rowY, MainGUI.LIST_ACTION_W, "-", mx >= minusX && mx <= minusX + MainGUI.LIST_ACTION_W && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H, hasValue)
            rowY += MainGUI.LIST_ITEM_H + MainGUI.LIST_ITEM_GAP
        }
    }

    private fun renderDropdownList(nvg: NVGRenderer, setting: DropdownListSetting, x: Float, w: Float, y: Float, lines: List<String>, size: Float, step: Float, blockH: Float, mx: Float, my: Float) {
        val textY = y + 9f; labels(nvg, lines, x, textY, size, step, Theme.TEXT_PRIMARY)
        val values = setting.getValues(); var rowY = textY + blockH + 6f; val itemH = MainGUI.LIST_ITEM_H; val gap = MainGUI.LIST_ITEM_GAP; val actW = MainGUI.LIST_ACTION_W; val textSize = 12.5f; val pad = 6f
        val itemFieldW = w - actW * 3f - gap * 3f
        for (i in values.indices) {
            val display = "${i + 1}.  ${values[i]}"
            nvg.roundedRect(x, rowY, itemFieldW, itemH, 7f, Theme.BG_FIELD); nvg.rectOutline(x, rowY, itemFieldW, itemH, 7f, 1f, Theme.BORDER_DEFAULT)
            val clipped = owner.pushContentLocalScissor(nvg, x + pad, rowY + 1f, itemFieldW - pad * 2f, itemH - 2f)
            nvg.text(Fonts.REGULAR, display, x + pad, rowY + (itemH - textSize) / 2f, textSize, Theme.TEXT_PRIMARY); owner.popContentLocalScissor(nvg, clipped)
            val upX = x + itemFieldW + gap; val downX = upX + actW + gap; val minusX = downX + actW + gap
            owner.renderListActionButtonControl(nvg, upX, rowY, actW, "↑", mx >= upX && mx <= upX + actW && my >= rowY && my <= rowY + itemH, i > 0)
            owner.renderListActionButtonControl(nvg, downX, rowY, actW, "↓", mx >= downX && mx <= downX + actW && my >= rowY && my <= rowY + itemH, i < values.size - 1)
            owner.renderListActionButtonControl(nvg, minusX, rowY, actW, "−", mx >= minusX && mx <= minusX + actW && my >= rowY && my <= rowY + itemH, true)
            rowY += itemH + gap
        }
        val picker = setting.getAddPicker(); val dropW = w - actW - gap; val open = owner.isOpenDropdown(picker)
        nvg.roundedRect(x, rowY, dropW, itemH, 7f, Theme.BG_FIELD); nvg.rectOutline(x, rowY, dropW, itemH, 7f, 1f, if (open) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        val clipped = owner.pushContentLocalScissor(nvg, x + pad, rowY + 1f, dropW - 30f - pad, itemH - 2f)
        nvg.text(Fonts.REGULAR, picker.getSelectedOption(), x + pad, rowY + (itemH - textSize) / 2f, textSize, Theme.TEXT_PRIMARY); owner.popContentLocalScissor(nvg, clipped)
        nvg.rect(x + dropW - 30f, rowY + 6f, 1f, itemH - 12f, Theme.SEPARATOR); DropdownChevron.renderDown(nvg, x + dropW - 30f, rowY, 30f, itemH, Theme.TEXT_MUTED)
        if (open) owner.setDropdownOverlayBounds(x, rowY + itemH, dropW, itemH)
        val addX = x + dropW + gap
        owner.renderListActionButtonControl(nvg, addX, rowY, actW, "+", mx >= addX && mx <= addX + actW && my >= rowY && my <= rowY + itemH, true)
    }

    private fun renderDropdown(nvg: NVGRenderer, setting: DropdownSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float, mx: Float, my: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        val boxW = MainGUI.DROPDOWN_FIELD_W; val boxH = MainGUI.DROPDOWN_FIELD_H; val boxX = x + w - boxW; val boxY = y + (cardH - boxH) / 2f; val open = owner.isOpenDropdown(setting)
        owner.renderDropdownActionButtonsControl(nvg, setting, boxX, boxY, boxH, mx, my)
        nvg.roundedRect(boxX, boxY, boxW, boxH, 7f, Theme.BG_FIELD); nvg.rectOutline(boxX, boxY, boxW, boxH, 7f, 1f, if (open) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        val pad = 6f; val textSize = 12.5f; val clipped = owner.pushContentLocalScissor(nvg, boxX + pad, boxY + 1f, boxW - 30f - pad, boxH - 2f)
        nvg.text(Fonts.REGULAR, setting.getSelectedOption(), boxX + pad, boxY + (boxH - textSize) / 2f, textSize, Theme.TEXT_PRIMARY); owner.popContentLocalScissor(nvg, clipped)
        nvg.rect(boxX + boxW - 30f, boxY + 6f, 1f, boxH - 12f, Theme.SEPARATOR); DropdownChevron.renderDown(nvg, boxX + boxW - 30f, boxY, 30f, boxH, Theme.TEXT_MUTED)
        if (open) owner.setDropdownOverlayBounds(boxX, boxY + boxH, boxW, boxH)
    }

    private fun renderAction(nvg: NVGRenderer, setting: ActionSetting, x: Float, w: Float, y: Float, cardH: Float, mx: Float, my: Float) {
        val buttonH = 32f; val buttonY = y + (cardH - buttonH) / 2f; val hovered = mx >= x && mx <= x + w && my >= buttonY && my <= buttonY + buttonH
        val bg = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG
        val border = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f)
        nvg.roundedRect(x, buttonY, w, buttonH, 7f, bg); nvg.rectOutline(x, buttonY, w, buttonH, 7f, 1f, border)
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize(setting.getName()), x, buttonY, w, buttonH, 12.5f, if (hovered) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE)
    }

    private fun renderColor(nvg: NVGRenderer, setting: ColorSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float, mx: Float, my: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        val swatchW = 24f; val swatchH = 24f; val swatchX = x + w - swatchW; val swatchY = y + (cardH - swatchH) / 2f
        val hex = String.format("#%06X", setting.getValue() and 0xFFFFFF); val hexW = nvg.textWidth(Fonts.MONO, hex, 11f)
        nvg.text(Fonts.MONO, hex, swatchX - hexW - 7f, swatchY + (swatchH - 11f) / 2f, 11f, Theme.TEXT_MUTED)
        val active = owner.isActiveColorSetting(setting)
        if (mx >= swatchX && mx <= swatchX + swatchW && my >= swatchY && my <= swatchY + swatchH) owner.setHoveredColorSetting(setting)
        nvg.roundedRect(swatchX, swatchY, swatchW, swatchH, 5f, setting.getValue() or 0xFF000000.toInt())
        nvg.rectOutline(swatchX, swatchY, swatchW, swatchH, 5f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.12f))
    }

    private fun renderPosition(nvg: NVGRenderer, setting: PositionSetting, x: Float, w: Float, y: Float, cardH: Float, midY: Float, lines: List<String>, size: Float, step: Float, blockH: Float, mx: Float, my: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_PRIMARY)
        val boxH = 32f; val boxY = y + (cardH - boxH) / 2f; val captureW = 56f; val gap = MainGUI.LIST_ITEM_GAP; val actionW = MainGUI.LIST_ACTION_W; val actions = setting.getActionButtons()
        val stripW = if (actions.isEmpty()) 0f else actions.size * actionW + actions.size * gap; val captureX = x + w - stripW - captureW
        val pillH = MainGUI.PILL_H; val valueGap = 6f; val valueW = 46f; val zX = captureX - valueGap - valueW; val yX = zX - valueGap - valueW; val xX = yX - valueGap - valueW
        val pillW = 36f; val pillX = xX - valueGap - pillW - 10f; val highlightW = 52f
        nvg.textRight(Fonts.REGULAR, AetherLang.localize("Highlight"), pillX - highlightW - 8f, midY - size / 2f, highlightW, size, Theme.TEXT_PRIMARY)
        owner.renderPillControl(nvg, pillX, midY - pillH / 2f, setting.isHighlighted(), setting)
        val boxes = floatArrayOf(xX, yX, zX); val values = doubleArrayOf(setting.getX(), setting.getY(), setting.getZ())
        for (i in 0..2) {
            val active = owner.isActivePositionEditor(setting, i); nvg.roundedRect(boxes[i], boxY, valueW, boxH, 7f, Theme.BG_FIELD)
            nvg.rectOutline(boxes[i], boxY, valueW, boxH, 7f, 1f, if (active) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
            if (active) owner.setEditorBounds(boxes[i], boxY, valueW, boxH)
            val display = if (active) owner.currentTextBuffer() else String.format("%.1f", values[i]); val pad = 6f; val textSize = 12.5f; val visibleW = valueW - pad * 2f
            val offset = if (active) owner.inlineTextScrollOffsetApproxFor(display, owner.currentTextCursor(), textSize, visibleW) else 0f
            val drawX = boxes[i] + pad - offset; val clipped = owner.pushContentLocalScissor(nvg, boxes[i] + pad, boxY + 1f, visibleW, boxH - 2f)
            if (active && owner.textHasSelection()) {
                val start = owner.currentTextSelectionStart(); val end = owner.currentTextSelectionEnd()
                val selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, start), textSize); val selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, start, end), textSize)
                nvg.roundedRect(selX, boxY + 4f, maxOf(1f, selW), boxH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
            }
            nvg.text(Fonts.REGULAR, display, drawX, boxY + (boxH - textSize) / 2f, textSize, if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
            if (active && (System.currentTimeMillis() / 530) % 2L == 0L) nvg.rect(drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, owner.currentTextCursor()), textSize), boxY + 4f, 1f, boxH - 8f, Theme.TEXT_PRIMARY)
            owner.popContentLocalScissor(nvg, clipped)
        }
        val hovered = mx >= captureX && mx <= captureX + captureW && my >= boxY && my <= boxY + boxH
        nvg.roundedRect(captureX, boxY, captureW, boxH, 7f, if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG)
        nvg.rectOutline(captureX, boxY, captureW, boxH, 7f, 1f, if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f))
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Capture"), captureX, boxY, captureW, boxH, 11.5f, if (hovered) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE)
        var actionX = captureX + captureW + gap
        for (action in actions) { val actionHovered = mx >= actionX && mx <= actionX + actionW && my >= boxY && my <= boxY + boxH; owner.renderListActionButtonControl(nvg, actionX, boxY, actionW, action.label(), actionHovered, action.isEnabled()); actionX += actionW + gap }
    }

    private fun renderMultiDropdown(nvg: NVGRenderer, setting: MultiDropdownSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        var chipY = y + 12f; labels(nvg, lines, x, chipY, size, step, Theme.TEXT_LABEL)
        val chipH = MultiDropdownSetting.CHIP_H; val gap = MultiDropdownSetting.CHIP_GAP; val fontSize = MultiDropdownSetting.CHIP_FONT_SZ
        chipY += blockH + 8f; var cx = x
        for (i in setting.getOptions().indices) {
            val text = AetherLang.localize(setting.getOptions()[i]); val chipW = MultiDropdownSetting.chipWidth(text)
            if (cx > x && cx + chipW > x + w) { cx = x; chipY += chipH + gap }
            val selected = setting.isSelected(i); val bg = if (selected) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG
            nvg.roundedRect(cx, chipY, chipW, chipH, 5f, bg); nvg.rectOutline(cx, chipY, chipW, chipH, 5f, 1f, if (selected) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.5f) else Theme.BORDER_DEFAULT)
            nvg.textCentered(Fonts.REGULAR, text, cx, chipY, chipW, chipH, fontSize, if (selected) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE)
            cx += chipW + gap
        }
    }

    private fun renderKeybind(nvg: NVGRenderer, setting: KeybindSetting, x: Float, w: Float, y: Float, cardH: Float, lines: List<String>, size: Float, step: Float, blockH: Float) {
        labels(nvg, lines, x, y + (cardH - blockH) / 2f, size, step, Theme.TEXT_LABEL)
        val buttonH = 32f; val buttonY = y + (cardH - buttonH) / 2f; val resetW = 60f; val bindW = 132f; val gap = 8f
        val resetX = x + w - resetW; val bindX = resetX - gap - bindW; val listening = owner.isAwaitingKeybindCapture(setting)
        nvg.roundedRect(bindX, buttonY, bindW, buttonH, 7f, if (listening) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.16f) else Theme.BG_FIELD)
        nvg.rectOutline(bindX, buttonY, bindW, buttonH, 7f, 1f, if (listening) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        val bindText = if (listening) AetherLang.localize("Press a key...") else setting.getBoundKeyName(); val pad = 8f
        val clipped = owner.pushContentLocalScissor(nvg, bindX + pad, buttonY + 1f, bindW - pad * 2f, buttonH - 2f)
        nvg.textCentered(Fonts.REGULAR, bindText, bindX + pad, buttonY, bindW - pad * 2f, buttonH, 12f, if (listening) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE); owner.popContentLocalScissor(nvg, clipped)
        nvg.roundedRect(resetX, buttonY, resetW, buttonH, 7f, Theme.ELEMENT_BG)
        nvg.rectOutline(resetX, buttonY, resetW, buttonH, 7f, 1f, if (setting.isDefault()) Theme.BORDER_DEFAULT else Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Reset"), resetX, buttonY, resetW, buttonH, 12f, if (setting.isDefault()) Theme.TEXT_MUTED else Theme.TEXT_VALUE)
    }
}
