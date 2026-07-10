package dev.aether.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.macro.MacroStateManager;
import dev.aether.notification.NotificationRenderer;
import dev.aether.ui.settings.*;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.util.Fonts;
import dev.aether.ui.theme.Theme;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NVGScreen;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Full NVG config menu - Farming/Visuals main tabs, subtab sidebar,
 * scrollable SettingGroup cards with all setting types rendered inline.
 * Colors sourced from {@link Theme} for live theming support.
 */
public class MainGUI extends NVGScreen {
    public record LaunchTarget(int mainTab, String moduleName, boolean openModuleDetail) {
        public static LaunchTarget bootstrapAuthentication() {
            return new LaunchTarget(0, "Authentication", true);
        }
    }

    // -- Layout constants ------------------------------------------------------

    static final float SIDEBAR_W       = 60f;
    static final float SIDEBAR_EXPANDED = 194f;
    static final float TOP_BAR_H       = 53f;
    static final float FILTER_BAR_H   = 36f;

    private static final String[] TAB_NAMES = { "Modules", "Colors", "Config", "Keybinds", "Settings" };
    private static final String[] TAB_DESCS = {
            "Manage and configure modules",
            "Customise HUD and menu colors",
            "Save and manage config and theme profiles",
            "Edit Aether keybinds and keep them synced with Minecraft controls",
            "General client settings"
    };

    private static final String[] COLOR_FILTERS = { "All", "HUD Colors", "Menu Colors" };
    private static final String[] CONFIG_FILTERS = { "All", "Config Profiles", "Theme Profiles" };
    private static final String[] SINGLE_FILTER = { "All" };
    static final float RADIUS       = 10f;
    static final float ITEM_PAD    = 20f;
    private static final float GROUP_GAP   = 12f;
    static final float HEADER_H    = 46f;
    static final float HEADER_TO_FIRST_SETTING_GAP = 5f;
    /** Height of the toggle pill bounding box. Track is 38x21 px. */
    static final float PILL_H      = 21f;
    /** Height of a compact group-section label row used in the flat Colors/Settings renderer. */
    static final float FLAT_LABEL_H = 32f;
    static final float DROPDOWN_FIELD_W = 130f;
    static final float DROPDOWN_FIELD_H = 32f;
    static final float DROPDOWN_ACTION_W = 32f;
    static final float DROPDOWN_ACTION_GAP = 6f;

    // -- Card grid -------------------------------------------------------------

    static final int   CARD_COLS  = 3;
    static final float CARD_HGAP  = 10f;   // gap between columns
    static final float CARD_VGAP  = 10f;   // gap between rows
    static final float CARD_H     = 82f;
    static final float SECT_GAP   = 22f;   // space above a section header
    static final float SECT_H     = 26f;   // section header row height
    static final float SECT_SEP   = 10f;   // space between header and first card row

    // -- Module detail view ----------------------------------------------------

    static final float MODULE_CAT_W  = 160f;  // width of left category panel
    static final float MOD_HEADER_H  = 74f;   // height of module info header in right panel

    // -- Pet tracker layout ----------------------------------------------------
    private static final float PET_FIELD_H = 42f;
    private static final float PET_ROW_GAP = 8f;
    private static final float PET_COL_GAP = 8f;

    // -- Panel / content bounds ------------------------------------------------

    private float px, py, pw, ph;
    float contX, contY, contW, contH;
    /** Physical-pixel ratio (physical px per logical px). Updated each render. */
    private float pr = 1f;
    /** Screen size in physical pixels. Updated each render. */
    private float physW, physH;

    // -- Entrance animation ----------------------------------------------------

    private float alpha = 0f, animScale = 0.96f, animOffsetY = 120f;

    // -- UI scale --------------------------------------------------------------

    /**
     * Scale multiplier for the entire menu.
     * 1.0 = every layout unit is exactly 1 physical pixel.
     * Increase to make the menu larger, decrease to shrink it.
     * Wire to a slider in the Settings tab once that exists.
     */
    public static float uiScale = 1.5f;
    public static float uiTextScale = 1;

    // -- Sidebar ---------------------------------------------------------------

    private float sidebarAnim = 0f;   // 0 = collapsed, 1 = expanded
    /** Computed once on first frame to fit the longest tab label. */
    private float computedSidebarExpanded = 0f;

    // -- Navigation ------------------------------------------------------------

    private int   activeMain   = 0;
    private int   activeSubtab = 0;
    /** Animated selection index for the main tabs (slides smoothly). */
    private float animMainSel  = 0f;
    /** Animated selection index for the subtab list (slides smoothly). */
    private float animSubSel   = 0f;

    // -- Search ----------------------------------------------------------------

    boolean searchMode  = false;
    String  searchQuery = "";
    float searchScrollY = 0f, searchTargetScrollY = 0f, searchMaxScrollY = 0f;
    float searchBoxX, searchBoxY, searchBoxW, searchBoxH;

    // -- Filter bar ------------------------------------------------------------

    private int     activeFilter     = 0;
    private float   filterBarAnimX  = 0f, filterBarAnimW  = 0f;
    private float   filterBarTargetX = 0f, filterBarTargetW = 0f;
    private boolean filterBarInited  = false;

    // -- Profiles tab ---------------------------------------------------------

    String  profileNameInput  = "";
    boolean profileNameFocus  = false;
    int     profileNameTarget = 0;
    String  profileRenameInput = "";
    String  profileRenameOriginal = "";
    boolean profileRenameFocus = false;
    int     profileRenameTarget = 0;
    private float   profileScrollY    = 0f;
    private float   profileTargetScrollY = 0f;
    private float   profileMaxScrollY = 0f;

    // -- Click areas (repopulated each render frame, consumed on click) ---------

    private record ClickArea(float x, float y, float w, float h, Runnable action) {}
    private final List<ClickArea> clickAreas = new ArrayList<>();

    // -- Sidebar icon button bounds (set during render) -------------------------

    private float btnSearchX, btnSearchY, btnSearchW, btnSearchH;

    // -- Scroll ----------------------------------------------------------------

    float scrollY       = 0f;
    float targetScrollY = 0f;
    float maxScrollY    = 0f;
    private float modulesOverviewScrollY = 0f;
    private float modulesOverviewTargetScrollY = 0f;
    private float moduleDetailScrollY = 0f;
    private float moduleDetailTargetScrollY = 0f;
    private long  lastFrameTimeNanos = System.nanoTime();

    // -- Scrollbar drag --------------------------------------------------------
    /** 0=none  1=main  2=search  3=profile  4=subtab */
    private int   sbDragging        = 0;
    private float sbDragThumbOffset = 0f;
    // Cached track geometry written during render (for hit-testing)
    float sbMainTrackT, sbMainTrackH, sbMainThumbH;
    float sbSrchTrackT, sbSrchTrackH, sbSrchThumbH;
    private float sbProfTrackT, sbProfTrackH, sbProfThumbH;
    private float sbSubTrackT,  sbSubTrackH,  sbSubThumbH;

    // -- Subtab sidebar scroll -------------------------------------------------
    private float subtabScrollY       = 0f;
    private float subtabTargetScrollY = 0f;
    private float subtabMaxScrollY    = 0f;

    // -- Slider drag -----------------------------------------------------------

    private SliderSetting dragSetting;
    private RangeSliderSetting dragRangeSetting;
    private float dragTrackX, dragTrackW, dragMin, dragMax;
    private int dragRangeHandle = 0;

    // -- Text editing ----------------------------------------------------------

    SliderSetting activeSliderField;
    TextSetting   activeText;
    ListSetting   activeList;
    int           activeListIndex = -1;
    PositionSetting activePosField;
    KeybindSetting activeKeybindCapture;
    /** 0=X, 1=Y, 2=Z */
    int           activePosIdx;
    StringBuilder textBuf    = new StringBuilder();
    int           textCursor = 0;
    int           textSelectionAnchor = -1;
    boolean       textSelectionDragging = false;
    boolean       textSelectionPendingDrag = false;
    int           textSelectionPressCursor = -1;
    float         textSelectionPressX = 0f;
    float         textSelectionPressY = 0f;
    static final float TEXT_SINGLE_FIELD_H = 20f;
    static final float TEXT_MULTI_LINE_STEP = 14f;
    static final float TEXT_MULTI_PAD_Y = 6f;
    static final float TEXT_INLINE_FIELD_H = 32f;
    static final float TEXT_INLINE_FIELD_GAP = 12f;
    static final float LIST_ITEM_H = TEXT_INLINE_FIELD_H;
    static final float LIST_ITEM_GAP = 6f;
    static final float LIST_ACTION_W = 24f;
    static final float SLIDER_FIELD_TRACK_GAP = 14f;
    boolean activeEditorBoundsSet = false;
    float activeEditorX;
    float activeEditorY;
    float activeEditorW;
    float activeEditorH;

    // -- Dropdown -------------------------------------------------------------

    DropdownSetting openDd;
    private float ddX, ddY, ddW, ddButtonH;
    private float ddAnimAmt = 0f;    // 0->1 open fade
    static final float DD_ITEM_H = 30f;

    // -- HSV Color picker ------------------------------------------------------

    /** The color swatch the mouse is currently hovering over (updated each frame). */
    ColorSetting  hoveredColor;
    ColorSetting  activeColor;
    private float cpHue = 0f, cpSat = 1f, cpVal = 1f, cpAlpha = 1f;
    /** 0=none 1=SV 2=Hue 3=Alpha */
    private int cpDrag = 0;
    static final int CP_SV = 1, CP_HUE = 2, CP_ALPHA = 3;
    boolean cpHexFocus = false;
    private final StringBuilder cpHexBuf = new StringBuilder();
    /** Picker layout bounds (written each frame). */
    private float cpPX, cpPY, cpPW, cpPH;
    private float cpSvX, cpSvY, cpSvW, cpSvH;
    private float cpHBarY, cpABarY, cpBarH;
    private float cpHexX, cpHexY, cpHexW, cpHexH;

    // -- Toggle pill animations ------------------------------------------------

    private record Section(String name, List<ModulesTab.SubTab> subtabs) {}
    /** Card hover progress per subtab (0 = not hovered, 1 = fully hovered). */
    private final IdentityHashMap<ModulesTab.SubTab, Float> cardHoverAnim = new IdentityHashMap<>();

    // -- Module detail view ----------------------------------------------------

    /** Non-null when the user has opened a module's settings view. */
    private ModulesTab.SubTab activeSubTab = null;
    /** Index of the selected category (SettingGroup) within activeSubTab. */
    private int activeCategoryIdx = 0;
    /** Hover animation per category item in the left panel (keyed by SettingGroup or this for "All"). */
    private final IdentityHashMap<Object, Float> catHoverAnim = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Float> subTabBarAnim = new IdentityHashMap<>();
    boolean suppressNestedContentScissor = false;
    /** Animated Y position for the selected category bar. */
    private float catBarAnimY = 0f;
    private float catBarFromY = 0f;
    private float catBarTargetY = 0f;
    private float catBarAnimT = 1f;
    private boolean catBarInited = false;
    private long catBarStartNanos = 0L;

    // -- Panel drag ------------------------------------------------------------

    private boolean panelDragging = false;
    private float   dragPanelOffX, dragPanelOffY;

    // -- Children visibility override (right-click toggle) ---------------------

    private final Set<SettingGroup> forcedOverride = new HashSet<>();
    private final MainGUIContext context = new MainGUIContext();
    private final MainGUILayout layoutHelper = new MainGUILayout();
    private final MainGUIActions actions = new MainGUIActions(this, context);
    private final MainGUITextLayout textLayout = new MainGUITextLayout();
    private final MainGUIRenderPrimitives renderPrimitives = new MainGUIRenderPrimitives(this, textLayout);
    private final MainGUIChromeRenderer chromeRenderer = new MainGUIChromeRenderer(this);
    private final MainGUIContentRenderer contentRenderer = new MainGUIContentRenderer(this, chromeRenderer);
    private final MainGUIModuleOverviewRenderer moduleOverviewRenderer = new MainGUIModuleOverviewRenderer(this);
    private final MainGUIFlatPanelRenderer flatPanelRenderer = new MainGUIFlatPanelRenderer(this);
    private final MainGUIModuleDetailRenderer moduleDetailRenderer = new MainGUIModuleDetailRenderer(this);
    private final MainGUISettingRowRenderer settingRowRenderer = new MainGUISettingRowRenderer(this);
    private final MainGUIOverlayController overlayController = new MainGUIOverlayController(this);
    private final MainGUISettingInteractionController settingInteractionController = new MainGUISettingInteractionController(this);
    private final MainGUITransitionRenderer transitionRenderer = new MainGUITransitionRenderer(this);
    private final MainGUIPetTrackerPanel petTrackerPanel = new MainGUIPetTrackerPanel(this);
    private final MainGUIMouseController mouseController = new MainGUIMouseController(this);
    private final MainGUIKeyboardController keyboardController = new MainGUIKeyboardController(this);
    private final MainGUISearchPanel searchPanel = new MainGUISearchPanel(this);
    private final MainGUIProfilesPanel profilesPanel = new MainGUIProfilesPanel(this);
    private final MainGUIInlineTextEditor inlineTextEditor = new MainGUIInlineTextEditor(this);
    private final LaunchTarget launchTarget;
    private boolean launchTargetApplied;
    private boolean frameStatePrepared;
    private boolean renderedQueuedFrame;
    private float frameMouseX;
    private float frameMouseY;
    private float frameDeltaTime;

    // -- Constructor -----------------------------------------------------------

    public MainGUI() {
        this(null);
    }

