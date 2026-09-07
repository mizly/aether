package dev.aether.ui;

import dev.aether.config.ConfigProfileManager;
import dev.aether.config.ThemeProfileManager;
import dev.aether.notification.NotificationManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

import java.util.List;

final class MainGUIProfilesPanel {
    private final MainGUI owner;

    private record LayoutCursor(float y, float tot) {}

    MainGUIProfilesPanel(MainGUI owner) {
        this.owner = owner;
    }

    void commitRename() {
        if (!owner.profileRenameFocus) {
            return;
        }

        boolean isConfig = owner.profileRenameTarget == 0;
        String oldName = owner.profileRenameOriginal;
        String newName = owner.profileRenameInput.trim();
        owner.cancelProfileRename();
        if (newName.isBlank()) {
            return;
        }
        boolean renamed = isConfig
                ? ConfigProfileManager.rename(oldName, newName)
                : ThemeProfileManager.rename(oldName, newName);
        if (renamed) {
            String title = isConfig ? AetherLang.localize("Config Renamed") : AetherLang.localize("Theme Renamed");
            NotificationManager.success(title, "\"" + oldName + "\" renamed to \"" + newName + "\"");
        } else {
            String title = isConfig ? AetherLang.localize("Config Rename Failed") : AetherLang.localize("Theme Rename Failed");
            NotificationManager.error(title, "\"" + newName + "\" could not be used");
        }
    }

    void render(NVGRenderer nvg, float mx, float my) {
        float scrollTop = owner.contentScrollTop();
        float scrollH = owner.contentScrollHeight();

        if (!owner.suppressNestedContentScissor) {
            nvg.pushScissor(owner.contX, scrollTop, owner.contW, scrollH);
        }

        float gx = owner.contX + MainGUI.ITEM_PAD;
        float gw = owner.contW - MainGUI.ITEM_PAD * 2f;
        float y = scrollTop - owner.scrollY;
        float tot = 0f;

        if (owner.showConfigProfiles()) {
            y += 14f;
            tot += 14f;
            LayoutCursor cursor = renderSection(nvg, mx, my, gx, gw, y, tot, true, AetherLang.localize("Config Profiles"));
            y = cursor.y();
            tot = cursor.tot();
        }
        if (owner.showThemeProfiles()) {
            y += 14f;
            tot += 14f;
            LayoutCursor cursor = renderSection(nvg, mx, my, gx, gw, y, tot, false, AetherLang.localize("Theme Profiles"));
            y = cursor.y();
            tot = cursor.tot();
        }

        owner.maxScrollY = Math.max(0f, tot - scrollH + 16f);
        owner.targetScrollY = Math.max(0f, Math.min(owner.maxScrollY, owner.targetScrollY));
        if (!owner.suppressNestedContentScissor) {
            nvg.popScissor();
        }

        if (owner.maxScrollY > 0f) {
            float ratio = scrollH / (tot + 16f);
            float thumbH = Math.max(30f, scrollH * ratio);
            float trackT = scrollTop + 2f;
            float trackH = scrollH - 4f;
            owner.sbMainTrackT = trackT;
            owner.sbMainTrackH = trackH;
            owner.sbMainThumbH = thumbH;
            float thumbY = MainGUI.scrollbarThumbY(trackT, trackH, thumbH, owner.scrollY, owner.maxScrollY);
            nvg.roundedRect(owner.contX + owner.contW - 6f, thumbY, 4f, thumbH, 2f, Theme.withAlpha(Theme.TEXT_SECONDARY, 0.5f));
        }
    }

