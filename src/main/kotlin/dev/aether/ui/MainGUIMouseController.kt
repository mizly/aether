package dev.aether.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent

internal class MainGUIMouseController(private val owner: MainGUI) {
    fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = owner.scaleMouseX(click.x())
        val my = owner.scaleMouseY(click.y())
        if (owner.handleKeybindMouseButtonCapture(click.button())) return true
        if (click.button() == 1) {
            owner.handleRightClickEvent(mx, my)
            return true
        }
        if (click.button() != 0) return true

        if (owner.hasActiveColorOverlay()) {
            if (owner.handleColorPickerClickEvent(mx, my)) return true
            owner.commitColor()
        }
        if (owner.hasInlineEditorFocus() && !owner.isPointInsideActiveEditorState(mx, my)) owner.commitText()
        if (owner.isOutsidePanel(mx, my)) {
            owner.handleOutsidePanelClick()
            return true
        }
        if (owner.tryStartContentScrollbarDrag(mx, my)) return true
        if (owner.tryStartSubtabScrollbarDrag(mx, my)) return true
        if (owner.isSearchButtonHit(mx, my)) {
            owner.handleSearchButtonClick()
            return true
        }
        if (owner.hasOpenDropdown()) {
            owner.handleDropdownClickEvent(mx, my)
            return true
        }
        if (owner.isPointInsideSearchFieldState(mx, my)) {
            owner.handleSearchFieldClick(mx, my)
            return true
        }
        owner.collapseSearchIfNeeded()
        if (owner.tryBeginPanelDrag(mx, my)) return true
        if (owner.tryHandleSidebarNavigationClick(mx, my, Minecraft.getInstance())) return true
        if (owner.isSidebarExpandedOverContent(mx, my)) return true
        if (owner.runClickAreas(mx, my)) return true
        owner.handleContentAreaClick(mx, my)
        return true
    }

    fun mouseDragged(click: MouseButtonEvent, dX: Double, dY: Double): Boolean {
        val mx = owner.scaleMouseX(click.x())
        val my = owner.scaleMouseY(click.y())
        if (owner.dragPanel(mx, my)) return true
        if (owner.dragSlider(mx)) return true
        if (owner.dragRangeSlider(mx)) return true
        if (owner.dragActiveColorOverlay(mx, my)) return true
        if (owner.updateActiveTextSelection(mx)) return true
        if (owner.tryBeginActiveTextSelection(click.button(), mx, my)) return true
        if (owner.dragScrollbar(my)) return true
        return false
    }

    fun mouseReleased(click: MouseButtonEvent): Boolean {
        owner.finishMouseInteractions()
        return false
    }

    fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean =
        owner.handleMouseScroll(owner.scaleMouseX(mx), owner.scaleMouseY(my), sy.toFloat())
}
