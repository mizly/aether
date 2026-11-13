package dev.aether.ui

import dev.aether.config.ConfigProfileManager
import dev.aether.config.ThemeProfileManager
import dev.aether.notification.NotificationManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.theme.ThemePreset
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import net.minecraft.client.Minecraft

internal class MainGUIProfilesPanel(private val owner: MainGUI) {
    private data class LayoutCursor(val y: Float, val tot: Float)

    fun commitRename() {
        if (!owner.profileRenameFocus) {
            return
        }

        val isConfig = owner.profileRenameTarget == 0
        val oldName = owner.profileRenameOriginal
        val newName = owner.profileRenameInput.trim()
        owner.cancelProfileRename()
        if (newName.isBlank()) {
            return
        }
        val renamed = if (isConfig) {
            ConfigProfileManager.rename(oldName, newName)
        } else {
            ThemeProfileManager.rename(oldName, newName)
        }
        if (renamed) {
            val title = if (isConfig) AetherLang.localize("Config Renamed") else AetherLang.localize("Theme Renamed")
            NotificationManager.success(title, "\"$oldName\" renamed to \"$newName\"")
        } else {
            val title = if (isConfig) AetherLang.localize("Config Rename Failed") else AetherLang.localize("Theme Rename Failed")
            NotificationManager.error(title, "\"$newName\" could not be used")
        }
    }

    fun render(nvg: NVGRenderer, mx: Float, my: Float) {
        val scrollTop = owner.contentScrollTop()
        val scrollH = owner.contentScrollHeight()

        if (!owner.suppressNestedContentScissor) {
            nvg.pushScissor(owner.contX, scrollTop, owner.contW, scrollH)
        }

        val gx = owner.contX + MainGUI.ITEM_PAD
        val gw = owner.contW - MainGUI.ITEM_PAD * 2f
        var y = scrollTop - owner.scrollY
        var tot = 0f

        if (owner.showConfigProfiles()) {
            y += 14f
            tot += 14f
            val cursor = renderSection(nvg, mx, my, gx, gw, y, tot, true, AetherLang.localize("Config Profiles"))
            y = cursor.y
            tot = cursor.tot
        }
        if (owner.showThemeProfiles()) {
            y += 14f
            tot += 14f
            val cursor = renderSection(nvg, mx, my, gx, gw, y, tot, false, AetherLang.localize("Theme Profiles"))
            y = cursor.y
            tot = cursor.tot
        }

        owner.maxScrollY = Math.max(0f, tot - scrollH + 16f)
        owner.targetScrollY = Math.max(0f, Math.min(owner.maxScrollY, owner.targetScrollY))
        if (!owner.suppressNestedContentScissor) {
            nvg.popScissor()
        }

        if (owner.maxScrollY > 0f) {
            val ratio = scrollH / (tot + 16f)
            val thumbH = Math.max(30f, scrollH * ratio)
            val trackT = scrollTop + 2f
            val trackH = scrollH - 4f
            owner.sbMainTrackT = trackT
            owner.sbMainTrackH = trackH
            owner.sbMainThumbH = thumbH
            val thumbY = owner.scrollbarThumbY(trackT, trackH, thumbH, owner.scrollY, owner.maxScrollY)
            nvg.roundedRect(owner.contX + owner.contW - 6f, thumbY, 4f, thumbH, 2f, Theme.withAlpha(Theme.TEXT_SECONDARY, 0.5f))
        }
    }

