package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.SkinFaceProvider
import dev.aether.telemetry.AetherAuthService
import dev.aether.telemetry.AetherAuthState
import dev.aether.ui.util.Fonts
import dev.aether.ui.theme.Theme
import dev.aether.util.AetherLang

internal class MainGUIChromeRenderer(private val owner: MainGUI) {
    private val sidebarSelection = SelectionAnimation()
    private val filterPosition = SelectionAnimation()
    private val filterWidth = SelectionAnimation()

    fun renderSidebar(nvg: NVGRenderer, mx: Float, my: Float) {
        var context = owner.context()

        if (context.animation.computedSidebarExpanded < 1f) {
            var maxTW = 0f
            for (name in arrayOf(AetherLang.localize("Modules"), AetherLang.localize("Colors"), AetherLang.localize("Config"), AetherLang.localize("Keybinds"), AetherLang.localize("Settings"), AetherLang.localize("HUD Positions"))) {
                maxTW = Math.max(maxTW, nvg.textWidth(Fonts.REGULAR, name, 12f))
            }
            owner.setComputedSidebarExpanded(MainGUI.SB_H_PAD + MainGUI.SB_PILL + 10f + maxTW + 14f)
            context = owner.context()
        }

        val sbW = MainGUI.SIDEBAR_W + context.animation.sidebarAnim * (context.animation.computedSidebarExpanded - MainGUI.SIDEBAR_W)
        val textX = context.layout.px + MainGUI.SB_H_PAD + MainGUI.SB_PILL + 10f

        nvg.roundedRect(context.layout.px, context.layout.py, sbW, context.layout.ph, MainGUI.RADIUS, Theme.SIDEBAR_BG)
        nvg.rect(context.layout.px + MainGUI.RADIUS, context.layout.py, sbW - MainGUI.RADIUS, context.layout.ph, Theme.SIDEBAR_BG)
        nvg.rect(context.layout.px + sbW - 1f, context.layout.py, 1f, context.layout.ph, Theme.SEPARATOR)

        nvg.pushScissor(context.layout.px, context.layout.py, sbW, context.layout.ph)

        val logoSize = 34f
        val logoX = context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - logoSize) / 2f
        val logoY = context.layout.py + (MainGUI.SB_LOGO_H - logoSize) / 2f
        nvg.renderSVG("/assets/aether/icons/logo.svg", logoX, logoY, logoSize, logoSize, Theme.ACCENT_PRIMARY)
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save()
            nvg.globalAlpha(context.animation.sidebarAnim)
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f)
            nvg.text(Fonts.BOLD, AetherLang.localize("Aether"), textX, logoY + (logoSize - 14f) / 2f, 18f, Theme.ACCENT_PRIMARY)
            nvg.restore()
        }

        val sepY = context.layout.py + MainGUI.SB_LOGO_H
        nvg.rect(context.layout.px + MainGUI.SB_H_PAD, sepY, sbW - MainGUI.SB_H_PAD * 2f, 1f, Theme.SEPARATOR)

        val sidebarHoverColor = Theme.TEXT_VALUE
        val tabIds = arrayOf("modules", "colors", "config")
        val tabLabels = arrayOf(AetherLang.localize("Modules"), AetherLang.localize("Colors"), AetherLang.localize("Config"))
        val tabIconW = floatArrayOf(16f, 16f, 14f)
        val tabIconH = floatArrayOf(19f, 16f, 18f)
        val tabsY = sepY + MainGUI.SB_SEP_GAP
        val profileTabY = context.layout.py + context.layout.ph - MainGUI.SB_BOT_PAD - 44f
        val settingsTabY = profileTabY - 44f - MainGUI.SB_SEP_GAP
        val keybindsTabY = settingsTabY - 44f
        val hudPositionsTabY = keybindsTabY - 44f
        val selectedY = when (context.navigation.activeMain) {
            3 -> keybindsTabY
            4 -> settingsTabY
            else -> tabsY + context.navigation.activeMain * 44f
        }
        val highlightY = sidebarSelection.update(selectedY - context.layout.py, Theme.ANIM_TIME_MS, System.nanoTime())
        nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, context.layout.py + highlightY + MainGUI.SB_ROW_PAD, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0x55))

        for (i in tabLabels.indices) {
            val tabY = tabsY + i * 44f
            val pillY = tabY + MainGUI.SB_ROW_PAD
            val selected = context.navigation.activeMain == i
            val hovered = !selected && mx >= context.layout.px && mx < context.layout.px + sbW && my >= tabY && my <= tabY + 44f
            val color = if (selected) Theme.ACCENT_PRIMARY else if (hovered) sidebarHoverColor else Theme.TEXT_MUTED

            if (hovered) {
                nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, pillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f, Theme.withAlpha(Theme.TEXT_MUTED, 0x33))
            }

            val iconOffX = (MainGUI.SB_PILL - tabIconW[i]) / 2f
            val iconOffY = (MainGUI.SB_PILL - tabIconH[i]) / 2f
            nvg.renderSVG("/assets/aether/icons/" + tabIds[i] + ".svg", context.layout.px + MainGUI.SB_H_PAD + iconOffX, pillY + iconOffY, tabIconW[i], tabIconH[i], color)

            if (context.animation.sidebarAnim > 0.01f) {
                nvg.save()
                nvg.globalAlpha(context.animation.sidebarAnim)
                nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f)
                nvg.text(Fonts.REGULAR, tabLabels[i], textX, pillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, color)
                nvg.restore()
            }
            owner.offerHoverHelp("sidebar:" + tabIds[i], tabLabels[i], when (i) {
                0 -> AetherLang.localize("Manage and configure modules")
                1 -> AetherLang.localize("Customise HUD and menu colors")
                else -> AetherLang.localize("Save and manage config and theme profiles")
            }, context.layout.px, tabY, sbW, 44f, mx, my)
        }

        nvg.rect(context.layout.px + MainGUI.SB_H_PAD, hudPositionsTabY - MainGUI.SB_SEP_GAP, sbW - MainGUI.SB_H_PAD * 2f, 1f, Theme.SEPARATOR)

        val hudPillY = hudPositionsTabY + MainGUI.SB_ROW_PAD
        val hudHovered = mx >= context.layout.px && mx < context.layout.px + sbW && my >= hudPositionsTabY && my <= hudPositionsTabY + 44f
        val hudColor = if (hudHovered) sidebarHoverColor else Theme.TEXT_MUTED
        if (hudHovered) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, hudPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f, Theme.withAlpha(Theme.TEXT_MUTED, 0x33))
        }
        val hudIconSize = 18f
        nvg.renderSVG("/assets/aether/icons/hud_positions.svg", context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - hudIconSize) / 2f, hudPillY + (MainGUI.SB_PILL - hudIconSize) / 2f, hudIconSize, hudIconSize, hudColor)
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save()
            nvg.globalAlpha(context.animation.sidebarAnim)
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f)
            nvg.text(Fonts.REGULAR, AetherLang.localize("HUD Positions"), textX, hudPillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, hudColor)
            nvg.restore()
        }
        owner.offerHoverHelp("sidebar:hud_positions", AetherLang.localize("HUD Positions"), AetherLang.localize("Open the HUD layout editor to move and arrange Aether overlays."), context.layout.px, hudPositionsTabY, sbW, 44f, mx, my)

        val keybindsPillY = keybindsTabY + MainGUI.SB_ROW_PAD
        val keybindsSelected = context.navigation.activeMain == 3
        val keybindsHovered = !keybindsSelected && mx >= context.layout.px && mx < context.layout.px + sbW && my >= keybindsTabY && my <= keybindsTabY + 44f
        val keybindsColor = if (keybindsSelected) Theme.ACCENT_PRIMARY else if (keybindsHovered) sidebarHoverColor else Theme.TEXT_MUTED
        if (keybindsHovered) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, keybindsPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f, Theme.withAlpha(Theme.TEXT_MUTED, 0x33))
        }
        val keybindsIconSize = 18f
        nvg.renderSVG("/assets/aether/icons/keybinds.svg", context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - keybindsIconSize) / 2f, keybindsPillY + (MainGUI.SB_PILL - keybindsIconSize) / 2f, keybindsIconSize, keybindsIconSize, keybindsColor)
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save()
            nvg.globalAlpha(context.animation.sidebarAnim)
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f)
            nvg.text(Fonts.REGULAR, AetherLang.localize("Keybinds"), textX, keybindsPillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, keybindsColor)
            nvg.restore()
        }
        owner.offerHoverHelp("sidebar:keybinds", AetherLang.localize("Keybinds"), AetherLang.localize("Edit Aether keybinds and keep them synced with Minecraft controls."), context.layout.px, keybindsTabY, sbW, 44f, mx, my)

        val settingsPillY = settingsTabY + MainGUI.SB_ROW_PAD
        val settingsSelected = context.navigation.activeMain == 4
        val settingsHovered = !settingsSelected && mx >= context.layout.px && mx < context.layout.px + sbW && my >= settingsTabY && my <= settingsTabY + 44f
        val settingsColor = if (settingsSelected) Theme.ACCENT_PRIMARY else if (settingsHovered) sidebarHoverColor else Theme.TEXT_MUTED
        if (settingsHovered) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, settingsPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f, Theme.withAlpha(Theme.TEXT_MUTED, 0x33))
        }
        val settingsIconSize = 16f
        nvg.renderSVG("/assets/aether/icons/settings.svg", context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - settingsIconSize) / 2f, settingsPillY + (MainGUI.SB_PILL - settingsIconSize) / 2f, settingsIconSize, settingsIconSize, settingsColor)
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save()
            nvg.globalAlpha(context.animation.sidebarAnim)
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f)
            nvg.text(Fonts.REGULAR, AetherLang.localize("Settings"), textX, settingsPillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, settingsColor)
            nvg.restore()
        }
        owner.offerHoverHelp("sidebar:settings", AetherLang.localize("Settings"), AetherLang.localize("General client settings."), context.layout.px, settingsTabY, sbW, 44f, mx, my)

        nvg.rect(context.layout.px + MainGUI.SB_H_PAD, profileTabY - MainGUI.SB_SEP_GAP, sbW - MainGUI.SB_H_PAD * 2f, 1f, Theme.SEPARATOR)
        renderFarmProfile(nvg, context, textX, profileTabY, mx, my)

        nvg.popScissor()
    }

    fun renderContentTopBar(nvg: NVGRenderer, mx: Float, my: Float) {
        var context = owner.context()
        val name = owner.currentTabName()
        val desc = owner.currentTabDescription()

        val nameY = context.layout.contY + (MainGUI.TOP_BAR_H - 15f) / 2f
        nvg.text(Fonts.BOLD, name, context.layout.contX + 20f, nameY, 15f, Theme.TEXT_PRIMARY)

        val nameW = nvg.textWidth(Fonts.BOLD, name, 15f)
        val sepX = context.layout.contX + 20f + nameW + 10f
        nvg.rect(sepX, context.layout.contY + 17f, 1f, 19f, Theme.SEPARATOR)

        val descY = context.layout.contY + (MainGUI.TOP_BAR_H - 12f) / 2f
        nvg.text(Fonts.REGULAR, desc, sepX + 10f, descY, 12f, Theme.TEXT_MUTED)

        val searchW = 190f
        val searchH = 30f
        val searchX = context.layout.contX + context.layout.contW - 20f - searchW
        val searchY = context.layout.contY + (MainGUI.TOP_BAR_H - searchH) / 2f
        owner.setSearchBoxBounds(searchX, searchY, searchW, searchH)
        context = owner.context()

        val searchBg = Theme.BG_FIELD
        val searchOutline = if (context.navigation.searchMode) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT
        nvg.roundedRect(searchX, searchY, searchW, searchH, 7f, searchBg)
        nvg.rectOutlineSolid(searchX, searchY, searchW, searchH, 7f, 1f, searchOutline)
        nvg.renderSVG("/assets/aether/icons/search.svg", searchX + 8f, searchY + (searchH - 14f) / 2f, 14f, 14f, if (context.navigation.searchMode) Theme.ACCENT_PRIMARY else Theme.TEXT_MUTED)

        if (context.navigation.searchQuery.isEmpty()) {
            nvg.text(Fonts.REGULAR, AetherLang.localize("Search settings..."), searchX + 30f, searchY + (searchH - 11f) / 2f, 11f, if (context.navigation.searchMode) Theme.TEXT_SECONDARY else Theme.TEXT_MUTED)
        } else {
            val display = if (owner.isSearchEditorActiveState()) owner.currentTextBuffer() else context.navigation.searchQuery
            val textPad = 6f
            val fontSize = 11f
            val visibleTextW = searchW - 30f - textPad * 2f
            val textOffset = if (owner.isSearchEditorActiveState()) owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, display, owner.currentTextCursor(), fontSize, visibleTextW) else 0f
            val drawX = searchX + 30f + textPad - textOffset
            nvg.pushScissor(searchX + 30f + textPad, searchY + 1f, visibleTextW, searchH - 2f)
            if (owner.isSearchEditorActiveState() && owner.textHasSelection()) {
                val selStart = owner.currentTextSelectionStart()
                val selEnd = owner.currentTextSelectionEnd()
                val selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, selStart), fontSize)
                val selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, selStart, selEnd), fontSize)
                nvg.roundedRect(selX, searchY + 4f, Math.max(1f, selW), searchH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f))
            }
            nvg.text(Fonts.REGULAR, display, drawX, searchY + (searchH - fontSize) / 2f, fontSize, Theme.TEXT_PRIMARY)
            if (owner.isSearchEditorActiveState() && (System.currentTimeMillis() / 530) % 2 == 0L) {
                val caretX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, owner.currentTextCursor()), fontSize)
                nvg.rect(caretX, searchY + (searchH - 14f) / 2f, 1f, 14f, Theme.TEXT_PRIMARY)
            }
            nvg.popScissor()
        }

        owner.addClickArea(searchX, searchY, searchW, searchH, Runnable { owner.activateSearchField() })
        owner.offerHoverHelp("search", AetherLang.localize("Search settings..."), AetherLang.localize("Search all Aether settings by name and jump directly to matching controls."), searchX, searchY, searchW, searchH, mx, my)
        nvg.rect(context.layout.contX, context.layout.contY + MainGUI.TOP_BAR_H, context.layout.contW, 1f, Theme.SEPARATOR)
    }

    private fun renderFarmProfile(nvg: NVGRenderer, context: MainGUIContext, textX: Float, rowY: Float, mx: Float, my: Float) {
        val headSize = 28f
        val pillY = rowY + MainGUI.SB_ROW_PAD
        val headX = context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - headSize) / 2f
        val headY = pillY + (MainGUI.SB_PILL - headSize) / 2f

        SkinFaceProvider.render(nvg, headX, headY, headSize, 1f)

        if (context.animation.sidebarAnim <= 0.01f) {
            owner.clearLoginButtonBounds()
            return
        }

        val cy = pillY + MainGUI.SB_PILL / 2f
        nvg.save()
        nvg.globalAlpha(context.animation.sidebarAnim)
        nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f)
        if (AetherAuthService.isAuthenticated()) {
            owner.clearLoginButtonBounds()
            renderPlayedHours(nvg, textX, cy)
        } else {
            renderLoginButton(nvg, textX, cy, mx, my)
        }
        nvg.restore()
    }

    private fun renderPlayedHours(nvg: NVGRenderer, textX: Float, cy: Float) {
        val textSize = 12f
        nvg.text(Fonts.BOLD, formatPlayedHours(AetherAuthService.getTotalSeconds()), textX, cy - textSize + 1f, textSize, Theme.ACCENT_PRIMARY)
        nvg.text(Fonts.BOLD, AetherLang.localize("HOURS PLAYED"), textX, cy + 2f, 8f, Theme.TEXT_TERTIARY)
    }

    private fun renderLoginButton(nvg: NVGRenderer, textX: Float, cy: Float, mx: Float, my: Float) {
        val authenticating = AetherAuthService.getState() == AetherAuthState.AUTHENTICATING
        val label = AetherLang.localize(if (authenticating) "SIGNING IN..." else "LOG IN")
        val fontSize = 9f
        val labelW = nvg.textWidth(Fonts.BOLD, label, fontSize)
        val btnH = 18f
        val btnW = labelW + 18f
        val btnY = cy - btnH / 2f
        val hovered = !authenticating && mx >= textX && mx < textX + btnW && my >= btnY && my < btnY + btnH
        val accent = if (authenticating) Theme.TEXT_MUTED else Theme.ACCENT_PRIMARY

        nvg.roundedRect(textX, btnY, btnW, btnH, 6f, Theme.withAlpha(accent, if (hovered) 0x44 else 0x22))
        nvg.rectOutlineSolid(textX, btnY, btnW, btnH, 6f, 1f, Theme.withAlpha(accent, if (hovered) 0xFF else 0x88))
        nvg.text(Fonts.BOLD, label, textX + (btnW - labelW) / 2f, cy - fontSize / 2f, fontSize, accent)

        if (authenticating) {
            owner.clearLoginButtonBounds()
        } else {
            owner.setLoginButtonBounds(textX, btnY, btnW, btnH)
        }
    }

    fun renderFilterBar(nvg: NVGRenderer, mx: Float, my: Float) {
        var context = owner.context()
        val options = owner.currentTabFilters()
        val selectedIndex = Math.max(0, Math.min(context.navigation.activeFilter, options.size - 1))

        val barY = context.layout.contY + MainGUI.TOP_BAR_H + 1f
        val textY = barY + (MainGUI.FILTER_BAR_H - 12f) / 2f
        val optionPad = 14f
        var x = context.layout.contX + 20f

        val optionX = FloatArray(options.size)
        val optionW = FloatArray(options.size)
        for (i in options.indices) {
            val textW = nvg.textWidth(Fonts.REGULAR, options[i], 12f)
            optionX[i] = x
            optionW[i] = textW + optionPad * 2f
            x += optionW[i]
        }

        val targetX = optionX[selectedIndex] + optionPad
        val targetW = nvg.textWidth(Fonts.REGULAR, options[selectedIndex], 12f)
        owner.setFilterBarTarget(targetX, targetW)

        val nowNanos = System.nanoTime()
        if (!context.animation.filterBarInited) {
            filterPosition.reset(targetX - context.layout.contX, nowNanos)
            filterWidth.reset(targetW, nowNanos)
            owner.setFilterBarInitialized(true)
        }
        owner.setFilterBarAnimation(context.layout.contX + filterPosition.update(targetX - context.layout.contX, Theme.ANIM_TIME_MS, nowNanos), filterWidth.update(targetW, Theme.ANIM_TIME_MS, nowNanos))
        context = owner.context()

        for (i in options.indices) {
            val selected = context.navigation.activeFilter == i
            nvg.text(Fonts.REGULAR, options[i], optionX[i] + optionPad, textY, 12f, if (selected) Theme.ACCENT_PRIMARY else Theme.TEXT_MUTED)

            val filterIndex = i
            owner.addClickArea(optionX[i], barY, optionW[i], MainGUI.FILTER_BAR_H, Runnable { owner.onFilterSelected(filterIndex) })
        }

        val sepY = barY + MainGUI.FILTER_BAR_H
        nvg.rect(context.layout.contX, sepY, context.layout.contW, 1f, Theme.SEPARATOR)
        nvg.roundedRect(context.animation.filterBarAnimX, sepY - 1f, context.animation.filterBarAnimW, 2f, 1f, Theme.ACCENT_PRIMARY)
    }

    companion object {
        private fun formatPlayedHours(seconds: Long): String {
            return (seconds / 3600L).toString() + "h " + ((seconds % 3600L) / 60L) + "m"
        }
    }
}
