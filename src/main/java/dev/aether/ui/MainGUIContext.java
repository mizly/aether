package dev.aether.ui;

import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;

import java.util.Set;

final class MainGUIContext {
    final LayoutState layout = new LayoutState();
    final NavigationState navigation = new NavigationState();
    final ScrollState scroll = new ScrollState();
    final AnimationState animation = new AnimationState();
    final EditorState editor = new EditorState();
    final OverlayState overlay = new OverlayState();
    final InteractionState interaction = new InteractionState();
    final TransitionState transition = new TransitionState();

    static final class LayoutState {
        float px;
        float py;
        float pw;
        float ph;
        float contX;
        float contY;
        float contW;
        float contH;
        float pr = 1f;
        float physW;
        float physH;
        float btnSearchX;
        float btnSearchY;
        float btnSearchW;
        float btnSearchH;
        float searchBoxX;
        float searchBoxY;
        float searchBoxW;
        float searchBoxH;
        float activeEditorX;
        float activeEditorY;
        float activeEditorW;
        float activeEditorH;
        int lastWidth = -1;
        int lastHeight = -1;
        float lastPixelRatio = -1f;
        float lastUiScale = -1f;
    }

    static final class NavigationState {
        int activeMain;
        int activeSubtab;
        int activeFilter;
        ModulesTab.SubTab activeSubTab;
        int activeCategoryIdx;
        boolean searchMode;
        String searchQuery = "";
        String profileNameInput = "";
        boolean profileNameFocus;
        int profileNameTarget;
        String profileRenameInput = "";
        String profileRenameOriginal = "";
        boolean profileRenameFocus;
        int profileRenameTarget;
        boolean suppressNestedContentScissor;
    }

    static final class ScrollState {
        float scrollY;
        float targetScrollY;
        float maxScrollY;
        float modulesOverviewScrollY;
        float modulesOverviewTargetScrollY;
        float moduleDetailScrollY;
        float moduleDetailTargetScrollY;
        float searchScrollY;
        float searchTargetScrollY;
        float searchMaxScrollY;
        float profileScrollY;
        float profileTargetScrollY;
        float profileMaxScrollY;
        float subtabScrollY;
        float subtabTargetScrollY;
        float subtabMaxScrollY;
        float sbMainTrackT;
        float sbMainTrackH;
        float sbMainThumbH;
        float sbSrchTrackT;
        float sbSrchTrackH;
        float sbSrchThumbH;
        float sbProfTrackT;
        float sbProfTrackH;
        float sbProfThumbH;
        float sbSubTrackT;
        float sbSubTrackH;
        float sbSubThumbH;
        long lastFrameTimeNanos;
    }

    static final class AnimationState {
        float alpha;
        float animScale;
        float animOffsetY;
        float sidebarAnim;
        float computedSidebarExpanded;
        float animMainSel;
        float animSubSel;
        float filterBarAnimX;
        float filterBarAnimW;
        float filterBarTargetX;
        float filterBarTargetW;
        boolean filterBarInited;
        float ddAnimAmt;
        float catBarAnimY;
        float catBarFromY;
        float catBarTargetY;
        float catBarAnimT;
        boolean catBarInited;
        long catBarStartNanos;
    }

    static final class EditorState {
        SliderSetting activeSliderField;
        TextSetting activeText;
        ListSetting activeList;
        int activeListIndex = -1;
        PositionSetting activePosField;
        int activePosIdx;
        String textBuffer = "";
        int textCursor;
        int textSelectionAnchor = -1;
        boolean textSelectionDragging;
        boolean textSelectionPendingDrag;
        int textSelectionPressCursor = -1;
        float textSelectionPressX;
        float textSelectionPressY;
        boolean activeEditorBoundsSet;
        boolean cpHexFocus;
        String cpHexBuffer = "";
    }

    static final class OverlayState {
        DropdownSetting openDropdown;
        float dropdownX;
        float dropdownY;
        float dropdownW;
        float dropdownButtonH;
        ColorSetting hoveredColor;
        ColorSetting activeColor;
        float cpHue;
        float cpSat = 1f;
        float cpVal = 1f;
        float cpAlpha = 1f;
        int cpDrag;
        float cpPX;
        float cpPY;
        float cpPW;
        float cpPH;
        float cpSvX;
        float cpSvY;
        float cpSvW;
        float cpSvH;
        float cpHBarY;
        float cpABarY;
        float cpBarH;
        float cpHexX;
        float cpHexY;
        float cpHexW;
        float cpHexH;
    }

    static final class InteractionState {
        int scrollbarDragging;
        float scrollbarDragThumbOffset;
        SliderSetting dragSetting;
        RangeSliderSetting dragRangeSetting;
        float dragTrackX;
        float dragTrackW;
        float dragMin;
        float dragMax;
        int dragRangeHandle;
        boolean panelDragging;
        float dragPanelOffX;
        float dragPanelOffY;
        Set<SettingGroup> forcedOverride;
    }

    static final class TransitionState {
        MainGUIContentViewState fromState;
        MainGUIContentViewState toState;
        long startNanos;
    }
}
