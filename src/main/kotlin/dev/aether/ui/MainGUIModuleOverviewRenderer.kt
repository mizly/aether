package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.theme.Theme

final class MainGUIModuleOverviewRenderer(private val owner: MainGUI) {
    fun render(nvg: NVGRenderer, mx: Float, my: Float, scrollTop: Float, scrollH: Float) {
        var context = owner.context()
        val gridX = context.layout.contX + MainGUI.ITEM_PAD
        val gridW = context.layout.contW - MainGUI.ITEM_PAD * 2f
        val cardW = (gridW - MainGUI.CARD_HGAP * (MainGUI.CARD_COLS - 1)) / MainGUI.CARD_COLS
        val clipped = owner.pushContentLocalScissor(nvg, context.layout.contX, scrollTop, context.layout.contW, scrollH)
        var y = scrollTop - context.scroll.scrollY
        var total = 0f
        for (section in owner.moduleSectionsForFilter()) {
            if (section.subtabs().isEmpty()) continue
            val gap = if (total == 0f) MainGUI.SECT_GAP * 0.6f else MainGUI.SECT_GAP
            y += gap
            total += gap
            owner.renderSectionHeader(nvg, section.name(), gridX, y, gridW)
            y += MainGUI.SECT_H
            total += MainGUI.SECT_H
            y += MainGUI.SECT_SEP
            total += MainGUI.SECT_SEP
            val subtabs = section.subtabs()
            var i = 0
            while (i < subtabs.size) {
                for (col in 0 until MainGUI.CARD_COLS) {
                    val idx = i + col
                    if (idx >= subtabs.size) break
                    val subTab = subtabs[idx]
                    val cardX = gridX + col * (cardW + MainGUI.CARD_HGAP)
                    var hover = owner.moduleCardHoverProgress(subTab)
                    val inCard = mx >= cardX && mx < cardX + cardW && my >= y && my < y + MainGUI.CARD_H
                    hover += ((if (inCard) 1f else 0f) - hover) * Math.min(1f, Theme.animationFactor() * 7f)
                    owner.setModuleCardHoverProgress(subTab, hover)
                    if (y + MainGUI.CARD_H > scrollTop && y < scrollTop + scrollH) owner.renderModuleCardPanel(nvg, subTab, cardX, y, cardW, hover, mx, my)
                }
                y += MainGUI.CARD_H + MainGUI.CARD_VGAP
                total += MainGUI.CARD_H + MainGUI.CARD_VGAP
                i += MainGUI.CARD_COLS
            }
            total -= MainGUI.CARD_VGAP
            y -= MainGUI.CARD_VGAP
            y += 14f
            total += 14f
        }
        owner.popContentLocalScissor(nvg, clipped)
        owner.renderMainScrollbar(nvg, total, scrollTop, scrollH, context.layout.contX + context.layout.contW - 6f)
    }
}