    public MainGUI(LaunchTarget launchTarget) {
        super("Aether");
        this.launchTarget = launchTarget;
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void initNVG() {
        Minecraft client = Minecraft.getInstance();
        if (MacroStateManager.isMacroRunning()) {
            MacroStateManager.stopMacro(client, "MainGUI opened", false);
        }
        MainGUIRegistry.refresh();
        // Resolve pixel ratio from the live render target so panel dimensions
        // are expressed in physical pixels, not GUI-scaled logical pixels.
        try {
            var rt = client.getMainRenderTarget();
            pr = (float) rt.width / this.width;
        } catch (Exception ignored) {
            pr = 1f;
        }
        applyFrameLayout(true, 0f, 0f);
        activeSliderField = null;
        activeText  = null;
        activeList  = null;
        activeListIndex = -1;
        openDd      = null;
        dragSetting = null;
        lastFrameTimeNanos = System.nanoTime();
        refreshContext();
        applyLaunchTargetIfNeeded();
    }

    // -- Data helpers ----------------------------------------------------------

    private List<ModulesTab.SubTab> subtabs() {
        return switch (activeMain) {
            case 0  -> MainGUIRegistry.MODULE_SUBTABS;
            case 1  -> MainGUIRegistry.COLORS_SUBTABS;
            case 2  -> MainGUIRegistry.PROFILES_SUBTABS;
            case 3  -> MainGUIRegistry.KEYBINDS_SUBTABS;
            case 4  -> MainGUIRegistry.SETTINGS_SUBTABS;
            default -> MainGUIRegistry.PROFILES_SUBTABS;
        };
    }

    private List<SettingGroup> groups() {
        if (activeMain == 2) return List.of();   // Profiles - custom render
        List<ModulesTab.SubTab> subs = subtabs();
        if (subs.isEmpty()) return List.of();
        if (activeSubtab >= subs.size()) activeSubtab = 0;
        return subs.get(activeSubtab).groups();
    }

    static boolean hasTopFilterBar(int mainTab) {
        return mainTab >= 0 && mainTab <= 2;
    }

    private boolean hasTopFilterBar() {
        return hasTopFilterBar(activeMain);
    }

    float contentScrollTop() {
        return layoutHelper.contentScrollTop(contY, hasTopFilterBar(), TOP_BAR_H, FILTER_BAR_H);
    }

    float contentScrollHeight() {
        return layoutHelper.contentScrollHeight(contY, contH, hasTopFilterBar(), TOP_BAR_H, FILTER_BAR_H);
    }

    private void resetActiveContentScroll() {
        actions.resetActiveContentScroll();
    }

    private void syncActiveBodyScrollState() {
        actions.syncActiveBodyScrollState();
    }

    private void enterModuleDetail(ModulesTab.SubTab subTab) {
        actions.enterModuleDetail(subTab);
    }

    private void exitModuleDetail() {
        actions.exitModuleDetail();
    }

    boolean pushContentLocalScissor(NVGRenderer nvg, float x, float y, float w, float h) {
        if (suppressNestedContentScissor) return false;
        nvg.pushScissor(x, y, w, h);
        return true;
    }

    void popContentLocalScissor(NVGRenderer nvg, boolean clipped) {
        if (clipped) nvg.popScissor();
    }

    void addClickArea(float x, float y, float w, float h, Runnable action) {
        clickAreas.add(new ClickArea(x, y, w, h, action));
    }

    void openModuleDetailFromCard(ModulesTab.SubTab subTab) {
        enterModuleDetail(subTab);
    }

    private List<ModulesTab.SubTab> flatSubtabsForFilter() {
        List<ModulesTab.SubTab> subs = subtabs();
        if (activeMain != 1) return subs;
        int filterIndex = normalizedActiveFilterIndex();
        return switch (filterIndex) {
            case 1 -> subs.size() > 0 ? List.of(subs.get(0)) : List.of();
            case 2 -> subs.size() > 1 ? List.of(subs.get(1)) : List.of();
            default -> subs;
        };
    }

    boolean showConfigProfiles() {
        int filterIndex = normalizedActiveFilterIndex();
        return filterIndex == 0 || filterIndex == 1;
    }

    boolean showThemeProfiles() {
        int filterIndex = normalizedActiveFilterIndex();
        return filterIndex == 0 || filterIndex == 2;
    }

    boolean isProfileNameFocused(boolean isConfig) {
        return profileNameFocus && profileNameTarget == (isConfig ? 0 : 1);
    }

    boolean isProfileRenameFocused(boolean isConfig, String name) {
        return profileRenameFocus
                && profileRenameTarget == (isConfig ? 0 : 1)
                && profileRenameOriginal.equals(name);
    }

    void beginProfileRename(boolean isConfig, String name) {
        commitText();
        commitColor();
        openDd = null;
        profileNameFocus = false;
        profileRenameTarget = isConfig ? 0 : 1;
        profileRenameOriginal = name;
        profileRenameInput = name;
        profileRenameFocus = true;
    }

    void cancelProfileRename() {
        profileRenameFocus = false;
        profileRenameOriginal = "";
        profileRenameInput = "";
    }

    void commitProfileRename() {
        profilesPanel.commitRename();
    }

    /**
     * Returns the list of sections (name + groups) to display in the Modules
     * card grid based on the active filter.
     */
    private List<Section> sectionsForFilter() {
        List<Section> availableSections = availableModuleSections();
        if (availableSections.size() <= 1) {
            return availableSections;
        }

        int filterIndex = normalizedActiveFilterIndex();
        if (filterIndex == 0) {
            return availableSections;
        }

        int sectionIndex = filterIndex - 1;
        if (sectionIndex < 0 || sectionIndex >= availableSections.size()) {
            return availableSections;
        }

        return List.of(availableSections.get(sectionIndex));
    }

    private List<Section> availableModuleSections() {
        List<Section> sections = new ArrayList<>(MainGUIRegistry.MODULE_SECTIONS.size());
        for (MainGUIRegistry.ModuleSection section : MainGUIRegistry.MODULE_SECTIONS) {
            sections.add(new Section(section.displayName(), new ArrayList<>(section.subtabs())));
        }
        return sections;
    }

    /** Whether children of {@code group} should currently be visible. */
    private boolean showChildren(SettingGroup group) {
        if (group.isAlwaysOn()) return true;
        boolean forced = forcedOverride.contains(group);
        return group.isEnabled() != forced;
    }

    private boolean isPetTrackerGroup(SettingGroup group) {
        return group.getRawName().startsWith("Pet XP Tracker");
    }

    private int normalizedActiveSubtabIndex() {
        List<ModulesTab.SubTab> subs = subtabs();
        if (subs.isEmpty()) return 0;
        return Math.max(0, Math.min(activeSubtab, subs.size() - 1));
    }

    private int normalizedActiveCategoryIdx() {
        if (activeMain != 0 || activeSubTab == null) return activeCategoryIdx;
        int maxCatIdx = activeSubTab.groups().size();
        int normalized = Math.max(0, Math.min(activeCategoryIdx, maxCatIdx));
        if (maxCatIdx == 1 && normalized == 0) return 1;
        return normalized;
    }

    MainGUIContentViewState snapshotContentState() {
        int filterIndex = normalizedActiveFilterIndex();
        return new MainGUIContentViewState(
                activeMain,
                normalizedActiveSubtabIndex(),
                filterIndex,
                activeSubTab,
                normalizedActiveCategoryIdx(),
                searchQuery
        );
    }

    void applyContentState(MainGUIContentViewState state) {
        activeMain = state.activeMain();
        activeSubtab = state.activeSubtab();
        activeFilter = state.activeFilter();
        activeFilter = normalizedActiveFilterIndex();
        activeSubTab = state.activeSubTab();
        activeCategoryIdx = state.activeCategoryIdx();
        searchQuery = state.searchQuery();
    }

    private Setting findSetting(SettingGroup group, String name) {
        for (Setting setting : group.getSettings()) {
            if (setting.getRawName().equals(name)) return setting;
        }
        return null;
    }

    private float renderPetTrackerHeaderButtons(NVGRenderer nvg, SettingGroup group, float right, float btnY,
                                                float btnH, float mx, float my) {
        long trackerCount = currentPetTrackerGroupCount();
        boolean canAdd = true;
        boolean canRemove = trackerCount > 1;
        float gap = 6f;

        if (canRemove) {
            float btnW = 68f;
            float bx = right - btnW;
            boolean hovered = mx >= bx && mx <= bx + btnW && my >= btnY && my <= btnY + btnH;
            nvg.roundedRect(bx, btnY, btnW, btnH, 4f, hovered ? Theme.BG_HOVER : Theme.BG_FIELD);
            nvg.rectOutline(bx, btnY, btnW, btnH, 4f, 1f, Theme.BORDER_DEFAULT);
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Remove"), bx, btnY, btnW, btnH, 11f, Theme.TEXT_PRIMARY);
            clickAreas.add(new ClickArea(bx, btnY, btnW, btnH, () -> {
            Setting remove = findSetting(group, "Remove Pet");
                if (remove instanceof ActionSetting action) {
                    action.execute();
                }
            }));
            right = bx - gap;
        }

        if (canAdd) {
            float btnW = 56f;
            float bx = right - btnW;
            boolean hovered = mx >= bx && mx <= bx + btnW && my >= btnY && my <= btnY + btnH;
            nvg.roundedRect(bx, btnY, btnW, btnH, 4f, hovered ? Theme.BG_HOVER : Theme.BG_FIELD);
            nvg.rectOutline(bx, btnY, btnW, btnH, 4f, 1f, Theme.BORDER_DEFAULT);
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Add"), bx, btnY, btnW, btnH, 11f, Theme.TEXT_PRIMARY);
            clickAreas.add(new ClickArea(bx, btnY, btnW, btnH, () -> {
                Setting add = findSetting(group, "Add Pet");
                if (add instanceof ActionSetting action) {
                    action.execute();
                }
            }));
            right = bx - gap;
        }

        return right;
    }

    private long currentPetTrackerGroupCount() {
        if (activeSubTab != null) {
            return activeSubTab.groups().stream().filter(this::isPetTrackerGroup).count();
        }
        return groups().stream().filter(this::isPetTrackerGroup).count();
    }

    private boolean hasActiveInlineEditor() {
        return inlineTextEditor.hasActiveInlineEditor();
    }

    private boolean showSearchResults() {
        return searchQuery.length() >= 2;
    }

    private boolean isSearchEditorActive() {
        return inlineTextEditor.isSearchEditorActive();
    }

    private void syncSearchQueryFromEditor() {
        inlineTextEditor.syncSearchQueryFromEditor();
    }

    private void focusSearchField() {
        inlineTextEditor.focusSearchField();
    }

    private boolean isPointInsideSearchField(float mx, float my) {
        return inlineTextEditor.isPointInsideSearchField(mx, my);
    }

    private void updateSearchSelectionFromMouse(float mx) {
        inlineTextEditor.updateSearchSelectionFromMouse(mx);
    }

    private boolean hasTextSelection() {
        return inlineTextEditor.hasTextSelection();
    }

    private int textSelectionStart() {
        return inlineTextEditor.textSelectionStart();
    }

    private int textSelectionEnd() {
        return inlineTextEditor.textSelectionEnd();
    }

    private void clearTextSelection() {
        inlineTextEditor.clearTextSelection();
    }

    private void normalizeTextCursorState() {
        inlineTextEditor.normalizeTextCursorState();
    }

    private String safeSubstring(String value, int start, int end) {
        return inlineTextEditor.safeSubstring(value, start, end);
    }

    private void setTextCursor(int cursor, boolean extendSelection) {
        inlineTextEditor.setTextCursor(cursor, extendSelection);
    }

    void selectAllText() {
        inlineTextEditor.selectAllText();
    }

    private boolean deleteSelectedText() {
        return inlineTextEditor.deleteSelectedText();
    }

    private void insertTextAtCursor(String text) {
        inlineTextEditor.insertTextAtCursor(text);
    }

    private static boolean isWordChar(char c) {
        return MainGUIInlineTextEditor.isWordChar(c);
    }

    private static int moveCursorWord(String text, int cursor, int direction) {
        return MainGUIInlineTextEditor.moveCursorWord(text, cursor, direction);
    }

    private static int cursorFromInlineText(String text, float fontSize, float startX, float mx) {
        return MainGUIInlineTextEditor.cursorFromInlineText(text, fontSize, startX, mx);
    }

    private void updateInlineSelectionFromMouse(float mx) {
        inlineTextEditor.updateInlineSelectionFromMouse(mx);
    }

    private void beginInlineCaretPress(float mx, float my) {
        inlineTextEditor.beginInlineCaretPress(mx, my);
    }

    private boolean shouldBeginInlineSelectionDrag(float mx, float my) {
        return inlineTextEditor.shouldBeginInlineSelectionDrag(mx, my);
    }

    private void setActiveEditorBounds(float x, float y, float w, float h) {
        inlineTextEditor.setActiveEditorBounds(x, y, w, h);
    }

    private boolean isPointInsideActiveEditor(float mx, float my) {
        return inlineTextEditor.isPointInsideActiveEditor(mx, my);
    }

    // -- Render ----------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        prepareFrameState(mx, my, dt);
        AetherRenderQueue.enqueue(this::renderQueuedFrame);
    }

    public void renderAfterGameRenderer(float dt) {
        if (renderedQueuedFrame) {
            renderedQueuedFrame = false;
            return;
        }
        if (!frameStatePrepared) {
            Minecraft client = Minecraft.getInstance();
            int mx = (int) client.mouseHandler.getScaledXPos(client.getWindow());
            int my = (int) client.mouseHandler.getScaledYPos(client.getWindow());
            prepareFrameState(mx, my, dt);
        }

        renderPreparedFrame(frameMouseX, frameMouseY, frameDeltaTime);
        frameStatePrepared = false;
    }

