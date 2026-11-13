package dev.aether.ui

import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting

internal class MainGUITransientRenderState {
    var hoveredColor: ColorSetting? = null
    var activeEditorBoundsSet = false
    var filterBarAnimX = 0f
    var filterBarAnimW = 0f
    var filterBarTargetX = 0f
    var filterBarTargetW = 0f
    var filterBarInited = false
    var maxScrollY = 0f
    var searchMaxScrollY = 0f
    var profileMaxScrollY = 0f
    var subtabMaxScrollY = 0f
    var sbMainTrackT = 0f
    var sbMainTrackH = 0f
    var sbMainThumbH = 0f
    var sbSrchTrackT = 0f
    var sbSrchTrackH = 0f
    var sbSrchThumbH = 0f
    var sbProfTrackT = 0f
    var sbProfTrackH = 0f
    var sbProfThumbH = 0f
    var sbSubTrackT = 0f
    var sbSubTrackH = 0f
    var sbSubThumbH = 0f
    var openDropdown: DropdownSetting? = null
    var activeColor: ColorSetting? = null
    var suppressNestedContentScissor = false
    var scrollY = 0f
    var targetScrollY = 0f
}