    private LayoutCursor renderSection(
            NVGRenderer nvg,
            float mx,
            float my,
            float gx,
            float gw,
            float y,
            float tot,
            boolean isConfig,
            String title
    ) {
        owner.renderSectionHeader(nvg, title, gx, y, gw);
        y += MainGUI.SECT_H;
        tot += MainGUI.SECT_H;
        y += MainGUI.SECT_SEP;
        tot += MainGUI.SECT_SEP;

        nvg.text(Fonts.BOLD, AetherLang.localize("Save Profile"), gx, y, 10f, Theme.TEXT_MUTED);
        y += 16f;
        tot += 16f;

        float fieldW = gw - 76f;
        float fieldH = 32f;
        boolean nameFocused = owner.isProfileNameFocused(isConfig);
        nvg.roundedRect(gx, y, fieldW, fieldH, 7f, Theme.BG_FIELD);
        nvg.rectOutline(gx, y, fieldW, fieldH, 7f, 1f, nameFocused ? Theme.BORDER_ACTIVE : Theme.BORDER_DEFAULT);
        String nameDisp = owner.profileNameInput.isEmpty() && !nameFocused ? AetherLang.localize("Profile name...") : owner.profileNameInput;
        int nameColor = owner.profileNameInput.isEmpty() && !nameFocused ? Theme.TEXT_MUTED : Theme.TEXT_LABEL;
        boolean clipProfileName = owner.pushContentLocalScissor(nvg, gx + 10f, y + 1f, fieldW - 20f, fieldH - 2f);
        nvg.text(Fonts.REGULAR, nameDisp, gx + 10f, y + (fieldH - 12f) / 2f, 12f, nameColor);
        owner.popContentLocalScissor(nvg, clipProfileName);
        if (nameFocused && (System.currentTimeMillis() / 530) % 2 == 0) {
            float cursorX = gx + 10f + nvg.textWidth(Fonts.REGULAR, owner.profileNameInput, 12f);
            nvg.rect(cursorX, y + 8f, 1f, fieldH - 16f, Theme.TEXT_LABEL);
        }

        int target = isConfig ? 0 : 1;
        final float fieldY = y;
        owner.addClickArea(gx, y, fieldW, fieldH, () -> {
            owner.commitText();
            owner.commitColor();
            owner.openDd = null;
            owner.profileNameTarget = target;
            owner.profileNameFocus = true;
        });

        float saveBtnX = gx + fieldW + 8f;
        float saveBtnW = 60f;
        boolean saveHov = mx >= saveBtnX && mx <= saveBtnX + saveBtnW && my >= fieldY && my <= fieldY + fieldH;
        int saveBg = saveHov ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG;
        int saveBrd = saveHov ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) : Theme.withAlpha(0xFFFFFFFF, 0.06f);
        int saveTxt = saveHov ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
        nvg.roundedRect(saveBtnX, y, saveBtnW, fieldH, 7f, saveBg);
        nvg.rectOutline(saveBtnX, y, saveBtnW, fieldH, 7f, 1f, saveBrd);
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Save"), saveBtnX, y, saveBtnW, fieldH, 12.5f, saveTxt);
        final String capturedName = owner.profileNameInput;
        owner.addClickArea(saveBtnX, y, saveBtnW, fieldH, () -> {
            if (!capturedName.isBlank()) {
                if (isConfig) {
                    ConfigProfileManager.save(capturedName);
                    NotificationManager.success(AetherLang.localize("Config Saved"), "\"" + capturedName + "\" saved successfully");
                } else {
                    ThemeProfileManager.save(capturedName);
                    NotificationManager.success(AetherLang.localize("Theme Saved"), "\"" + capturedName + "\" saved successfully");
                }
                owner.profileNameInput = "";
                owner.profileNameFocus = false;
            }
        });
        y += fieldH + 18f;
        tot += fieldH + 18f;

        nvg.text(Fonts.BOLD, AetherLang.localize("Saved Profiles"), gx, y, 10f, Theme.TEXT_MUTED);
        y += 14f;
        tot += 14f;