    private void renderQueuedFrame() {
        if (Minecraft.getInstance().screen != this) {
            frameStatePrepared = false;
            return;
        }
        if (!frameStatePrepared) {
            Minecraft client = Minecraft.getInstance();
            int mx = (int) client.mouseHandler.getScaledXPos(client.getWindow());
            int my = (int) client.mouseHandler.getScaledYPos(client.getWindow());
            prepareFrameState(mx, my, frameDeltaTime);
        }

        renderPreparedFrame(frameMouseX, frameMouseY, frameDeltaTime);
        frameStatePrepared = false;
        renderedQueuedFrame = true;
    }

    private void prepareFrameState(int mx, int my, float dt) {
        MainGUIRegistry.refresh();
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();

        float prevPhysW = physW;
        float prevPhysH = physH;

        // Resolve pixel ratio so all coordinates are in physical pixels.
        // We do this before beginFrame so hover detection uses the right scale.
        try {
            var rt = Minecraft.getInstance().getMainRenderTarget();
            pr = (float) rt.width / this.width;
        } catch (Exception ignored) { pr = 1f; }
        syncFrameLayoutForWindowSize(prevPhysW, prevPhysH);
        // Convert logical mouse coords to layout units (physical px / uiScale)
        float pmx = mx * pr / uiScale;
        float pmy = my * pr / uiScale;
        long nowNanos = System.nanoTime();
        long dtNanos = Math.max(0L, Math.min(100_000_000L, nowNanos - lastFrameTimeNanos));
        lastFrameTimeNanos = nowNanos;

        float as    = Theme.animationFactor();
        float animT = Math.min(1f, as * 2.5f);

        // Entrance animation
        alpha      += (1f - alpha)      * as;
        animScale  += (1f - animScale)  * (as * 1.1f);
        animOffsetY += (0f - animOffsetY) * (as * 1.1f);

        // Sidebar tab sliding (kept for compatibility, unused in new render)
        animMainSel += (activeMain   - animMainSel) * animT;
        animSubSel  += (activeSubtab - animSubSel)  * animT;

        // Sidebar hover expand (physical coords)
        float expandedW = computedSidebarExpanded > 0f ? computedSidebarExpanded : SIDEBAR_EXPANDED;
        float sbCurW = SIDEBAR_W + sidebarAnim * (expandedW - SIDEBAR_W);
        boolean sbHov = pmx >= px && pmx < px + sbCurW && pmy >= py && pmy <= py + ph;
        sidebarAnim  += ((sbHov ? 1f : 0f) - sidebarAnim) * Math.min(1f, as * 0.5f);

        // Smooth scroll
        float scrollSpeed = scrollAnimStep(dtNanos);
        scrollY        += (targetScrollY        - scrollY)        * scrollSpeed;
        scrollY         = Math.max(0f, Math.min(maxScrollY,        scrollY));
        searchScrollY  += (searchTargetScrollY  - searchScrollY)  * scrollSpeed;
        searchScrollY   = Math.max(0f, Math.min(searchMaxScrollY,  searchScrollY));
        profileScrollY += (profileTargetScrollY - profileScrollY) * scrollSpeed;
        profileScrollY  = Math.max(0f, Math.min(profileMaxScrollY, profileScrollY));
        subtabScrollY  += (subtabTargetScrollY - subtabScrollY) * scrollSpeed;
        subtabScrollY   = Math.max(0f, Math.min(subtabMaxScrollY, subtabScrollY));
        syncActiveBodyScrollState();

        // Dropdown fade-in
        if (openDd != null) ddAnimAmt = Math.min(1f, ddAnimAmt + animT);

        activeEditorBoundsSet = false;
        frameMouseX = pmx;
        frameMouseY = pmy;
        frameDeltaTime = dt;
        frameStatePrepared = true;
    }

