package dev.aether.ui

import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.RangeSliderSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import java.util.Set

class MainGUIContext {
    val layout = LayoutState()
    val navigation = NavigationState()
    val scroll = ScrollState()
    val animation = AnimationState()
    val editor = EditorState()
    val overlay = OverlayState()
    val interaction = InteractionState()
    val transition = TransitionState()

    class LayoutState {
        var px = 0f; var py = 0f; var pw = 0f; var ph = 0f
        var contX = 0f; var contY = 0f; var contW = 0f; var contH = 0f
        var pr = 1f; var physW = 0f; var physH = 0f
        var btnSearchX = 0f; var btnSearchY = 0f; var btnSearchW = 0f; var btnSearchH = 0f
        var searchBoxX = 0f; var searchBoxY = 0f; var searchBoxW = 0f; var searchBoxH = 0f
        var activeEditorX = 0f; var activeEditorY = 0f; var activeEditorW = 0f; var activeEditorH = 0f
        var lastWidth = -1; var lastHeight = -1
        var lastPixelRatio = -1f; var lastUiScale = -1f
    }
    class NavigationState {
        var activeMain = 0; var activeSubtab = 0; var activeFilter = 0
        var activeSubTab: ModulesTab.SubTab? = null
        var activeCategoryIdx = 0; var searchMode = false; var searchQuery = ""
        var profileNameInput = ""; var profileNameFocus = false; var profileNameTarget = 0
        var profileRenameInput = ""; var profileRenameOriginal = ""; var profileRenameFocus = false; var profileRenameTarget = 0
        var suppressNestedContentScissor = false
    }
    class ScrollState {
        var scrollY = 0f; var targetScrollY = 0f; var maxScrollY = 0f
        var modulesOverviewScrollY = 0f; var modulesOverviewTargetScrollY = 0f
        var moduleDetailScrollY = 0f; var moduleDetailTargetScrollY = 0f
        var searchScrollY = 0f; var searchTargetScrollY = 0f; var searchMaxScrollY = 0f
        var profileScrollY = 0f; var profileTargetScrollY = 0f; var profileMaxScrollY = 0f
        var subtabScrollY = 0f; var subtabTargetScrollY = 0f; var subtabMaxScrollY = 0f
        var sbMainTrackT = 0f; var sbMainTrackH = 0f; var sbMainThumbH = 0f
        var sbSrchTrackT = 0f; var sbSrchTrackH = 0f; var sbSrchThumbH = 0f
        var sbProfTrackT = 0f; var sbProfTrackH = 0f; var sbProfThumbH = 0f
        var sbSubTrackT = 0f; var sbSubTrackH = 0f; var sbSubThumbH = 0f
        var lastFrameTimeNanos = 0L
    }
    class AnimationState {
        var alpha = 0f; var animScale = 0f; var animOffsetY = 0f; var sidebarAnim = 0f
        var computedSidebarExpanded = 0f; var animMainSel = 0f; var animSubSel = 0f
        var filterBarAnimX = 0f; var filterBarAnimW = 0f; var filterBarTargetX = 0f; var filterBarTargetW = 0f
        var filterBarInited = false; var ddAnimAmt = 0f
    }
    class EditorState {
        var activeSliderField: SliderSetting? = null; var activeText: TextSetting? = null; var activeList: ListSetting? = null
        var activeListIndex = -1; var activePosField: PositionSetting? = null; var activePosIdx = 0
        var textBuffer = ""; var textCursor = 0; var textSelectionAnchor = -1
        var textSelectionDragging = false; var textSelectionPendingDrag = false; var textSelectionPressCursor = -1
        var textSelectionPressX = 0f; var textSelectionPressY = 0f; var activeEditorBoundsSet = false
        var cpHexFocus = false; var cpHexBuffer = ""
    }
    class OverlayState {
        var openDropdown: DropdownSetting? = null
        var dropdownX = 0f; var dropdownY = 0f; var dropdownW = 0f; var dropdownButtonH = 0f
        var hoveredColor: ColorSetting? = null; var activeColor: ColorSetting? = null
        var cpHue = 0f; var cpSat = 1f; var cpVal = 1f; var cpAlpha = 1f; var cpDrag = 0
        var cpPX = 0f; var cpPY = 0f; var cpPW = 0f; var cpPH = 0f
        var cpSvX = 0f; var cpSvY = 0f; var cpSvW = 0f; var cpSvH = 0f
        var cpHBarY = 0f; var cpABarY = 0f; var cpBarH = 0f
        var cpHexX = 0f; var cpHexY = 0f; var cpHexW = 0f; var cpHexH = 0f
    }
    class InteractionState {
        var scrollbarDragging = 0; var scrollbarDragThumbOffset = 0f
        var dragSetting: SliderSetting? = null; var dragRangeSetting: RangeSliderSetting? = null
        var dragTrackX = 0f; var dragTrackW = 0f; var dragMin = 0f; var dragMax = 0f; var dragRangeHandle = 0
        var panelDragging = false; var dragPanelOffX = 0f; var dragPanelOffY = 0f
        var forcedOverride: MutableSet<SettingGroup>? = null
    }
    class TransitionState {
        var fromState: MainGUIContentViewState? = null; var toState: MainGUIContentViewState? = null; var startNanos = 0L
    }
}
