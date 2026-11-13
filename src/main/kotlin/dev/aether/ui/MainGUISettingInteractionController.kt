package dev.aether.ui

import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.DropdownListSetting
import dev.aether.ui.settings.MultiDropdownSetting
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.RangeSliderSetting
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SettingType
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting

class MainGUISettingInteractionController(private val owner: MainGUI) {
    fun handleContentAreaClick(mx: Float, my: Float) {
        if (mx < owner.contentX() || mx > owner.contentX() + owner.contentW()
            || my < owner.contentY() || my > owner.contentY() + owner.contentH()) return
        if (owner.searchResultsVisible()) owner.handleSearchClickEvent(mx, my)
        else if (owner.getActiveMainTab() == 0 && owner.getActiveModuleSubTab() != null) handleModuleSettingsPanelClick(mx, my)
        else if (owner.getActiveMainTab() == 1 || owner.getActiveMainTab() == 3 || owner.getActiveMainTab() == 4) handleFlatContentClick(mx, my)
    }

    fun handleContentClick(mx: Float, my: Float) {
        owner.closeOpenDropdown()
        val gx = owner.contentX() + MainGUI.ITEM_PAD
        val gw = owner.contentW() - MainGUI.ITEM_PAD * 2f
        var y2 = 0f
        for (group in owner.activeGroupsForInteraction()) {
            y2 += MainGUI.groupGap()
            val headerSY = owner.contentY() - owner.getActiveScrollY() + y2
            if (!group.isAlwaysOn()) {
                val pillX = gx + gw - 52f
                val pillY = headerSY + (MainGUI.HEADER_H - MainGUI.PILL_H) / 2f
                if (mx >= pillX && mx <= pillX + 38f && my >= pillY && my <= pillY + MainGUI.PILL_H) { group.toggle(); return }
            }
            y2 += MainGUI.HEADER_H
            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y2 += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                if (owner.isPetTrackerSettingsGroup(group)) {
                    val sh = owner.petTrackerGroupHeightValue()
                    val sSY = owner.contentY() - owner.getActiveScrollY() + y2
                    if (sSY + sh > owner.contentY() && sSY < owner.contentY() + owner.contentH() && my >= sSY && my <= sSY + sh && owner.handlePetTrackerGroupInteraction(group, mx, my, gx, sSY, gw)) return
                    y2 += sh
                } else for (setting in group.getSettings()) {
                    if (!setting.isVisible()) continue
                    val sh = owner.settingHeightFor(setting, gw)
                    val sSY = owner.contentY() - owner.getActiveScrollY() + y2
                    if (sSY + sh > owner.contentY() && sSY < owner.contentY() + owner.contentH() && my >= sSY && my <= sSY + sh) { handleSettingClick(setting, mx, my, gx, sSY, gw, sh); return }
                    y2 += sh
                }
                y2 += 4f
            }
        }
    }

    fun handleFlatContentClick(mx: Float, my: Float) {
        owner.closeOpenDropdown()
        val scrollTop = owner.contentScrollTop()
        val gx = owner.contentX() + MainGUI.ITEM_PAD
        val gw = owner.contentW() - MainGUI.ITEM_PAD * 2f
        var y2 = 0f
        for (subTab in owner.flatSubtabsForCurrentFilter()) for (group in subTab.groups()) {
            y2 += 14f
            val labelSY = scrollTop - owner.getActiveScrollY() + y2
            if (!group.isAlwaysOn()) {
                val pillX = gx + gw - 38f
                val pillY = labelSY + (MainGUI.FLAT_LABEL_H - MainGUI.PILL_H) / 2f
                if (mx >= pillX && mx <= pillX + 38f && my >= pillY && my <= pillY + MainGUI.PILL_H) { group.toggle(); return }
            }
            y2 += MainGUI.FLAT_LABEL_H
            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y2 += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                if (owner.isPetTrackerSettingsGroup(group)) {
                    val sh = owner.petTrackerGroupHeightValue()
                    val sSY = scrollTop - owner.getActiveScrollY() + y2
                    if (my >= sSY && my <= sSY + sh && mx >= gx && mx <= gx + gw && owner.handlePetTrackerGroupInteraction(group, mx, my, gx, sSY, gw)) return
                    y2 += sh
                } else for (setting in group.getSettings()) {
                    if (!setting.isVisible()) continue
                    val sh = owner.settingHeightFor(setting, gw)
                    val sSY = scrollTop - owner.getActiveScrollY() + y2
                    if (my >= sSY && my <= sSY + sh && mx >= gx && mx <= gx + gw) { handleSettingClick(setting, mx, my, gx, sSY, gw, sh); return }
                    y2 += sh
                }
                y2 += 4f
            }
        }
    }

    fun handleModuleSettingsPanelClick(mx: Float, my: Float) {
        owner.closeOpenDropdown()
        if (!owner.isActiveModuleSubtabEnabled()) return
        val panelTop = owner.contentY() + MainGUI.TOP_BAR_H + 1f
        val rx = owner.contentX() + MainGUI.MODULE_CAT_W + 1f
        val rw = owner.contentW() - MainGUI.MODULE_CAT_W - 1f
        val settTop = panelTop + MainGUI.MOD_HEADER_H + 1f
        val gx = rx + MainGUI.ITEM_PAD
        val gw = rw - MainGUI.ITEM_PAD * 2f
        if (mx < rx) return
        var y2 = 0f
        for (group in owner.activeModuleClickGroups()) {
            y2 += 14f + MainGUI.HEADER_H
            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y2 += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                if (owner.isPetTrackerSettingsGroup(group)) {
                    val sh = owner.petTrackerGroupHeightValue()
                    val sSY = settTop - owner.getActiveScrollY() + y2
                    if (my >= sSY && my <= sSY + sh && mx >= gx && mx <= gx + gw && owner.handlePetTrackerGroupInteraction(group, mx, my, gx, sSY, gw)) return
                    y2 += sh
                } else for (setting in group.getSettings()) {
                    if (!setting.isVisible()) continue
                    val sh = owner.settingHeightFor(setting, gw)
                    val sSY = settTop - owner.getActiveScrollY() + y2
                    if (my >= sSY && my <= sSY + sh - 5f && mx >= gx && mx <= gx + gw) { handleSettingClick(setting, mx, my, gx, sSY, gw, sh); return }
                    y2 += sh
                }
                y2 += 4f
            }
        }
    }

    fun handleSettingClick(setting: Setting, mx: Float, my: Float, x: Float, y: Float, w: Float, h: Float) {
        if (setting.getType() != SettingType.TEXT && setting.getType() != SettingType.LIST && setting.getType() != SettingType.DROPDOWN_LIST) owner.commitText()
        if (setting.getType() != SettingType.COLOR) owner.commitColor()
        val ix = x + 16f
        val iw = w - 32f
        val cardH = h - 5f
        when (setting.getType()) {
            SettingType.TOGGLE -> (setting as ToggleSetting).toggle()
            SettingType.SLIDER -> handleSliderClick(setting as SliderSetting, mx, my, y, ix, iw, cardH)
            SettingType.RANGE_SLIDER -> handleRangeSliderClick(setting as RangeSliderSetting, mx, my, y, ix, iw, cardH)
            SettingType.TEXT -> handleTextClick(setting as TextSetting, mx, my, y, ix, iw, cardH)
            SettingType.INFO, SettingType.SECTION -> Unit
            SettingType.LIST -> handleListClick(setting as ListSetting, mx, my, y, w, ix, iw)
            SettingType.DROPDOWN_LIST -> handleDropdownListClick(setting as DropdownListSetting, mx, my, y, w, ix, iw)
            SettingType.DROPDOWN -> handleDropdownClick(setting as DropdownSetting, mx, my, y, ix, iw, cardH)
            SettingType.MULTI_DROPDOWN -> handleMultiDropdownClick(setting as MultiDropdownSetting, mx, my, y, ix, iw, cardH)
            SettingType.ACTION -> (setting as ActionSetting).execute()
            SettingType.COLOR -> handleColorClick(setting as ColorSetting)
            SettingType.POSITION -> handlePositionClick(setting as PositionSetting, mx, my, y, ix, iw, cardH)
            SettingType.KEYBIND -> handleKeybindClick(setting as KeybindSetting, mx, my, y, ix, iw, cardH)
        }
    }

    fun dragSlider(mx: Float): Boolean { if (!owner.hasDraggedSliderSetting()) return false; updateSlider(mx); return true }
    fun dragRangeSlider(mx: Float): Boolean { if (!owner.hasDraggedRangeSliderSetting()) return false; updateRangeSlider(mx); return true }
    fun updateSlider(mx: Float) = owner.applySliderDragValue(mx)
    fun updateRangeSlider(mx: Float) = owner.applyRangeSliderDragValue(mx)

    private fun handleSliderClick(setting: SliderSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        val sliderAreaW = iw / 3f; val sliderX = ix + iw - sliderAreaW; val valueBoxW = 66f; val valueBoxH = 32f
        val valueBoxY = y + (cardH - valueBoxH) / 2f; val trackX = sliderX + valueBoxW + MainGUI.SLIDER_FIELD_TRACK_GAP
        val trackW = sliderAreaW - valueBoxW - MainGUI.SLIDER_FIELD_TRACK_GAP; val trackY = y + (cardH - 12f) / 2f
        if (mx >= sliderX && mx <= sliderX + valueBoxW && my >= valueBoxY && my <= valueBoxY + valueBoxH) {
            val active = owner.isActiveSliderEditor(setting); val value = if (active) owner.currentTextBuffer() else owner.fmtSliderInput(setting)
            val size = 12.5f; val startX = sliderX + (valueBoxW - value.length * size * 0.52f) / 2f
            owner.focusSliderEditorField(setting); owner.beginInlineFieldCaretPress(sliderX, valueBoxY, valueBoxW, valueBoxH, value, size, startX, mx, my); return
        }
        if (mx >= trackX && mx <= trackX + trackW && my >= trackY && my <= trackY + 12f) { owner.startSliderDrag(setting, trackX, trackW); updateSlider(mx) }
    }

    private fun handleRangeSliderClick(setting: RangeSliderSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        val areaW = iw / 3f; val trackX = ix + iw - areaW + 120f + MainGUI.SLIDER_FIELD_TRACK_GAP
        val trackW = areaW - 120f - MainGUI.SLIDER_FIELD_TRACK_GAP; val trackY = y + (cardH - 12f) / 2f
        if (mx >= trackX && mx <= trackX + trackW && my >= trackY && my <= trackY + 12f) { owner.startRangeSliderDrag(setting, trackX, trackW, mx); updateRangeSlider(mx) }
    }

    private fun handleTextClick(setting: TextSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        if (setting.isMultiline()) {
            owner.commitText(); owner.activateTextSettingEditor(setting)
            val fieldY = y + 10f + owner.wrappedSettingLabelLineCountForRow(setting, iw + 32f) * owner.settingLabelLineStepForRow(setting) + 6f
            val fieldH = MainGUI.TEXT_MULTI_PAD_Y * 2f + setting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f
            owner.setEditorBounds(ix, fieldY, iw, fieldH); owner.selectAllText(); return
        }
        val active = owner.isActiveTextEditor(setting)
        if (!active) { owner.commitText(); owner.activateTextSettingEditor(setting) }
        val fieldW = owner.textInlineFieldWidthForRow(iw); val fieldX = ix + iw - fieldW
        val fieldY = y + (cardH - MainGUI.TEXT_INLINE_FIELD_H) / 2f; val value = owner.currentTextBuffer(); val pad = 6f; val size = 12.5f
        val textOffset = if (active) owner.inlineTextScrollOffsetApproxFor(value, owner.currentTextCursor(), size, fieldW - pad * 2f) else 0f
        owner.beginInlineFieldCaretPress(fieldX, fieldY, fieldW, MainGUI.TEXT_INLINE_FIELD_H, value, size, fieldX + pad - textOffset, mx, my)
    }

    private fun handleListClick(list: ListSetting, mx: Float, my: Float, y: Float, rowW: Float, ix: Float, iw: Float) {
        val listY = y + 9f + owner.wrappedSettingLabelLineCountForRow(list, rowW) * owner.settingLabelLineStepForRow(list) + 6f
        val values = list.getValues(); val rowCount = maxOf(1, values.size); val fieldW = iw - MainGUI.LIST_ACTION_W * 2f - MainGUI.LIST_ITEM_GAP * 2f
        for (i in 0 until rowCount) {
            val hasValue = i < values.size; val active = owner.isActiveListEditor(list, i); val rowY = listY + i * (MainGUI.LIST_ITEM_H + MainGUI.LIST_ITEM_GAP)
            val plusX = ix + fieldW + MainGUI.LIST_ITEM_GAP; val minusX = plusX + MainGUI.LIST_ACTION_W + MainGUI.LIST_ITEM_GAP
            if (mx in plusX..(plusX + MainGUI.LIST_ACTION_W) && my in rowY..(rowY + MainGUI.LIST_ITEM_H)) { owner.commitText(); owner.insertListEditorItem(list, if (hasValue) i + 1 else 0); return }
            if (hasValue && mx in minusX..(minusX + MainGUI.LIST_ACTION_W) && my in rowY..(rowY + MainGUI.LIST_ITEM_H)) { owner.commitText(); owner.deleteListEditorItem(list, i); return }
            if (mx >= ix && mx <= ix + fieldW && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H) {
                if (hasValue) {
                    if (!active) owner.commitText()
                    owner.focusListEditorItem(list, i); val value = owner.currentTextBuffer(); val pad = 6f; val size = 12.5f
                    val offset = if (active) owner.inlineTextScrollOffsetApproxFor(value, owner.currentTextCursor(), size, fieldW - pad * 2f) else 0f
                    owner.beginInlineFieldCaretPress(ix, rowY, fieldW, MainGUI.LIST_ITEM_H, value, size, ix + pad - offset, mx, my)
                } else {
                    owner.commitText(); owner.insertListEditorItem(list, 0)
                    owner.beginInlineFieldCaretPress(ix, rowY, fieldW, MainGUI.LIST_ITEM_H, owner.currentTextBuffer(), 12.5f, ix + 6f, mx, my)
                }
                return
            }
        }
    }

    private fun handleDropdownListClick(setting: DropdownListSetting, mx: Float, my: Float, y: Float, w: Float, ix: Float, iw: Float) {
        val itemH = MainGUI.LIST_ITEM_H; val gap = MainGUI.LIST_ITEM_GAP; val actW = MainGUI.LIST_ACTION_W
        var rowY = y + 9f + owner.wrappedSettingLabelLineCountForRow(setting, w) * owner.settingLabelLineStepForRow(setting) + 6f
        val itemFieldW = iw - actW * 3f - gap * 3f; val values = setting.getValues()
        for (i in values.indices) {
            val upX = ix + itemFieldW + gap; val downX = upX + actW + gap; val minusX = downX + actW + gap
            if (mx >= upX && mx <= upX + actW && my >= rowY && my <= rowY + itemH && i > 0) { setting.moveUp(i); return }
            if (mx >= downX && mx <= downX + actW && my >= rowY && my <= rowY + itemH && i < values.size - 1) { setting.moveDown(i); return }
            if (mx >= minusX && mx <= minusX + actW && my >= rowY && my <= rowY + itemH) { setting.removeValue(i); return }
            rowY += itemH + gap
        }
        val picker = setting.getAddPicker(); val dropW = iw - actW - gap; val addX = ix + dropW + gap
        if (mx >= ix && mx <= ix + dropW && my >= rowY && my <= rowY + itemH) {
            if (owner.isOpenDropdown(picker)) owner.closeOpenDropdown() else owner.openDropdownOverlayFor(picker, ix, rowY + itemH, dropW, itemH)
            return
        }
        if (mx >= addX && mx <= addX + actW && my >= rowY && my <= rowY + itemH) { owner.closeOpenDropdown(); val selected = picker.getSelectedOption(); if (selected != null && !selected.isBlank()) setting.addValue(selected) }
    }

    private fun handleDropdownClick(dropdown: DropdownSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        val h = MainGUI.DROPDOWN_FIELD_H; val y0 = y + (cardH - h) / 2f; val x0 = ix + iw - MainGUI.DROPDOWN_FIELD_W
        val index = owner.dropdownActionIndexFor(dropdown, x0, y0, h, mx, my)
        if (index >= 0) { dropdown.getIconActions()[index].execute(); return }
        if (owner.isOpenDropdown(dropdown)) owner.closeOpenDropdown() else owner.openDropdownOverlayFor(dropdown, x0, y0 + h, MainGUI.DROPDOWN_FIELD_W, h)
    }

    private fun handleMultiDropdownClick(setting: MultiDropdownSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        val chipH = MultiDropdownSetting.CHIP_H; val gap = MultiDropdownSetting.CHIP_GAP
        var chipY = y + 12f + owner.wrappedSettingLabelLineCountForRow(setting, iw + 32f) * owner.settingLabelLineStepForRow(setting) + 8f
        var cx = ix
        for (i in setting.getOptions().indices) {
            val width = MultiDropdownSetting.chipWidth(setting.getOptions()[i])
            if (cx > ix && cx + width > ix + iw) { cx = ix; chipY += chipH + gap }
            if (mx >= cx && mx <= cx + width && my >= chipY && my <= chipY + chipH) { setting.toggleOption(i); return }
            cx += width + gap
        }
    }

    private fun handleColorClick(setting: ColorSetting) { if (owner.isActiveColorSetting(setting)) { owner.commitColor(); return }; owner.activateColorSetting(setting) }

    private fun handlePositionClick(setting: PositionSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        val buttonH = 32f; val buttonY = y + (cardH - buttonH) / 2f; val captureW = 56f; val gap = MainGUI.LIST_ITEM_GAP; val actionW = MainGUI.LIST_ACTION_W
        val actions = setting.getActionButtons(); val stripW = if (actions.isEmpty()) 0f else actions.size * actionW + actions.size * gap
        val captureX = ix + iw - stripW - captureW; val valueGap = 6f; val valueW = 46f
        val zX = captureX - valueGap - valueW; val yX = zX - valueGap - valueW; val xX = yX - valueGap - valueW
        val pillW = 36f; val pillX = xX - valueGap - pillW - 10f; val midY = y + cardH / 2f
        if (mx >= pillX && mx <= pillX + pillW && my >= midY - MainGUI.PILL_H / 2f && my <= midY + MainGUI.PILL_H / 2f) { setting.setHighlighted(!setting.isHighlighted()); return }
        if (mx >= captureX && mx <= captureX + captureW && my >= buttonY && my <= buttonY + buttonH) { setting.capture(); return }
        var actionX = captureX + captureW + gap
        for (action in actions) { if (mx >= actionX && mx <= actionX + actionW && my >= buttonY && my <= buttonY + buttonH) { action.execute(); return }; actionX += actionW + gap }
        val boxes = floatArrayOf(xX, yX, zX); val values = doubleArrayOf(setting.getX(), setting.getY(), setting.getZ())
        for (i in 0..2) if (mx >= boxes[i] && mx <= boxes[i] + valueW && my >= buttonY && my <= buttonY + buttonH) {
            if (owner.isActivePositionEditor(setting, i)) return
            owner.commitText(); owner.focusPositionEditorField(setting, i, values[i], mx, my); return
        }
    }

    private fun handleKeybindClick(setting: KeybindSetting, mx: Float, my: Float, y: Float, ix: Float, iw: Float, cardH: Float) {
        val buttonH = 32f; val buttonY = y + (cardH - buttonH) / 2f; val resetW = 60f; val bindW = 132f; val gap = 8f
        val resetX = ix + iw - resetW; val bindX = resetX - gap - bindW
        if (mx >= resetX && mx <= resetX + resetW && my >= buttonY && my <= buttonY + buttonH) { setting.resetToDefault(); owner.clearKeybindCapture(); return }
        if (mx >= bindX && mx <= bindX + bindW && my >= buttonY && my <= buttonY + buttonH) owner.beginKeybindCapture(setting)
    }
}
