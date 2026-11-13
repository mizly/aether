package dev.aether.ui

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.macro.MacroStateManager
import dev.aether.notification.NotificationRenderer
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NVGScreen
import dev.aether.telemetry.AetherAuthService
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.RangeSliderSetting
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import dev.aether.util.AetherLang.Companion.localize
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Objects
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
class MainGUI @JvmOverloads constructor() : NVGScreen("Aether") {
    class LaunchTarget(private val mainTabValue: Int, private val moduleNameValue: String, private val openModuleDetailValue: Boolean) {
        fun mainTab(): Int = mainTabValue
        fun moduleName(): String = moduleNameValue
        fun openModuleDetail(): Boolean = openModuleDetailValue

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LaunchTarget) return false
            return mainTabValue == other.mainTabValue && moduleNameValue == other.moduleNameValue && openModuleDetailValue == other.openModuleDetailValue
        }

        override fun hashCode(): Int {
            var result = mainTabValue
            result = 31 * result + moduleNameValue.hashCode()
            result = 31 * result + openModuleDetailValue.hashCode()
            return result
        }

        override fun toString(): String = "LaunchTarget[mainTab=$mainTabValue, moduleName=$moduleNameValue, openModuleDetail=$openModuleDetailValue]"

        companion object {
            @JvmStatic
            fun bootstrapAuthentication(): LaunchTarget = LaunchTarget(0, "Authentication", true)
        }
    }

    companion object {
        @JvmField internal val SIDEBAR_W: Float = 60f
        @JvmField internal val SIDEBAR_EXPANDED: Float = 194f
        @JvmField internal val TOP_BAR_H: Float = 53f
        @JvmField internal val FILTER_BAR_H: Float = 36f
        private val TAB_NAMES = arrayOf("Modules", "Colors", "Config", "Keybinds", "Settings")
        private val TAB_DESCS = arrayOf(
            "Manage and configure modules",
            "Customise HUD and menu colors",
            "Save and manage config and theme profiles",
            "Edit Aether keybinds and keep them synced with Minecraft controls",
            "General client settings"
        )
        private val COLOR_FILTERS = arrayOf("All", "HUD Colors", "Menu Colors")
        private val CONFIG_FILTERS = arrayOf("All", "Config Profiles", "Theme Profiles")
        private val SINGLE_FILTER = arrayOf("All")
        @JvmField internal val RADIUS: Float = 10f
        @JvmField internal val ITEM_PAD: Float = 20f
        private val GROUP_GAP: Float = 12f
        @JvmField internal val HEADER_H: Float = 46f
        @JvmField internal val HEADER_TO_FIRST_SETTING_GAP: Float = 5f
        @JvmField internal val PILL_H: Float = 21f
        // compact group-section label row in the flat colors/settings renderer
        @JvmField internal val FLAT_LABEL_H: Float = 32f
        @JvmField internal val DROPDOWN_FIELD_W: Float = 130f
        @JvmField internal val DROPDOWN_FIELD_H: Float = 32f
        @JvmField internal val DROPDOWN_ACTION_W: Float = 32f
        @JvmField internal val DROPDOWN_ACTION_GAP: Float = 6f
        @JvmField internal val CARD_COLS: Int = 3
        @JvmField internal val CARD_HGAP: Float = 10f
        @JvmField internal val CARD_VGAP: Float = 10f
        @JvmField internal val CARD_H: Float = 82f
        @JvmField internal val SECT_GAP: Float = 22f
        @JvmField internal val SECT_H: Float = 26f
        @JvmField internal val SECT_SEP: Float = 10f
        @JvmField internal val MODULE_CAT_W: Float = 160f
        @JvmField internal val MOD_HEADER_H: Float = 74f

        // -- Sidebar layout constants ----------------------------------------------
        @JvmField internal val SB_H_PAD: Float = 12f
        @JvmField internal val SB_PILL: Float = 36f
        // logo plus the spacing below it
        @JvmField internal val SB_LOGO_H: Float = 56f
        @JvmField internal val SB_SEP_GAP: Float = 8f
        // pill inset from the row top
        @JvmField internal val SB_ROW_PAD: Float = (44f - SB_PILL) / 2f
        // settings row to panel bottom
        @JvmField internal val SB_BOT_PAD: Float = 8f
        private val PET_FIELD_H: Float = 42f
        private val PET_ROW_GAP: Float = 8f
        private val PET_COL_GAP: Float = 8f

        @JvmStatic
        internal fun petFieldHeight(): Float {
            return PET_FIELD_H
        }

        @JvmStatic
        internal fun petRowGap(): Float {
            return PET_ROW_GAP
        }

        @JvmStatic
        internal fun petColGap(): Float {
            return PET_COL_GAP
        }
        @JvmField var uiScale: Float = 1.5f
        @JvmField var uiTextScale: Float = 1f
        private const val HOVER_HELP_DELAY_MS: Long = 260L
        @JvmField internal val TEXT_SINGLE_FIELD_H: Float = 20f
        @JvmField internal val TEXT_MULTI_LINE_STEP: Float = 14f
        @JvmField internal val TEXT_MULTI_PAD_Y: Float = 6f
        @JvmField internal val TEXT_INLINE_FIELD_H: Float = 32f
        @JvmField internal val TEXT_INLINE_FIELD_GAP: Float = 12f
        @JvmField internal val LIST_ITEM_H: Float = TEXT_INLINE_FIELD_H
        @JvmField internal val LIST_ITEM_GAP: Float = 6f
        @JvmField internal val LIST_ACTION_W: Float = 24f
        @JvmField internal val SLIDER_FIELD_TRACK_GAP: Float = 14f
        @JvmField internal val DD_ITEM_H: Float = 30f
        @JvmField internal val CP_SV: Int = 1
        @JvmField internal val CP_HUE: Int = 2
        @JvmField internal val CP_ALPHA: Int = 3
        @JvmStatic
        internal fun hasTopFilterBar(mainTab: Int): Boolean {
            return mainTab >= 0 && mainTab <= 2
        }
        @JvmStatic
        private fun isWordChar(c: Char): Boolean {
            return MainGUIInlineTextEditor.isWordChar(c)
        }

        @JvmStatic
        private fun moveCursorWord(text: String, cursor: Int, direction: Int): Int {
            return MainGUIInlineTextEditor.moveCursorWord(text, cursor, direction)
        }

        @JvmStatic
        private fun cursorFromInlineText(text: String, fontSize: Float, startX: Float, mx: Float): Int {
            return MainGUIInlineTextEditor.cursorFromInlineText(text, fontSize, startX, mx)
        }
        @JvmStatic internal fun groupGap(): Float = GROUP_GAP
    }

    private var px: Float = 0f
    private var py: Float = 0f
    private var pw: Float = 0f
    private var ph: Float = 0f
    @JvmField internal var contX: Float = 0f
    @JvmField internal var contY: Float = 0f
    @JvmField internal var contW: Float = 0f
    @JvmField internal var contH: Float = 0f
    private var pr: Float = 1f
    private var physW: Float = 0f
    private var physH: Float = 0f

    private var alpha: Float = 0f
    private var animScale: Float = 0.96f
    private var animOffsetY: Float = 120f

    // 1.0 means one layout unit is one physical pixel
    // Theme.UI_SCALE is the source of truth; this syncs from it each frame except while the slider is dragging, so the slider can't feed back

    private var hoverHelpCandidateKey: String? = null
    private var hoverHelpCandidateTitle: String? = null
    private var hoverHelpCandidateDescription: String? = null
    private var hoverHelpLastKey: String? = null
    private var hoverHelpStartedAt: Long = 0L

    internal fun offerHoverHelp(key: String?, title: String?, description: String?, x: Float, y: Float, w: Float, h: Float, mx: Float, my: Float) {
        if (key == null || title == null || description == null || description.isBlank()) {
            return
        }
        if (mx < x || mx > x + w || my < y || my > y + h) {
            return
        }
        hoverHelpCandidateKey = key
        hoverHelpCandidateTitle = title
        hoverHelpCandidateDescription = description
    }

    private fun renderHoverHelp(nvg: NVGRenderer, mx: Float, my: Float) {
        if (hoverHelpCandidateKey == null
                || openDd != null
                || activeColor != null
                || activeSliderField != null
                || activeText != null
                || activeList != null
                || activePosField != null) {
            hoverHelpLastKey = null
            hoverHelpStartedAt = 0L
            return
        }

        val now = System.currentTimeMillis()
        if (hoverHelpCandidateKey != hoverHelpLastKey) {
            hoverHelpLastKey = hoverHelpCandidateKey
            hoverHelpStartedAt = now
            return
        }
        if (now - hoverHelpStartedAt < HOVER_HELP_DELAY_MS) {
            return
        }

        val maxTextW = Math.min(330f, Math.max(220f, pw * 0.36f))
        val titleSize = 12.5f
        val bodySize = 11f
        val padX = 12f
        val padY = 10f
        val titleGap = 6f
        val bodyStep = 14f
        val bodyLines = wrapTextForWidth(nvg, hoverHelpCandidateDescription!!, Fonts.REGULAR, bodySize, maxTextW)
        if (bodyLines.isEmpty()) {
            return
        }

        val titleW = nvg.textWidth(Fonts.BOLD, hoverHelpCandidateTitle!!, titleSize)
        var bodyW = 0f
        for (line in bodyLines) {
            bodyW = Math.max(bodyW, nvg.textWidth(Fonts.REGULAR, line, bodySize))
        }
        val tooltipW = Math.min(maxTextW + padX * 2f, Math.max(190f, Math.max(titleW, bodyW) + padX * 2f))
        val tooltipH = padY * 2f + titleSize + titleGap + bodyLines.size * bodyStep

        var tx = mx + 16f
        var ty = my + 16f
        val panelRight = px + pw - 8f
        val panelBottom = py + ph - 8f
        if (tx + tooltipW > panelRight) {
            tx = mx - tooltipW - 16f
        }
        if (ty + tooltipH > panelBottom) {
            ty = my - tooltipH - 16f
        }
        tx = Math.max(px + 8f, Math.min(tx, panelRight - tooltipW))
        ty = Math.max(py + 8f, Math.min(ty, panelBottom - tooltipH))

        nvg.shadow(tx, ty, tooltipW, tooltipH, 7f, 12f, Theme.withAlpha(0xFF000000.toInt(), 0.55f))
        nvg.roundedRect(tx, ty, tooltipW, tooltipH, 7f, Theme.BG_SECONDARY)
        nvg.rectOutlineSolid(tx, ty, tooltipW, tooltipH, 7f, 1f,
                Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.42f))
        nvg.text(Fonts.BOLD, hoverHelpCandidateTitle!!, tx + padX, ty + padY, titleSize, Theme.TEXT_PRIMARY)
        val bodyY = ty + padY + titleSize + titleGap
        for (i in bodyLines.indices) {
            nvg.text(Fonts.REGULAR, bodyLines[i], tx + padX, bodyY + i * bodyStep, bodySize, Theme.TEXT_SECONDARY)
        }
    }

    private var sidebarAnim: Float = 0f

    private var activeMain: Int = 0
    private var activeSubtab: Int = 0
    // computed once on the first frame to fit the longest tab label
    private var computedSidebarExpanded: Float = 0f
    private var animMainSel: Float = 0f
    private var animSubSel: Float = 0f

    @JvmField internal var searchMode: Boolean = false
    @JvmField internal var searchQuery: String = ""
    @JvmField internal var searchScrollY: Float = 0f
    @JvmField internal var searchTargetScrollY: Float = 0f
    @JvmField internal var searchMaxScrollY: Float = 0f
    @JvmField internal var searchBoxX: Float = 0f
    @JvmField internal var searchBoxY: Float = 0f
    @JvmField internal var searchBoxW: Float = 0f
    @JvmField internal var searchBoxH: Float = 0f

    private var activeFilter: Int = 0
    private var filterBarAnimX: Float = 0f
    private var filterBarAnimW: Float = 0f
    private var filterBarTargetX: Float = 0f
    private var filterBarTargetW: Float = 0f
    private var filterBarInited: Boolean = false

    @JvmField internal var profileNameInput: String = ""
    @JvmField internal var profileNameFocus: Boolean = false
    @JvmField internal var profileNameTarget: Int = 0
    @JvmField internal var profileRenameInput: String = ""
    @JvmField internal var profileRenameOriginal: String = ""
    @JvmField internal var profileRenameFocus: Boolean = false
    @JvmField internal var profileRenameTarget: Int = 0
    private var profileScrollY: Float = 0f
    private var profileTargetScrollY: Float = 0f
    private var profileMaxScrollY: Float = 0f

    private var loginButtonX: Float = 0f
    private var loginButtonY: Float = 0f
    private var loginButtonW: Float = 0f
    private var loginButtonH: Float = 0f

    private class ClickArea(private val xValue: Float, private val yValue: Float, private val wValue: Float, private val hValue: Float, private val actionValue: Runnable) {
        fun x(): Float = xValue
        fun y(): Float = yValue
        fun w(): Float = wValue
        fun h(): Float = hValue
        fun action(): Runnable = actionValue

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ClickArea) return false
            return java.lang.Float.compare(xValue, other.xValue) == 0
                    && java.lang.Float.compare(yValue, other.yValue) == 0
                    && java.lang.Float.compare(wValue, other.wValue) == 0
                    && java.lang.Float.compare(hValue, other.hValue) == 0
                    && actionValue == other.actionValue
        }

        override fun hashCode(): Int = Objects.hash(xValue, yValue, wValue, hValue, actionValue)

        override fun toString(): String = "ClickArea[x=$xValue, y=$yValue, w=$wValue, h=$hValue, action=$actionValue]"
    }
    private val clickAreas: MutableList<ClickArea> = ArrayList()

    private var btnSearchX: Float = 0f
    private var btnSearchY: Float = 0f
    private var btnSearchW: Float = 0f
    private var btnSearchH: Float = 0f

    @JvmField internal var scrollY: Float = 0f
    @JvmField internal var targetScrollY: Float = 0f
    @JvmField internal var maxScrollY: Float = 0f
    private var modulesOverviewScrollY: Float = 0f
    private var modulesOverviewTargetScrollY: Float = 0f
    private var moduleDetailScrollY: Float = 0f
    private var moduleDetailTargetScrollY: Float = 0f
    @JvmField internal var lastFrameTimeNanos: Long = System.nanoTime()

    private var sbDragging: Int = 0
    private var sbDragThumbOffset: Float = 0f
    @JvmField internal var sbMainTrackT: Float = 0f
    @JvmField internal var sbMainTrackH: Float = 0f
    @JvmField internal var sbMainThumbH: Float = 0f
    @JvmField internal var sbSrchTrackT: Float = 0f
    @JvmField internal var sbSrchTrackH: Float = 0f
    @JvmField internal var sbSrchThumbH: Float = 0f
    private var sbProfTrackT: Float = 0f
    private var sbProfTrackH: Float = 0f
    private var sbProfThumbH: Float = 0f
    private var sbSubTrackT: Float = 0f
    private var sbSubTrackH: Float = 0f
    private var sbSubThumbH: Float = 0f

    private var subtabScrollY: Float = 0f
    private var subtabTargetScrollY: Float = 0f
    private var subtabMaxScrollY: Float = 0f

    private var dragSetting: SliderSetting? = null
    private var dragRangeSetting: RangeSliderSetting? = null
    private var dragTrackX: Float = 0f
    private var dragTrackW: Float = 0f
    private var dragMin: Float = 0f
    private var dragMax: Float = 0f
    private var dragRangeHandle: Int = 0

    @JvmField internal var activeSliderField: SliderSetting? = null
    @JvmField internal var activeText: TextSetting? = null
    @JvmField internal var activeList: ListSetting? = null
    @JvmField internal var activeListIndex: Int = -1
    @JvmField internal var activePosField: PositionSetting? = null
    @JvmField internal var activeKeybindCapture: KeybindSetting? = null
    // 0=X 1=Y 2=Z
    @JvmField internal var activePosIdx: Int = 0
    @JvmField internal var textBuf: StringBuilder = StringBuilder()
    @JvmField internal var textCursor: Int = 0
    @JvmField internal var textSelectionAnchor: Int = -1
    @JvmField internal var textSelectionDragging: Boolean = false
    @JvmField internal var textSelectionPendingDrag: Boolean = false
    @JvmField internal var textSelectionPressCursor: Int = -1
    @JvmField internal var textSelectionPressX: Float = 0f
    @JvmField internal var textSelectionPressY: Float = 0f

    @JvmField internal var activeEditorBoundsSet: Boolean = false
    @JvmField internal var activeEditorX: Float = 0f
    @JvmField internal var activeEditorY: Float = 0f
    @JvmField internal var activeEditorW: Float = 0f
    @JvmField internal var activeEditorH: Float = 0f

    @JvmField internal var openDd: DropdownSetting? = null
    private var ddX: Float = 0f
    private var ddY: Float = 0f
    private var ddW: Float = 0f
    private var ddButtonH: Float = 0f
    private var ddAnimAmt: Float = 0f

    @JvmField internal var hoveredColor: ColorSetting? = null
    @JvmField internal var activeColor: ColorSetting? = null
    private var cpHue: Float = 0f
    private var cpSat: Float = 1f
    private var cpVal: Float = 1f
    private var cpAlpha: Float = 1f
    private var cpDrag: Int = 0

    @JvmField internal var cpHexFocus: Boolean = false
    private val cpHexBuf: StringBuilder = StringBuilder()
    private var cpPX: Float = 0f
    private var cpPY: Float = 0f
    private var cpPW: Float = 0f
    private var cpPH: Float = 0f
    private var cpSvX: Float = 0f
    private var cpSvY: Float = 0f
    private var cpSvW: Float = 0f
    private var cpSvH: Float = 0f
    private var cpHBarY: Float = 0f
    private var cpABarY: Float = 0f
    private var cpBarH: Float = 0f
    private var cpHexX: Float = 0f
    private var cpHexY: Float = 0f
    private var cpHexW: Float = 0f
    private var cpHexH: Float = 0f

    private class Section(private val nameValue: String, private val subtabsValue: List<ModulesTab.SubTab>) {
        fun name(): String = nameValue
        fun subtabs(): List<ModulesTab.SubTab> = subtabsValue

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Section) return false
            return nameValue == other.nameValue && subtabsValue == other.subtabsValue
        }

        override fun hashCode(): Int = Objects.hash(nameValue, subtabsValue)

        override fun toString(): String = "Section[name=$nameValue, subtabs=$subtabsValue]"
    }
    private val cardHoverAnim: IdentityHashMap<ModulesTab.SubTab, Float> = IdentityHashMap()

    // non-null once the user opens a module's settings view
    @JvmField internal var activeSubTab: ModulesTab.SubTab? = null
    @JvmField internal var activeCategoryIdx: Int = 0
    // keyed by SettingGroup, or this for "All"
    private val catHoverAnim: IdentityHashMap<Any, Float> = IdentityHashMap()
    private val subTabBarAnim: IdentityHashMap<Any, Float> = IdentityHashMap()
    @JvmField internal var suppressNestedContentScissor: Boolean = false

    private var panelDragging: Boolean = false
    private var dragPanelOffX: Float = 0f
    private var dragPanelOffY: Float = 0f

    private val forcedOverride: MutableSet<SettingGroup> = HashSet()
    private val context: MainGUIContext = MainGUIContext()
    private val layoutHelper: MainGUILayout = MainGUILayout()
    private val actions: MainGUIActions = MainGUIActions(this, context)
    private val textLayout: MainGUITextLayout = MainGUITextLayout()
    private val renderPrimitives: MainGUIRenderPrimitives = MainGUIRenderPrimitives(this, textLayout)
    private val chromeRenderer: MainGUIChromeRenderer = MainGUIChromeRenderer(this)
    private val contentRenderer: MainGUIContentRenderer = MainGUIContentRenderer(this, chromeRenderer)
    private val moduleOverviewRenderer: MainGUIModuleOverviewRenderer = MainGUIModuleOverviewRenderer(this)
    private val flatPanelRenderer: MainGUIFlatPanelRenderer = MainGUIFlatPanelRenderer(this)
    private val moduleDetailRenderer: MainGUIModuleDetailRenderer = MainGUIModuleDetailRenderer(this)
    private val settingRowRenderer: MainGUISettingRowRenderer = MainGUISettingRowRenderer(this)
    private val overlayController: MainGUIOverlayController = MainGUIOverlayController(this)
    private val settingInteractionController: MainGUISettingInteractionController = MainGUISettingInteractionController(this)
    private val transitionRenderer: MainGUITransitionRenderer = MainGUITransitionRenderer(this)
    private val petTrackerPanel: MainGUIPetTrackerPanel = MainGUIPetTrackerPanel(this)
    private val mouseController: MainGUIMouseController = MainGUIMouseController(this)
    private val keyboardController: MainGUIKeyboardController = MainGUIKeyboardController(this)
    private val searchPanel: MainGUISearchPanel = MainGUISearchPanel(this)
    private val profilesPanel: MainGUIProfilesPanel = MainGUIProfilesPanel(this)
    private val inlineTextEditor: MainGUIInlineTextEditor = MainGUIInlineTextEditor(this)
    private var launchTarget: LaunchTarget? = null
    private var launchTargetApplied: Boolean = false
    private var frameStatePrepared: Boolean = false
    private var renderedQueuedFrame: Boolean = false
    private var frameMouseX: Float = 0f
    private var frameMouseY: Float = 0f
    private var frameDeltaTime: Float = 0f
    constructor(launchTarget: LaunchTarget?) : this() {
        this.launchTarget = launchTarget
    }

    override fun initNVG() {
        val client = Minecraft.getInstance()
        if (MacroStateManager.isAutomationRunning()) {
            MacroStateManager.stopMacro(client, "MainGUI opened", false)
        }
        MainGUIRegistry.refresh()
        AetherAuthService.refreshAccount()
        // Resolve pixel ratio from the live render target so panel dimensions
        // are expressed in physical pixels, not GUI-scaled logical pixels.
        try {
            val rt = client.gameRenderer.mainRenderTarget()
            pr = rt.width.toFloat() / this.width
        } catch (ignored: Exception) {
            pr = 1f
        }
        applyFrameLayout(true, 0f, 0f)
        activeSliderField = null
        activeText = null
        activeList = null
        activeListIndex = -1
        openDd = null
        dragSetting = null
        lastFrameTimeNanos = System.nanoTime()
        refreshContext()
        applyLaunchTargetIfNeeded()
    }

    // -- Data helpers ----------------------------------------------------------

    private fun subtabs(): MutableList<ModulesTab.SubTab> {
        return when (activeMain) {
            0 -> MainGUIRegistry.MODULE_SUBTABS
            1 -> MainGUIRegistry.COLORS_SUBTABS
            2 -> MainGUIRegistry.PROFILES_SUBTABS
            3 -> MainGUIRegistry.KEYBINDS_SUBTABS
            4 -> MainGUIRegistry.SETTINGS_SUBTABS
            else -> MainGUIRegistry.PROFILES_SUBTABS
        }
    }

    private fun groups(): List<SettingGroup> {
        if (activeMain == 2) {
            return listOf()
        }
        val subs = subtabs()
        if (subs.isEmpty()) {
            return listOf()
        }
        if (activeSubtab >= subs.size) {
            activeSubtab = 0
        }
        return ArrayList(subs[activeSubtab].groups())
    }

    private fun hasTopFilterBar(): Boolean {
        return hasTopFilterBar(activeMain)
    }

    internal fun contentScrollTop(): Float {
        return layoutHelper.contentScrollTop(contY, hasTopFilterBar(), TOP_BAR_H, FILTER_BAR_H)
    }

    internal fun contentScrollHeight(): Float {
        return layoutHelper.contentScrollHeight(contY, contH, hasTopFilterBar(), TOP_BAR_H, FILTER_BAR_H)
    }

    private fun resetActiveContentScroll() {
        actions.resetActiveContentScroll()
    }

    private fun syncActiveBodyScrollState() {
        actions.syncActiveBodyScrollState()
    }

    private fun enterModuleDetail(subTab: ModulesTab.SubTab) {
        actions.enterModuleDetail(subTab)
    }

    private fun exitModuleDetail() {
        actions.exitModuleDetail()
    }

    internal fun pushContentLocalScissor(nvg: NVGRenderer, x: Float, y: Float, w: Float, h: Float): Boolean {
        if (suppressNestedContentScissor) {
            return false
        }
        nvg.pushScissor(x, y, w, h)
        return true
    }

    internal fun popContentLocalScissor(nvg: NVGRenderer, clipped: Boolean) {
        if (clipped) {
            nvg.popScissor()
        }
    }

    internal fun addClickArea(x: Float, y: Float, w: Float, h: Float, action: Runnable) {
        clickAreas.add(ClickArea(x, y, w, h, action))
    }

    internal fun openModuleDetailFromCard(subTab: ModulesTab.SubTab) {
        enterModuleDetail(subTab)
    }

    internal fun openSearchResult(mainTab: Int, subTab: ModulesTab.SubTab, group: SettingGroup?, setting: Setting?) {
        actions.openSearchResult(mainTab, subTab, group, setting)
    }

    internal fun ensureSearchGroupVisible(group: SettingGroup?) {
        if (group != null && !group.isAlwaysOn() && !showChildren(group)) {
            forcedOverride.add(group)
        }
    }

    private fun flatSubtabsForFilter(): List<ModulesTab.SubTab> {
        val subs = subtabs()
        if (activeMain != 1) {
            return subs
        }
        val filterIndex = normalizedActiveFilterIndex()
        return when (filterIndex) {
            1 -> if (subs.size > 0) listOf(subs[0]) else listOf()
            2 -> if (subs.size > 1) listOf(subs[1]) else listOf()
            else -> subs
        }
    }

    internal fun showConfigProfiles(): Boolean {
        val filterIndex = normalizedActiveFilterIndex()
        return filterIndex == 0 || filterIndex == 1
    }

    internal fun showThemeProfiles(): Boolean {
        val filterIndex = normalizedActiveFilterIndex()
        return filterIndex == 0 || filterIndex == 2
    }

    internal fun isProfileNameFocused(isConfig: Boolean): Boolean {
        return profileNameFocus && profileNameTarget == (if (isConfig) 0 else 1)
    }

    internal fun isProfileRenameFocused(isConfig: Boolean, name: String): Boolean {
        return profileRenameFocus && profileRenameTarget == (if (isConfig) 0 else 1) && profileRenameOriginal == name
    }

    internal fun beginProfileRename(isConfig: Boolean, name: String) {
        commitText()
        commitColor()
        openDd = null
        profileNameFocus = false
        profileRenameTarget = if (isConfig) 0 else 1
        profileRenameOriginal = name
        profileRenameInput = name
        profileRenameFocus = true
    }

    internal fun cancelProfileRename() {
        profileRenameFocus = false
        profileRenameOriginal = ""
        profileRenameInput = ""
    }

    internal fun commitProfileRename() {
        profilesPanel.commitRename()
    }

    private fun sectionsForFilter(): List<Section> {
        val availableSections = availableModuleSections()
        if (availableSections.size <= 1) {
            return availableSections
        }

        val filterIndex = normalizedActiveFilterIndex()
        if (filterIndex == 0) {
            return availableSections
        }

        val sectionIndex = filterIndex - 1
        if (sectionIndex < 0 || sectionIndex >= availableSections.size) {
            return availableSections
        }

        return listOf(availableSections[sectionIndex])
    }

    private fun availableModuleSections(): List<Section> {
        val sections = ArrayList<Section>(MainGUIRegistry.MODULE_SECTIONS.size)
        for (section in MainGUIRegistry.MODULE_SECTIONS) {
            sections.add(Section(section.displayName(), ArrayList(section.subtabs())))
        }
        return sections
    }

    private fun showChildren(group: SettingGroup): Boolean {
        if (group.isAlwaysOn()) {
            return true
        }
        val forced = forcedOverride.contains(group)
        return group.isEnabled() != forced
    }

    private fun isPetTrackerGroup(group: SettingGroup): Boolean {
        return group.getRawName().startsWith("Pet XP Tracker")
    }

    private fun normalizedActiveSubtabIndex(): Int {
        val subs = subtabs()
        if (subs.isEmpty()) {
            return 0
        }
        return Math.max(0, Math.min(activeSubtab, subs.size - 1))
    }

    private fun normalizedActiveCategoryIdx(): Int {
        if (activeMain != 0 || activeSubTab == null) {
            return activeCategoryIdx
        }
        val maxCatIdx = activeSubTab!!.groups().size
        val normalized = Math.max(0, Math.min(activeCategoryIdx, maxCatIdx))
        if (maxCatIdx == 1 && normalized == 0) {
            return 1
        }
        return normalized
    }

    internal fun snapshotContentState(): MainGUIContentViewState {
        val filterIndex = normalizedActiveFilterIndex()
        return MainGUIContentViewState(activeMain, normalizedActiveSubtabIndex(), filterIndex, activeSubTab, normalizedActiveCategoryIdx(), searchQuery)
    }

    internal fun applyContentState(state: MainGUIContentViewState) {
        activeMain = state.activeMain()
        activeSubtab = state.activeSubtab()
        activeFilter = state.activeFilter()
        activeFilter = normalizedActiveFilterIndex()
        activeSubTab = state.activeSubTab()
        activeCategoryIdx = state.activeCategoryIdx()
        searchQuery = state.searchQuery() ?: ""
    }

    private fun findSetting(group: SettingGroup, name: String): Setting? {
        for (setting in group.getSettings()) {
            if (setting.getRawName() == name) {
                return setting
            }
        }
        return null
    }

    private fun renderPetTrackerHeaderButtons(nvg: NVGRenderer, group: SettingGroup, right: Float, btnY: Float, btnH: Float, mx: Float, my: Float): Float {
        val trackerCount = currentPetTrackerGroupCount()
        val canAdd = true
        val canRemove = trackerCount > 1
        val gap = 6f

        var currentRight = right
        if (canRemove) {
            val btnW = 68f
            val bx = currentRight - btnW
            val hovered = mx >= bx && mx <= bx + btnW && my >= btnY && my <= btnY + btnH
            nvg.roundedRect(bx, btnY, btnW, btnH, 4f, if (hovered) Theme.BG_HOVER else Theme.BG_FIELD)
            nvg.rectOutline(bx, btnY, btnW, btnH, 4f, 1f, Theme.BORDER_DEFAULT)
            nvg.textCentered(Fonts.REGULAR, localize("Remove"), bx, btnY, btnW, btnH, 11f, Theme.TEXT_PRIMARY)
            clickAreas.add(ClickArea(bx, btnY, btnW, btnH, Runnable {
                val remove = findSetting(group, "Remove Pet")
                if (remove is ActionSetting) {
                    remove.execute()
                }
            }))
            currentRight = bx - gap
        }

        if (canAdd) {
            val btnW = 56f
            val bx = currentRight - btnW
            val hovered = mx >= bx && mx <= bx + btnW && my >= btnY && my <= btnY + btnH
            nvg.roundedRect(bx, btnY, btnW, btnH, 4f, if (hovered) Theme.BG_HOVER else Theme.BG_FIELD)
            nvg.rectOutline(bx, btnY, btnW, btnH, 4f, 1f, Theme.BORDER_DEFAULT)
            nvg.textCentered(Fonts.REGULAR, localize("Add"), bx, btnY, btnW, btnH, 11f, Theme.TEXT_PRIMARY)
            clickAreas.add(ClickArea(bx, btnY, btnW, btnH, Runnable {
                val add = findSetting(group, "Add Pet")
                if (add is ActionSetting) {
                    add.execute()
                }
            }))
            currentRight = bx - gap
        }

        return currentRight
    }

    private fun currentPetTrackerGroupCount(): Long {
        if (activeSubTab != null) {
            return activeSubTab!!.groups().stream().filter { isPetTrackerGroup(it) }.count()
        }
        return groups().stream().filter { isPetTrackerGroup(it) }.count()
    }

    private fun hasActiveInlineEditor(): Boolean {
        return inlineTextEditor.hasActiveInlineEditor()
    }

    private fun showSearchResults(): Boolean {
        return searchQuery.length >= 2
    }

    private fun isSearchEditorActive(): Boolean {
        return inlineTextEditor.isSearchEditorActive()
    }

    private fun syncSearchQueryFromEditor() {
        inlineTextEditor.syncSearchQueryFromEditor()
    }

    private fun focusSearchField() {
        inlineTextEditor.focusSearchField()
    }

    private fun isPointInsideSearchField(mx: Float, my: Float): Boolean {
        return inlineTextEditor.isPointInsideSearchField(mx, my)
    }

    private fun updateSearchSelectionFromMouse(mx: Float) {
        inlineTextEditor.updateSearchSelectionFromMouse(mx)
    }

    private fun hasTextSelection(): Boolean {
        return inlineTextEditor.hasTextSelection()
    }

    private fun textSelectionStart(): Int {
        return inlineTextEditor.textSelectionStart()
    }

    private fun textSelectionEnd(): Int {
        return inlineTextEditor.textSelectionEnd()
    }

    private fun clearTextSelection() {
        inlineTextEditor.clearTextSelection()
    }

    private fun normalizeTextCursorState() {
        inlineTextEditor.normalizeTextCursorState()
    }

    private fun safeSubstring(value: String, start: Int, end: Int): String {
        return inlineTextEditor.safeSubstring(value, start, end)
    }

    private fun setTextCursor(cursor: Int, extendSelection: Boolean) {
        inlineTextEditor.setTextCursor(cursor, extendSelection)
    }

    internal fun selectAllText() {
        inlineTextEditor.selectAllText()
    }

    private fun deleteSelectedText(): Boolean {
        return inlineTextEditor.deleteSelectedText()
    }

    private fun insertTextAtCursor(text: String) {
        inlineTextEditor.insertTextAtCursor(text)
    }

    private fun updateInlineSelectionFromMouse(mx: Float) {
        inlineTextEditor.updateInlineSelectionFromMouse(mx)
    }

    private fun beginInlineCaretPress(mx: Float, my: Float) {
        inlineTextEditor.beginInlineCaretPress(mx, my)
    }

    private fun shouldBeginInlineSelectionDrag(mx: Float, my: Float): Boolean {
        return inlineTextEditor.shouldBeginInlineSelectionDrag(mx, my)
    }

    private fun setActiveEditorBounds(x: Float, y: Float, w: Float, h: Float) {
        inlineTextEditor.setActiveEditorBounds(x, y, w, h)
    }

    private fun isPointInsideActiveEditor(mx: Float, my: Float): Boolean {
        return inlineTextEditor.isPointInsideActiveEditor(mx, my)
    }

    // -- Render ----------------------------------------------------------------

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, dt: Float) {
        prepareFrameState(mx, my, dt)
        AetherRenderQueue.enqueue(Runnable { renderQueuedFrame() })
    }

    fun renderAfterGameRenderer(dt: Float) {
        if (renderedQueuedFrame) {
            renderedQueuedFrame = false
            return
        }
        if (!frameStatePrepared) {
            val client = Minecraft.getInstance()
            val mx = client.mouseHandler.getScaledXPos(client.getWindow()).toInt()
            val my = client.mouseHandler.getScaledYPos(client.getWindow()).toInt()
            prepareFrameState(mx, my, dt)
        }

        renderPreparedFrame(frameMouseX, frameMouseY, frameDeltaTime)
        frameStatePrepared = false
    }

    private fun renderQueuedFrame() {
        if (Minecraft.getInstance().gui.screen() !== this) {
            frameStatePrepared = false
            return
        }
        if (!frameStatePrepared) {
            val client = Minecraft.getInstance()
            val mx = client.mouseHandler.getScaledXPos(client.getWindow()).toInt()
            val my = client.mouseHandler.getScaledYPos(client.getWindow()).toInt()
            prepareFrameState(mx, my, frameDeltaTime)
        }

        renderPreparedFrame(frameMouseX, frameMouseY, frameDeltaTime)
        frameStatePrepared = false
        renderedQueuedFrame = true
    }

    private fun prepareFrameState(mx: Int, my: Int, dt: Float) {
        MainGUIRegistry.refresh()
        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init()
        }

        val prevPhysW = physW
        val prevPhysH = physH

        // Resolve pixel ratio so all coordinates are in physical pixels.
        // We do this before beginFrame so hover detection uses the right scale.
        try {
            val rt = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            pr = rt.width.toFloat() / this.width
        } catch (ignored: Exception) {
            pr = 1f
        }
        syncFrameLayoutForWindowSize(prevPhysW, prevPhysH)
        // Convert logical mouse coords to layout units (physical px / uiScale)
        val pmx = mx * pr / uiScale
        val pmy = my * pr / uiScale
        val nowNanos = System.nanoTime()
        val dtNanos = Math.max(0L, Math.min(100_000_000L, nowNanos - lastFrameTimeNanos))
        lastFrameTimeNanos = nowNanos

        val asFactor = Theme.animationFactor()
        val animT = Math.min(1f, asFactor * 2.5f)

        // Entrance animation
        alpha += (1f - alpha) * asFactor
        animScale += (1f - animScale) * (asFactor * 1.1f)
        animOffsetY += (0f - animOffsetY) * (asFactor * 1.1f)

        // Sidebar tab sliding (kept for compatibility, unused in new render)
        animMainSel += (activeMain - animMainSel) * animT
        animSubSel += (activeSubtab - animSubSel) * animT

        // Sidebar hover expand (physical coords)
        val expandedW = if (computedSidebarExpanded > 0f) computedSidebarExpanded else SIDEBAR_EXPANDED
        val sbCurW = SIDEBAR_W + sidebarAnim * (expandedW - SIDEBAR_W)
        val sbHov = pmx >= px && pmx < px + sbCurW && pmy >= py && pmy <= py + ph
        sidebarAnim += ((if (sbHov) 1f else 0f) - sidebarAnim) * Math.min(1f, asFactor * 0.5f)

        // Smooth scroll
        val scrollSpeed = scrollAnimStep(dtNanos)
        scrollY += (targetScrollY - scrollY) * scrollSpeed
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY))
        searchScrollY += (searchTargetScrollY - searchScrollY) * scrollSpeed
        searchScrollY = Math.max(0f, Math.min(searchMaxScrollY, searchScrollY))
        profileScrollY += (profileTargetScrollY - profileScrollY) * scrollSpeed
        profileScrollY = Math.max(0f, Math.min(profileMaxScrollY, profileScrollY))
        subtabScrollY += (subtabTargetScrollY - subtabScrollY) * scrollSpeed
        subtabScrollY = Math.max(0f, Math.min(subtabMaxScrollY, subtabScrollY))
        syncActiveBodyScrollState()

        // Dropdown fade-in
        if (openDd != null) {
            ddAnimAmt = Math.min(1f, ddAnimAmt + animT)
        }

        activeEditorBoundsSet = false
        frameMouseX = pmx
        frameMouseY = pmy
        frameDeltaTime = dt
        frameStatePrepared = true
    }

    private fun renderPreparedFrame(pmx: Float, pmy: Float, dt: Float) {
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer()!!
        try {
            // Scale NVG canvas: 1 unit = uiScale physical pixels.
            // At uiScale=1.0 everything is pixel-perfect; raise to enlarge,
            // lower to shrink the entire menu.
            nvg.save()
            nvg.scale(uiScale / pr, uiScale / pr)
            nvg.setTextScale(uiTextScale)

            val canvasW = physW / uiScale
            val canvasH = physH / uiScale
            nvg.rect(0f, 0f, canvasW, canvasH, Theme.withAlpha(0xFF000000.toInt(), (alpha * 170).toInt()))

            val scx = canvasW / 2f
            val scy = canvasH / 2f

            // Background: scale + slide + fade (zoom-in effect on the chrome)
            nvg.save()
            nvg.translate(scx, scy)
            nvg.scale(animScale, animScale)
            nvg.translate(-scx, -scy)
            nvg.translate(0f, animOffsetY)
            nvg.globalAlpha(alpha)
            nvg.shadow(px, py, pw, ph, RADIUS, 26f, Theme.withAlpha(0xFF000000.toInt(), 0.75f))
            nvg.roundedRect(px, py, pw, ph, RADIUS, Theme.PANEL_BG)
            nvg.rectOutline(px, py, pw, ph, RADIUS, 1f, Theme.BORDER_DEFAULT)
            nvg.restore()

            // Content: slide + fade only - no scale so text stays at its final
            // rendered size and doesn't jitter during the zoom-in animation
            nvg.save()
            nvg.translate(0f, animOffsetY)
            nvg.globalAlpha(alpha)
            renderPanel(nvg, pmx, pmy)
            nvg.restore() // entrance-animation transform

            nvg.restore() // physical-pixel scale

            // Gui.render() is suppressed while this screen is open, so we drive
            // the HUD fade-out here instead of keeping the HUD fully visible.
            renderOptionalFeatureHud(nvg, dt)
        } finally {
            NanoVGManager.endFrame()
        }
    }

    private fun renderPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        refreshContext()
        clickAreas.clear()
        hoveredColor = null
        hoverHelpCandidateKey = null
        hoverHelpCandidateTitle = null
        hoverHelpCandidateDescription = null
        transitionRenderer.syncContentTransition()
        transitionRenderer.renderContentWithTransition(nvg, mx, my)
        renderSidebar(nvg, mx, my)
        renderHoverHelp(nvg, mx, my)
    }

    private fun syncContentTransition() {
        transitionRenderer.syncContentTransition()
    }

    private fun renderContentWithTransition(nvg: NVGRenderer, mx: Float, my: Float) {
        transitionRenderer.renderContentWithTransition(nvg, mx, my)
    }

    // -- Sidebar ---------------------------------------------------------------

    private fun renderSidebar(nvg: NVGRenderer, mx: Float, my: Float) {
        chromeRenderer.renderSidebar(nvg, mx, my)
    }

    // -- Content top bar -------------------------------------------------------

    private fun renderContentTopBar(nvg: NVGRenderer, mx: Float, my: Float) {
        chromeRenderer.renderContentTopBar(nvg, mx, my)
    }

    // -- Filter bar ------------------------------------------------------------

    private fun renderFilterBar(nvg: NVGRenderer, mx: Float, my: Float) {
        chromeRenderer.renderFilterBar(nvg, mx, my)
    }

    // -- Content area ----------------------------------------------------------

    private fun renderContent(nvg: NVGRenderer, mx: Float, my: Float) {
        contentRenderer.renderContent(nvg, mx, my)
    }

    private fun renderModuleDetailView(nvg: NVGRenderer, mx: Float, my: Float) {
        contentRenderer.renderModuleDetailView(nvg, mx, my)
    }

    private fun renderModuleDetailBody(nvg: NVGRenderer, mx: Float, my: Float) {
        contentRenderer.renderModuleDetailBody(nvg, mx, my)
    }

    // -- Module card grid (View 1) ----------------------------------------------

    private fun renderModuleGrid(nvg: NVGRenderer, mx: Float, my: Float, scrollTop: Float, scrollH: Float) {
        moduleOverviewRenderer.render(nvg, mx, my, scrollTop, scrollH)
    }

    // -- Flat settings renderer (Colors + Settings tabs) -----------------------

    private fun renderFlatContent(nvg: NVGRenderer, mx: Float, my: Float) {
        flatPanelRenderer.render(nvg, mx, my)
    }

    private fun renderFlatGroupLabel(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        // Section name
        val nameCol = if (group.isEnabled() && !group.isAlwaysOn()) Theme.GROUP_ACTIVE else Theme.TEXT_MUTED
        nvg.text(Fonts.BOLD, group.getName(), x, y + (FLAT_LABEL_H - 10f) / 2f, 10f, nameCol)

        // Thin horizontal rule after the label
        val textW = nvg.textWidth(Fonts.BOLD, group.getName(), 10f)
        var lineRight = if (group.isAlwaysOn()) x + w else x + w - 50f
        if (isPetTrackerGroup(group)) {
            val buttonsLeft = renderPetTrackerHeaderButtons(nvg, group, x + w - 12f, y + (FLAT_LABEL_H - 22f) / 2f, 22f, mx, my)
            lineRight = Math.min(lineRight, buttonsLeft - 8f)
        }
        nvg.rect(x + textW + 8f, y + FLAT_LABEL_H / 2f, lineRight - (x + textW + 8f), 1f, Theme.SEPARATOR)

        // Toggle pill for non-alwaysOn groups
        if (!group.isAlwaysOn()) {
            renderPill(nvg, x + w - 38f, y + (FLAT_LABEL_H - PILL_H) / 2f, group.isEnabled(), group)
        }
    }

    internal fun renderSectionHeader(nvg: NVGRenderer, name: String, x: Float, y: Float, w: Float) {
        renderPrimitives.renderSectionHeader(nvg, name, x, y, w)
    }

    private fun moduleCardName(sub: ModulesTab.SubTab): String {
        return localize(sub.name())
    }

    private fun isSubTabEnabled(sub: ModulesTab.SubTab): Boolean {
        return sub.isEnabled()
    }

    private fun renderModuleCard(nvg: NVGRenderer, sub: ModulesTab.SubTab, x: Float, y: Float, w: Float, hov: Float, mx: Float, my: Float) {
        renderPrimitives.renderModuleCard(nvg, sub, x, y, w, hov, mx, my)
    }

    // -- Module detail view (View 2) -------------------------------------------

    private fun renderModuleTopBar(nvg: NVGRenderer, mx: Float, my: Float) {
        moduleDetailRenderer.renderTopBar(nvg, mx, my)
    }

    private fun renderModuleCategoryPanel(nvg: NVGRenderer, mx: Float, my: Float, panelTop: Float, panelH: Float) {
        moduleDetailRenderer.renderCategoryPanel(nvg, mx, my, panelTop, panelH)
    }

    private fun renderModuleSettingsPanel(nvg: NVGRenderer, mx: Float, my: Float, panelTop: Float, panelH: Float) {
        moduleDetailRenderer.renderSettingsPanel(nvg, mx, my, panelTop, panelH)
    }

    private fun subTabBarAnimValue(key: Any, active: Boolean): Float {
        val target = if (active) 1f else 0f
        var current = subTabBarAnim.getOrDefault(key, target)
        val animationFactor = Theme.animationFactor()
        current += (target - current) * Math.min(0.05f, animationFactor * 3f)
        subTabBarAnim.put(key, current)
        return current
    }

    private fun renderGroupHeader(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float, interactive: Boolean) {
        nvg.roundedRect(x, y, w, HEADER_H, 6f, Theme.BG_SECONDARY)

        val lerp = subTabBarAnimValue(group, (group.isEnabled() && !group.isAlwaysOn()) || (group.isAlwaysOn() && interactive))
        nvg.rectOutline(x, y, w, HEADER_H, 6f, 0.8f, Theme.blend(Theme.SEPARATOR, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.22f), lerp))

        val titleColor = if (interactive) Theme.TEXT_PRIMARY else Theme.withAlpha(Theme.TEXT_DIM, 210)
        val descColor = if (interactive) Theme.TEXT_SECONDARY else Theme.withAlpha(Theme.TEXT_DIM, 170)
        nvg.text(Fonts.BOLD, localize(group.getName()), x + 12f, y + 9f, 13f, titleColor)
        nvg.text(Fonts.REGULAR, localize(group.getDescription()), x + 12f, y + 26f, 10f, descColor)
        offerHoverHelp("group:" + group.getRawName(), group.getName(), group.getDescription(), x, y, w, HEADER_H, mx, my)

        if (isPetTrackerGroup(group)) {
            renderPetTrackerHeaderButtons(nvg, group, x + w - 12f, y + 12f, 22f, mx, my)
        }

        if (!group.isAlwaysOn() && !group.isEnabled() && forcedOverride.contains(group)) {
            nvg.text(Fonts.REGULAR, "\u25bc", x + w - 68f, y + (HEADER_H - 11f) / 2f, 9f, Theme.withAlpha(Theme.TEXT_SECONDARY, 150))
        }

        if (!group.isAlwaysOn()) {
            val pillX = x + w - 52f
            val pillY = y + (HEADER_H - PILL_H) / 2f
            renderPill(nvg, pillX, pillY, group.isEnabled(), group)
            if (interactive) {
                clickAreas.add(ClickArea(pillX - 6f, pillY - 6f, 48f, PILL_H + 12f, Runnable { group.toggle() }))
            }
        }

        if (!interactive) {
            nvg.roundedRect(x, y, w, HEADER_H, 6f, Theme.withAlpha(Theme.PANEL_BG, 0.34f))
        }
    }

    internal fun renderSettingRow(nvg: NVGRenderer, setting: Setting, x: Float, y: Float, w: Float, h: Float, mx: Float, my: Float) {
        settingRowRenderer.render(nvg, setting, x, y, w, h, mx, my)
    }

    private fun petTrackerGroupHeight(): Float {
        return PET_FIELD_H * 2f + PET_ROW_GAP
    }

    private fun renderPetTrackerGroup(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        petTrackerPanel.renderGroup(nvg, group, x, y, w, mx, my)
    }

    private fun handlePetTrackerGroupClick(group: SettingGroup, mx: Float, my: Float, x: Float, y: Float, w: Float): Boolean {
        return petTrackerPanel.handleGroupClick(group, mx, my, x, y, w)
    }

    private fun renderListActionButton(nvg: NVGRenderer, x: Float, y: Float, w: Float, label: String, hovered: Boolean, enabled: Boolean) {
        renderPrimitives.renderListActionButton(nvg, x, y, w, label, hovered, enabled)
    }

    // -- Toggle pill -----------------------------------------------------------

    private fun renderPill(nvg: NVGRenderer, x: Float, y: Float, on: Boolean, key: Any) {
        renderPrimitives.renderPill(nvg, x, y, on, key)
    }

    // -- Dropdown overlay ------------------------------------------------------

    private fun renderDropdownOverlay(nvg: NVGRenderer, mx: Float, my: Float) {
        overlayController.renderDropdownOverlay(nvg, mx, my)
    }

    // -- HSV Color picker overlay ----------------------------------------------

    private fun renderColorOverlay(nvg: NVGRenderer) {
        overlayController.renderColorOverlay(nvg)
    }

    // -- Helpers ---------------------------------------------------------------

    internal fun settingH(s: Setting, rowW: Float): Float {
        return textLayout.settingHeight(s, rowW)
    }

    private fun wrappedSettingLabelLineCount(setting: Setting, rowW: Float): Int {
        return textLayout.wrappedSettingLabelLineCount(setting, rowW)
    }

    private fun settingLabelFontSize(setting: Setting): Float {
        return textLayout.settingLabelFontSize(setting)
    }

    private fun settingLabelLineStep(setting: Setting): Float {
        return textLayout.settingLabelLineStep(setting)
    }

    private fun settingLabelMaxWidth(setting: Setting, rowW: Float): Float {
        return textLayout.settingLabelMaxWidth(setting, rowW)
    }

    private fun dropdownActionStripWidth(dropdown: DropdownSetting): Float {
        return textLayout.dropdownActionStripWidth(dropdown)
    }

    private fun dropdownActionStartX(dropdown: DropdownSetting, fieldX: Float): Float {
        return textLayout.dropdownActionStartX(dropdown, fieldX)
    }

    private fun dropdownActionIndexAt(dropdown: DropdownSetting, fieldX: Float, fieldY: Float, fieldH: Float, mx: Float, my: Float): Int {
        return textLayout.dropdownActionIndexAt(dropdown, fieldX, fieldY, fieldH, mx, my)
    }

    private fun renderDropdownActionButtons(nvg: NVGRenderer, dropdown: DropdownSetting, fieldX: Float, fieldY: Float, fieldH: Float, mx: Float, my: Float) {
        renderPrimitives.renderDropdownActionButtons(nvg, dropdown, fieldX, fieldY, fieldH, mx, my)
    }
    private fun textInlineFieldWidth(innerWidth: Float): Float {
        return textLayout.textInlineFieldWidth(innerWidth)
    }

    private fun inlineTextScrollOffset(nvg: NVGRenderer, font: String, text: String?, cursor: Int, fontSize: Float, visibleWidth: Float): Float {
        return textLayout.inlineTextScrollOffset(nvg, font, text, cursor, fontSize, visibleWidth)
    }

    internal fun inlineTextScrollOffsetApprox(text: String?, cursor: Int, fontSize: Float, visibleWidth: Float): Float {
        return textLayout.inlineTextScrollOffsetApprox(text, cursor, fontSize, visibleWidth)
    }

    private fun estimateWrappedLineCount(text: String?, maxWidth: Float, fontSize: Float): Int {
        return textLayout.estimateWrappedLineCount(text, maxWidth, fontSize)
    }

    private fun wrapSettingLabel(nvg: NVGRenderer, setting: Setting, rowW: Float): List<String> {
        return ArrayList(textLayout.wrapSettingLabel(nvg, setting, rowW))
    }

    internal fun wrapTextForWidth(nvg: NVGRenderer, text: String?, fontName: String, fontSize: Float, maxWidth: Float): List<String> {
        return ArrayList(textLayout.wrapTextToWidth(nvg, text, fontName, fontSize, maxWidth))
    }

    private fun fittingPrefixLength(nvg: NVGRenderer, text: String, fontName: String, fontSize: Float, maxWidth: Float): Int {
        return textLayout.fittingPrefixLength(nvg, text, fontName, fontSize, maxWidth)
    }

    private fun clampScroll(value: Float, maxScroll: Float): Float {
        return Math.max(0f, Math.min(maxScroll, value))
    }

    private fun scrollAnimStep(dtNanos: Long): Float {
        val dtMs = Math.max(0f, dtNanos.toFloat() / 1_000_000f)
        if (dtMs <= 0f) {
            return 0f
        }
        val durationMs = Math.max(1f, Theme.ANIM_TIME_MS)
        return 1f - Math.exp(-(dtMs.toDouble()) / (durationMs.toDouble() / 3.0)).toFloat()
    }

    internal fun scrollbarThumbY(trackT: Float, trackH: Float, thumbH: Float, scrollVal: Float, maxScroll: Float): Float {
        if (maxScroll <= 0f || trackH <= thumbH) {
            return trackT
        }
        val thumbY = trackT + (scrollVal / maxScroll) * (trackH - thumbH)
        return Math.max(trackT, Math.min(trackT + trackH - thumbH, thumbY))
    }

    private fun hitScrollbarTrack(mx: Float, my: Float, trackT: Float, trackH: Float, barX: Float): Boolean {
        return mx >= barX - 6f && mx <= barX + 10f && my >= trackT && my <= trackT + trackH
    }

    private fun hitScrollbarThumb(mx: Float, my: Float, trackT: Float, trackH: Float, thumbH: Float, scrollVal: Float, maxScroll: Float, barX: Float): Boolean {
        if (!hitScrollbarTrack(mx, my, trackT, trackH, barX)) {
            return false
        }
        val thumbY = scrollbarThumbY(trackT, trackH, thumbH, scrollVal, maxScroll)
        return my >= thumbY && my <= thumbY + thumbH
    }

    private fun setScrollbarScroll(dragMode: Int, value: Float, immediate: Boolean) {
        when (dragMode) {
            1 -> {
                targetScrollY = clampScroll(value, maxScrollY)
                if (immediate) {
                    scrollY = targetScrollY
                }
            }
            2 -> {
                searchTargetScrollY = clampScroll(value, searchMaxScrollY)
                if (immediate) {
                    searchScrollY = searchTargetScrollY
                }
            }
            3 -> {
                profileTargetScrollY = clampScroll(value, profileMaxScrollY)
                if (immediate) {
                    profileScrollY = profileTargetScrollY
                }
            }
            4 -> {
                subtabTargetScrollY = clampScroll(value, subtabMaxScrollY)
                if (immediate) {
                    subtabScrollY = subtabTargetScrollY
                }
            }
        }
    }

    private fun startScrollbarDrag(dragMode: Int, mx: Float, my: Float, trackT: Float, trackH: Float, thumbH: Float, scrollVal: Float, maxScroll: Float, barX: Float) {
        if (!hitScrollbarTrack(mx, my, trackT, trackH, barX) || maxScroll <= 0f) {
            return
        }
        sbDragging = dragMode
        val thumbY = scrollbarThumbY(trackT, trackH, thumbH, scrollVal, maxScroll)
        val thumbHit = hitScrollbarThumb(mx, my, trackT, trackH, thumbH, scrollVal, maxScroll, barX)
        sbDragThumbOffset = if (thumbHit) my - thumbY else thumbH * 0.5f
        updateScrollbarDrag(my, thumbHit)
    }

    private fun updateScrollbarDrag(my: Float, immediate: Boolean) {
        val trackT: Float
        val trackH: Float
        val thumbH: Float
        val maxScroll: Float
        when (sbDragging) {
            1 -> {
                trackT = sbMainTrackT
                trackH = sbMainTrackH
                thumbH = sbMainThumbH
                maxScroll = maxScrollY
            }
            2 -> {
                trackT = sbSrchTrackT
                trackH = sbSrchTrackH
                thumbH = sbSrchThumbH
                maxScroll = searchMaxScrollY
            }
            3 -> {
                trackT = sbProfTrackT
                trackH = sbProfTrackH
                thumbH = sbProfThumbH
                maxScroll = profileMaxScrollY
            }
            4 -> {
                trackT = sbSubTrackT
                trackH = sbSubTrackH
                thumbH = sbSubThumbH
                maxScroll = subtabMaxScrollY
            }
            else -> {
                return
            }
        }

        val range = trackH - thumbH
        if (range <= 0f || maxScroll <= 0f) {
            return
        }
        val thumbTop = Math.max(trackT, Math.min(trackT + range, my - sbDragThumbOffset))
        val scrollValue = ((thumbTop - trackT) / range) * maxScroll
        setScrollbarScroll(sbDragging, scrollValue, immediate)
    }

    private fun textSettingHeight(setting: TextSetting): Float {
        return inlineTextEditor.textSettingHeight(setting)
    }

    private fun textFieldHeight(setting: TextSetting): Float {
        return inlineTextEditor.textFieldHeight(setting)
    }

    private fun splitLines(text: String): Array<String> {
        return MainGUIInlineTextEditor.splitLines(text)
    }

    private fun getLineStart(text: String, cursor: Int): Int {
        return MainGUIInlineTextEditor.getLineStart(text, cursor)
    }

    private fun getLineEnd(text: String, cursor: Int): Int {
        return MainGUIInlineTextEditor.getLineEnd(text, cursor)
    }

    private fun getCursorLine(text: String, cursor: Int): Int {
        return MainGUIInlineTextEditor.getCursorLine(text, cursor)
    }

    private fun getCursorColumn(text: String, cursor: Int): Int {
        return MainGUIInlineTextEditor.getCursorColumn(text, cursor)
    }

    private fun moveCursorVertical(text: String, cursor: Int, delta: Int): Int {
        return MainGUIInlineTextEditor.moveCursorVertical(text, cursor, delta)
    }

    internal fun getMultilineViewportStart(setting: TextSetting, text: String, cursor: Int): Int {
        return inlineTextEditor.getMultilineViewportStart(setting, text, cursor)
    }

    private fun fmtSlider(ss: SliderSetting): String {
        val v = ss.getValue()
        val sf = ss.getSuffix() ?: ""
        return if (ss.getDecimals() == 0) v.toInt().toString() + sf else String.format("%.${ss.getDecimals()}f", v) + sf
    }

    private fun fmtRangeSlider(rs: RangeSliderSetting): String {
        val sf = rs.getSuffix() ?: ""
        if (rs.getDecimals() == 0) {
            return Math.round(rs.getLowerValue()).toString() + sf + " - " + Math.round(rs.getUpperValue()) + sf
        }
        return String.format("%.${rs.getDecimals()}f", rs.getLowerValue()) + sf + " - " + String.format("%.${rs.getDecimals()}f", rs.getUpperValue()) + sf
    }

    internal fun fmtSliderInput(ss: SliderSetting): String {
        val v = ss.getValue()
        return if (ss.getDecimals() == 0) Math.round(v).toString() else String.format(Locale.US, "%.${ss.getDecimals()}f", v)
    }

    // -- HSV <-> ARGB ------------------------------------------------------------

    internal fun argbToHsv(argb: Int) {
        overlayController.argbToHsv(argb)
    }

    private fun updateColorFromHsv() {
        overlayController.updateColorFromHsv()
    }

    // -- Mouse input -----------------------------------------------------------

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        return mouseController.mouseClicked(click, doubled)
    }

    private fun handleRightClick(mx: Float, my: Float) {
        if (mx < contX || mx > contX + contW || my < contY || my > contY + contH) {
            return
        }
        // Flat content tabs don't use right-click group toggling
        if (activeMain == 1 || activeMain >= 3) {
            return
        }

        val gx = contX + ITEM_PAD
        val gw = contW - ITEM_PAD * 2f
        var y2 = 0f

        for (group in groups()) {
            y2 += GROUP_GAP
            val headerSY = contY - scrollY + y2
            if (my >= headerSY && my <= headerSY + HEADER_H && mx >= gx && mx <= gx + gw) {
                if (group.isAlwaysOn()) {
                    return
                }
                if (forcedOverride.contains(group)) {
                    forcedOverride.remove(group)
                } else {
                    forcedOverride.add(group)
                }
                return
            }
            y2 += HEADER_H
            if (showChildren(group) && group.hasSettings()) {
                y2 += HEADER_TO_FIRST_SETTING_GAP
                if (isPetTrackerGroup(group)) {
                    y2 += petTrackerGroupHeight()
                } else {
                    for (s in group.getSettings()) {
                        if (s.isVisible()) {
                            y2 += settingH(s, gw)
                        }
                    }
                }
                y2 += 4f
            }
        }
    }

    private fun handleColorPickerClick(mx: Float, my: Float): Boolean {
        return overlayController.handleColorPickerClick(mx, my)
    }

    private fun handleColorPickerDrag(mx: Float, my: Float) {
        overlayController.handleColorPickerDrag(mx, my)
    }

    private fun handleDropdownClick(mx: Float, my: Float) {
        overlayController.handleDropdownClick(mx, my)
    }

    private fun handleContentClick(mx: Float, my: Float) {
        settingInteractionController.handleContentClick(mx, my)
    }

    private fun handleFlatContentClick(mx: Float, my: Float) {
        settingInteractionController.handleFlatContentClick(mx, my)
    }

    private fun handleModuleSettingsPanelClick(mx: Float, my: Float) {
        settingInteractionController.handleModuleSettingsPanelClick(mx, my)
    }

    private fun focusListItem(list: ListSetting, index: Int) {
        val values = list.getValues()
        if (index < 0 || index >= values.size) {
            return
        }
        activeList = list
        activeListIndex = index
        activeText = null
        textBuf = StringBuilder(values.get(index))
        textCursor = textBuf.length
        clearTextSelection()
    }

    private fun addListItem(list: ListSetting, insertIndex: Int) {
        val values = list.getValues()
        val target = Math.max(0, Math.min(insertIndex, values.size))
        values.add(target, "")
        list.setValues(values)
        focusListItem(list, target)
    }

    private fun removeListItem(list: ListSetting, index: Int) {
        val values = list.getValues()
        if (index < 0 || index >= values.size) {
            return
        }
        values.removeAt(index)
        list.setValues(values)
        if (activeList == list) {
            activeList = null
            activeListIndex = -1
        }
    }

    private fun focusSliderField(slider: SliderSetting) {
        activeSliderField = slider
        activeText = null
        activeList = null
        activeListIndex = -1
        activePosField = null
        textBuf = StringBuilder(fmtSliderInput(slider))
        textCursor = textBuf.length
        clearTextSelection()
    }

    internal fun handleSettingClick(setting: Setting, mx: Float, my: Float, x: Float, y: Float, w: Float, h: Float) {
        settingInteractionController.handleSettingClick(setting, mx, my, x, y, w, h)
    }

    private fun updateSlider(mx: Float) {
        settingInteractionController.updateSlider(mx)
    }

    private fun updateRangeSlider(mx: Float) {
        settingInteractionController.updateRangeSlider(mx)
    }

    override fun mouseDragged(click: MouseButtonEvent, dX: Double, dY: Double): Boolean {
        return mouseController.mouseDragged(click, dX, dY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        return mouseController.mouseReleased(click)
    }

    override fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean {
        return mouseController.mouseScrolled(mx, my, sx, sy)
    }

    // -- Keyboard input --------------------------------------------------------

    override fun keyPressed(input: KeyEvent): Boolean {
        if (keyboardController.keyPressed(input)) {
            return true
        }
        return super.keyPressed(input)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        return keyboardController.charTyped(input)
    }

    // -- Commit helpers --------------------------------------------------------

    internal fun commitText() {
        inlineTextEditor.commitText()
    }

    internal fun commitColor() {
        overlayController.commitColor()
    }

    // -- Search content --------------------------------------------------------

    private fun renderSearchContent(nvg: NVGRenderer, mx: Float, my: Float) {
        searchPanel.render(nvg, mx, my)
    }

    private fun handleSearchClick(mx: Float, my: Float) {
        searchPanel.handleClick(mx, my)
    }

    internal fun handleSearchClickEvent(mx: Float, my: Float) {
        handleSearchClick(mx, my)
    }

    // -- Profile content -------------------------------------------------------

    private fun renderProfileContent(nvg: NVGRenderer, mx: Float, my: Float) {
        profilesPanel.render(nvg, mx, my)
    }

    internal fun refreshContext() {
        activeFilter = normalizedActiveFilterIndex()
        context.layout.px = px
        context.layout.py = py
        context.layout.pw = pw
        context.layout.ph = ph
        context.layout.contX = contX
        context.layout.contY = contY
        context.layout.contW = contW
        context.layout.contH = contH
        context.layout.pr = pr
        context.layout.physW = physW
        context.layout.physH = physH
        context.layout.btnSearchX = btnSearchX
        context.layout.btnSearchY = btnSearchY
        context.layout.btnSearchW = btnSearchW
        context.layout.btnSearchH = btnSearchH
        context.layout.searchBoxX = searchBoxX
        context.layout.searchBoxY = searchBoxY
        context.layout.searchBoxW = searchBoxW
        context.layout.searchBoxH = searchBoxH
        context.layout.activeEditorX = activeEditorX
        context.layout.activeEditorY = activeEditorY
        context.layout.activeEditorW = activeEditorW
        context.layout.activeEditorH = activeEditorH

        context.navigation.activeMain = activeMain
        context.navigation.activeSubtab = activeSubtab
        context.navigation.activeFilter = activeFilter
        context.navigation.activeSubTab = activeSubTab
        context.navigation.activeCategoryIdx = activeCategoryIdx
        context.navigation.searchMode = searchMode
        context.navigation.searchQuery = searchQuery
        context.navigation.profileNameInput = profileNameInput
        context.navigation.profileNameFocus = profileNameFocus
        context.navigation.profileNameTarget = profileNameTarget
        context.navigation.profileRenameInput = profileRenameInput
        context.navigation.profileRenameOriginal = profileRenameOriginal
        context.navigation.profileRenameFocus = profileRenameFocus
        context.navigation.profileRenameTarget = profileRenameTarget
        context.navigation.suppressNestedContentScissor = suppressNestedContentScissor

        context.scroll.scrollY = scrollY
        context.scroll.targetScrollY = targetScrollY
        context.scroll.maxScrollY = maxScrollY
        context.scroll.modulesOverviewScrollY = modulesOverviewScrollY
        context.scroll.modulesOverviewTargetScrollY = modulesOverviewTargetScrollY
        context.scroll.moduleDetailScrollY = moduleDetailScrollY
        context.scroll.moduleDetailTargetScrollY = moduleDetailTargetScrollY
        context.scroll.searchScrollY = searchScrollY
        context.scroll.searchTargetScrollY = searchTargetScrollY
        context.scroll.searchMaxScrollY = searchMaxScrollY
        context.scroll.profileScrollY = profileScrollY
        context.scroll.profileTargetScrollY = profileTargetScrollY
        context.scroll.profileMaxScrollY = profileMaxScrollY
        context.scroll.subtabScrollY = subtabScrollY
        context.scroll.subtabTargetScrollY = subtabTargetScrollY
        context.scroll.subtabMaxScrollY = subtabMaxScrollY
        context.scroll.sbMainTrackT = sbMainTrackT
        context.scroll.sbMainTrackH = sbMainTrackH
        context.scroll.sbMainThumbH = sbMainThumbH
        context.scroll.sbSrchTrackT = sbSrchTrackT
        context.scroll.sbSrchTrackH = sbSrchTrackH
        context.scroll.sbSrchThumbH = sbSrchThumbH
        context.scroll.sbProfTrackT = sbProfTrackT
        context.scroll.sbProfTrackH = sbProfTrackH
        context.scroll.sbProfThumbH = sbProfThumbH
        context.scroll.sbSubTrackT = sbSubTrackT
        context.scroll.sbSubTrackH = sbSubTrackH
        context.scroll.sbSubThumbH = sbSubThumbH
        context.scroll.lastFrameTimeNanos = lastFrameTimeNanos

        context.animation.alpha = alpha
        context.animation.animScale = animScale
        context.animation.animOffsetY = animOffsetY
        context.animation.sidebarAnim = sidebarAnim
        context.animation.computedSidebarExpanded = computedSidebarExpanded
        context.animation.animMainSel = animMainSel
        context.animation.animSubSel = animSubSel
        context.animation.filterBarAnimX = filterBarAnimX
        context.animation.filterBarAnimW = filterBarAnimW
        context.animation.filterBarTargetX = filterBarTargetX
        context.animation.filterBarTargetW = filterBarTargetW
        context.animation.filterBarInited = filterBarInited
        context.animation.ddAnimAmt = ddAnimAmt

        context.editor.activeSliderField = activeSliderField
        context.editor.activeText = activeText
        context.editor.activeList = activeList
        context.editor.activeListIndex = activeListIndex
        context.editor.activePosField = activePosField
        context.editor.activePosIdx = activePosIdx
        context.editor.textBuffer = textBuf.toString()
        context.editor.textCursor = textCursor
        context.editor.textSelectionAnchor = textSelectionAnchor
        context.editor.textSelectionDragging = textSelectionDragging
        context.editor.textSelectionPendingDrag = textSelectionPendingDrag
        context.editor.textSelectionPressCursor = textSelectionPressCursor
        context.editor.textSelectionPressX = textSelectionPressX
        context.editor.textSelectionPressY = textSelectionPressY
        context.editor.activeEditorBoundsSet = activeEditorBoundsSet
        context.editor.cpHexFocus = cpHexFocus
        context.editor.cpHexBuffer = cpHexBuf.toString()

        context.overlay.openDropdown = openDd
        context.overlay.dropdownX = ddX
        context.overlay.dropdownY = ddY
        context.overlay.dropdownW = ddW
        context.overlay.dropdownButtonH = ddButtonH
        context.overlay.hoveredColor = hoveredColor
        context.overlay.activeColor = activeColor
        context.overlay.cpHue = cpHue
        context.overlay.cpSat = cpSat
        context.overlay.cpVal = cpVal
        context.overlay.cpAlpha = cpAlpha
        context.overlay.cpDrag = cpDrag
        context.overlay.cpPX = cpPX
        context.overlay.cpPY = cpPY
        context.overlay.cpPW = cpPW
        context.overlay.cpPH = cpPH
        context.overlay.cpSvX = cpSvX
        context.overlay.cpSvY = cpSvY
        context.overlay.cpSvW = cpSvW
        context.overlay.cpSvH = cpSvH
        context.overlay.cpHBarY = cpHBarY
        context.overlay.cpABarY = cpABarY
        context.overlay.cpBarH = cpBarH
        context.overlay.cpHexX = cpHexX
        context.overlay.cpHexY = cpHexY
        context.overlay.cpHexW = cpHexW
        context.overlay.cpHexH = cpHexH

        context.interaction.scrollbarDragging = sbDragging
        context.interaction.scrollbarDragThumbOffset = sbDragThumbOffset
        context.interaction.dragSetting = dragSetting
        context.interaction.dragRangeSetting = dragRangeSetting
        context.interaction.dragTrackX = dragTrackX
        context.interaction.dragTrackW = dragTrackW
        context.interaction.dragMin = dragMin
        context.interaction.dragMax = dragMax
        context.interaction.dragRangeHandle = dragRangeHandle
        context.interaction.panelDragging = panelDragging
        context.interaction.dragPanelOffX = dragPanelOffX
        context.interaction.dragPanelOffY = dragPanelOffY
        context.interaction.forcedOverride = forcedOverride
    }

    internal fun context(): MainGUIContext {
        return actions.context()
    }

    internal fun getActiveMainTab(): Int {
        return activeMain
    }

    internal fun getActiveModuleSubTab(): ModulesTab.SubTab? {
        return activeSubTab
    }

    internal fun getActiveScrollY(): Float {
        return scrollY
    }

    internal fun getActiveTargetScrollY(): Float {
        return targetScrollY
    }

    internal fun getModulesOverviewScrollY(): Float {
        return modulesOverviewScrollY
    }

    internal fun getModulesOverviewTargetScrollY(): Float {
        return modulesOverviewTargetScrollY
    }

    internal fun getModuleDetailScrollY(): Float {
        return moduleDetailScrollY
    }

    internal fun getModuleDetailTargetScrollY(): Float {
        return moduleDetailTargetScrollY
    }

    internal fun isShowingSearchResults(): Boolean {
        return showSearchResults()
    }

    internal fun setActiveScroll(scrollY: Float, targetScrollY: Float) {
        this.scrollY = scrollY
        this.targetScrollY = targetScrollY
    }

    internal fun setModulesOverviewScroll(scrollY: Float, targetScrollY: Float) {
        modulesOverviewScrollY = scrollY
        modulesOverviewTargetScrollY = targetScrollY
    }

    internal fun setModuleDetailScroll(scrollY: Float, targetScrollY: Float) {
        moduleDetailScrollY = scrollY
        moduleDetailTargetScrollY = targetScrollY
    }

    internal fun setActiveModuleSubTab(subTab: ModulesTab.SubTab?) {
        activeSubTab = subTab
    }

    internal fun setActiveCategoryIndex(categoryIndex: Int) {
        activeCategoryIdx = categoryIndex
    }

    internal fun setActiveMainTab(mainTab: Int) {
        activeMain = mainTab
    }

    internal fun setActiveSubtabIndex(subtabIndex: Int) {
        activeSubtab = subtabIndex
    }

    internal fun setProfileScroll(scrollY: Float, targetScrollY: Float) {
        profileScrollY = scrollY
        profileTargetScrollY = targetScrollY
    }

    internal fun setSearchScroll(scrollY: Float, targetScrollY: Float) {
        searchScrollY = scrollY
        searchTargetScrollY = targetScrollY
    }

    internal fun setSearchMode(searchMode: Boolean) {
        this.searchMode = searchMode
    }

    internal fun setSearchQuery(searchQuery: String) {
        this.searchQuery = searchQuery
    }

    internal fun setActiveFilterIndex(filterIndex: Int) {
        activeFilter = filterIndex
    }

    internal fun setFilterBarInitialized(initialized: Boolean) {
        filterBarInited = initialized
    }

    internal fun clearInlineTextSelection() {
        clearTextSelection()
    }

    internal fun setComputedSidebarExpanded(width: Float) {
        computedSidebarExpanded = width
    }

    internal fun currentTabName(): String {
        val idx = Math.max(0, Math.min(activeMain, TAB_NAMES.size - 1))
        return localize(TAB_NAMES[idx])
    }

    internal fun currentTabDescription(): String {
        val idx = Math.max(0, Math.min(activeMain, TAB_DESCS.size - 1))
        return localize(TAB_DESCS[idx])
    }

    internal fun currentTabFilters(): Array<String> {
        return filtersForMainTab(activeMain)
    }

    private fun normalizedActiveFilterIndex(): Int {
        val filters = filtersForMainTab(activeMain)
        if (filters.isEmpty()) {
            return 0
        }
        return Math.max(0, Math.min(activeFilter, filters.size - 1))
    }

    private fun filtersForMainTab(mainTab: Int): Array<String> {
        return when (mainTab) {
            0 -> moduleFilterOptions()
            1 -> localizedStrings(COLOR_FILTERS)
            2 -> localizedStrings(CONFIG_FILTERS)
            3, 4 -> localizedStrings(SINGLE_FILTER)
            else -> localizedStrings(SINGLE_FILTER)
        }
    }

    private fun localizedStrings(values: Array<String>): Array<String> {
        val localized = ArrayList<String>(values.size)
        for (value in values) {
            localized.add(localize(value))
        }
        return localized.toTypedArray()
    }

    private fun moduleFilterOptions(): Array<String> {
        val sections = availableModuleSections()
        if (sections.isEmpty()) {
            return SINGLE_FILTER
        }
        if (sections.size == 1) {
            return arrayOf(localize(sections[0].name()))
        }

        val options = ArrayList<String>(sections.size + 1)
        options.add(localize("All"))
        for (section in sections) {
            options.add(localize(section.name()))
        }
        return options.toTypedArray()
    }

    internal fun setSearchBoxBounds(x: Float, y: Float, w: Float, h: Float) {
        searchBoxX = x
        searchBoxY = y
        searchBoxW = w
        searchBoxH = h
        refreshContext()
    }

    private fun applyLaunchTargetIfNeeded() {
        if (launchTargetApplied || launchTarget == null) {
            return
        }

        launchTargetApplied = true
        activeMain = Math.max(0, launchTarget!!.mainTab())
        activeFilter = 0
        activeSubtab = 0

        if (!launchTarget!!.openModuleDetail() || launchTarget!!.moduleName() == null || activeMain != 0) {
            return
        }

        val targetSubTab = findModuleSubTabByName(launchTarget!!.moduleName()) ?: return

        activeSubTab = targetSubTab
        activeCategoryIdx = 0
        scrollY = 0f
        targetScrollY = 0f
        modulesOverviewScrollY = 0f
        modulesOverviewTargetScrollY = 0f
        moduleDetailScrollY = 0f
        moduleDetailTargetScrollY = 0f
    }

    private fun findModuleSubTabByName(name: String): ModulesTab.SubTab? {
        if (name.isBlank()) {
            return null
        }

        for (subTab in MainGUIRegistry.MODULE_SUBTABS) {
            if (subTab.name().equals(name, ignoreCase = true) || localize(subTab.name()).equals(name, ignoreCase = true)) {
                return subTab
            }
        }
        return null
    }

    internal fun isSearchEditorActiveState(): Boolean {
        return isSearchEditorActive()
    }

    internal fun currentTextBuffer(): String {
        return textBuf.toString()
    }

    internal fun currentTextCursor(): Int {
        return textCursor
    }

    internal fun textHasSelection(): Boolean {
        return hasTextSelection()
    }

    internal fun currentTextSelectionStart(): Int {
        return textSelectionStart()
    }

    internal fun currentTextSelectionEnd(): Int {
        return textSelectionEnd()
    }

    internal fun safeTextSlice(value: String, start: Int, end: Int): String {
        return safeSubstring(value, start, end)
    }

    internal fun inlineTextScrollOffsetFor(nvg: NVGRenderer, font: String, text: String?, cursor: Int, fontSize: Float, visibleWidth: Float): Float {
        return inlineTextScrollOffset(nvg, font, text, cursor, fontSize, visibleWidth)
    }

    internal fun activateSearchField() {
        if (!searchMode) {
            commitText()
            commitColor()
            openDd = null
            focusSearchField()
        }
    }

    internal fun setFilterBarTarget(x: Float, w: Float) {
        filterBarTargetX = x
        filterBarTargetW = w
        refreshContext()
    }

    internal fun setFilterBarAnimation(x: Float, w: Float) {
        filterBarAnimX = x
        filterBarAnimW = w
        refreshContext()
    }

    internal fun onFilterSelected(filterIndex: Int) {
        activeFilter = Math.max(0, Math.min(filterIndex, currentTabFilters().size - 1))
        resetActiveContentScroll()
    }

    internal fun searchResultsVisible(): Boolean {
        return showSearchResults()
    }

    internal fun hasOpenDropdown(): Boolean {
        return openDd != null
    }

    internal fun hasActiveColorOverlay(): Boolean {
        return activeColor != null
    }

    internal fun renderSearchContentPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderSearchContent(nvg, mx, my)
    }

    internal fun renderProfileContentPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderProfileContent(nvg, mx, my)
    }

    internal fun renderContentPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderContent(nvg, mx, my)
    }

    internal fun renderContentTopBarPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderContentTopBar(nvg, mx, my)
    }

    internal fun renderFilterBarPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderFilterBar(nvg, mx, my)
    }

    internal fun renderModuleGridPanel(nvg: NVGRenderer, mx: Float, my: Float, scrollTop: Float, scrollH: Float) {
        renderModuleGrid(nvg, mx, my, scrollTop, scrollH)
    }

    internal fun renderFlatContentPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderFlatContent(nvg, mx, my)
    }

    internal fun renderDropdownOverlayPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderDropdownOverlay(nvg, mx, my)
    }

    internal fun renderColorOverlayPanel(nvg: NVGRenderer) {
        renderColorOverlay(nvg)
    }

    internal fun renderModuleTopBarPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderModuleTopBar(nvg, mx, my)
    }

    internal fun renderModuleDetailBodyPanel(nvg: NVGRenderer, mx: Float, my: Float) {
        renderModuleDetailBody(nvg, mx, my)
    }

    internal fun renderModuleCategoryPanelSection(nvg: NVGRenderer, mx: Float, my: Float, panelTop: Float, panelH: Float) {
        renderModuleCategoryPanel(nvg, mx, my, panelTop, panelH)
    }

    internal fun renderModuleSettingsPanelSection(nvg: NVGRenderer, mx: Float, my: Float, panelTop: Float, panelH: Float) {
        renderModuleSettingsPanel(nvg, mx, my, panelTop, panelH)
    }

    internal fun moduleSectionsForFilter(): List<MainGUIModuleSection> {
        val result = ArrayList<MainGUIModuleSection>()
        for (section in sectionsForFilter()) {
            result.add(MainGUIModuleSection(localize(section.name()), section.subtabs()))
        }
        return result
    }

    internal fun moduleCardHoverProgress(subTab: ModulesTab.SubTab): Float {
        return cardHoverAnim.getOrDefault(subTab, 0f)
    }

    internal fun setModuleCardHoverProgress(subTab: ModulesTab.SubTab, progress: Float) {
        cardHoverAnim.put(subTab, progress)
    }

    internal fun renderModuleCardPanel(nvg: NVGRenderer, subTab: ModulesTab.SubTab, x: Float, y: Float, w: Float, hover: Float, mx: Float, my: Float) {
        renderModuleCard(nvg, subTab, x, y, w, hover, mx, my)
    }

    internal fun flatSubtabsForCurrentFilter(): List<ModulesTab.SubTab> {
        return flatSubtabsForFilter()
    }

    internal fun renderFlatGroupLabelPanel(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        renderFlatGroupLabel(nvg, group, x, y, w, mx, my)
    }

    internal fun shouldShowChildren(group: SettingGroup): Boolean {
        return showChildren(group)
    }

    internal fun isPetTrackerSettingsGroup(group: SettingGroup): Boolean {
        return isPetTrackerGroup(group)
    }

    internal fun petTrackerGroupHeightValue(): Float {
        return petTrackerGroupHeight()
    }

    internal fun renderPetTrackerGroupPanel(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        renderPetTrackerGroup(nvg, group, x, y, w, mx, my)
    }

    internal fun settingHeightFor(setting: Setting, width: Float): Float {
        return settingH(setting, width)
    }

    internal fun moduleSettingsWidth(): Float {
        val rightW = contW - MODULE_CAT_W - 1f
        return rightW - ITEM_PAD * 2f
    }

    internal fun moduleSettingsHeight(): Float {
        return contH - TOP_BAR_H - 1f - MOD_HEADER_H - 1f
    }

    internal fun renderMainScrollbar(nvg: NVGRenderer, totalContentHeight: Float, scrollTop: Float, scrollH: Float, barX: Float) {
        maxScrollY = Math.max(0f, totalContentHeight - scrollH + 16f)
        targetScrollY = Math.max(0f, Math.min(maxScrollY, targetScrollY))
        if (maxScrollY <= 0f) {
            return
        }
        val ratio = scrollH / (totalContentHeight + 16f)
        val thumbH = Math.max(30f, scrollH * ratio)
        val trackT = scrollTop + 2f
        val trackH = scrollH - 4f
        sbMainTrackT = trackT
        sbMainTrackH = trackH
        sbMainThumbH = thumbH
        val thumbY = scrollbarThumbY(trackT, trackH, thumbH, scrollY, maxScrollY)
        nvg.roundedRect(barX, thumbY, 4f, thumbH, 2f, Theme.withAlpha(Theme.TEXT_SECONDARY, 0.5f))
    }

    internal fun moduleCardNameFor(subTab: ModulesTab.SubTab): String {
        return moduleCardName(subTab)
    }

    internal fun isSubTabEnabledFor(subTab: ModulesTab.SubTab): Boolean {
        return isSubTabEnabled(subTab)
    }

    internal fun exitModuleDetailView() {
        exitModuleDetail()
    }

    internal fun getActiveCategoryIndex(): Int {
        return activeCategoryIdx
    }

    internal fun moduleCategoryAnimationKey(group: SettingGroup, isAll: Boolean): Any {
        return if (isAll) this else group
    }

    internal fun moduleCategoryHoverProgress(key: Any?): Float {
        return catHoverAnim.getOrDefault(key, 0f)
    }

    internal fun setModuleCategoryHoverProgress(key: Any?, progress: Float) {
        catHoverAnim.put(key, progress)
    }

    internal fun selectModuleCategory(categoryIndex: Int) {
        activeCategoryIdx = categoryIndex
        scrollY = 0f
        targetScrollY = 0f
    }

    internal fun renderGroupHeaderPanel(nvg: NVGRenderer, group: SettingGroup, x: Float, y: Float, w: Float, mx: Float, my: Float, interactive: Boolean) {
        renderGroupHeader(nvg, group, x, y, w, mx, my, interactive)
    }

    internal fun renderPillControl(nvg: NVGRenderer, x: Float, y: Float, on: Boolean, key: Any) {
        renderPill(nvg, x, y, on, key)
    }

    internal fun wrapSettingLabelForRow(nvg: NVGRenderer, setting: Setting, rowW: Float): List<String> {
        return wrapSettingLabel(nvg, setting, rowW)
    }

    internal fun settingLabelFontSizeForRow(setting: Setting): Float {
        return settingLabelFontSize(setting)
    }

    internal fun settingLabelLineStepForRow(setting: Setting): Float {
        return settingLabelLineStep(setting)
    }

    internal fun wrappedSettingLabelLineCountForRow(setting: Setting, rowW: Float): Int {
        return wrappedSettingLabelLineCount(setting, rowW)
    }

    internal fun isActiveSliderEditor(setting: SliderSetting): Boolean {
        return activeSliderField == setting
    }

    internal fun isActiveTextEditor(setting: Setting): Boolean {
        return activeText == setting
    }

    internal fun isActiveListEditor(setting: ListSetting, index: Int): Boolean {
        return activeList == setting && activeListIndex == index
    }

    internal fun isOpenDropdown(setting: DropdownSetting): Boolean {
        return openDd == setting
    }

    internal fun isActiveColorSetting(setting: ColorSetting): Boolean {
        return activeColor == setting
    }

    internal fun isActivePositionEditor(setting: PositionSetting, index: Int): Boolean {
        return activePosField == setting && activePosIdx == index
    }

    internal fun setHoveredColorSetting(setting: ColorSetting?) {
        hoveredColor = setting
    }

    internal fun setDropdownOverlayBounds(x: Float, y: Float, w: Float, buttonH: Float) {
        ddX = x
        ddY = y
        ddW = w
        ddButtonH = buttonH
    }

    internal fun setEditorBounds(x: Float, y: Float, w: Float, h: Float) {
        setActiveEditorBounds(x, y, w, h)
    }

    internal fun formatSliderValue(setting: SliderSetting): String {
        return fmtSlider(setting)
    }

    internal fun formatRangeSliderValue(setting: RangeSliderSetting): String {
        return fmtRangeSlider(setting)
    }

    internal fun renderDropdownActionButtonsControl(nvg: NVGRenderer, dropdown: DropdownSetting, fieldX: Float, fieldY: Float, fieldH: Float, mx: Float, my: Float) {
        renderDropdownActionButtons(nvg, dropdown, fieldX, fieldY, fieldH, mx, my)
    }

    internal fun renderListActionButtonControl(nvg: NVGRenderer, x: Float, y: Float, w: Float, label: String, hovered: Boolean, enabled: Boolean) {
        renderListActionButton(nvg, x, y, w, label, hovered, enabled)
    }

    internal fun textInlineFieldWidthForRow(innerWidth: Float): Float {
        return textInlineFieldWidth(innerWidth)
    }

    internal fun inlineTextScrollOffsetApproxFor(text: String?, cursor: Int, fontSize: Float, visibleWidth: Float): Float {
        return inlineTextScrollOffsetApprox(text, cursor, fontSize, visibleWidth)
    }

    internal fun clickAreaCount(): Int {
        return clickAreas.size
    }

    private fun syncFrameLayoutForWindowSize(prevPhysW: Float, prevPhysH: Float) {
        // Apply the persisted GUI scale, but hold it steady while a slider is being dragged: the
        // UI Scale slider lives inside this panel, so rescaling mid-drag would shift the slider's
        // own coordinate space under the cursor and run the value away to max.
        if (dragSetting == null && dragRangeSetting == null) {
            uiScale = Theme.UI_SCALE
            uiTextScale = Theme.TEXT_SCALE
        }
        val sizeChanged = context.layout.lastWidth != width || context.layout.lastHeight != height || Math.abs(context.layout.lastPixelRatio - pr) > 0.001f || Math.abs(context.layout.lastUiScale - uiScale) > 0.001f
        if (!sizeChanged) {
            physW = width * pr
            physH = height * pr
            return
        }
        applyFrameLayout(false, prevPhysW, prevPhysH)
    }

    private fun applyFrameLayout(forceCenter: Boolean, prevPhysW: Float, prevPhysH: Float) {
        val frameLayout = layoutHelper.computeFrameLayout(width, height, pr, uiScale, SIDEBAR_W)
        val oldPx = px
        val oldPy = py
        val oldPw = pw
        val oldPh = ph

        pw = frameLayout.pw
        ph = frameLayout.ph
        physW = frameLayout.physW
        physH = frameLayout.physH

        if (forceCenter || oldPw <= 0f || oldPh <= 0f || prevPhysW <= 0f || prevPhysH <= 0f) {
            px = frameLayout.px
            py = frameLayout.py
        } else {
            val oldCanvasW = prevPhysW / uiScale
            val oldCanvasH = prevPhysH / uiScale
            val centeredX = (oldCanvasW - oldPw) / 2f
            val centeredY = (oldCanvasH - oldPh) / 2f
            val wasCentered = Math.abs(oldPx - centeredX) <= 0.5f && Math.abs(oldPy - centeredY) <= 0.5f
            if (wasCentered) {
                px = frameLayout.px
                py = frameLayout.py
            } else {
                val canvasW = physW / uiScale
                val canvasH = physH / uiScale
                px = Math.max(0f, Math.min(canvasW - pw, oldPx))
                py = Math.max(0f, Math.min(canvasH - ph, oldPy))
            }
        }

        contX = px + SIDEBAR_W
        contY = py
        contW = pw - SIDEBAR_W
        contH = ph
        context.layout.lastWidth = width
        context.layout.lastHeight = height
        context.layout.lastPixelRatio = pr
        context.layout.lastUiScale = uiScale
        refreshContext()
    }

    internal fun trimClickAreas(clickCount: Int) {
        while (clickAreas.size > clickCount) {
            clickAreas.removeAt(clickAreas.size - 1)
        }
    }

    internal fun clearTransitionOverlaysForPassiveRender() {
        openDd = null
        activeColor = null
    }

    internal fun setSuppressNestedContentScissorState(suppress: Boolean) {
        suppressNestedContentScissor = suppress
    }

    internal fun captureTransientRenderState(): MainGUITransientRenderState {
        val state = MainGUITransientRenderState()
        state.hoveredColor = hoveredColor
        state.activeEditorBoundsSet = activeEditorBoundsSet
        state.filterBarAnimX = filterBarAnimX
        state.filterBarAnimW = filterBarAnimW
        state.filterBarTargetX = filterBarTargetX
        state.filterBarTargetW = filterBarTargetW
        state.filterBarInited = filterBarInited
        state.maxScrollY = maxScrollY
        state.searchMaxScrollY = searchMaxScrollY
        state.profileMaxScrollY = profileMaxScrollY
        state.subtabMaxScrollY = subtabMaxScrollY
        state.sbMainTrackT = sbMainTrackT
        state.sbMainTrackH = sbMainTrackH
        state.sbMainThumbH = sbMainThumbH
        state.sbSrchTrackT = sbSrchTrackT
        state.sbSrchTrackH = sbSrchTrackH
        state.sbSrchThumbH = sbSrchThumbH
        state.sbProfTrackT = sbProfTrackT
        state.sbProfTrackH = sbProfTrackH
        state.sbProfThumbH = sbProfThumbH
        state.sbSubTrackT = sbSubTrackT
        state.sbSubTrackH = sbSubTrackH
        state.sbSubThumbH = sbSubThumbH
        state.openDropdown = openDd
        state.activeColor = activeColor
        state.suppressNestedContentScissor = suppressNestedContentScissor
        state.scrollY = scrollY
        state.targetScrollY = targetScrollY
        return state
    }

    internal fun restoreTransitionRenderState(state: MainGUITransientRenderState, interactive: Boolean, restoreScroll: Boolean) {
        if (!interactive) {
            hoveredColor = state.hoveredColor
            activeEditorBoundsSet = state.activeEditorBoundsSet
            filterBarAnimX = state.filterBarAnimX
            filterBarAnimW = state.filterBarAnimW
            filterBarTargetX = state.filterBarTargetX
            filterBarTargetW = state.filterBarTargetW
            filterBarInited = state.filterBarInited
            maxScrollY = state.maxScrollY
            searchMaxScrollY = state.searchMaxScrollY
            profileMaxScrollY = state.profileMaxScrollY
            subtabMaxScrollY = state.subtabMaxScrollY
            sbMainTrackT = state.sbMainTrackT
            sbMainTrackH = state.sbMainTrackH
            sbMainThumbH = state.sbMainThumbH
            sbSrchTrackT = state.sbSrchTrackT
            sbSrchTrackH = state.sbSrchTrackH
            sbSrchThumbH = state.sbSrchThumbH
            sbProfTrackT = state.sbProfTrackT
            sbProfTrackH = state.sbProfTrackH
            sbProfThumbH = state.sbProfThumbH
            sbSubTrackT = state.sbSubTrackT
            sbSubTrackH = state.sbSubTrackH
            sbSubThumbH = state.sbSubThumbH
        }
        openDd = state.openDropdown
        activeColor = state.activeColor
        suppressNestedContentScissor = state.suppressNestedContentScissor
        if (restoreScroll) {
            scrollY = state.scrollY
            targetScrollY = state.targetScrollY
        }
    }

    internal fun contentX(): Float {
        return contX
    }

    internal fun contentY(): Float {
        return contY
    }

    internal fun contentW(): Float {
        return contW
    }

    internal fun contentH(): Float {
        return contH
    }

    internal fun activeGroupsForInteraction(): List<SettingGroup> {
        return groups()
    }

    internal fun activeModuleClickGroups(): List<SettingGroup> {
        if (activeSubTab == null) {
            return listOf()
        }
        val maxCatIdx = activeSubTab!!.groups().size
        if (activeCategoryIdx > maxCatIdx) {
            activeCategoryIdx = 0
        }
        val subs = activeSubTab!!.groups()
        return if (activeCategoryIdx == 0) ArrayList(subs) else listOf(subs[activeCategoryIdx - 1])
    }

    internal fun isActiveModuleSubtabEnabled(): Boolean {
        return activeSubTab != null && isSubTabEnabled(activeSubTab!!)
    }

    internal fun handlePetTrackerGroupInteraction(group: SettingGroup, mx: Float, my: Float, x: Float, y: Float, w: Float): Boolean {
        return handlePetTrackerGroupClick(group, mx, my, x, y, w)
    }

    internal fun focusListEditorItem(list: ListSetting, index: Int) {
        focusListItem(list, index)
    }

    internal fun insertListEditorItem(list: ListSetting, insertIndex: Int) {
        addListItem(list, insertIndex)
    }

    internal fun deleteListEditorItem(list: ListSetting, index: Int) {
        removeListItem(list, index)
    }

    internal fun focusSliderEditorField(slider: SliderSetting) {
        focusSliderField(slider)
    }

    internal fun activateTextSettingEditor(setting: TextSetting) {
        activeText = setting
        textBuf = StringBuilder(setting.getValue())
        textCursor = textBuf.length
        clearTextSelection()
    }

    internal fun focusPositionEditorField(setting: PositionSetting, index: Int, value: Double, mx: Float, my: Float) {
        activePosField = setting
        activePosIdx = index
        textBuf = StringBuilder(String.format("%.1f", value))
        textCursor = textBuf.length
        clearTextSelection()
        beginInlineCaretPress(mx, my)
    }

    internal fun beginInlineFieldCaretPress(x: Float, y: Float, w: Float, h: Float, value: String, fontSize: Float, startX: Float, mx: Float, my: Float) {
        setActiveEditorBounds(x, y, w, h)
        textCursor = MainGUIInlineTextEditor.cursorFromInlineText(value, fontSize, startX, mx)
        clearTextSelection()
        beginInlineCaretPress(mx, my)
    }

    internal fun dropdownActionIndexFor(dropdown: DropdownSetting, fieldX: Float, fieldY: Float, fieldH: Float, mx: Float, my: Float): Int {
        return dropdownActionIndexAt(dropdown, fieldX, fieldY, fieldH, mx, my)
    }

    internal fun openDropdownOverlayFor(dropdown: DropdownSetting, x: Float, y: Float, w: Float, buttonH: Float) {
        openDd = dropdown
        ddAnimAmt = 0f
        ddX = x
        ddY = y
        ddW = w
        ddButtonH = buttonH
    }

    internal fun activateColorSetting(setting: ColorSetting) {
        activeColor = setting
        argbToHsv(setting.getValue())
        cpHexFocus = false
        cpHexBuf.setLength(0)
        cpDrag = 0
    }

    internal fun startSliderDrag(setting: SliderSetting, trackX: Float, trackW: Float) {
        activeSliderField = null
        dragSetting = setting
        dragTrackX = trackX
        dragTrackW = trackW
        dragMin = setting.getMin()
        dragMax = setting.getMax()
    }

    internal fun startRangeSliderDrag(setting: RangeSliderSetting, trackX: Float, trackW: Float, mx: Float) {
        activeSliderField = null
        dragSetting = null
        dragRangeSetting = setting
        dragTrackX = trackX
        dragTrackW = trackW
        dragMin = setting.getMin()
        dragMax = setting.getMax()
        val lowerT = (setting.getLowerValue() - setting.getMin()) / (setting.getMax() - setting.getMin())
        val upperT = (setting.getUpperValue() - setting.getMin()) / (setting.getMax() - setting.getMin())
        val lowerX = trackX + trackW * lowerT
        val upperX = trackX + trackW * upperT
        dragRangeHandle = if (Math.abs(mx - lowerX) <= Math.abs(mx - upperX)) 1 else 2
    }

    internal fun hasDraggedSliderSetting(): Boolean {
        return dragSetting != null
    }

    internal fun hasDraggedRangeSliderSetting(): Boolean {
        return dragRangeSetting != null
    }

    internal fun applySliderDragValue(mx: Float) {
        if (dragSetting == null) {
            return
        }
        val t = Math.max(0f, Math.min(1f, (mx - dragTrackX) / dragTrackW))
        dragSetting!!.setValue(dragMin + t * (dragMax - dragMin))
    }

    internal fun applyRangeSliderDragValue(mx: Float) {
        if (dragRangeSetting == null || dragRangeHandle == 0) {
            return
        }
        val t = Math.max(0f, Math.min(1f, (mx - dragTrackX) / dragTrackW))
        val value = dragMin + t * (dragMax - dragMin)
        if (dragRangeHandle == 1) {
            dragRangeSetting!!.setLowerValue(value)
        } else {
            dragRangeSetting!!.setUpperValue(value)
        }
    }

    internal fun scaleMouseX(rawX: Double): Float {
        return rawX.toFloat() * pr / uiScale
    }

    internal fun scaleMouseY(rawY: Double): Float {
        return rawY.toFloat() * pr / uiScale
    }

    internal fun handleRightClickEvent(mx: Float, my: Float) {
        handleRightClick(mx, my)
    }

    internal fun handleColorPickerClickEvent(mx: Float, my: Float): Boolean {
        return handleColorPickerClick(mx, my)
    }

    internal fun handleDropdownClickEvent(mx: Float, my: Float) {
        handleDropdownClick(mx, my)
    }

    internal fun hasInlineEditorFocus(): Boolean {
        return hasActiveInlineEditor()
    }

    internal fun isPointInsideActiveEditorState(mx: Float, my: Float): Boolean {
        return isPointInsideActiveEditor(mx, my)
    }

    internal fun isOutsidePanel(mx: Float, my: Float): Boolean {
        return mx < px || mx > px + pw || my < py || my > py + ph
    }

    internal fun handleOutsidePanelClick() {
        if (searchMode) {
            actions.clearSearch()
        }
        commitText()
    }

    internal fun tryStartContentScrollbarDrag(mx: Float, my: Float): Boolean {
        val sbX = contX + contW - 6f
        if (showSearchResults() && searchMaxScrollY > 0f && hitScrollbarTrack(mx, my, sbSrchTrackT, sbSrchTrackH, sbX)) {
            startScrollbarDrag(2, mx, my, sbSrchTrackT, sbSrchTrackH, sbSrchThumbH, searchScrollY, searchMaxScrollY, sbX)
            return true
        }
        if (!showSearchResults() && maxScrollY > 0f && hitScrollbarTrack(mx, my, sbMainTrackT, sbMainTrackH, sbX)) {
            startScrollbarDrag(1, mx, my, sbMainTrackT, sbMainTrackH, sbMainThumbH, scrollY, maxScrollY, sbX)
            return true
        }
        return false
    }

    internal fun tryStartSubtabScrollbarDrag(mx: Float, my: Float): Boolean {
        val subSbX = px + SIDEBAR_W - 5f
        if (subtabMaxScrollY > 0f && hitScrollbarTrack(mx, my, sbSubTrackT, sbSubTrackH, subSbX)) {
            startScrollbarDrag(4, mx, my, sbSubTrackT, sbSubTrackH, sbSubThumbH, subtabScrollY, subtabMaxScrollY, subSbX)
            return true
        }
        return false
    }

    internal fun isSearchButtonHit(mx: Float, my: Float): Boolean {
        return mx >= btnSearchX && mx <= btnSearchX + btnSearchW && my >= btnSearchY && my <= btnSearchY + btnSearchH
    }

    internal fun handleSearchButtonClick() {
        searchMode = !searchMode
        if (!searchMode) {
            actions.clearSearch()
        } else {
            commitText()
            commitColor()
            openDd = null
            focusSearchField()
        }
    }

    internal fun isPointInsideSearchFieldState(mx: Float, my: Float): Boolean {
        return isPointInsideSearchField(mx, my)
    }

    internal fun handleSearchFieldClick(mx: Float, my: Float) {
        if (!searchMode) {
            commitText()
            commitColor()
            openDd = null
            focusSearchField()
        } else if (!isSearchEditorActive()) {
            focusSearchField()
        }
        val textPad = 6f
        val fontSize = 11f
        val visibleTextW = searchBoxW - 30f - textPad * 2f
        val textOffset = inlineTextScrollOffsetApprox(textBuf.toString(), textCursor, fontSize, visibleTextW)
        val startX = searchBoxX + 30f + textPad - textOffset
        textCursor = cursorFromInlineText(textBuf.toString(), fontSize, startX, mx)
        clearTextSelection()
        beginInlineCaretPress(mx, my)
    }

    internal fun collapseSearchIfNeeded() {
        if (searchMode) {
            searchMode = false
            clearTextSelection()
            normalizeTextCursorState()
        }
    }

    internal fun tryBeginPanelDrag(mx: Float, my: Float): Boolean {
        if (my >= py && my <= py + SB_LOGO_H && mx >= px && mx <= px + SIDEBAR_W) {
            panelDragging = true
            dragPanelOffX = mx - px
            dragPanelOffY = my - py
            return true
        }
        return false
    }

    internal fun setLoginButtonBounds(x: Float, y: Float, w: Float, h: Float) {
        loginButtonX = x
        loginButtonY = y
        loginButtonW = w
        loginButtonH = h
    }

    internal fun clearLoginButtonBounds() {
        loginButtonW = 0f
        loginButtonH = 0f
    }

    internal fun tryHandleSidebarNavigationClick(mx: Float, my: Float, minecraft: Minecraft): Boolean {
        if (loginButtonW > 0f && mx >= loginButtonX && mx <= loginButtonX + loginButtonW && my >= loginButtonY && my <= loginButtonY + loginButtonH) {
            AetherAuthService.beginLogin()
            return true
        }
        val sbClickW = SIDEBAR_W + sidebarAnim * ((if (computedSidebarExpanded > 0f) computedSidebarExpanded else SIDEBAR_EXPANDED) - SIDEBAR_W)
        val tabsStartY = py + SB_LOGO_H + SB_SEP_GAP
        for (i in 0 until 3) {
            val tabY = tabsStartY + i * 44f
            if (mx >= px && mx <= px + sbClickW && my >= tabY && my <= tabY + 44f) {
                actions.switchMainTab(i)
                return true
            }
        }
        val profileClickY = py + ph - SB_BOT_PAD - 44f
        val settClickY = profileClickY - 44f - SB_SEP_GAP
        val keybindsClickY = settClickY - 44f
        val hudPosClickY = keybindsClickY - 44f
        if (mx >= px && mx <= px + sbClickW && my >= hudPosClickY && my <= hudPosClickY + 44f) {
            openOptionalHudEditor(minecraft)
            return true
        }
        if (mx >= px && mx <= px + sbClickW && my >= keybindsClickY && my <= keybindsClickY + 44f) {
            actions.switchMainTab(3)
            return true
        }
        if (mx >= px && mx <= px + sbClickW && my >= settClickY && my <= settClickY + 44f) {
            actions.switchMainTab(4)
            return true
        }
        return false
    }

    internal fun isSidebarExpandedOverContent(mx: Float, my: Float): Boolean {
        val sbClickW = SIDEBAR_W + sidebarAnim * ((if (computedSidebarExpanded > 0f) computedSidebarExpanded else SIDEBAR_EXPANDED) - SIDEBAR_W)
        return sidebarAnim > 0.01f && mx >= px && mx < px + sbClickW && my >= py && my <= py + ph
    }

    private fun renderOptionalFeatureHud(nvg: NVGRenderer, dt: Float) {
        AetherBootstrapHooks.renderConfigScreenOverlay(nvg, width.toFloat(), height.toFloat(), dt)
        NotificationRenderer.render(nvg, width.toFloat(), height.toFloat())
    }

    private fun openOptionalHudEditor(minecraft: Minecraft) {
        val screen = AetherBootstrapHooks.maybeCreateHudEditScreen()
        if (screen != null) {
            minecraft.gui.setScreen(screen)
        }
    }

    internal fun runClickAreas(mx: Float, my: Float): Boolean {
        for (area in clickAreas) {
            if (mx >= area.x() && mx <= area.x() + area.w() && my >= area.y() && my <= area.y() + area.h()) {
                area.action().run()
                return true
            }
        }
        return false
    }

    internal fun handleContentAreaClick(mx: Float, my: Float) {
        settingInteractionController.handleContentAreaClick(mx, my)
    }

    internal fun dragPanel(mx: Float, my: Float): Boolean {
        if (!panelDragging) {
            return false
        }
        px = Math.max(0f, Math.min(physW / uiScale - pw, mx - dragPanelOffX))
        py = Math.max(0f, Math.min(physH / uiScale - ph, my - dragPanelOffY))
        contX = px + SIDEBAR_W
        contY = py
        return true
    }

    internal fun dragSlider(mx: Float): Boolean {
        return settingInteractionController.dragSlider(mx)
    }

    internal fun dragRangeSlider(mx: Float): Boolean {
        return settingInteractionController.dragRangeSlider(mx)
    }

    internal fun dragActiveColorOverlay(mx: Float, my: Float): Boolean {
        if (activeColor == null || cpDrag == 0) {
            return false
        }
        handleColorPickerDrag(mx, my)
        return true
    }

    internal fun updateActiveTextSelection(mx: Float): Boolean {
        if (!(hasActiveInlineEditor() || isSearchEditorActive()) || !textSelectionDragging) {
            return false
        }
        if (isSearchEditorActive()) {
            updateSearchSelectionFromMouse(mx)
            syncSearchQueryFromEditor()
        } else {
            updateInlineSelectionFromMouse(mx)
        }
        return true
    }

    internal fun tryBeginActiveTextSelection(button: Int, mx: Float, my: Float): Boolean {
        if (!(hasActiveInlineEditor() || isSearchEditorActive())) {
            return false
        }
        if (button != 0) {
            return false
        }
        val inside = (hasActiveInlineEditor() && isPointInsideActiveEditor(mx, my)) || (isSearchEditorActive() && isPointInsideSearchField(mx, my))
        if (!inside || !shouldBeginInlineSelectionDrag(mx, my)) {
            return false
        }
        textSelectionDragging = true
        textSelectionPendingDrag = false
        textSelectionAnchor = Math.max(0, Math.min(textSelectionPressCursor, textBuf.length))
        if (isSearchEditorActive()) {
            updateSearchSelectionFromMouse(mx)
            syncSearchQueryFromEditor()
        } else {
            updateInlineSelectionFromMouse(mx)
        }
        return true
    }

    internal fun dragScrollbar(my: Float): Boolean {
        if (sbDragging == 0) {
            return false
        }
        updateScrollbarDrag(my, true)
        return true
    }

    internal fun finishMouseInteractions() {
        dragSetting = null
        dragRangeSetting = null
        dragRangeHandle = 0
        panelDragging = false
        cpDrag = 0
        sbDragging = 0
        sbDragThumbOffset = 0f
        textSelectionDragging = false
        textSelectionPendingDrag = false
        textSelectionPressCursor = -1
        normalizeTextCursorState()
    }

    internal fun handleMouseScroll(mx: Float, my: Float, scrollYDelta: Float): Boolean {
        if (mx < contX || mx > contX + contW || my < contY || my > contY + contH) {
            return false
        }
        val delta = scrollYDelta * 20f
        if (showSearchResults()) {
            searchTargetScrollY = Math.max(0f, Math.min(searchMaxScrollY, searchTargetScrollY - delta))
        } else {
            targetScrollY = Math.max(0f, Math.min(maxScrollY, targetScrollY - delta))
            syncActiveBodyScrollState()
        }
        return true
    }

    internal fun handleEscapeKey(): Boolean {
        if (activeKeybindCapture != null) {
            activeKeybindCapture = null
            return true
        }
        if (searchMode || searchQuery.isNotEmpty()) {
            actions.clearSearch()
            return true
        }
        if (profileRenameFocus) {
            cancelProfileRename()
            return true
        }
        if (profileNameFocus) {
            profileNameFocus = false
            return true
        }
        if (activeSubTab != null) {
            exitModuleDetail()
            return true
        }
        commitText()
        commitColor()
        if (openDd != null) {
            openDd = null
            return true
        }
        super.onClose()
        return true
    }

    internal fun handleInlineControlShortcut(key: Int): Boolean {
        return inlineTextEditor.handleControlShortcut(key)
    }

    internal fun handleSearchEditorKey(key: Int, ctrl: Boolean, shift: Boolean): Boolean {
        return inlineTextEditor.handleSearchEditorKeyPressed(key, ctrl, shift)
    }

    internal fun handleProfileNameKey(key: Int): Boolean {
        return inlineTextEditor.handleProfileNameKeyPressed(key)
    }

    internal fun handleInlineEditorKey(key: Int, ctrl: Boolean, shift: Boolean): Boolean {
        return inlineTextEditor.handleInlineEditorKeyPressed(key, ctrl, shift)
    }

    internal fun handleOverlayHexKey(key: Int): Boolean {
        return overlayController.handleHexKeyPressed(key)
    }

    internal fun handleInlineCharTyped(ch: Char): Boolean {
        if (activeKeybindCapture != null) {
            return true
        }
        return inlineTextEditor.handleCharTyped(ch)
    }

    internal fun isAwaitingKeybindCapture(setting: KeybindSetting): Boolean {
        return activeKeybindCapture == setting
    }

    internal fun beginKeybindCapture(setting: KeybindSetting) {
        activeKeybindCapture = setting
    }

    internal fun clearKeybindCapture() {
        activeKeybindCapture = null
    }

    internal fun isAnyKeybindCaptureActive(): Boolean {
        return activeKeybindCapture != null
    }

    internal fun handleKeybindCapture(input: KeyEvent): Boolean {
        if (activeKeybindCapture == null) {
            return false
        }
        val setting = activeKeybindCapture!!
        activeKeybindCapture = null
        if (input.key() == InputConstants.KEY_ESCAPE) {
            return true
        }
        if (input.key() == InputConstants.KEY_DELETE || input.key() == InputConstants.KEY_BACKSPACE) {
            setting.clearBinding()
            return true
        }
        setting.setBoundKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(input.key()))
        return true
    }

    internal fun handleKeybindMouseButtonCapture(button: Int): Boolean {
        if (activeKeybindCapture == null) {
            return false
        }
        if (button == 0) {
            activeKeybindCapture = null
            return false
        }
        val setting = activeKeybindCapture!!
        activeKeybindCapture = null
        setting.setBoundKey(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(button))
        return true
    }

    internal fun handleOverlayHexChar(ch: Char): Boolean {
        return overlayController.handleHexChar(ch)
    }

    internal fun panelX(): Float {
        return px
    }

    internal fun panelY(): Float {
        return py
    }

    internal fun panelW(): Float {
        return pw
    }

    internal fun panelH(): Float {
        return ph
    }

    internal fun currentOpenDropdown(): DropdownSetting? {
        return openDd
    }

    internal fun closeOpenDropdown() {
        openDd = null
    }

    internal fun dropdownOverlayX(): Float {
        return ddX
    }

    internal fun dropdownOverlayY(): Float {
        return ddY
    }

    internal fun dropdownOverlayW(): Float {
        return ddW
    }

    internal fun dropdownButtonHeight(): Float {
        return ddButtonH
    }

    internal fun dropdownAnimAmount(): Float {
        return ddAnimAmt
    }

    internal fun activeColorSetting(): ColorSetting? {
        return activeColor
    }

    internal fun clearActiveColorSetting() {
        activeColor = null
    }

    internal fun colorHue(): Float {
        return cpHue
    }

    internal fun setColorHue(hue: Float) {
        cpHue = hue
    }

    internal fun colorSaturation(): Float {
        return cpSat
    }

    internal fun setColorSaturation(saturation: Float) {
        cpSat = saturation
    }

    internal fun colorValue(): Float {
        return cpVal
    }

    internal fun setColorValue(value: Float) {
        cpVal = value
    }

    internal fun colorAlpha(): Float {
        return cpAlpha
    }

    internal fun setColorAlpha(alpha: Float) {
        cpAlpha = alpha
    }

    internal fun colorDragMode(): Int {
        return cpDrag
    }

    internal fun setColorDragMode(dragMode: Int) {
        cpDrag = dragMode
    }

    internal fun isColorHexFocused(): Boolean {
        return cpHexFocus
    }

    internal fun setColorHexFocused(focused: Boolean) {
        cpHexFocus = focused
    }

    internal fun colorHexBuffer(): StringBuilder {
        return cpHexBuf
    }

    internal fun setColorPickerPanelBounds(x: Float, y: Float, w: Float, h: Float) {
        cpPX = x
        cpPY = y
        cpPW = w
        cpPH = h
    }

    internal fun colorPickerPanelX(): Float {
        return cpPX
    }

    internal fun colorPickerPanelY(): Float {
        return cpPY
    }

    internal fun colorPickerPanelW(): Float {
        return cpPW
    }

    internal fun colorPickerPanelH(): Float {
        return cpPH
    }

    internal fun setColorPickerSvBounds(x: Float, y: Float, w: Float, h: Float) {
        cpSvX = x
        cpSvY = y
        cpSvW = w
        cpSvH = h
    }

    internal fun colorPickerSvX(): Float {
        return cpSvX
    }

    internal fun colorPickerSvY(): Float {
        return cpSvY
    }

    internal fun colorPickerSvW(): Float {
        return cpSvW
    }

    internal fun colorPickerSvH(): Float {
        return cpSvH
    }

    internal fun setColorPickerBarBounds(hueY: Float, alphaY: Float, barH: Float) {
        cpHBarY = hueY
        cpABarY = alphaY
        cpBarH = barH
    }

    internal fun colorPickerHueBarY(): Float {
        return cpHBarY
    }

    internal fun colorPickerAlphaBarY(): Float {
        return cpABarY
    }

    internal fun colorPickerBarH(): Float {
        return cpBarH
    }

    internal fun setColorPickerHexBounds(x: Float, y: Float, w: Float, h: Float) {
        cpHexX = x
        cpHexY = y
        cpHexW = w
        cpHexH = h
    }

    internal fun colorPickerHexX(): Float {
        return cpHexX
    }

    internal fun colorPickerHexY(): Float {
        return cpHexY
    }

    internal fun colorPickerHexW(): Float {
        return cpHexW
    }

    internal fun colorPickerHexH(): Float {
        return cpHexH
    }
}