    private void renderPreparedFrame(float pmx, float pmy, float dt) {
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            // Scale NVG canvas: 1 unit = uiScale physical pixels.
            // At uiScale=1.0 everything is pixel-perfect; raise to enlarge,
            // lower to shrink the entire menu.
            nvg.save();
            nvg.scale(uiScale / pr, uiScale / pr);
            nvg.setTextScale(uiTextScale);

            float canvasW = physW / uiScale, canvasH = physH / uiScale;
            nvg.rect(0, 0, canvasW, canvasH,
                    Theme.withAlpha(0xFF000000, (int)(alpha * 170)));

            float scx = canvasW / 2f, scy = canvasH / 2f;

            // Background: scale + slide + fade (zoom-in effect on the chrome)
            nvg.save();
            nvg.translate(scx, scy);
            nvg.scale(animScale, animScale);
            nvg.translate(-scx, -scy);
            nvg.translate(0, animOffsetY);
            nvg.globalAlpha(alpha);
            nvg.shadow(px, py, pw, ph, RADIUS, 26f,
                    Theme.withAlpha(0xFF000000, 0.75f));
            nvg.roundedRect(px, py, pw, ph, RADIUS, Theme.PANEL_BG);
            nvg.rectOutline(px, py, pw, ph, RADIUS, 1f, Theme.BORDER_DEFAULT);
            nvg.restore();

            // Content: slide + fade only - no scale so text stays at its final
            // rendered size and doesn't jitter during the zoom-in animation
            nvg.save();
            nvg.translate(0, animOffsetY);
            nvg.globalAlpha(alpha);
            renderPanel(nvg, pmx, pmy);
            nvg.restore(); // entrance-animation transform

            nvg.restore(); // physical-pixel scale

            // Gui.render() is suppressed while this screen is open, so we drive
            // the HUD fade-out here instead of keeping the HUD fully visible.
            renderOptionalFeatureHud(nvg, dt);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private void renderPanel(NVGRenderer nvg, float mx, float my) {
        refreshContext();
        clickAreas.clear();
        hoveredColor = null;
        transitionRenderer.syncContentTransition();
        transitionRenderer.renderContentWithTransition(nvg, mx, my);
        renderSidebar(nvg, mx, my);
    }

    private void syncContentTransition() {
        transitionRenderer.syncContentTransition();
    }

    private void renderContentWithTransition(NVGRenderer nvg, float mx, float my) {
        transitionRenderer.renderContentWithTransition(nvg, mx, my);
    }

    // -- Sidebar ---------------------------------------------------------------


    // -- Sidebar layout constants ----------------------------------------------
    /** Horizontal padding inside the sidebar (icon box starts here from panel left). */
    static final float SB_H_PAD    = 12f;
    /** Size of each tab's icon pill (highlight square). */
    static final float SB_PILL     = 36f;
    /** Height of the logo section (logo + spacing below it). */
    static final float SB_LOGO_H   = 56f;
    /** Gap between the logo separator and the first tab row. */
    static final float SB_SEP_GAP  = 8f;
    /** Vertical padding within a tab row (pill inset from row top). */
    static final float SB_ROW_PAD  = (44f - SB_PILL) / 2f;   // = 4f
    /** Vertical bottom margin for the bottom section (Settings row to panel bottom). */
    static final float SB_BOT_PAD  = 8f;

    private void renderSidebar(NVGRenderer nvg, float mx, float my) {
        chromeRenderer.renderSidebar(nvg, mx, my);
    }

    // -- Content top bar -------------------------------------------------------

    private void renderContentTopBar(NVGRenderer nvg, float mx, float my) {
        chromeRenderer.renderContentTopBar(nvg, mx, my);
    }

    // -- Filter bar ------------------------------------------------------------

    private void renderFilterBar(NVGRenderer nvg, float mx, float my) {
        chromeRenderer.renderFilterBar(nvg, mx, my);
    }

    // -- Content area ----------------------------------------------------------

    private void renderContent(NVGRenderer nvg, float mx, float my) {
        contentRenderer.renderContent(nvg, mx, my);
    }

    private void renderModuleDetailView(NVGRenderer nvg, float mx, float my) {
        contentRenderer.renderModuleDetailView(nvg, mx, my);
    }

    private void renderModuleDetailBody(NVGRenderer nvg, float mx, float my) {
        contentRenderer.renderModuleDetailBody(nvg, mx, my);
    }


    private void syncCategoryBarAnimation(float targetY) {
        if (!catBarInited) {
            catBarAnimY = targetY;
            catBarFromY = targetY;
            catBarTargetY = targetY;
            catBarAnimT = 1f;
            catBarStartNanos = System.nanoTime();
            catBarInited = true;
            return;
        }

        if (Math.abs(catBarTargetY - targetY) > 0.5f) {
            catBarFromY = catBarAnimY;
            catBarTargetY = targetY;
            catBarStartNanos = System.nanoTime();
        }

        float durationMs = Math.max(1f, Theme.ANIM_TIME_MS);
        float elapsedMs = Math.max(0f, (System.nanoTime() - catBarStartNanos) / 1_000_000f);
        float rawT = Math.max(0f, Math.min(1f, elapsedMs / durationMs));
        catBarAnimT = rawT * rawT * (3f - 2f * rawT);
        catBarAnimY = catBarFromY + (catBarTargetY - catBarFromY) * catBarAnimT;
        if (rawT >= 1f) {
            catBarFromY = catBarTargetY;
            catBarAnimT = 1f;
        }
    }

    // -- Module card grid (View 1) ----------------------------------------------

    private void renderModuleGrid(NVGRenderer nvg, float mx, float my,
                                   float scrollTop, float scrollH) {
        moduleOverviewRenderer.render(nvg, mx, my, scrollTop, scrollH);
    }

    // -- Flat settings renderer (Colors + Settings tabs) -----------------------

    private void renderFlatContent(NVGRenderer nvg, float mx, float my) {
        flatPanelRenderer.render(nvg, mx, my);
    }

    private void renderFlatGroupLabel(NVGRenderer nvg, SettingGroup group,
                                      float x, float y, float w, float mx, float my) {
        // Section name
        int nameCol = (group.isEnabled() && !group.isAlwaysOn()) ? Theme.GROUP_ACTIVE : Theme.TEXT_MUTED;
        nvg.text(Fonts.BOLD, group.getName(), x, y + (FLAT_LABEL_H - 10f) / 2f, 10f, nameCol);

        // Thin horizontal rule after the label
        float textW = nvg.textWidth(Fonts.BOLD, group.getName(), 10f);
        float lineRight = group.isAlwaysOn() ? x + w : x + w - 50f;
        if (isPetTrackerGroup(group)) {
            float buttonsLeft = renderPetTrackerHeaderButtons(
                    nvg, group, x + w - 12f, y + (FLAT_LABEL_H - 22f) / 2f, 22f, mx, my);
            lineRight = Math.min(lineRight, buttonsLeft - 8f);
        }
        nvg.rect(x + textW + 8f, y + FLAT_LABEL_H / 2f, lineRight - (x + textW + 8f), 1f, Theme.SEPARATOR);

        // Toggle pill for non-alwaysOn groups
        if (!group.isAlwaysOn()) {
            renderPill(nvg, x + w - 38f, y + (FLAT_LABEL_H - PILL_H) / 2f, group.isEnabled(), group);
        }
    }

    void renderSectionHeader(NVGRenderer nvg, String name, float x, float y, float w) {
        renderPrimitives.renderSectionHeader(nvg, name, x, y, w);
    }

    /** Derives the display name for a module card from its SubTab. */
    private static String moduleCardName(ModulesTab.SubTab sub) {
        return AetherLang.localize(sub.name());
    }

    private static boolean isSubTabEnabled(ModulesTab.SubTab sub) {
        return sub.isEnabled();
    }

    private void renderModuleCard(NVGRenderer nvg, ModulesTab.SubTab sub,
                                   float x, float y, float w, float hov,
                                   float mx, float my) {
        renderPrimitives.renderModuleCard(nvg, sub, x, y, w, hov, mx, my);
    }

    // -- Module detail view (View 2) -------------------------------------------

    private void renderModuleTopBar(NVGRenderer nvg, float mx, float my) {
        moduleDetailRenderer.renderTopBar(nvg, mx, my);
    }

    private void renderModuleCategoryPanel(NVGRenderer nvg, float mx, float my,
                                            float panelTop, float panelH) {
        moduleDetailRenderer.renderCategoryPanel(nvg, mx, my, panelTop, panelH);
    }

    private void renderModuleSettingsPanel(NVGRenderer nvg, float mx, float my,
                                            float panelTop, float panelH) {
        moduleDetailRenderer.renderSettingsPanel(nvg, mx, my, panelTop, panelH);
    }

    private float subTabBarAnimValue(Object key, boolean active) {
        float target = active ? 1f : 0f;
        float current = subTabBarAnim.getOrDefault(key, target);
        float animationFactor = Theme.animationFactor();
        current += (target - current) * Math.min(0.05f, animationFactor * 3f);
        subTabBarAnim.put(key, current);
        return current;
    }

    private void renderGroupHeader(NVGRenderer nvg, SettingGroup group,
                                   float x, float y, float w, float mx, float my,
                                   boolean interactive) {
        nvg.roundedRect(x, y, w, HEADER_H, 6f, Theme.BG_SECONDARY);

        int stripe;
        float lerp = subTabBarAnimValue(group, (group.isEnabled() && !group.isAlwaysOn()) || (group.isAlwaysOn() && interactive));
        stripe = Theme.blend(0xFFFFFFFF, Theme.ACCENT_PRIMARY, lerp);

        nvg.rectOutlineVerticalSides(x, y, w, HEADER_H, 7f, 1f, Theme.withAlpha(stripe, 0.15f + 0.25f * lerp), lerp * 0.4f);

        int titleColor = interactive ? Theme.TEXT_PRIMARY : Theme.withAlpha(Theme.TEXT_DIM, 210);
        int descColor = interactive ? Theme.TEXT_SECONDARY : Theme.withAlpha(Theme.TEXT_DIM, 170);
        nvg.text(Fonts.BOLD,    AetherLang.localize(group.getName()),        x + 12f, y +  9f, 13f, titleColor);
        nvg.text(Fonts.REGULAR, AetherLang.localize(group.getDescription()), x + 12f, y + 26f, 10f, descColor);

        if (isPetTrackerGroup(group)) {
            renderPetTrackerHeaderButtons(nvg, group, x + w - 12f, y + 12f, 22f, mx, my);
        }

        if (!group.isAlwaysOn() && !group.isEnabled() && forcedOverride.contains(group)) {
            nvg.text(Fonts.REGULAR, "\u25bc", x + w - 68f, y + (HEADER_H - 11f) / 2f, 9f,
                    Theme.withAlpha(Theme.TEXT_SECONDARY, 150));
        }

        if (!group.isAlwaysOn()) {
            float pillX = x + w - 52f;
            float pillY = y + (HEADER_H - PILL_H) / 2f;
            renderPill(nvg, pillX, pillY, group.isEnabled(), group);
            if (interactive) {
                clickAreas.add(new ClickArea(pillX - 6f, pillY - 6f, 48f, PILL_H + 12f, group::toggle));
            }
        }

        if (!interactive) {
            nvg.roundedRect(x, y, w, HEADER_H, 6f, Theme.withAlpha(Theme.PANEL_BG, 0.34f));
        }
    }

    void renderSettingRow(NVGRenderer nvg, Setting setting,
                                  float x, float y, float w, float h,
                                  float mx, float my) {
        settingRowRenderer.render(nvg, setting, x, y, w, h, mx, my);
    }

    private float petTrackerGroupHeight() {
        return PET_FIELD_H * 2f + PET_ROW_GAP;
    }

    static float petFieldHeight() {
        return PET_FIELD_H;
    }

    static float petRowGap() {
        return PET_ROW_GAP;
    }

    static float petColGap() {
        return PET_COL_GAP;
    }

    private void renderPetTrackerGroup(NVGRenderer nvg, SettingGroup group, float x, float y, float w, float mx, float my) {
        petTrackerPanel.renderGroup(nvg, group, x, y, w, mx, my);
    }

    private boolean handlePetTrackerGroupClick(SettingGroup group, float mx, float my, float x, float y, float w) {
        return petTrackerPanel.handleGroupClick(group, mx, my, x, y, w);
    }

    private void renderListActionButton(NVGRenderer nvg, float x, float y, float w, String label,
                                        boolean hovered, boolean enabled) {
        renderPrimitives.renderListActionButton(nvg, x, y, w, label, hovered, enabled);
    }

    // -- Toggle pill -----------------------------------------------------------

    private void renderPill(NVGRenderer nvg, float x, float y, boolean on, Object key) {
        renderPrimitives.renderPill(nvg, x, y, on, key);
    }

    // -- Dropdown overlay ------------------------------------------------------

    private void renderDropdownOverlay(NVGRenderer nvg, float mx, float my) {
        overlayController.renderDropdownOverlay(nvg, mx, my);
    }

    // -- HSV Color picker overlay ----------------------------------------------

    private void renderColorOverlay(NVGRenderer nvg) {
        overlayController.renderColorOverlay(nvg);
    }

    // -- Helpers ---------------------------------------------------------------

    float settingH(Setting s, float rowW) {
        return textLayout.settingHeight(s, rowW);
    }

    private int wrappedSettingLabelLineCount(Setting setting, float rowW) {
        return textLayout.wrappedSettingLabelLineCount(setting, rowW);
    }

    private float settingLabelFontSize(Setting setting) {
        return textLayout.settingLabelFontSize(setting);
    }

    private float settingLabelLineStep(Setting setting) {
        return textLayout.settingLabelLineStep(setting);
    }

    private float settingLabelMaxWidth(Setting setting, float rowW) {
        return textLayout.settingLabelMaxWidth(setting, rowW);
    }

    private float dropdownActionStripWidth(DropdownSetting dropdown) {
        return textLayout.dropdownActionStripWidth(dropdown);
    }

    private float dropdownActionStartX(DropdownSetting dropdown, float fieldX) {
        return textLayout.dropdownActionStartX(dropdown, fieldX);
    }

    private int dropdownActionIndexAt(DropdownSetting dropdown, float fieldX, float fieldY, float fieldH,
                                      float mx, float my) {
        return textLayout.dropdownActionIndexAt(dropdown, fieldX, fieldY, fieldH, mx, my);
    }

    private void renderDropdownActionButtons(NVGRenderer nvg, DropdownSetting dropdown,
                                             float fieldX, float fieldY, float fieldH,
                                             float mx, float my) {
        renderPrimitives.renderDropdownActionButtons(nvg, dropdown, fieldX, fieldY, fieldH, mx, my);
    }

    private float textInlineFieldWidth(float innerWidth) {
        return textLayout.textInlineFieldWidth(innerWidth);
    }

    private float inlineTextScrollOffset(NVGRenderer nvg, String font, String text,
                                         int cursor, float fontSize, float visibleWidth) {
        return textLayout.inlineTextScrollOffset(nvg, font, text, cursor, fontSize, visibleWidth);
    }

    float inlineTextScrollOffsetApprox(String text, int cursor, float fontSize, float visibleWidth) {
        return textLayout.inlineTextScrollOffsetApprox(text, cursor, fontSize, visibleWidth);
    }

    private int estimateWrappedLineCount(String text, float maxWidth, float fontSize) {
        return textLayout.estimateWrappedLineCount(text, maxWidth, fontSize);
    }

    private List<String> wrapSettingLabel(NVGRenderer nvg, Setting setting, float rowW) {
        return textLayout.wrapSettingLabel(nvg, setting, rowW);
    }

    List<String> wrapTextForWidth(NVGRenderer nvg, String text, String fontName, float fontSize, float maxWidth) {
        return textLayout.wrapTextToWidth(nvg, text, fontName, fontSize, maxWidth);
    }

    private int fittingPrefixLength(NVGRenderer nvg, String text, String fontName, float fontSize, float maxWidth) {
        return textLayout.fittingPrefixLength(nvg, text, fontName, fontSize, maxWidth);
    }

    private static float clampScroll(float value, float maxScroll) {
        return Math.max(0f, Math.min(maxScroll, value));
    }

    private static float scrollAnimStep(long dtNanos) {
        float dtMs = Math.max(0f, dtNanos / 1_000_000f);
        if (dtMs <= 0f) return 0f;
        float durationMs = Math.max(1f, Theme.ANIM_TIME_MS);
        return 1f - (float) Math.exp(-dtMs / (durationMs / 3f));
    }

    static float scrollbarThumbY(float trackT, float trackH, float thumbH, float scrollVal, float maxScroll) {
        if (maxScroll <= 0f || trackH <= thumbH) return trackT;
        float thumbY = trackT + (scrollVal / maxScroll) * (trackH - thumbH);
        return Math.max(trackT, Math.min(trackT + trackH - thumbH, thumbY));
    }

    private static boolean hitScrollbarTrack(float mx, float my, float trackT, float trackH, float barX) {
        return mx >= barX - 6f && mx <= barX + 10f && my >= trackT && my <= trackT + trackH;
    }

    private static boolean hitScrollbarThumb(float mx, float my, float trackT, float trackH, float thumbH,
                                             float scrollVal, float maxScroll, float barX) {
        if (!hitScrollbarTrack(mx, my, trackT, trackH, barX)) return false;
        float thumbY = scrollbarThumbY(trackT, trackH, thumbH, scrollVal, maxScroll);
        return my >= thumbY && my <= thumbY + thumbH;
    }

    private void setScrollbarScroll(int dragMode, float value, boolean immediate) {
        switch (dragMode) {
            case 1 -> {
                targetScrollY = clampScroll(value, maxScrollY);
                if (immediate) scrollY = targetScrollY;
            }
            case 2 -> {
                searchTargetScrollY = clampScroll(value, searchMaxScrollY);
                if (immediate) searchScrollY = searchTargetScrollY;
            }
            case 3 -> {
                profileTargetScrollY = clampScroll(value, profileMaxScrollY);
                if (immediate) profileScrollY = profileTargetScrollY;
            }
            case 4 -> {
                subtabTargetScrollY = clampScroll(value, subtabMaxScrollY);
                if (immediate) subtabScrollY = subtabTargetScrollY;
            }
        }
    }

    private void startScrollbarDrag(int dragMode, float mx, float my, float trackT, float trackH, float thumbH,
                                    float scrollVal, float maxScroll, float barX) {
        if (!hitScrollbarTrack(mx, my, trackT, trackH, barX) || maxScroll <= 0f) return;
        sbDragging = dragMode;
        float thumbY = scrollbarThumbY(trackT, trackH, thumbH, scrollVal, maxScroll);
        boolean thumbHit = hitScrollbarThumb(mx, my, trackT, trackH, thumbH, scrollVal, maxScroll, barX);
        sbDragThumbOffset = thumbHit ? (my - thumbY) : (thumbH * 0.5f);
        updateScrollbarDrag(my, thumbHit);
    }

    private void updateScrollbarDrag(float my, boolean immediate) {
        float trackT;
        float trackH;
        float thumbH;
        float maxScroll;
        switch (sbDragging) {
            case 1 -> {
                trackT = sbMainTrackT;
                trackH = sbMainTrackH;
                thumbH = sbMainThumbH;
                maxScroll = maxScrollY;
            }
            case 2 -> {
                trackT = sbSrchTrackT;
                trackH = sbSrchTrackH;
                thumbH = sbSrchThumbH;
                maxScroll = searchMaxScrollY;
            }
            case 3 -> {
                trackT = sbProfTrackT;
                trackH = sbProfTrackH;
                thumbH = sbProfThumbH;
                maxScroll = profileMaxScrollY;
            }
            case 4 -> {
                trackT = sbSubTrackT;
                trackH = sbSubTrackH;
                thumbH = sbSubThumbH;
                maxScroll = subtabMaxScrollY;
            }
            default -> {
                return;
            }
        }

        float range = trackH - thumbH;
        if (range <= 0f || maxScroll <= 0f) return;
        float thumbTop = Math.max(trackT, Math.min(trackT + range, my - sbDragThumbOffset));
        float scrollValue = ((thumbTop - trackT) / range) * maxScroll;
        setScrollbarScroll(sbDragging, scrollValue, immediate);
    }

    private float textSettingHeight(TextSetting setting) {
        return inlineTextEditor.textSettingHeight(setting);
    }

    private float textFieldHeight(TextSetting setting) {
        return inlineTextEditor.textFieldHeight(setting);
    }

    private static String[] splitLines(String text) {
        return MainGUIInlineTextEditor.splitLines(text);
    }

    private static int getLineStart(String text, int cursor) {
        return MainGUIInlineTextEditor.getLineStart(text, cursor);
    }

    private static int getLineEnd(String text, int cursor) {
        return MainGUIInlineTextEditor.getLineEnd(text, cursor);
    }

    private static int getCursorLine(String text, int cursor) {
        return MainGUIInlineTextEditor.getCursorLine(text, cursor);
    }

    private static int getCursorColumn(String text, int cursor) {
        return MainGUIInlineTextEditor.getCursorColumn(text, cursor);
    }

    private static int moveCursorVertical(String text, int cursor, int delta) {
        return MainGUIInlineTextEditor.moveCursorVertical(text, cursor, delta);
    }

    int getMultilineViewportStart(TextSetting setting, String text, int cursor) {
        return inlineTextEditor.getMultilineViewportStart(setting, text, cursor);
    }

    private String fmtSlider(SliderSetting ss) {
        float v   = ss.getValue();
        String sf = ss.getSuffix() != null ? ss.getSuffix() : "";
        return ss.getDecimals() == 0
                ? (int) v + sf
                : String.format("%." + ss.getDecimals() + "f", v) + sf;
    }

    private String fmtRangeSlider(RangeSliderSetting rs) {
        String sf = rs.getSuffix() != null ? rs.getSuffix() : "";
        if (rs.getDecimals() == 0) {
            return Math.round(rs.getLowerValue()) + sf + " - " + Math.round(rs.getUpperValue()) + sf;
        }
        return String.format("%." + rs.getDecimals() + "f", rs.getLowerValue()) + sf
                + " - "
                + String.format("%." + rs.getDecimals() + "f", rs.getUpperValue()) + sf;
    }

    String fmtSliderInput(SliderSetting ss) {
        float v = ss.getValue();
        return ss.getDecimals() == 0
                ? String.valueOf(Math.round(v))
                : String.format(Locale.US, "%." + ss.getDecimals() + "f", v);
    }

    // -- HSV <-> ARGB ------------------------------------------------------------

    void argbToHsv(int argb) {
        overlayController.argbToHsv(argb);
    }

    private void updateColorFromHsv() {
        overlayController.updateColorFromHsv();
    }

    // -- Mouse input -----------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return mouseController.mouseClicked(click, doubled);
    }

    private void handleRightClick(float mx, float my) {
        if (mx < contX || mx > contX + contW || my < contY || my > contY + contH) return;
        // Flat content tabs don't use right-click group toggling
        if (activeMain == 1 || activeMain >= 3) return;

        float gx = contX + ITEM_PAD;
        float gw = contW - ITEM_PAD * 2f;
        float y2 = 0f;

        for (SettingGroup group : groups()) {
            y2 += GROUP_GAP;
            float headerSY = contY - scrollY + y2;
            if (my >= headerSY && my <= headerSY + HEADER_H && mx >= gx && mx <= gx + gw) {
                if (group.isAlwaysOn()) return;
                if (forcedOverride.contains(group)) forcedOverride.remove(group);
                else forcedOverride.add(group);
                return;
            }
            y2 += HEADER_H;
            if (showChildren(group) && group.hasSettings()) {
                y2 += HEADER_TO_FIRST_SETTING_GAP;
                if (isPetTrackerGroup(group)) y2 += petTrackerGroupHeight();
                else for (Setting s : group.getSettings()) {
                    if (s.isVisible()) y2 += settingH(s, gw);
                }
                y2 += 4f;
            }
        }
    }