    private fun renderSection(
        nvg: NVGRenderer,
        mx: Float,
        my: Float,
        gx: Float,
        gw: Float,
        y: Float,
        tot: Float,
        isConfig: Boolean,
        title: String
    ): LayoutCursor {
        owner.renderSectionHeader(nvg, title, gx, y, gw)
        var currentY = y + MainGUI.SECT_H + MainGUI.SECT_SEP
        var currentTot = tot + MainGUI.SECT_H + MainGUI.SECT_SEP

        if (!isConfig) {
            val presetsHeight = renderThemePresets(nvg, mx, my, gx, gw, currentY)
            currentY += presetsHeight
            currentTot += presetsHeight
        }

        nvg.text(Fonts.BOLD, AetherLang.localize("Save Profile"), gx, currentY, 10f, Theme.TEXT_MUTED)
        currentY += 16f
        currentTot += 16f

        val fieldW = gw - 76f
        val fieldH = 32f
        val nameFocused = owner.isProfileNameFocused(isConfig)
        nvg.roundedRect(gx, currentY, fieldW, fieldH, 7f, Theme.BG_FIELD)
        nvg.rectOutline(gx, currentY, fieldW, fieldH, 7f, 1f, if (nameFocused) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        val nameDisp = if (owner.profileNameInput.isEmpty() && !nameFocused) AetherLang.localize("Profile name...") else owner.profileNameInput
        val nameColor = if (owner.profileNameInput.isEmpty() && !nameFocused) Theme.TEXT_MUTED else Theme.TEXT_LABEL
        val clipProfileName = owner.pushContentLocalScissor(nvg, gx + 10f, currentY + 1f, fieldW - 20f, fieldH - 2f)
        nvg.text(Fonts.REGULAR, nameDisp, gx + 10f, currentY + (fieldH - 12f) / 2f, 12f, nameColor)
        owner.popContentLocalScissor(nvg, clipProfileName)
        if (nameFocused && (System.currentTimeMillis() / 530) % 2 == 0L) {
            val cursorX = gx + 10f + nvg.textWidth(Fonts.REGULAR, owner.profileNameInput, 12f)
            nvg.rect(cursorX, currentY + 8f, 1f, fieldH - 16f, Theme.TEXT_LABEL)
        }

        val target = if (isConfig) 0 else 1
        val fieldY = currentY
        owner.addClickArea(gx, currentY, fieldW, fieldH, Runnable {
            owner.commitText()
            owner.commitColor()
            owner.openDd = null
            owner.profileNameTarget = target
            owner.profileNameFocus = true
        })

        val saveBtnX = gx + fieldW + 8f
        val saveBtnW = 60f
        val saveHov = mx >= saveBtnX && mx <= saveBtnX + saveBtnW && my >= fieldY && my <= fieldY + fieldH
        val saveBg = if (saveHov) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG
        val saveBrd = if (saveHov) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f)
        val saveTxt = if (saveHov) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE
        nvg.roundedRect(saveBtnX, currentY, saveBtnW, fieldH, 7f, saveBg)
        nvg.rectOutline(saveBtnX, currentY, saveBtnW, fieldH, 7f, 1f, saveBrd)
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Save"), saveBtnX, currentY, saveBtnW, fieldH, 12.5f, saveTxt)
        owner.offerHoverHelp(
            "profile-save:" + if (isConfig) "config" else "theme",
            AetherLang.localize("Save"),
            AetherLang.localize(
                if (isConfig) "Saves the current client configuration as a named profile."
                else "Saves the current menu and HUD theme as a named profile."
            ),
            saveBtnX, currentY, saveBtnW, fieldH, mx, my
        )
        val capturedName = owner.profileNameInput
        owner.addClickArea(saveBtnX, currentY, saveBtnW, fieldH, Runnable {
            if (capturedName.isNotBlank()) {
                if (isConfig) {
                    ConfigProfileManager.save(capturedName)
                    NotificationManager.success(AetherLang.localize("Config Saved"), "\"$capturedName\" saved successfully")
                } else {
                    ThemeProfileManager.save(capturedName)
                    NotificationManager.success(AetherLang.localize("Theme Saved"), "\"$capturedName\" saved successfully")
                }
                owner.profileNameInput = ""
                owner.profileNameFocus = false
            }
        })
        currentY += fieldH + 18f
        currentTot += fieldH + 18f

        nvg.text(Fonts.BOLD, AetherLang.localize("Saved Profiles"), gx, currentY, 10f, Theme.TEXT_MUTED)
        currentY += 14f
        currentTot += 14f

        val profiles = if (isConfig) ConfigProfileManager.list() else ThemeProfileManager.list()
        if (profiles.isEmpty()) {
            val emptyH = 42f
            nvg.roundedRect(gx, currentY, gw, emptyH, 7f, Theme.CARD_BG)
            nvg.rectOutline(gx, currentY, gw, emptyH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f))
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize("No saved profiles yet."), gx, currentY, gw, emptyH, 11f, Theme.TEXT_MUTED)
            currentY += emptyH + 4f
            currentTot += emptyH + 4f
        } else {
            val rowH = 44f
            val btnW = 60f
            val btnH = 26f
            val btnGap = 5f
            for (name in profiles) {
                val rowY = currentY
                nvg.roundedRect(gx, rowY, gw, rowH, 7f, Theme.CARD_BG)
                nvg.rectOutline(gx, rowY, gw, rowH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f))

                val btnX = gx + gw - 3f * (btnW + btnGap) - btnGap
                val btnY = rowY + (rowH - btnH) / 2f
                val profileName = name
                val nameX = gx + 10f
                val nameY = rowY + 7f
                val nameW = Math.max(40f, btnX - nameX - 10f)
                val nameH = rowH - 14f
                val renaming = owner.isProfileRenameFocused(isConfig, profileName)
                if (renaming) {
                    nvg.roundedRect(nameX, nameY, nameW, nameH, 5f, Theme.BG_FIELD)
                    nvg.rectOutline(nameX, nameY, nameW, nameH, 5f, 1f, Theme.ACCENT_PRIMARY)
                    val clipRename = owner.pushContentLocalScissor(nvg, nameX + 8f, nameY, nameW - 16f, nameH)
                    nvg.text(Fonts.REGULAR, owner.profileRenameInput, nameX + 8f, rowY + (rowH - 12f) / 2f, 12f, Theme.TEXT_LABEL)
                    owner.popContentLocalScissor(nvg, clipRename)
                    if ((System.currentTimeMillis() / 530) % 2 == 0L) {
                        val cursorX = nameX + 8f + nvg.textWidth(Fonts.REGULAR, owner.profileRenameInput, 12f)
                        nvg.rect(Math.min(cursorX, nameX + nameW - 8f), nameY + 7f, 1f, nameH - 14f, Theme.TEXT_LABEL)
                    }
                } else {
                    val nameHovered = mx >= nameX && mx <= nameX + nameW && my >= nameY && my <= nameY + nameH
                    val rowNameColor = if (nameHovered) Theme.ACCENT_PRIMARY else Theme.TEXT_LABEL
                    val clipName = owner.pushContentLocalScissor(nvg, nameX + 4f, rowY, nameW - 8f, rowH)
                    nvg.text(Fonts.REGULAR, name, nameX + 4f, rowY + (rowH - 12f) / 2f, 12f, rowNameColor)
                    owner.popContentLocalScissor(nvg, clipName)
                }

                owner.addClickArea(nameX, nameY, nameW, nameH, Runnable { owner.beginProfileRename(isConfig, profileName) })

                for (index in 0 until 3) {
                    val actionX = btnX + index * (btnW + btnGap)
                    val hovered = mx >= actionX && mx <= actionX + btnW && my >= btnY && my <= btnY + btnH
                    val label = when (index) {
                        0 -> AetherLang.localize("Load")
                        1 -> AetherLang.localize("Export")
                        else -> AetherLang.localize("Delete")
                    }
                    val isDelete = index == 2
                    val bgColor = if (isDelete) {
                        if (hovered) Theme.withAlpha(0xFFCC3333.toInt(), 0.20f) else Theme.ELEMENT_BG
                    } else {
                        if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG
                    }
                    val borderColor = if (isDelete) {
                        if (hovered) Theme.withAlpha(0xFFCC3333.toInt(), 0.45f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f)
                    } else {
                        if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f)
                    }
                    val textColor = if (isDelete) {
                        if (hovered) 0xFFFF8888.toInt() else Theme.TEXT_VALUE
                    } else {
                        if (hovered) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE
                    }
                    nvg.roundedRect(actionX, btnY, btnW, btnH, 5f, bgColor)
                    nvg.rectOutline(actionX, btnY, btnW, btnH, 5f, 1f, borderColor)
                    nvg.textCentered(Fonts.REGULAR, label, actionX, btnY, btnW, btnH, 11.5f, textColor)
                    val help = when (index) {
                        0 -> if (isConfig) "Loads this saved configuration profile." else "Loads this saved theme profile."
                        1 -> "Copies this profile as JSON so it can be backed up or shared."
                        else -> "Deletes this saved profile."
                    }
                    owner.offerHoverHelp(
                        "profile-action:" + (if (isConfig) "config:" else "theme:") + profileName + ":" + index,
                        label, AetherLang.localize(help), actionX, btnY, btnW, btnH, mx, my
                    )
                    val action = index
                    owner.addClickArea(actionX, btnY, btnW, btnH, Runnable {
                        val mc = Minecraft.getInstance()
                        when (action) {
                            0 -> if (isConfig) {
                                if (ConfigProfileManager.load(profileName)) {
                                    owner.refreshContext()
                                    NotificationManager.success(AetherLang.localize("Config Loaded"), "\"$profileName\" loaded successfully")
                                } else {
                                    NotificationManager.error(AetherLang.localize("Config Load Failed"), "\"$profileName\" could not be loaded")
                                }
                            } else {
                                ThemeProfileManager.load(profileName)
                                NotificationManager.success(AetherLang.localize("Theme Loaded"), "\"$profileName\" loaded successfully")
                            }
                            1 -> {
                                val json = if (isConfig) ConfigProfileManager.exportJsonSanitized(profileName) else ThemeProfileManager.exportJson(profileName)
                                mc.keyboardHandler.setClipboard(json)
                                NotificationManager.info(AetherLang.localize("Copied to Clipboard"), "\"$profileName\" exported")
                            }
                            2 -> if (isConfig) {
                                ConfigProfileManager.delete(profileName)
                                NotificationManager.warning(AetherLang.localize("Config Deleted"), "\"$profileName\" was deleted")
                            } else {
                                ThemeProfileManager.delete(profileName)
                                NotificationManager.warning(AetherLang.localize("Theme Deleted"), "\"$profileName\" was deleted")
                            }
                        }
                    })
                }
                currentY += rowH + 5f
                currentTot += rowH + 5f
            }
        }

        currentY += 10f
        currentTot += 10f
        val importW = gw
        val importH = 32f
        val importHovered = mx >= gx && mx <= gx + importW && my >= currentY && my <= currentY + importH
        val importBg = if (importHovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG
        val importBrd = if (importHovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f)
        val importTxt = if (importHovered) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE
        nvg.roundedRect(gx, currentY, importW, importH, 7f, importBg)
        nvg.rectOutline(gx, currentY, importW, importH, 7f, 1f, importBrd)
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Import from Clipboard"), gx, currentY, importW, importH, 12.5f, importTxt)
        owner.addClickArea(gx, currentY, importW, importH, Runnable {
            val json = Minecraft.getInstance().keyboardHandler.getClipboard()
            if (!json.isNullOrBlank()) {
                val importName = if (owner.profileNameInput.isBlank()) "imported" else owner.profileNameInput
                if (isConfig) {
                    ConfigProfileManager.importFromClipboard(importName, json)
                } else {
                    ThemeProfileManager.importJson(importName, json)
                }
            }
        })
        currentY += importH + 16f
        currentTot += importH + 16f

        return LayoutCursor(currentY, currentTot)
    }

    private fun renderThemePresets(nvg: NVGRenderer, mx: Float, my: Float, gx: Float, gw: Float, y: Float): Float {
        nvg.text(Fonts.BOLD, AetherLang.localize("Colour Presets"), gx, y, 10f, Theme.TEXT_MUTED)
        val presets = ThemePreset.values()
        val gap = 8f
        val buttonH = 32f
        var minButtonW = nvg.textWidth(Fonts.REGULAR, AetherLang.localize("Default Colours"), 12.5f) + 24f
        for (preset in presets) {
            minButtonW = Math.max(minButtonW, nvg.textWidth(Fonts.REGULAR, AetherLang.localize(preset.label()), 12.5f) + 24f)
        }
        val columns = Math.max(1, Math.min(presets.size + 1, ((gw + gap) / (minButtonW + gap)).toInt()))
        val buttonW = (gw - gap * (columns - 1)) / columns
        for (i in 0..presets.size) {
            val preset = if (i < presets.size) presets[i] else null
            val label = AetherLang.localize(preset?.label() ?: "Default Colours")
            val buttonX = gx + (i % columns) * (buttonW + gap)
            val buttonY = y + 16f + (i / columns) * (buttonH + gap)
            val hovered = mx >= buttonX && mx <= buttonX + buttonW && my >= buttonY && my <= buttonY + buttonH
            val bg = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) else Theme.ELEMENT_BG
            val border = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) else Theme.withAlpha(0xFFFFFFFF.toInt(), 0.06f)
            val text = if (hovered) Theme.ACCENT_PRIMARY else Theme.TEXT_VALUE
            nvg.roundedRect(buttonX, buttonY, buttonW, buttonH, 7f, bg)
            nvg.rectOutline(buttonX, buttonY, buttonW, buttonH, 7f, 1f, border)
            nvg.textCentered(Fonts.REGULAR, label, buttonX, buttonY, buttonW, buttonH, 12.5f, text)
            owner.addClickArea(buttonX, buttonY, buttonW, buttonH, Runnable {
                owner.commitText()
                owner.commitColor()
                if (preset == null) {
                    Theme.resetColorsToDefaults()
                } else {
                    preset.apply()
                }
                Theme.saveTheme()
            })
        }
        val rows = (presets.size + columns) / columns
        return 16f + rows * (buttonH + gap) - gap + 18f
    }
}
