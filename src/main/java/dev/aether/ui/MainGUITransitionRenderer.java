package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;

final class MainGUITransitionRenderer {
    private final MainGUI owner;

    MainGUITransitionRenderer(MainGUI owner) {
        this.owner = owner;
    }

    void syncContentTransition() {
        MainGUIContext.TransitionState transition = owner.context().transition;
        MainGUIContentViewState current = owner.snapshotContentState();
        if (transition.toState == null) {
            transition.toState = current;
            return;
        }
        if (!transition.toState.equals(current)) {
            transition.fromState = transition.toState;
            transition.toState = current;
            transition.startNanos = System.nanoTime();
        }
    }

    void renderContentWithTransition(NVGRenderer nvg, float mx, float my) {
        MainGUIContext.TransitionState transition = owner.context().transition;
        MainGUIContentViewState fromState = transition.fromState;
        MainGUIContentViewState toState = transition.toState;
        if (fromState == null || toState == null) {
            owner.renderContentPanel(nvg, mx, my);
            return;
        }

        float durationMs = Math.max(1f, dev.aether.ui.theme.Theme.ANIM_TIME_MS);
        float elapsedMs = Math.max(0f, (System.nanoTime() - transition.startNanos) / 1_000_000f);
        float rawT = Math.max(0f, Math.min(1f, elapsedMs / durationMs));
        float t = rawT * rawT * (3f - 2f * rawT);
        float strongEaseT = rawT * rawT * rawT * (rawT * (rawT * 6f - 15f) + 10f);

        if (rawT >= 1f) {
            transition.fromState = null;
            owner.renderContentPanel(nvg, mx, my);
            return;
        }

        float outgoingOffsetY = -22f * t;
        float incomingOffsetY = 22f * (1f - t);
        float clipInset = 1f;
        float fullClipX = owner.contentX() + clipInset;
        float fullClipY = owner.contentY() + clipInset;
        float fullClipW = Math.max(0f, owner.contentW() - clipInset * 2f);
        float fullClipH = Math.max(0f, owner.contentH() - clipInset * 2f);

        boolean fromModuleDetail = isModuleDetailState(fromState);
        boolean toModuleDetail = isModuleDetailState(toState);
        boolean categoryOnlyTransition = isCategoryOnlyTransition(fromState, toState);
        boolean filterOnlyTransition = isFilterOnlyTransition(fromState, toState);

        if (categoryOnlyTransition) {
            renderModuleDetailTopBarForState(nvg, mx, my, toState, true);

            float bodyClipX = owner.contentX() + clipInset;
            float bodyClipY = owner.contentY() + MainGUI.TOP_BAR_H + 1f + clipInset;
            float bodyClipW = Math.max(0f, owner.contentW() - clipInset * 2f);
            float bodyClipH = Math.max(0f, owner.contentH() - MainGUI.TOP_BAR_H - 1f - clipInset * 2f);

            nvg.save();
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH);
            renderContentForState(nvg, mx, my, fromState, false, true, true);
            nvg.popScissor();
            nvg.restore();

            nvg.save();
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH);
            nvg.globalAlpha(t);
            renderContentForState(nvg, mx, my, toState, true, true, true);
            nvg.popScissor();
            nvg.restore();
            return;
        }

        if (filterOnlyTransition) {
            float bodyClipX = owner.contentX() + clipInset;
            float bodyClipY = owner.contentY() + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f + clipInset;
            float bodyClipW = Math.max(0f, owner.contentW() - clipInset * 2f);
            float bodyClipH = Math.max(0f, owner.contentH() - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f - clipInset * 2f);
            float headerClipX = owner.contentX() + clipInset;
            float headerClipY = owner.contentY() + clipInset;
            float headerClipW = Math.max(0f, owner.contentW() - clipInset * 2f);
            float headerClipH = Math.max(0f, bodyClipY - owner.contentY() - clipInset);
            int direction = Integer.compare(toState.activeFilter(), fromState.activeFilter());
            if (direction == 0) {
                direction = 1;
            }
            float bodySlideX = owner.contentW() * direction;

            nvg.save();
            nvg.pushScissor(headerClipX, headerClipY, headerClipW, headerClipH);
            owner.renderContentTopBarPanel(nvg, mx, my);
            owner.renderFilterBarPanel(nvg, mx, my);
            nvg.popScissor();
            nvg.restore();

            nvg.save();
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH);
            nvg.translate(-bodySlideX * strongEaseT, 0f);
            renderFilterBodyForState(nvg, mx, my, fromState, false);
            nvg.popScissor();
            nvg.restore();

            nvg.save();
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH);
            nvg.translate(bodySlideX * (1f - strongEaseT), 0f);
            renderFilterBodyForState(nvg, mx, my, toState, true);
            nvg.popScissor();
            nvg.restore();
            return;
        }

        if (fromModuleDetail || toModuleDetail) {
            float bodyClipX = owner.contentX() + clipInset;
            float bodyClipY = owner.contentY() + MainGUI.TOP_BAR_H + 1f + clipInset;
            float bodyClipW = Math.max(0f, owner.contentW() - clipInset * 2f);
            float bodyClipH = Math.max(0f, owner.contentH() - MainGUI.TOP_BAR_H - 1f - clipInset * 2f);
            float headerClipX = owner.contentX() + clipInset;
            float headerClipY = owner.contentY() + clipInset;
            float headerClipW = Math.max(0f, owner.contentW() - clipInset * 2f);
            float headerClipH = Math.max(0f, bodyClipY - owner.contentY() - clipInset);
            int direction = toModuleDetail ? 1 : -1;
            float bodySlideX = owner.contentW() * direction;

            nvg.save();
            nvg.pushScissor(headerClipX, headerClipY, headerClipW, headerClipH);
            nvg.translate(bodySlideX * strongEaseT, 0f);
            if (fromModuleDetail) {
                renderModuleDetailTopBarForState(nvg, mx, my, fromState, false);
            } else {
                renderContentTopBarForState(nvg, mx, my, fromState, false);
            }
            nvg.popScissor();
            nvg.restore();

            nvg.save();
            nvg.pushScissor(headerClipX, headerClipY, headerClipW, headerClipH);
            nvg.translate(-bodySlideX * (1f - strongEaseT), 0f);
            if (toModuleDetail) {
                renderModuleDetailTopBarForState(nvg, mx, my, toState, true);
            } else {
                renderContentTopBarForState(nvg, mx, my, toState, true);
            }
            nvg.popScissor();
            nvg.restore();

            nvg.save();
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH);
            nvg.translate(bodySlideX * strongEaseT, 0f);
            if (fromModuleDetail) {
                renderModuleDetailBodyForState(nvg, mx, my, fromState, false);
            } else {
                renderModulesOverviewBodyForState(nvg, mx, my, fromState, false);
            }
            nvg.popScissor();
            nvg.restore();

            nvg.save();
            nvg.pushScissor(bodyClipX, bodyClipY, bodyClipW, bodyClipH);
            nvg.translate(-bodySlideX * (1f - strongEaseT), 0f);
            if (toModuleDetail) {
                renderModuleDetailBodyForState(nvg, mx, my, toState, true);
            } else {
                renderModulesOverviewBodyForState(nvg, mx, my, toState, true);
            }
            nvg.popScissor();
            nvg.restore();
            return;
        }

        nvg.save();
        nvg.pushScissor(fullClipX, fullClipY, fullClipW, fullClipH);
        nvg.translate(0f, outgoingOffsetY);
        nvg.globalAlpha(1f - t);
        renderContentForState(nvg, mx, my, fromState, false, false, true);
        nvg.popScissor();
        nvg.restore();

        nvg.save();
        nvg.pushScissor(fullClipX, fullClipY, fullClipW, fullClipH);
        nvg.translate(0f, incomingOffsetY);
        nvg.globalAlpha(t);
        renderContentForState(nvg, mx, my, toState, true, false, true);
        nvg.popScissor();
        nvg.restore();
    }

    private void renderContentForState(NVGRenderer nvg, float mx, float my, MainGUIContentViewState state,
                                       boolean interactive, boolean moduleBodyOnly, boolean suppressNestedScissor) {
        MainGUIContentViewState savedState = owner.snapshotContentState();
        int clickCount = owner.clickAreaCount();
        MainGUITransientRenderState transientState = owner.captureTransientRenderState();

        owner.applyContentState(state);
        owner.setSuppressNestedContentScissorState(suppressNestedScissor);
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender();
        }

        if (moduleBodyOnly) {
            owner.renderModuleDetailBodyPanel(nvg, mx, my);
        } else {
            owner.renderContentPanel(nvg, mx, my);
        }

        if (!interactive) {
            owner.trimClickAreas(clickCount);
        }
        owner.restoreTransitionRenderState(transientState, interactive, false);
        owner.applyContentState(savedState);
    }

    private void renderContentTopBarForState(NVGRenderer nvg, float mx, float my, MainGUIContentViewState state, boolean interactive) {
        MainGUIContentViewState savedState = owner.snapshotContentState();
        int clickCount = owner.clickAreaCount();
        MainGUITransientRenderState transientState = owner.captureTransientRenderState();

        owner.applyContentState(state);
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender();
        }

        owner.renderContentTopBarPanel(nvg, mx, my);

        if (!interactive) {
            owner.trimClickAreas(clickCount);
        }
        owner.restoreTransitionRenderState(transientState, interactive, false);
        owner.applyContentState(savedState);
    }

    private void renderModuleDetailTopBarForState(NVGRenderer nvg, float mx, float my, MainGUIContentViewState state, boolean interactive) {
        MainGUIContentViewState savedState = owner.snapshotContentState();
        int clickCount = owner.clickAreaCount();
        MainGUITransientRenderState transientState = owner.captureTransientRenderState();

        owner.applyContentState(state);
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender();
        }

        owner.renderModuleTopBarPanel(nvg, mx, my);

        if (!interactive) {
            owner.trimClickAreas(clickCount);
        }
        owner.restoreTransitionRenderState(transientState, interactive, false);
        owner.applyContentState(savedState);
    }

    private void renderModuleDetailBodyForState(NVGRenderer nvg, float mx, float my, MainGUIContentViewState state, boolean interactive) {
        MainGUITransientRenderState transientState = owner.captureTransientRenderState();
        owner.setActiveScroll(owner.getModuleDetailScrollY(), owner.getModuleDetailTargetScrollY());
        renderContentForState(nvg, mx, my, state, interactive, true, true);
        owner.restoreTransitionRenderState(transientState, interactive, true);
    }

    private void renderModulesOverviewBodyForState(NVGRenderer nvg, float mx, float my, MainGUIContentViewState state, boolean interactive) {
        MainGUIContentViewState savedState = owner.snapshotContentState();
        int clickCount = owner.clickAreaCount();
        MainGUITransientRenderState transientState = owner.captureTransientRenderState();

        owner.applyContentState(state);
        owner.setSuppressNestedContentScissorState(true);
        owner.setActiveScroll(owner.getModulesOverviewScrollY(), owner.getModulesOverviewTargetScrollY());
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender();
        }

        if (owner.searchResultsVisible()) {
            owner.renderSearchContentPanel(nvg, mx, my);
        } else {
            owner.renderFilterBarPanel(nvg, mx, my);
            float scrollTop = owner.contentY() + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f;
            float scrollH = owner.contentH() - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f;
            owner.renderModuleGridPanel(nvg, mx, my, scrollTop, scrollH);
        }

        if (!interactive) {
            owner.trimClickAreas(clickCount);
        }
        owner.restoreTransitionRenderState(transientState, interactive, true);
        owner.applyContentState(savedState);
    }

    private void renderFilterBodyForState(NVGRenderer nvg, float mx, float my, MainGUIContentViewState state, boolean interactive) {
        MainGUIContentViewState savedState = owner.snapshotContentState();
        int clickCount = owner.clickAreaCount();
        MainGUITransientRenderState transientState = owner.captureTransientRenderState();

        owner.applyContentState(state);
        owner.setSuppressNestedContentScissorState(true);
        if (!interactive) {
            owner.clearTransitionOverlaysForPassiveRender();
        }

        if (owner.getActiveMainTab() == 0) {
            float scrollTop = owner.contentY() + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f;
            float scrollH = owner.contentH() - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f;
            owner.renderModuleGridPanel(nvg, mx, my, scrollTop, scrollH);
        } else if (owner.getActiveMainTab() == 2) {
            owner.renderProfileContentPanel(nvg, mx, my);
        } else {
            owner.renderFlatContentPanel(nvg, mx, my);
        }

        if (!interactive) {
            owner.trimClickAreas(clickCount);
        }
        owner.restoreTransitionRenderState(transientState, interactive, false);
        owner.applyContentState(savedState);
    }

    private boolean isModuleDetailState(MainGUIContentViewState state) {
        return state.activeMain() == 0 && state.activeSubTab() != null;
    }

    private boolean isCategoryOnlyTransition(MainGUIContentViewState from, MainGUIContentViewState to) {
        return from.activeMain() == 0
                && to.activeMain() == 0
                && from.activeSubTab() != null
                && from.activeSubTab() == to.activeSubTab()
                && from.activeSubtab() == to.activeSubtab()
                && from.activeFilter() == to.activeFilter()
                && from.searchQuery().equals(to.searchQuery())
                && from.activeCategoryIdx() != to.activeCategoryIdx();
    }

    private boolean isFilterOnlyTransition(MainGUIContentViewState from, MainGUIContentViewState to) {
        return from.activeMain() == to.activeMain()
                && MainGUI.hasTopFilterBar(from.activeMain())
                && from.activeSubTab() == null
                && to.activeSubTab() == null
                && from.activeSubtab() == to.activeSubtab()
                && from.searchQuery().equals(to.searchQuery())
                && from.activeCategoryIdx() == to.activeCategoryIdx()
                && from.activeFilter() != to.activeFilter();
    }
}