    private boolean handleColorPickerClick(float mx, float my) {
        return overlayController.handleColorPickerClick(mx, my);
    }

    private void handleColorPickerDrag(float mx, float my) {
        overlayController.handleColorPickerDrag(mx, my);
    }

    private void handleDropdownClick(float mx, float my) {
        overlayController.handleDropdownClick(mx, my);
    }

    private void handleContentClick(float mx, float my) {
        settingInteractionController.handleContentClick(mx, my);
    }

    /** Click handler for the flat Colors / Settings renderer. */
    private void handleFlatContentClick(float mx, float my) {
        settingInteractionController.handleFlatContentClick(mx, my);
    }

    /** Click handler for module detail view (right settings panel). */
    private void handleModuleSettingsPanelClick(float mx, float my) {
        settingInteractionController.handleModuleSettingsPanelClick(mx, my);
    }

    private void focusListItem(ListSetting list, int index) {
        List<String> values = list.getValues();
        if (index < 0 || index >= values.size()) return;
        activeList = list;
        activeListIndex = index;
        activeText = null;
        textBuf = new StringBuilder(values.get(index));
        textCursor = textBuf.length();
        clearTextSelection();
    }

    private void addListItem(ListSetting list, int insertIndex) {
        List<String> values = list.getValues();
        int target = Math.max(0, Math.min(insertIndex, values.size()));
        values.add(target, "");
        list.setValues(values);
        focusListItem(list, target);
    }

    private void removeListItem(ListSetting list, int index) {
        List<String> values = list.getValues();
        if (index < 0 || index >= values.size()) return;
        values.remove(index);
        list.setValues(values);
        if (activeList == list) {
            activeList = null;
            activeListIndex = -1;
        }
    }

    private void focusSliderField(SliderSetting slider) {
        activeSliderField = slider;
        activeText = null;
        activeList = null;
        activeListIndex = -1;
        activePosField = null;
        textBuf = new StringBuilder(fmtSliderInput(slider));
        textCursor = textBuf.length();
        clearTextSelection();
    }

    void handleSettingClick(Setting setting, float mx, float my,
                                    float x, float y, float w, float h) {
        settingInteractionController.handleSettingClick(setting, mx, my, x, y, w, h);
    }

    private void updateSlider(float mx) {
        settingInteractionController.updateSlider(mx);
    }

    private void updateRangeSlider(float mx) {
        settingInteractionController.updateRangeSlider(mx);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dX, double dY) {
        return mouseController.mouseDragged(click, dX, dY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return mouseController.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        return mouseController.mouseScrolled(mx, my, sx, sy);
    }

    // -- Keyboard input --------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (keyboardController.keyPressed(input)) {
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        return keyboardController.charTyped(input);
    }

    // -- Commit helpers --------------------------------------------------------

    void commitText() {
        inlineTextEditor.commitText();
    }

    void commitColor() {
        overlayController.commitColor();
    }

    // -- Search content --------------------------------------------------------

    private void renderSearchContent(NVGRenderer nvg, float mx, float my) {
        searchPanel.render(nvg, mx, my);
    }

    private void handleSearchClick(float mx, float my) {
        searchPanel.handleClick(mx, my);
    }

    void handleSearchClickEvent(float mx, float my) {
        handleSearchClick(mx, my);
    }

    // -- Profile content -------------------------------------------------------

    private void renderProfileContent(NVGRenderer nvg, float mx, float my) {
        profilesPanel.render(nvg, mx, my);
    }

    void refreshContext() {
        activeFilter = normalizedActiveFilterIndex();
        context.layout.px = px;
        context.layout.py = py;
        context.layout.pw = pw;
        context.layout.ph = ph;
        context.layout.contX = contX;
        context.layout.contY = contY;
        context.layout.contW = contW;
        context.layout.contH = contH;
        context.layout.pr = pr;
        context.layout.physW = physW;
        context.layout.physH = physH;
        context.layout.btnSearchX = btnSearchX;
        context.layout.btnSearchY = btnSearchY;
        context.layout.btnSearchW = btnSearchW;
        context.layout.btnSearchH = btnSearchH;
        context.layout.searchBoxX = searchBoxX;
        context.layout.searchBoxY = searchBoxY;
        context.layout.searchBoxW = searchBoxW;
        context.layout.searchBoxH = searchBoxH;
        context.layout.activeEditorX = activeEditorX;
        context.layout.activeEditorY = activeEditorY;
        context.layout.activeEditorW = activeEditorW;
        context.layout.activeEditorH = activeEditorH;

        context.navigation.activeMain = activeMain;
        context.navigation.activeSubtab = activeSubtab;
        context.navigation.activeFilter = activeFilter;
        context.navigation.activeSubTab = activeSubTab;
        context.navigation.activeCategoryIdx = activeCategoryIdx;
        context.navigation.searchMode = searchMode;
        context.navigation.searchQuery = searchQuery;
        context.navigation.profileNameInput = profileNameInput;
        context.navigation.profileNameFocus = profileNameFocus;
        context.navigation.profileNameTarget = profileNameTarget;
        context.navigation.profileRenameInput = profileRenameInput;
        context.navigation.profileRenameOriginal = profileRenameOriginal;
        context.navigation.profileRenameFocus = profileRenameFocus;
        context.navigation.profileRenameTarget = profileRenameTarget;
        context.navigation.suppressNestedContentScissor = suppressNestedContentScissor;

        context.scroll.scrollY = scrollY;
        context.scroll.targetScrollY = targetScrollY;
        context.scroll.maxScrollY = maxScrollY;
        context.scroll.modulesOverviewScrollY = modulesOverviewScrollY;
        context.scroll.modulesOverviewTargetScrollY = modulesOverviewTargetScrollY;
        context.scroll.moduleDetailScrollY = moduleDetailScrollY;
        context.scroll.moduleDetailTargetScrollY = moduleDetailTargetScrollY;
        context.scroll.searchScrollY = searchScrollY;
        context.scroll.searchTargetScrollY = searchTargetScrollY;
        context.scroll.searchMaxScrollY = searchMaxScrollY;
        context.scroll.profileScrollY = profileScrollY;
        context.scroll.profileTargetScrollY = profileTargetScrollY;
        context.scroll.profileMaxScrollY = profileMaxScrollY;
        context.scroll.subtabScrollY = subtabScrollY;
        context.scroll.subtabTargetScrollY = subtabTargetScrollY;
        context.scroll.subtabMaxScrollY = subtabMaxScrollY;
        context.scroll.sbMainTrackT = sbMainTrackT;
        context.scroll.sbMainTrackH = sbMainTrackH;
        context.scroll.sbMainThumbH = sbMainThumbH;
        context.scroll.sbSrchTrackT = sbSrchTrackT;
        context.scroll.sbSrchTrackH = sbSrchTrackH;
        context.scroll.sbSrchThumbH = sbSrchThumbH;
        context.scroll.sbProfTrackT = sbProfTrackT;
        context.scroll.sbProfTrackH = sbProfTrackH;
        context.scroll.sbProfThumbH = sbProfThumbH;
        context.scroll.sbSubTrackT = sbSubTrackT;
        context.scroll.sbSubTrackH = sbSubTrackH;
        context.scroll.sbSubThumbH = sbSubThumbH;
        context.scroll.lastFrameTimeNanos = lastFrameTimeNanos;

        context.animation.alpha = alpha;
        context.animation.animScale = animScale;
        context.animation.animOffsetY = animOffsetY;
        context.animation.sidebarAnim = sidebarAnim;
        context.animation.computedSidebarExpanded = computedSidebarExpanded;
        context.animation.animMainSel = animMainSel;
        context.animation.animSubSel = animSubSel;
        context.animation.filterBarAnimX = filterBarAnimX;
        context.animation.filterBarAnimW = filterBarAnimW;
        context.animation.filterBarTargetX = filterBarTargetX;
        context.animation.filterBarTargetW = filterBarTargetW;
        context.animation.filterBarInited = filterBarInited;
        context.animation.ddAnimAmt = ddAnimAmt;
        context.animation.catBarAnimY = catBarAnimY;
        context.animation.catBarFromY = catBarFromY;
        context.animation.catBarTargetY = catBarTargetY;
        context.animation.catBarAnimT = catBarAnimT;
        context.animation.catBarInited = catBarInited;
        context.animation.catBarStartNanos = catBarStartNanos;

        context.editor.activeSliderField = activeSliderField;
        context.editor.activeText = activeText;
        context.editor.activeList = activeList;
        context.editor.activeListIndex = activeListIndex;
        context.editor.activePosField = activePosField;
        context.editor.activePosIdx = activePosIdx;
        context.editor.textBuffer = textBuf.toString();
        context.editor.textCursor = textCursor;
        context.editor.textSelectionAnchor = textSelectionAnchor;
        context.editor.textSelectionDragging = textSelectionDragging;
        context.editor.textSelectionPendingDrag = textSelectionPendingDrag;
        context.editor.textSelectionPressCursor = textSelectionPressCursor;
        context.editor.textSelectionPressX = textSelectionPressX;
        context.editor.textSelectionPressY = textSelectionPressY;
        context.editor.activeEditorBoundsSet = activeEditorBoundsSet;
        context.editor.cpHexFocus = cpHexFocus;
        context.editor.cpHexBuffer = cpHexBuf.toString();

        context.overlay.openDropdown = openDd;
        context.overlay.dropdownX = ddX;
        context.overlay.dropdownY = ddY;
        context.overlay.dropdownW = ddW;
        context.overlay.dropdownButtonH = ddButtonH;
        context.overlay.hoveredColor = hoveredColor;
        context.overlay.activeColor = activeColor;
        context.overlay.cpHue = cpHue;
        context.overlay.cpSat = cpSat;
        context.overlay.cpVal = cpVal;
        context.overlay.cpAlpha = cpAlpha;
        context.overlay.cpDrag = cpDrag;
        context.overlay.cpPX = cpPX;
        context.overlay.cpPY = cpPY;
        context.overlay.cpPW = cpPW;
        context.overlay.cpPH = cpPH;
        context.overlay.cpSvX = cpSvX;
        context.overlay.cpSvY = cpSvY;
        context.overlay.cpSvW = cpSvW;
        context.overlay.cpSvH = cpSvH;
        context.overlay.cpHBarY = cpHBarY;
        context.overlay.cpABarY = cpABarY;
        context.overlay.cpBarH = cpBarH;
        context.overlay.cpHexX = cpHexX;
        context.overlay.cpHexY = cpHexY;
        context.overlay.cpHexW = cpHexW;
        context.overlay.cpHexH = cpHexH;

        context.interaction.scrollbarDragging = sbDragging;
        context.interaction.scrollbarDragThumbOffset = sbDragThumbOffset;
        context.interaction.dragSetting = dragSetting;
        context.interaction.dragRangeSetting = dragRangeSetting;
        context.interaction.dragTrackX = dragTrackX;
        context.interaction.dragTrackW = dragTrackW;
        context.interaction.dragMin = dragMin;
        context.interaction.dragMax = dragMax;
        context.interaction.dragRangeHandle = dragRangeHandle;
        context.interaction.panelDragging = panelDragging;
        context.interaction.dragPanelOffX = dragPanelOffX;
        context.interaction.dragPanelOffY = dragPanelOffY;
        context.interaction.forcedOverride = forcedOverride;
    }

    MainGUIContext context() {
        return actions.context();
    }

    int getActiveMainTab() {
        return activeMain;
    }

    ModulesTab.SubTab getActiveModuleSubTab() {
        return activeSubTab;
    }

    float getActiveScrollY() {
        return scrollY;
    }

    float getActiveTargetScrollY() {
        return targetScrollY;
    }

    float getModulesOverviewScrollY() {
        return modulesOverviewScrollY;
    }

    float getModulesOverviewTargetScrollY() {
        return modulesOverviewTargetScrollY;
    }

    float getModuleDetailScrollY() {
        return moduleDetailScrollY;
    }

    float getModuleDetailTargetScrollY() {
        return moduleDetailTargetScrollY;
    }

    boolean isShowingSearchResults() {
        return showSearchResults();
    }

    void setActiveScroll(float scrollY, float targetScrollY) {
        this.scrollY = scrollY;
        this.targetScrollY = targetScrollY;
    }

    void setModulesOverviewScroll(float scrollY, float targetScrollY) {
        modulesOverviewScrollY = scrollY;
        modulesOverviewTargetScrollY = targetScrollY;
    }

    void setModuleDetailScroll(float scrollY, float targetScrollY) {
        moduleDetailScrollY = scrollY;
        moduleDetailTargetScrollY = targetScrollY;
    }

    void setActiveModuleSubTab(ModulesTab.SubTab subTab) {
        activeSubTab = subTab;
    }

    void setActiveCategoryIndex(int categoryIndex) {
        activeCategoryIdx = categoryIndex;
    }

    void setActiveMainTab(int mainTab) {
        activeMain = mainTab;
    }

    void setActiveSubtabIndex(int subtabIndex) {
        activeSubtab = subtabIndex;
    }

    void setProfileScroll(float scrollY, float targetScrollY) {
        profileScrollY = scrollY;
        profileTargetScrollY = targetScrollY;
    }

