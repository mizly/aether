package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme

internal class MainGUITransitionRenderer(private val owner: MainGUI) {
    fun syncContentTransition() {
        val transition = owner.context().transition
        val current = owner.snapshotContentState()
        if (transition.toState == null) {
            transition.toState = current
            return
        }
        val previous = transition.toState ?: return
        if (!previous.equals(current)) {
            transition.fromState = previous
            transition.toState = current
            transition.startNanos = System.nanoTime()
        }
    }

    fun renderContentWithTransition(nvg: NVGRenderer, mx: Float, my: Float) {
        val transition = owner.context().transition
        val fromState = transition.fromState
        val toState = transition.toState
        if (fromState == null || toState == null) {
            owner.renderContentPanel(nvg, mx, my)
            return
        }

        val durationMs = Math.max(1f, Theme.ANIM_TIME_MS)
        val elapsedMs = Math.max(0f, (System.nanoTime() - transition.startNanos) / 1_000_000f)
        val rawT = Math.max(0f, Math.min(1f, elapsedMs / durationMs))
        val t = rawT * rawT * (3f - 2f * rawT)
        val strongEaseT = rawT * rawT * rawT * (rawT * (rawT * 6f - 15f) + 10f)

        if (rawT >= 1f) {
            transition.fromState = null
            owner.renderContentPanel(nvg, mx, my)
            return
        }

        val outgoingOffsetY = -22f * t
        val incomingOffsetY = 22f * (1f - t)
        val clipInset = 1f
        val fullClipX = owner.contentX() + clipInset
        val fullClipY = owner.contentY() + clipInset
        val fullClipW = Math.max(0f, owner.contentW() - clipInset * 2f)
        val fullClipH = Math.max(0f, owner.contentH() - clipInset * 2f)

        val fromModuleDetail = isModuleDetailState(fromState)
        val toModuleDetail = isModuleDetailState(toState)
        val categoryOnlyTransition = isCategoryOnlyTransition(fromState, toState)
        val filterOnlyTransition = isFilterOnlyTransition(fromState, toState)

        if (categoryOnlyTransition) {
            renderModuleDetailTopBarForState(nvg, mx, my, toState, true)

            val bodyClipX = owner.contentX() + clipInset
            val bodyClipY = owner.contentY() + MainGUI.TOP_BAR_H + 1f + clipInset
            val bodyClipW = Math.max(0f, owner.contentW() - clipInset * 2f)
            val bodyClipH = Math.max(0f, owner.contentH() - MainGUI.TOP_BAR_H - 1f - clipInset * 2f)

            nvg.save()
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH)
            renderContentForState(nvg, mx, my, fromState, false, true, true)
            nvg.popScissor()
            nvg.restore()

            nvg.save()
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH)
            nvg.globalAlpha(t)
            renderContentForState(nvg, mx, my, toState, true, true, true)
            nvg.popScissor()
            nvg.restore()
            return
        }

        if (filterOnlyTransition) {
            val bodyClipX = owner.contentX() + clipInset
            val bodyClipY = owner.contentY() + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f + clipInset
            val bodyClipW = Math.max(0f, owner.contentW() - clipInset * 2f)
            val bodyClipH = Math.max(0f, owner.contentH() - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f - clipInset * 2f)
            val headerClipX = owner.contentX() + clipInset
            val headerClipY = owner.contentY() + clipInset
            val headerClipW = Math.max(0f, owner.contentW() - clipInset * 2f)
            val headerClipH = Math.max(0f, bodyClipY - owner.contentY() - clipInset)
            var direction = Integer.compare(toState.activeFilter(), fromState.activeFilter())
            if (direction == 0) {
                direction = 1
            }
            val bodySlideX = owner.contentW() * direction

            nvg.save()
            nvg.pushScissor(headerClipX, headerClipY, headerClipW, headerClipH)
            owner.renderContentTopBarPanel(nvg, mx, my)
            owner.renderFilterBarPanel(nvg, mx, my)
            nvg.popScissor()
            nvg.restore()

            nvg.save()
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH)
            nvg.translate(-bodySlideX * strongEaseT, 0f)
            renderFilterBodyForState(nvg, mx, my, fromState, false)
            nvg.popScissor()
            nvg.restore()

            nvg.save()
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH)
            nvg.translate(bodySlideX * (1f - strongEaseT), 0f)
            renderFilterBodyForState(nvg, mx, my, toState, true)
            nvg.popScissor()
            nvg.restore()
            return
        }

        if (fromModuleDetail || toModuleDetail) {
            val bodyClipX = owner.contentX() + clipInset
            val bodyClipY = owner.contentY() + MainGUI.TOP_BAR_H + 1f + clipInset
            val bodyClipW = Math.max(0f, owner.contentW() - clipInset * 2f)
            val bodyClipH = Math.max(0f, owner.contentH() - MainGUI.TOP_BAR_H - 1f - clipInset * 2f)
            val headerClipX = owner.contentX() + clipInset
            val headerClipY = owner.contentY() + clipInset
            val headerClipW = Math.max(0f, owner.contentW() - clipInset * 2f)
            val headerClipH = Math.max(0f, bodyClipY - owner.contentY() - clipInset)
            val direction = if (toModuleDetail) 1 else -1
            val bodySlideX = owner.contentW() * direction

            nvg.save()
            nvg.pushScissor(headerClipX, headerClipY, headerClipW, headerClipH)
            nvg.translate(bodySlideX * strongEaseT, 0f)
            if (fromModuleDetail) {
                renderModuleDetailTopBarForState(nvg, mx, my, fromState, false)
            } else {
                renderContentTopBarForState(nvg, mx, my, fromState, false)
            }
            nvg.popScissor()
            nvg.restore()

            nvg.save()
            nvg.pushScissor(headerClipX, headerClipY, headerClipW, headerClipH)
            nvg.translate(-bodySlideX * (1f - strongEaseT), 0f)
            if (toModuleDetail) {
                renderModuleDetailTopBarForState(nvg, mx, my, toState, true)
            } else {
                renderContentTopBarForState(nvg, mx, my, toState, true)
            }
            nvg.popScissor()
            nvg.restore()

            nvg.save()
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH)
            nvg.translate(bodySlideX * strongEaseT, 0f)
            if (fromModuleDetail) {
                renderModuleDetailBodyForState(nvg, mx, my, fromState, false)
            } else {
                renderModulesOverviewBodyForState(nvg, mx, my, fromState, false)
            }
            nvg.popScissor()
            nvg.restore()

            nvg.save()
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH)
            nvg.translate(-bodySlideX * (1f - strongEaseT), 0f)
            if (toModuleDetail) {
                renderModuleDetailBodyForState(nvg, mx, my, toState, true)
            } else {
                renderModulesOverviewBodyForState(nvg, mx, my, toState, true)
            }
            nvg.popScissor()
            nvg.restore()
            return
        }

        nvg.save()
        nvg.pushScissor(fullClipX, fullClipY, fullClipW, fullClipH)
        nvg.translate(0f, outgoingOffsetY)
        nvg.globalAlpha(1f - t)
        renderContentForState(nvg, mx, my, fromState, false, false, true)
        nvg.popScissor()
        nvg.restore()

        nvg.save()
        nvg.pushScissor(fullClipX, fullClipY, fullClipW, fullClipH)
        nvg.translate(0f, incomingOffsetY)
        nvg.globalAlpha(t)
        renderContentForState(nvg, mx, my, toState, true, false, true)
        nvg.popScissor()
        nvg.restore()
    }

    private fun renderContentForState(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        state: MainGUIContentViewState,
        interactive: Boolean,
        moduleBodyOnly: Boolean,
        suppressNestedScissor: Boolean
    ) {
        val savedState = owner.snapshotContentState()
        val clickCount = owner.clickAreaCount()
        val transientState = owner.captureTransientRenderState()

        owner.applyContentState(state)
        owner.setSuppressNestedContentScissorState(suppressNestedScissor)
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender()
        }

        if (moduleBodyOnly) {
            owner.renderModuleDetailBodyPanel(nvg, mx, my)
        } else {
            owner.renderContentPanel(nvg, mx, my)
        }

        if (!interactive) {
            owner.trimClickAreas(clickCount)
        }
        owner.restoreTransitionRenderState(transientState, interactive, false)
        owner.applyContentState(savedState)
    }

    private fun renderContentTopBarForState(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        state: MainGUIContentViewState,
        interactive: Boolean
    ) {
        val savedState = owner.snapshotContentState()
        val clickCount = owner.clickAreaCount()
        val transientState = owner.captureTransientRenderState()

        owner.applyContentState(state)
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender()
        }

        owner.renderContentTopBarPanel(nvg, mx, my)

        if (!interactive) {
            owner.trimClickAreas(clickCount)
        }
        owner.restoreTransitionRenderState(transientState, interactive, false)
        owner.applyContentState(savedState)
    }

    private fun renderModuleDetailTopBarForState(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        state: MainGUIContentViewState,
        interactive: Boolean
    ) {
        val savedState = owner.snapshotContentState()
        val clickCount = owner.clickAreaCount()
        val transientState = owner.captureTransientRenderState()

        owner.applyContentState(state)
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender()
        }

        owner.renderModuleTopBarPanel(nvg, mx, my)

        if (!interactive) {
            owner.trimClickAreas(clickCount)
        }
        owner.restoreTransitionRenderState(transientState, interactive, false)
        owner.applyContentState(savedState)
    }

    private fun renderModuleDetailBodyForState(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        state: MainGUIContentViewState,
        interactive: Boolean
    ) {
        val transientState = owner.captureTransientRenderState()
        owner.setActiveScroll(owner.getModuleDetailScrollY(), owner.getModuleDetailTargetScrollY())
        renderContentForState(nvg, mx, my, state, interactive, true, true)
        owner.restoreTransitionRenderState(transientState, interactive, true)
    }

    private fun renderModulesOverviewBodyForState(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        state: MainGUIContentViewState,
        interactive: Boolean
    ) {
        val savedState = owner.snapshotContentState()
        val clickCount = owner.clickAreaCount()
        val transientState = owner.captureTransientRenderState()

        owner.applyContentState(state)
        owner.setSuppressNestedContentScissorState(true)
        owner.setActiveScroll(owner.getModulesOverviewScrollY(), owner.getModulesOverviewTargetScrollY())
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender()
        }

        if (owner.searchResultsVisible()) {
            owner.renderSearchContentPanel(nvg, mx, my)
        } else {
            owner.renderFilterBarPanel(nvg, mx, my)
            val scrollTop = owner.contentY() + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f
            val scrollH = owner.contentH() - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f
            owner.renderModuleGridPanel(nvg, mx, my, scrollTop, scrollH)
        }

        if (!interactive) {
            owner.trimClickAreas(clickCount)
        }
        owner.restoreTransitionRenderState(transientState, interactive, true)
        owner.applyContentState(savedState)
    }

    private fun renderFilterBodyForState(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        state: MainGUIContentViewState,
        interactive: Boolean
    ) {
        val savedState = owner.snapshotContentState()
        val clickCount = owner.clickAreaCount()
        val transientState = owner.captureTransientRenderState()

        owner.applyContentState(state)
        owner.setSuppressNestedContentScissorState(true)
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender()
        }

        if (owner.getActiveMainTab() == 0) {
            val scrollTop = owner.contentY() + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f
            val scrollH = owner.contentH() - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f
            owner.renderModuleGridPanel(nvg, mx, my, scrollTop, scrollH)
        } else if (owner.getActiveMainTab() == 2) {
            owner.renderProfileContentPanel(nvg, mx, my)
        } else {
            owner.renderFlatContentPanel(nvg, mx, my)
        }

        if (!interactive) {
            owner.trimClickAreas(clickCount)
        }
        owner.restoreTransitionRenderState(transientState, interactive, false)
        owner.applyContentState(savedState)
    }

    private fun isModuleDetailState(state: MainGUIContentViewState): Boolean =
        state.activeMain() == 0 && state.activeSubTab() != null

    private fun isCategoryOnlyTransition(from: MainGUIContentViewState, to: MainGUIContentViewState): Boolean =
        from.activeMain() == 0 &&
            to.activeMain() == 0 &&
            from.activeSubTab() != null &&
            from.activeSubTab() == to.activeSubTab() &&
            from.activeSubtab() == to.activeSubtab() &&
            from.activeFilter() == to.activeFilter() &&
            from.searchQuery().equals(to.searchQuery()) &&
            from.activeCategoryIdx() != to.activeCategoryIdx()

    private fun isFilterOnlyTransition(from: MainGUIContentViewState, to: MainGUIContentViewState): Boolean =
        from.activeMain() == to.activeMain() &&
            MainGUI.hasTopFilterBar(from.activeMain()) &&
            from.activeSubTab() == null &&
            to.activeSubTab() == null &&
            from.activeSubtab() == to.activeSubtab() &&
            from.searchQuery().equals(to.searchQuery()) &&
            from.activeCategoryIdx() == to.activeCategoryIdx() &&
            from.activeFilter() != to.activeFilter()
}
