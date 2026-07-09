package dev.aether.ui;

final class MainGUILayout {
    private static final float PANEL_MARGIN = 40f;
    private static final float MAX_PANEL_W = 1060f;
    private static final float MAX_PANEL_H = 660f;

    MainGUIContext.LayoutState computeFrameLayout(int width, int height, float pixelRatio, float uiScale, float sidebarWidth) {
        MainGUIContext.LayoutState layout = new MainGUIContext.LayoutState();
        layout.pr = pixelRatio;
        layout.physW = width * pixelRatio;
        layout.physH = height * pixelRatio;

        float canvasW = layout.physW / uiScale;
        float canvasH = layout.physH / uiScale;

        layout.pw = Math.min(MAX_PANEL_W, canvasW - PANEL_MARGIN);
        layout.ph = Math.min(MAX_PANEL_H, canvasH - PANEL_MARGIN);
        layout.px = (canvasW - layout.pw) / 2f;
        layout.py = (canvasH - layout.ph) / 2f;
        layout.contX = layout.px + sidebarWidth;
        layout.contY = layout.py;
        layout.contW = layout.pw - sidebarWidth;
        layout.contH = layout.ph;
        return layout;
    }

    float contentScrollTop(float contY, boolean hasTopFilterBar, float topBarHeight, float filterBarHeight) {
        return contY + topBarHeight + 1f + (hasTopFilterBar ? filterBarHeight + 1f : 0f);
    }

    float contentScrollHeight(float contY, float contH, boolean hasTopFilterBar, float topBarHeight, float filterBarHeight) {
        return contH - (contentScrollTop(contY, hasTopFilterBar, topBarHeight, filterBarHeight) - contY);
    }
}