    void setSearchScroll(float scrollY, float targetScrollY) {
        searchScrollY = scrollY;
        searchTargetScrollY = targetScrollY;
    }

    void setSearchMode(boolean searchMode) {
        this.searchMode = searchMode;
    }

    void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery;
    }

    void setActiveFilterIndex(int filterIndex) {
        activeFilter = filterIndex;
    }

    void setFilterBarInitialized(boolean initialized) {
        filterBarInited = initialized;
    }

    void clearInlineTextSelection() {
        clearTextSelection();
    }

    void setComputedSidebarExpanded(float width) {
        computedSidebarExpanded = width;
    }

    String currentTabName() {
        int idx = Math.max(0, Math.min(activeMain, TAB_NAMES.length - 1));
        return AetherLang.localize(TAB_NAMES[idx]);
    }

    String currentTabDescription() {
        int idx = Math.max(0, Math.min(activeMain, TAB_DESCS.length - 1));
        return AetherLang.localize(TAB_DESCS[idx]);
    }

    String[] currentTabFilters() {
        return filtersForMainTab(activeMain);
    }

    private int normalizedActiveFilterIndex() {
        String[] filters = filtersForMainTab(activeMain);
        if (filters.length == 0) {
            return 0;
        }
        return Math.max(0, Math.min(activeFilter, filters.length - 1));
    }

    private String[] filtersForMainTab(int mainTab) {
        return switch (mainTab) {
            case 0 -> moduleFilterOptions();
            case 1 -> localizedStrings(COLOR_FILTERS);
            case 2 -> localizedStrings(CONFIG_FILTERS);
            case 3, 4 -> localizedStrings(SINGLE_FILTER);
            default -> localizedStrings(SINGLE_FILTER);
        };
    }

    private String[] localizedStrings(String[] values) {
        String[] localized = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            localized[i] = AetherLang.localize(values[i]);
        }
        return localized;
    }

    private String[] moduleFilterOptions() {
        List<Section> sections = availableModuleSections();
        if (sections.isEmpty()) {
            return SINGLE_FILTER;
        }
        if (sections.size() == 1) {
            return new String[] { AetherLang.localize(sections.getFirst().name()) };
        }

        String[] options = new String[sections.size() + 1];
        options[0] = AetherLang.localize("All");
        for (int i = 0; i < sections.size(); i++) {
            options[i + 1] = AetherLang.localize(sections.get(i).name());
        }
        return options;
    }

    void setSearchBoxBounds(float x, float y, float w, float h) {
        searchBoxX = x;
        searchBoxY = y;
        searchBoxW = w;
        searchBoxH = h;
        refreshContext();
    }

    private void applyLaunchTargetIfNeeded() {
        if (launchTargetApplied || launchTarget == null) {
            return;
        }

        launchTargetApplied = true;
        activeMain = Math.max(0, launchTarget.mainTab());
        activeFilter = 0;
        activeSubtab = 0;

        if (!launchTarget.openModuleDetail() || launchTarget.moduleName() == null || activeMain != 0) {
            return;
        }

        ModulesTab.SubTab targetSubTab = findModuleSubTabByName(launchTarget.moduleName());
        if (targetSubTab == null) {
            return;
        }

        activeSubTab = targetSubTab;
        activeCategoryIdx = 0;
        scrollY = 0f;
        targetScrollY = 0f;
        modulesOverviewScrollY = 0f;
        modulesOverviewTargetScrollY = 0f;
        moduleDetailScrollY = 0f;
        moduleDetailTargetScrollY = 0f;
    }

    private ModulesTab.SubTab findModuleSubTabByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (ModulesTab.SubTab subTab : MainGUIRegistry.MODULE_SUBTABS) {
            if (subTab.name().equalsIgnoreCase(name) || AetherLang.localize(subTab.name()).equalsIgnoreCase(name)) {
                return subTab;
            }
        }
        return null;
    }

    boolean isSearchEditorActiveState() {
        return isSearchEditorActive();
    }

    String currentTextBuffer() {
        return textBuf.toString();
    }

    int currentTextCursor() {
        return textCursor;
    }

    boolean textHasSelection() {
        return hasTextSelection();
    }

    int currentTextSelectionStart() {
        return textSelectionStart();
    }

    int currentTextSelectionEnd() {
        return textSelectionEnd();
    }

    String safeTextSlice(String value, int start, int end) {
        return safeSubstring(value, start, end);
    }

    float inlineTextScrollOffsetFor(NVGRenderer nvg, String font, String text, int cursor, float fontSize, float visibleWidth) {
        return inlineTextScrollOffset(nvg, font, text, cursor, fontSize, visibleWidth);
    }

    void activateSearchField() {
        if (!searchMode) {
            commitText();
            commitColor();
            openDd = null;
            focusSearchField();
        }
    }

    void setFilterBarTarget(float x, float w) {
        filterBarTargetX = x;
        filterBarTargetW = w;
        refreshContext();
    }

    void setFilterBarAnimation(float x, float w) {
        filterBarAnimX = x;
        filterBarAnimW = w;
        refreshContext();
    }

    void onFilterSelected(int filterIndex) {
        activeFilter = Math.max(0, Math.min(filterIndex, currentTabFilters().length - 1));
        resetActiveContentScroll();
    }

    boolean searchResultsVisible() {
        return showSearchResults();
    }

    boolean hasOpenDropdown() {
        return openDd != null;
    }

    boolean hasActiveColorOverlay() {
        return activeColor != null;
    }

    void renderSearchContentPanel(NVGRenderer nvg, float mx, float my) {
        renderSearchContent(nvg, mx, my);
    }

    void renderProfileContentPanel(NVGRenderer nvg, float mx, float my) {
        renderProfileContent(nvg, mx, my);
    }

    void renderContentPanel(NVGRenderer nvg, float mx, float my) {
        renderContent(nvg, mx, my);
    }

    void renderContentTopBarPanel(NVGRenderer nvg, float mx, float my) {
        renderContentTopBar(nvg, mx, my);
    }

    void renderFilterBarPanel(NVGRenderer nvg, float mx, float my) {
        renderFilterBar(nvg, mx, my);
    }

    void renderModuleGridPanel(NVGRenderer nvg, float mx, float my, float scrollTop, float scrollH) {
        renderModuleGrid(nvg, mx, my, scrollTop, scrollH);
    }

    void renderFlatContentPanel(NVGRenderer nvg, float mx, float my) {
        renderFlatContent(nvg, mx, my);
    }

    void renderDropdownOverlayPanel(NVGRenderer nvg, float mx, float my) {
        renderDropdownOverlay(nvg, mx, my);
    }

    void renderColorOverlayPanel(NVGRenderer nvg) {
        renderColorOverlay(nvg);
    }

    void renderModuleTopBarPanel(NVGRenderer nvg, float mx, float my) {
        renderModuleTopBar(nvg, mx, my);
    }

    void renderModuleDetailBodyPanel(NVGRenderer nvg, float mx, float my) {
        renderModuleDetailBody(nvg, mx, my);
    }

    void renderModuleCategoryPanelSection(NVGRenderer nvg, float mx, float my, float panelTop, float panelH) {
        renderModuleCategoryPanel(nvg, mx, my, panelTop, panelH);
    }

    void renderModuleSettingsPanelSection(NVGRenderer nvg, float mx, float my, float panelTop, float panelH) {
        renderModuleSettingsPanel(nvg, mx, my, panelTop, panelH);
    }

    List<MainGUIModuleSection> moduleSectionsForFilter() {
        List<MainGUIModuleSection> result = new ArrayList<>();
        for (Section section : sectionsForFilter()) {
            result.add(new MainGUIModuleSection(AetherLang.localize(section.name()), section.subtabs()));
        }
        return result;
    }

    float moduleCardHoverProgress(ModulesTab.SubTab subTab) {
        return cardHoverAnim.getOrDefault(subTab, 0f);
    }

    void setModuleCardHoverProgress(ModulesTab.SubTab subTab, float progress) {
        cardHoverAnim.put(subTab, progress);
    }

    void renderModuleCardPanel(NVGRenderer nvg, ModulesTab.SubTab subTab, float x, float y, float w, float hover, float mx, float my) {
        renderModuleCard(nvg, subTab, x, y, w, hover, mx, my);
    }

    List<ModulesTab.SubTab> flatSubtabsForCurrentFilter() {
        return flatSubtabsForFilter();
    }

    void renderFlatGroupLabelPanel(NVGRenderer nvg, SettingGroup group, float x, float y, float w, float mx, float my) {
        renderFlatGroupLabel(nvg, group, x, y, w, mx, my);
    }

    boolean shouldShowChildren(SettingGroup group) {
        return showChildren(group);
    }

    boolean isPetTrackerSettingsGroup(SettingGroup group) {
        return isPetTrackerGroup(group);
    }

    float petTrackerGroupHeightValue() {
        return petTrackerGroupHeight();
    }

    void renderPetTrackerGroupPanel(NVGRenderer nvg, SettingGroup group, float x, float y, float w, float mx, float my) {
        renderPetTrackerGroup(nvg, group, x, y, w, mx, my);
    }

    float settingHeightFor(Setting setting, float width) {
        return settingH(setting, width);
    }

    void renderMainScrollbar(NVGRenderer nvg, float totalContentHeight, float scrollTop, float scrollH, float barX) {
        maxScrollY = Math.max(0f, totalContentHeight - scrollH + 16f);
        targetScrollY = Math.max(0f, Math.min(maxScrollY, targetScrollY));
        if (maxScrollY <= 0f) {
            return;
        }
        float ratio = scrollH / (totalContentHeight + 16f);
        float thumbH = Math.max(30f, scrollH * ratio);
        float trackT = scrollTop + 2f;
        float trackH = scrollH - 4f;
        sbMainTrackT = trackT;
        sbMainTrackH = trackH;
        sbMainThumbH = thumbH;
        float thumbY = scrollbarThumbY(trackT, trackH, thumbH, scrollY, maxScrollY);
        nvg.roundedRect(barX, thumbY, 4f, thumbH, 2f, Theme.withAlpha(Theme.TEXT_SECONDARY, 0.5f));
    }

    String moduleCardNameFor(ModulesTab.SubTab subTab) {
        return moduleCardName(subTab);
    }

    boolean isSubTabEnabledFor(ModulesTab.SubTab subTab) {
        return isSubTabEnabled(subTab);
    }

    void exitModuleDetailView() {
        exitModuleDetail();
    }

    int getActiveCategoryIndex() {
        return activeCategoryIdx;
    }

    void syncModuleCategoryBarAnimation(float targetY) {
        syncCategoryBarAnimation(targetY);
    }

    Object moduleCategoryAnimationKey(SettingGroup group, boolean isAll) {
        return isAll ? this : group;
    }

    float moduleCategoryHoverProgress(Object key) {
        return catHoverAnim.getOrDefault(key, 0f);
    }

    void setModuleCategoryHoverProgress(Object key, float progress) {
        catHoverAnim.put(key, progress);
    }

    void selectModuleCategory(int categoryIndex) {
        activeCategoryIdx = categoryIndex;
        scrollY = 0f;
        targetScrollY = 0f;
    }

    void renderGroupHeaderPanel(NVGRenderer nvg, SettingGroup group, float x, float y, float w, float mx, float my, boolean interactive) {
        renderGroupHeader(nvg, group, x, y, w, mx, my, interactive);
    }

    void renderPillControl(NVGRenderer nvg, float x, float y, boolean on, Object key) {
        renderPill(nvg, x, y, on, key);
    }

    List<String> wrapSettingLabelForRow(NVGRenderer nvg, Setting setting, float rowW) {
        return wrapSettingLabel(nvg, setting, rowW);
    }

    float settingLabelFontSizeForRow(Setting setting) {
        return settingLabelFontSize(setting);
    }

    float settingLabelLineStepForRow(Setting setting) {
        return settingLabelLineStep(setting);
    }

    int wrappedSettingLabelLineCountForRow(Setting setting, float rowW) {
        return wrappedSettingLabelLineCount(setting, rowW);
    }

    boolean isActiveSliderEditor(SliderSetting setting) {
        return activeSliderField == setting;
    }

    boolean isActiveTextEditor(TextSetting setting) {
        return activeText == setting;
    }

    boolean isActiveListEditor(ListSetting setting, int index) {
        return activeList == setting && activeListIndex == index;
    }

    boolean isOpenDropdown(DropdownSetting setting) {
        return openDd == setting;
    }

    boolean isActiveColorSetting(ColorSetting setting) {
        return activeColor == setting;
    }

    boolean isActivePositionEditor(PositionSetting setting, int index) {
        return activePosField == setting && activePosIdx == index;
    }

    void setHoveredColorSetting(ColorSetting setting) {
        hoveredColor = setting;
    }

    void setDropdownOverlayBounds(float x, float y, float w, float buttonH) {
        ddX = x;
        ddY = y;
        ddW = w;
        ddButtonH = buttonH;
    }

    void setEditorBounds(float x, float y, float w, float h) {
        setActiveEditorBounds(x, y, w, h);
    }

    String formatSliderValue(SliderSetting setting) {
        return fmtSlider(setting);
    }

    String formatRangeSliderValue(RangeSliderSetting setting) {
        return fmtRangeSlider(setting);
    }

    void renderDropdownActionButtonsControl(NVGRenderer nvg, DropdownSetting dropdown, float fieldX, float fieldY, float fieldH, float mx, float my) {
        renderDropdownActionButtons(nvg, dropdown, fieldX, fieldY, fieldH, mx, my);
    }

    void renderListActionButtonControl(NVGRenderer nvg, float x, float y, float w, String label, boolean hovered, boolean enabled) {
        renderListActionButton(nvg, x, y, w, label, hovered, enabled);
    }

    float textInlineFieldWidthForRow(float innerWidth) {
        return textInlineFieldWidth(innerWidth);
    }

    float inlineTextScrollOffsetApproxFor(String text, int cursor, float fontSize, float visibleWidth) {
        return inlineTextScrollOffsetApprox(text, cursor, fontSize, visibleWidth);
    }

    int clickAreaCount() {
        return clickAreas.size();
    }

    private void syncFrameLayoutForWindowSize(float prevPhysW, float prevPhysH) {
        boolean sizeChanged = context.layout.lastWidth != width
                || context.layout.lastHeight != height
                || Math.abs(context.layout.lastPixelRatio - pr) > 0.001f
                || Math.abs(context.layout.lastUiScale - uiScale) > 0.001f;
        if (!sizeChanged) {
            physW = width * pr;
            physH = height * pr;
            return;
        }
        applyFrameLayout(false, prevPhysW, prevPhysH);
    }

    private void applyFrameLayout(boolean forceCenter, float prevPhysW, float prevPhysH) {
        MainGUIContext.LayoutState frameLayout = layoutHelper.computeFrameLayout(width, height, pr, uiScale, SIDEBAR_W);
        float oldPx = px;
        float oldPy = py;
        float oldPw = pw;
        float oldPh = ph;

        pw = frameLayout.pw;
        ph = frameLayout.ph;
        physW = frameLayout.physW;
        physH = frameLayout.physH;

        if (forceCenter || oldPw <= 0f || oldPh <= 0f || prevPhysW <= 0f || prevPhysH <= 0f) {
            px = frameLayout.px;
            py = frameLayout.py;
        } else {
            float oldCanvasW = prevPhysW / uiScale;
            float oldCanvasH = prevPhysH / uiScale;
            float centeredX = (oldCanvasW - oldPw) / 2f;
            float centeredY = (oldCanvasH - oldPh) / 2f;
            boolean wasCentered = Math.abs(oldPx - centeredX) <= 0.5f && Math.abs(oldPy - centeredY) <= 0.5f;
            if (wasCentered) {
                px = frameLayout.px;
                py = frameLayout.py;
            } else {
                float canvasW = physW / uiScale;
                float canvasH = physH / uiScale;
                px = Math.max(0f, Math.min(canvasW - pw, oldPx));
                py = Math.max(0f, Math.min(canvasH - ph, oldPy));
            }
        }

        contX = px + SIDEBAR_W;
        contY = py;
        contW = pw - SIDEBAR_W;
        contH = ph;
        context.layout.lastWidth = width;
        context.layout.lastHeight = height;
        context.layout.lastPixelRatio = pr;
        context.layout.lastUiScale = uiScale;
        refreshContext();
    }

    void trimClickAreas(int clickCount) {
        while (clickAreas.size() > clickCount) {
            clickAreas.remove(clickAreas.size() - 1);
        }
    }

    void clearTransitionOverlaysForPassiveRender() {
        openDd = null;
        activeColor = null;
    }

    void setSuppressNestedContentScissorState(boolean suppress) {
        suppressNestedContentScissor = suppress;
    }

    MainGUITransientRenderState captureTransientRenderState() {
        MainGUITransientRenderState state = new MainGUITransientRenderState();
        state.hoveredColor = hoveredColor;
        state.activeEditorBoundsSet = activeEditorBoundsSet;
        state.filterBarAnimX = filterBarAnimX;
        state.filterBarAnimW = filterBarAnimW;
        state.filterBarTargetX = filterBarTargetX;
        state.filterBarTargetW = filterBarTargetW;
        state.filterBarInited = filterBarInited;
        state.maxScrollY = maxScrollY;
        state.searchMaxScrollY = searchMaxScrollY;
        state.profileMaxScrollY = profileMaxScrollY;
        state.subtabMaxScrollY = subtabMaxScrollY;
        state.sbMainTrackT = sbMainTrackT;
        state.sbMainTrackH = sbMainTrackH;
        state.sbMainThumbH = sbMainThumbH;
        state.sbSrchTrackT = sbSrchTrackT;
        state.sbSrchTrackH = sbSrchTrackH;
        state.sbSrchThumbH = sbSrchThumbH;
        state.sbProfTrackT = sbProfTrackT;
        state.sbProfTrackH = sbProfTrackH;
        state.sbProfThumbH = sbProfThumbH;
        state.sbSubTrackT = sbSubTrackT;
        state.sbSubTrackH = sbSubTrackH;
        state.sbSubThumbH = sbSubThumbH;
        state.openDropdown = openDd;
        state.activeColor = activeColor;
        state.suppressNestedContentScissor = suppressNestedContentScissor;
        state.scrollY = scrollY;
        state.targetScrollY = targetScrollY;
        return state;
    }

    void restoreTransitionRenderState(MainGUITransientRenderState state, boolean interactive, boolean restoreScroll) {
        if (!interactive) {
            hoveredColor = state.hoveredColor;
            activeEditorBoundsSet = state.activeEditorBoundsSet;
            filterBarAnimX = state.filterBarAnimX;
            filterBarAnimW = state.filterBarAnimW;
            filterBarTargetX = state.filterBarTargetX;
            filterBarTargetW = state.filterBarTargetW;
            filterBarInited = state.filterBarInited;
            maxScrollY = state.maxScrollY;
            searchMaxScrollY = state.searchMaxScrollY;
            profileMaxScrollY = state.profileMaxScrollY;
            subtabMaxScrollY = state.subtabMaxScrollY;
            sbMainTrackT = state.sbMainTrackT;
            sbMainTrackH = state.sbMainTrackH;
            sbMainThumbH = state.sbMainThumbH;
            sbSrchTrackT = state.sbSrchTrackT;
            sbSrchTrackH = state.sbSrchTrackH;
            sbSrchThumbH = state.sbSrchThumbH;
            sbProfTrackT = state.sbProfTrackT;
            sbProfTrackH = state.sbProfTrackH;
            sbProfThumbH = state.sbProfThumbH;
            sbSubTrackT = state.sbSubTrackT;
            sbSubTrackH = state.sbSubTrackH;
            sbSubThumbH = state.sbSubThumbH;
        }
        openDd = state.openDropdown;
        activeColor = state.activeColor;
        suppressNestedContentScissor = state.suppressNestedContentScissor;
        if (restoreScroll) {
            scrollY = state.scrollY;
            targetScrollY = state.targetScrollY;
        }
    }

    static float groupGap() {
        return GROUP_GAP;
    }

    float contentX() {
        return contX;
    }

    float contentY() {
        return contY;
    }

    float contentW() {
        return contW;
    }

    float contentH() {
        return contH;
    }

    List<SettingGroup> activeGroupsForInteraction() {
        return groups();
    }

    List<SettingGroup> activeModuleClickGroups() {
        if (activeSubTab == null) {
            return List.of();
        }
        int maxCatIdx = activeSubTab.groups().size();
        if (activeCategoryIdx > maxCatIdx) {
            activeCategoryIdx = 0;
        }
        return activeCategoryIdx == 0
                ? activeSubTab.groups()
                : List.of(activeSubTab.groups().get(activeCategoryIdx - 1));
    }

    boolean isActiveModuleSubtabEnabled() {
        return activeSubTab != null && isSubTabEnabled(activeSubTab);
    }

    boolean handlePetTrackerGroupInteraction(SettingGroup group, float mx, float my, float x, float y, float w) {
        return handlePetTrackerGroupClick(group, mx, my, x, y, w);
    }

    void focusListEditorItem(ListSetting list, int index) {
        focusListItem(list, index);
    }

    void insertListEditorItem(ListSetting list, int insertIndex) {
        addListItem(list, insertIndex);
    }

    void deleteListEditorItem(ListSetting list, int index) {
        removeListItem(list, index);
    }

    void focusSliderEditorField(SliderSetting slider) {
        focusSliderField(slider);
    }

    void activateTextSettingEditor(TextSetting setting) {
        activeText = setting;
        textBuf = new StringBuilder(setting.getValue());
        textCursor = textBuf.length();
        clearTextSelection();
    }

    void focusPositionEditorField(PositionSetting setting, int index, double value, float mx, float my) {
        activePosField = setting;
        activePosIdx = index;
        textBuf = new StringBuilder(String.format("%.1f", value));
        textCursor = textBuf.length();
        clearTextSelection();
        beginInlineCaretPress(mx, my);
    }

    void beginInlineFieldCaretPress(float x, float y, float w, float h, String value,
                                    float fontSize, float startX, float mx, float my) {
        setActiveEditorBounds(x, y, w, h);
        textCursor = MainGUIInlineTextEditor.cursorFromInlineText(value, fontSize, startX, mx);
        clearTextSelection();
        beginInlineCaretPress(mx, my);
    }

    int dropdownActionIndexFor(DropdownSetting dropdown, float fieldX, float fieldY, float fieldH,
                               float mx, float my) {
        return dropdownActionIndexAt(dropdown, fieldX, fieldY, fieldH, mx, my);
    }

    void openDropdownOverlayFor(DropdownSetting dropdown, float x, float y, float w, float buttonH) {
        openDd = dropdown;
        ddAnimAmt = 0f;
        ddX = x;
        ddY = y;
        ddW = w;
        ddButtonH = buttonH;
    }

    void activateColorSetting(ColorSetting setting) {
        activeColor = setting;
        argbToHsv(setting.getValue());
        cpHexFocus = false;
        cpHexBuf.setLength(0);
        cpDrag = 0;
    }

    void startSliderDrag(SliderSetting setting, float trackX, float trackW) {
        activeSliderField = null;
        dragSetting = setting;
        dragTrackX = trackX;
        dragTrackW = trackW;
        dragMin = setting.getMin();
        dragMax = setting.getMax();
    }

    void startRangeSliderDrag(RangeSliderSetting setting, float trackX, float trackW, float mx) {
        activeSliderField = null;
        dragSetting = null;
        dragRangeSetting = setting;
        dragTrackX = trackX;
        dragTrackW = trackW;
        dragMin = setting.getMin();
        dragMax = setting.getMax();

        float lowerT = (setting.getLowerValue() - setting.getMin()) / (setting.getMax() - setting.getMin());
        float upperT = (setting.getUpperValue() - setting.getMin()) / (setting.getMax() - setting.getMin());
        float lowerX = trackX + trackW * lowerT;
        float upperX = trackX + trackW * upperT;
        dragRangeHandle = Math.abs(mx - lowerX) <= Math.abs(mx - upperX) ? 1 : 2;
    }

    boolean hasDraggedSliderSetting() {
        return dragSetting != null;
    }

    boolean hasDraggedRangeSliderSetting() {
        return dragRangeSetting != null;
    }

    void applySliderDragValue(float mx) {
        if (dragSetting == null) {
            return;
        }
        float t = Math.max(0f, Math.min(1f, (mx - dragTrackX) / dragTrackW));
        dragSetting.setValue(dragMin + t * (dragMax - dragMin));
    }

    void applyRangeSliderDragValue(float mx) {
        if (dragRangeSetting == null || dragRangeHandle == 0) {
            return;
        }
        float t = Math.max(0f, Math.min(1f, (mx - dragTrackX) / dragTrackW));
        float value = dragMin + t * (dragMax - dragMin);
        if (dragRangeHandle == 1) {
            dragRangeSetting.setLowerValue(value);
        } else {
            dragRangeSetting.setUpperValue(value);
        }
    }

    float scaleMouseX(double rawX) {
        return (float) rawX * pr / uiScale;
    }

    float scaleMouseY(double rawY) {
        return (float) rawY * pr / uiScale;
    }

    void handleRightClickEvent(float mx, float my) {
        handleRightClick(mx, my);
    }

    boolean handleColorPickerClickEvent(float mx, float my) {
        return handleColorPickerClick(mx, my);
    }

    void handleDropdownClickEvent(float mx, float my) {
        handleDropdownClick(mx, my);
    }

    boolean hasInlineEditorFocus() {
        return hasActiveInlineEditor();
    }

    boolean isPointInsideActiveEditorState(float mx, float my) {
        return isPointInsideActiveEditor(mx, my);
    }

    boolean isOutsidePanel(float mx, float my) {
        return mx < px || mx > px + pw || my < py || my > py + ph;
    }

    void handleOutsidePanelClick() {
        if (searchMode) {
            actions.clearSearch();
        }
        commitText();
    }

    boolean tryStartContentScrollbarDrag(float mx, float my) {
        float sbX = contX + contW - 6f;
        if (showSearchResults() && searchMaxScrollY > 0f
                && hitScrollbarTrack(mx, my, sbSrchTrackT, sbSrchTrackH, sbX)) {
            startScrollbarDrag(2, mx, my, sbSrchTrackT, sbSrchTrackH, sbSrchThumbH, searchScrollY, searchMaxScrollY, sbX);
            return true;
        }
        if (!showSearchResults() && maxScrollY > 0f
                && hitScrollbarTrack(mx, my, sbMainTrackT, sbMainTrackH, sbX)) {
            startScrollbarDrag(1, mx, my, sbMainTrackT, sbMainTrackH, sbMainThumbH, scrollY, maxScrollY, sbX);
            return true;
        }
        return false;
    }

    boolean tryStartSubtabScrollbarDrag(float mx, float my) {
        float subSbX = px + SIDEBAR_W - 5f;
        if (subtabMaxScrollY > 0f
                && hitScrollbarTrack(mx, my, sbSubTrackT, sbSubTrackH, subSbX)) {
            startScrollbarDrag(4, mx, my, sbSubTrackT, sbSubTrackH, sbSubThumbH, subtabScrollY, subtabMaxScrollY, subSbX);
            return true;
        }
        return false;
    }

    boolean isSearchButtonHit(float mx, float my) {
        return mx >= btnSearchX && mx <= btnSearchX + btnSearchW && my >= btnSearchY && my <= btnSearchY + btnSearchH;
    }

    void handleSearchButtonClick() {
        searchMode = !searchMode;
        if (!searchMode) {
            actions.clearSearch();
        } else {
            commitText();
            commitColor();
            openDd = null;
            focusSearchField();
        }
    }

    boolean isPointInsideSearchFieldState(float mx, float my) {
        return isPointInsideSearchField(mx, my);
    }

    void handleSearchFieldClick(float mx, float my) {
        if (!searchMode) {
            commitText();
            commitColor();
            openDd = null;
            focusSearchField();
        } else if (!isSearchEditorActive()) {
            focusSearchField();
        }
        float textPad = 6f;
        float fontSize = 11f;
        float visibleTextW = searchBoxW - 30f - textPad * 2f;
        float textOffset = inlineTextScrollOffsetApprox(textBuf.toString(), textCursor, fontSize, visibleTextW);
        float startX = searchBoxX + 30f + textPad - textOffset;
        textCursor = cursorFromInlineText(textBuf.toString(), fontSize, startX, mx);
        clearTextSelection();
        beginInlineCaretPress(mx, my);
    }

    void collapseSearchIfNeeded() {
        if (searchMode) {
            searchMode = false;
            clearTextSelection();
            normalizeTextCursorState();
        }
    }

    boolean tryBeginPanelDrag(float mx, float my) {
        if (my >= py && my <= py + SB_LOGO_H && mx >= px && mx <= px + SIDEBAR_W) {
            panelDragging = true;
            dragPanelOffX = mx - px;
            dragPanelOffY = my - py;
            return true;
        }
        return false;
    }

    boolean tryHandleSidebarNavigationClick(float mx, float my, Minecraft minecraft) {
        float sbClickW = SIDEBAR_W + sidebarAnim * ((computedSidebarExpanded > 0f ? computedSidebarExpanded : SIDEBAR_EXPANDED) - SIDEBAR_W);
        float tabsStartY = py + SB_LOGO_H + SB_SEP_GAP;
        for (int i = 0; i < 3; i++) {
            float tabY = tabsStartY + i * 44f;
            if (mx >= px && mx <= px + sbClickW && my >= tabY && my <= tabY + 44f) {
                actions.switchMainTab(i);
                return true;
            }
        }
        float profileClickY = py + ph - SB_BOT_PAD - 44f;
        float settClickY = profileClickY - 44f - SB_SEP_GAP;
        float keybindsClickY = settClickY - 44f;
        float hudPosClickY = keybindsClickY - 44f;
        if (mx >= px && mx <= px + sbClickW && my >= hudPosClickY && my <= hudPosClickY + 44f) {
            openOptionalHudEditor(minecraft);
            return true;
        }
        if (mx >= px && mx <= px + sbClickW && my >= keybindsClickY && my <= keybindsClickY + 44f) {
            actions.switchMainTab(3);
            return true;
        }
        if (mx >= px && mx <= px + sbClickW && my >= settClickY && my <= settClickY + 44f) {
            actions.switchMainTab(4);
            return true;
        }
        return false;
    }

    boolean isSidebarExpandedOverContent(float mx, float my) {
        float sbClickW = SIDEBAR_W + sidebarAnim * ((computedSidebarExpanded > 0f ? computedSidebarExpanded : SIDEBAR_EXPANDED) - SIDEBAR_W);
        return sidebarAnim > 0.01f && mx >= px && mx < px + sbClickW && my >= py && my <= py + ph;
    }

    private void renderOptionalFeatureHud(NVGRenderer nvg, float dt) {
        AetherBootstrapHooks.renderConfigScreenOverlay(nvg, (float) width, (float) height, dt);
        NotificationRenderer.render(nvg, (float) width, (float) height, dt);
    }

    private void openOptionalHudEditor(Minecraft minecraft) {
        net.minecraft.client.gui.screens.Screen screen = AetherBootstrapHooks.maybeCreateHudEditScreen();
        if (screen != null) {
            minecraft.setScreen(screen);
        }
    }

    boolean runClickAreas(float mx, float my) {
        for (ClickArea area : clickAreas) {
            if (mx >= area.x() && mx <= area.x() + area.w() && my >= area.y() && my <= area.y() + area.h()) {
                area.action().run();
                return true;
            }
        }
        return false;
    }

    void handleContentAreaClick(float mx, float my) {
        settingInteractionController.handleContentAreaClick(mx, my);
    }

    boolean dragPanel(float mx, float my) {
        if (!panelDragging) {
            return false;
        }
        px = Math.max(0, Math.min(physW / uiScale - pw, mx - dragPanelOffX));
        py = Math.max(0, Math.min(physH / uiScale - ph, my - dragPanelOffY));
        contX = px + SIDEBAR_W;
        contY = py;
        return true;
    }

    boolean dragSlider(float mx) {
        return settingInteractionController.dragSlider(mx);
    }

    boolean dragRangeSlider(float mx) {
        return settingInteractionController.dragRangeSlider(mx);
    }

    boolean dragActiveColorOverlay(float mx, float my) {
        if (activeColor == null || cpDrag == 0) {
            return false;
        }
        handleColorPickerDrag(mx, my);
        return true;
    }

    boolean updateActiveTextSelection(float mx) {
        if (!(hasActiveInlineEditor() || isSearchEditorActive()) || !textSelectionDragging) {
            return false;
        }
        if (isSearchEditorActive()) {
            updateSearchSelectionFromMouse(mx);
            syncSearchQueryFromEditor();
        } else {
            updateInlineSelectionFromMouse(mx);
        }
        return true;
    }

    boolean tryBeginActiveTextSelection(int button, float mx, float my) {
        if (!(hasActiveInlineEditor() || isSearchEditorActive()) || button != 0
                || !((hasActiveInlineEditor() && isPointInsideActiveEditor(mx, my))
                || (isSearchEditorActive() && isPointInsideSearchField(mx, my)))
                || !shouldBeginInlineSelectionDrag(mx, my)) {
            return false;
        }
        textSelectionDragging = true;
        textSelectionPendingDrag = false;
        textSelectionAnchor = Math.max(0, Math.min(textSelectionPressCursor, textBuf.length()));
        if (isSearchEditorActive()) {
            updateSearchSelectionFromMouse(mx);
            syncSearchQueryFromEditor();
        } else {
            updateInlineSelectionFromMouse(mx);
        }
        return true;
    }

    boolean dragScrollbar(float my) {
        if (sbDragging == 0) {
            return false;
        }
        updateScrollbarDrag(my, true);
        return true;
    }

    void finishMouseInteractions() {
        dragSetting = null;
        dragRangeSetting = null;
        dragRangeHandle = 0;
        panelDragging = false;
        cpDrag = 0;
        sbDragging = 0;
        sbDragThumbOffset = 0f;
        textSelectionDragging = false;
        textSelectionPendingDrag = false;
        textSelectionPressCursor = -1;
        normalizeTextCursorState();
    }

    boolean handleMouseScroll(float mx, float my, float scrollYDelta) {
        if (mx < contX || mx > contX + contW || my < contY || my > contY + contH) {
            return false;
        }
        float delta = scrollYDelta * 20f;
        if (showSearchResults()) {
            searchTargetScrollY = Math.max(0f, Math.min(searchMaxScrollY, searchTargetScrollY - delta));
        } else {
            targetScrollY = Math.max(0f, Math.min(maxScrollY, targetScrollY - delta));
            syncActiveBodyScrollState();
        }
        return true;
    }

    boolean handleEscapeKey() {
        if (activeKeybindCapture != null) {
            activeKeybindCapture = null;
            return true;
        }
        if (searchMode || !searchQuery.isEmpty()) {
            actions.clearSearch();
            return true;
        }
        if (profileRenameFocus) {
            cancelProfileRename();
            return true;
        }
        if (profileNameFocus) {
            profileNameFocus = false;
            return true;
        }
        if (activeSubTab != null) {
            exitModuleDetail();
            return true;
        }
        commitText();
        commitColor();
        if (openDd != null) {
            openDd = null;
            return true;
        }
        onClose();
        return true;
    }

    boolean handleInlineControlShortcut(int key) {
        return inlineTextEditor.handleControlShortcut(key);
    }

    boolean handleSearchEditorKey(int key, boolean ctrl, boolean shift) {
        return inlineTextEditor.handleSearchEditorKeyPressed(key, ctrl, shift);
    }

    boolean handleProfileNameKey(int key) {
        return inlineTextEditor.handleProfileNameKeyPressed(key);
    }

    boolean handleInlineEditorKey(int key, boolean ctrl, boolean shift) {
        return inlineTextEditor.handleInlineEditorKeyPressed(key, ctrl, shift);
    }

    boolean handleOverlayHexKey(int key) {
        return overlayController.handleHexKeyPressed(key);
    }

    boolean handleInlineCharTyped(char ch) {
        if (activeKeybindCapture != null) {
            return true;
        }
        return inlineTextEditor.handleCharTyped(ch);
    }

    boolean isAwaitingKeybindCapture(KeybindSetting setting) {
        return activeKeybindCapture == setting;
    }

    void beginKeybindCapture(KeybindSetting setting) {
        activeKeybindCapture = setting;
    }

    void clearKeybindCapture() {
        activeKeybindCapture = null;
    }

    boolean isAnyKeybindCaptureActive() {
        return activeKeybindCapture != null;
    }

    boolean handleKeybindCapture(KeyEvent input) {
        if (activeKeybindCapture == null) {
            return false;
        }

        KeybindSetting setting = activeKeybindCapture;
        activeKeybindCapture = null;

        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE) {
            setting.clearBinding();
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_UNKNOWN) {
            setting.setBoundKey(InputConstants.Type.SCANCODE.getOrCreate(input.scancode()));
            return true;
        }

        setting.setBoundKey(InputConstants.Type.KEYSYM.getOrCreate(input.key()));
        return true;
    }

    boolean handleKeybindMouseButtonCapture(int button) {
        if (activeKeybindCapture == null) {
            return false;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            activeKeybindCapture = null;
            return false;
        }

        KeybindSetting setting = activeKeybindCapture;
        activeKeybindCapture = null;
        setting.setBoundKey(InputConstants.Type.MOUSE.getOrCreate(button));
        return true;
    }

    boolean handleOverlayHexChar(char ch) {
        return overlayController.handleHexChar(ch);
    }

    float panelX() {
        return px;
    }

    float panelY() {
        return py;
    }

    float panelW() {
        return pw;
    }

    float panelH() {
        return ph;
    }

    DropdownSetting currentOpenDropdown() {
        return openDd;
    }

    void closeOpenDropdown() {
        openDd = null;
    }

    float dropdownOverlayX() {
        return ddX;
    }

    float dropdownOverlayY() {
        return ddY;
    }

    float dropdownOverlayW() {
        return ddW;
    }

    float dropdownButtonHeight() {
        return ddButtonH;
    }

    float dropdownAnimAmount() {
        return ddAnimAmt;
    }

    ColorSetting activeColorSetting() {
        return activeColor;
    }

    void clearActiveColorSetting() {
        activeColor = null;
    }

    float colorHue() {
        return cpHue;
    }

    void setColorHue(float hue) {
        cpHue = hue;
    }

    float colorSaturation() {
        return cpSat;
    }

    void setColorSaturation(float saturation) {
        cpSat = saturation;
    }

    float colorValue() {
        return cpVal;
    }

    void setColorValue(float value) {
        cpVal = value;
    }

    float colorAlpha() {
        return cpAlpha;
    }

    void setColorAlpha(float alpha) {
        cpAlpha = alpha;
    }

    int colorDragMode() {
        return cpDrag;
    }

    void setColorDragMode(int dragMode) {
        cpDrag = dragMode;
    }

    boolean isColorHexFocused() {
        return cpHexFocus;
    }

    void setColorHexFocused(boolean focused) {
        cpHexFocus = focused;
    }

    StringBuilder colorHexBuffer() {
        return cpHexBuf;
    }

    void setColorPickerPanelBounds(float x, float y, float w, float h) {
        cpPX = x;
        cpPY = y;
        cpPW = w;
        cpPH = h;
    }

    float colorPickerPanelX() {
        return cpPX;
    }

    float colorPickerPanelY() {
        return cpPY;
    }

    float colorPickerPanelW() {
        return cpPW;
    }

    float colorPickerPanelH() {
        return cpPH;
    }

    void setColorPickerSvBounds(float x, float y, float w, float h) {
        cpSvX = x;
        cpSvY = y;
        cpSvW = w;
        cpSvH = h;
    }

    float colorPickerSvX() {
        return cpSvX;
    }

    float colorPickerSvY() {
        return cpSvY;
    }

    float colorPickerSvW() {
        return cpSvW;
    }

    float colorPickerSvH() {
        return cpSvH;
    }

    void setColorPickerBarBounds(float hueY, float alphaY, float barH) {
        cpHBarY = hueY;
        cpABarY = alphaY;
        cpBarH = barH;
    }

    float colorPickerHueBarY() {
        return cpHBarY;
    }

    float colorPickerAlphaBarY() {
        return cpABarY;
    }

    float colorPickerBarH() {
        return cpBarH;
    }

    void setColorPickerHexBounds(float x, float y, float w, float h) {
        cpHexX = x;
        cpHexY = y;
        cpHexW = w;
        cpHexH = h;
    }

    float colorPickerHexX() {
        return cpHexX;
    }

    float colorPickerHexY() {
        return cpHexY;
    }

    float colorPickerHexW() {
        return cpHexW;
    }

    float colorPickerHexH() {
        return cpHexH;
    }
}