        List<String> profiles = isConfig ? ConfigProfileManager.list() : ThemeProfileManager.list();
        if (profiles.isEmpty()) {
            float emptyH = 42f;
            nvg.roundedRect(gx, y, gw, emptyH, 7f, Theme.CARD_BG);
            nvg.rectOutline(gx, y, gw, emptyH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF, 0.06f));
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize("No saved profiles yet."), gx, y, gw, emptyH, 11f, Theme.TEXT_MUTED);
            y += emptyH + 4f;
            tot += emptyH + 4f;
        } else {
            float rowH = 44f;
            float btnW = 60f;
            float btnH = 26f;
            float btnGap = 5f;
            for (String name : profiles) {
                float rowY = y;
                nvg.roundedRect(gx, rowY, gw, rowH, 7f, Theme.CARD_BG);
                nvg.rectOutline(gx, rowY, gw, rowH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF, 0.06f));

                float btnX = gx + gw - 3f * (btnW + btnGap) - btnGap;
                float btnY = rowY + (rowH - btnH) / 2f;
                final String profileName = name;
                float nameX = gx + 10f;
                float nameY = rowY + 7f;
                float nameW = Math.max(40f, btnX - nameX - 10f);
                float nameH = rowH - 14f;
                boolean renaming = owner.isProfileRenameFocused(isConfig, profileName);
                if (renaming) {
                    nvg.roundedRect(nameX, nameY, nameW, nameH, 5f, Theme.BG_FIELD);
                    nvg.rectOutline(nameX, nameY, nameW, nameH, 5f, 1f, Theme.BORDER_ACTIVE);
                    boolean clipRename = owner.pushContentLocalScissor(nvg, nameX + 8f, nameY, nameW - 16f, nameH);
                    nvg.text(Fonts.REGULAR, owner.profileRenameInput, nameX + 8f, rowY + (rowH - 12f) / 2f, 12f, Theme.TEXT_LABEL);
                    owner.popContentLocalScissor(nvg, clipRename);
                    if ((System.currentTimeMillis() / 530) % 2 == 0) {
                        float cursorX = nameX + 8f + nvg.textWidth(Fonts.REGULAR, owner.profileRenameInput, 12f);
                        nvg.rect(Math.min(cursorX, nameX + nameW - 8f), nameY + 7f, 1f, nameH - 14f, Theme.TEXT_LABEL);
                    }
                } else {
                    boolean nameHovered = mx >= nameX && mx <= nameX + nameW && my >= nameY && my <= nameY + nameH;
                    int rowNameColor = nameHovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_LABEL;
                    boolean clipName = owner.pushContentLocalScissor(nvg, nameX + 4f, rowY, nameW - 8f, rowH);
                    nvg.text(Fonts.REGULAR, name, nameX + 4f, rowY + (rowH - 12f) / 2f, 12f, rowNameColor);
                    owner.popContentLocalScissor(nvg, clipName);
                }

                owner.addClickArea(nameX, nameY, nameW, nameH, () -> owner.beginProfileRename(isConfig, profileName));

                for (int index = 0; index < 3; index++) {
                    float actionX = btnX + index * (btnW + btnGap);
                    boolean hovered = mx >= actionX && mx <= actionX + btnW && my >= btnY && my <= btnY + btnH;
                    String label = switch (index) {
                        case 0 -> AetherLang.localize("Load");
                        case 1 -> AetherLang.localize("Export");
                        default -> AetherLang.localize("Delete");
                    };
                    boolean isDelete = index == 2;
                    int bgColor = isDelete
                            ? (hovered ? Theme.withAlpha(0xFFCC3333, 0.20f) : Theme.ELEMENT_BG)
                            : (hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG);
                    int borderColor = isDelete
                            ? (hovered ? Theme.withAlpha(0xFFCC3333, 0.45f) : Theme.withAlpha(0xFFFFFFFF, 0.06f))
                            : (hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) : Theme.withAlpha(0xFFFFFFFF, 0.06f));
                    int textColor = isDelete
                            ? (hovered ? 0xFFFF8888 : Theme.TEXT_VALUE)
                            : (hovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE);
                    nvg.roundedRect(actionX, btnY, btnW, btnH, 5f, bgColor);
                    nvg.rectOutline(actionX, btnY, btnW, btnH, 5f, 1f, borderColor);
                    nvg.textCentered(Fonts.REGULAR, label, actionX, btnY, btnW, btnH, 11.5f, textColor);
                    final int action = index;
                    owner.addClickArea(actionX, btnY, btnW, btnH, () -> {
                        Minecraft mc = Minecraft.getInstance();
                        switch (action) {
                            case 0 -> {
                                if (isConfig) {
                                    if (ConfigProfileManager.load(profileName)) {
                                        owner.refreshContext();
                                        NotificationManager.success(AetherLang.localize("Config Loaded"), "\"" + profileName + "\" loaded successfully");
                                    } else {
                                        NotificationManager.error(AetherLang.localize("Config Load Failed"), "\"" + profileName + "\" could not be loaded");
                                    }
                                } else {
                                    ThemeProfileManager.load(profileName);
                                    NotificationManager.success(AetherLang.localize("Theme Loaded"), "\"" + profileName + "\" loaded successfully");
                                }
                            }
                            case 1 -> {
                                String json = isConfig
                                        ? ConfigProfileManager.exportJsonSanitized(profileName)
                                        : ThemeProfileManager.exportJson(profileName);
                                mc.keyboardHandler.setClipboard(json);
                                NotificationManager.info(AetherLang.localize("Copied to Clipboard"), "\"" + profileName + "\" exported");
                            }
                            case 2 -> {
                                if (isConfig) {
                                    ConfigProfileManager.delete(profileName);
                                    NotificationManager.warning(AetherLang.localize("Config Deleted"), "\"" + profileName + "\" was deleted");
                                } else {
                                    ThemeProfileManager.delete(profileName);
                                    NotificationManager.warning(AetherLang.localize("Theme Deleted"), "\"" + profileName + "\" was deleted");
                                }
                            }
                        }
                    });
                }
                y += rowH + 5f;
                tot += rowH + 5f;
            }
        }

        y += 10f;
        tot += 10f;
        float importW = gw;
        float importH = 32f;
        boolean importHovered = mx >= gx && mx <= gx + importW && my >= y && my <= y + importH;
        int importBg = importHovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG;
        int importBrd = importHovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) : Theme.withAlpha(0xFFFFFFFF, 0.06f);
        int importTxt = importHovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
        nvg.roundedRect(gx, y, importW, importH, 7f, importBg);
        nvg.rectOutline(gx, y, importW, importH, 7f, 1f, importBrd);
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Import from Clipboard"), gx, y, importW, importH, 12.5f, importTxt);
        owner.addClickArea(gx, y, importW, importH, () -> {
            String json = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (json != null && !json.isBlank()) {
                String importName = owner.profileNameInput.isBlank() ? "imported" : owner.profileNameInput;
                if (isConfig) {
                    ConfigProfileManager.importFromClipboard(importName, json);
                } else {
                    ThemeProfileManager.importJson(importName, json);
                }
            }
        });
        y += importH + 16f;
        tot += importH + 16f;

        return new LayoutCursor(y, tot);
    }
}
