package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.NumberText;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.PlotSetting;
import dev.aether.ui.settings.PlotToken;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.SectionSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SettingType;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.MONO;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// a module page's groups in aurora's look: rounded containers, hairline rows, inline explicit descriptions.
// stands in for the shared settings list until it lands, so it keeps to the controls aurora previews need
final class PanelRows {
    static final float GROUP_GAP = 14f;
    private static final float RADIUS = 12f;
    private static final float PAD_X = 18f;
    private static final float LABEL = 13f;
    private static final float DESC = 11.5f;
    private static final float DESC_LINE = 15f;
    private static final float ROW_MIN = 48f;
    private static final float FIELD_H = 30f;
    private static final float THUMB = 68f;

    record Key(String page, String group, int groupIndex, String setting, int settingIndex, String part) {
    }

    record Anchor(String key, String label, float y, boolean section) {
    }

    private final PanelStyle style;
    private final Set<String> peeked = new HashSet<>();
    private final java.util.Map<PlotSetting, Rect> thumbs = new java.util.IdentityHashMap<>();
    private int rangeHandle;

    PanelRows(PanelStyle style) {
        this.style = style;
    }

    // draws every group from y down and returns the content bottom; anchors get content-space tops
    float draw(PanelFrame f, String pageId, List<SettingGroup> groups, float x, float y, float w, boolean pageOn,
               float contentOrigin, List<Anchor> anchors, String enterKey) {
        GuiCanvas c = f.canvas();
        float cursor = y;
        int index = 0;
        int drawnIndex = 0;
        for (SettingGroup group : groups) {
            int groupIndex = index++;
            if (!group.hasSettings() && group.isAlwaysOn()) {
                continue;
            }
            String groupKey = pageId + "/" + group.getRawName() + "#" + groupIndex;
            float enter = f.anim().stagger(enterKey, drawnIndex++);
            anchors.add(new Anchor(groupKey, group.getName(), cursor - contentOrigin, false));
            c.save();
            c.alpha(enter);
            c.translate(0f, (1f - enter) * 10f);
            float height = drawGroup(f, pageId, group, groupIndex, groupKey, x, cursor, w, pageOn, contentOrigin, anchors);
            c.restore();
            cursor += height + GROUP_GAP;
        }
        return cursor;
    }

    private float drawGroup(PanelFrame f, String pageId, SettingGroup group, int groupIndex, String groupKey,
                            float x, float y, float w, boolean pageOn, float contentOrigin, List<Anchor> anchors) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        boolean toggleable = !group.isAlwaysOn();
        boolean enabled = group.isEnabled();
        boolean open = !toggleable || enabled || peeked.contains(groupKey);
        float innerX = x + PAD_X;
        float innerW = w - PAD_X * 2f;

        float headerH = groupHeaderHeight(c, group, innerW - 120f);
        List<Setting> visible = new ArrayList<>();
        if (open) {
            for (Setting setting : group.getSettings()) {
                if (setting.isVisible()) {
                    visible.add(setting);
                }
            }
        }
        float bodyH = 0f;
        for (Setting setting : visible) {
            bodyH += rowHeight(c, setting, innerW);
        }
        float total = headerH + bodyH + (visible.isEmpty() ? 0f : 6f);
        Rect box = new Rect(x, y, w, total);
        if (c.isVisible(box)) {
            PanelPaint.card(c, p, box, RADIUS, 0f);
        }

        // header
        float titleY = y + 18f;
        Key toggleKey = new Key(pageId, group.getRawName(), groupIndex, null, -1, "toggle");
        float right = innerX + innerW;
        if (toggleable) {
            Rect sw = new Rect(right - 38f, y + 15f, 38f, 22f);
            float on = f.anim().spring(toggleKey, enabled ? 1f : 0f);
            PanelPaint.toggle(c, p, sw, on, f.hits().hovered(toggleKey) ? 1f : 0f, true);
            f.hits().add(toggleKey, sw.inset(-4f), HitHandler.click(() -> {
                group.toggle();
                lever(f, group.isEnabled());
                if (group.isEnabled()) {
                    peeked.remove(groupKey);
                }
            }), Cursor.HAND);
            right -= 52f;
            if (!enabled) {
                Key peekKey = new Key(pageId, group.getRawName(), groupIndex, null, -1, "peek");
                boolean peeking = peeked.contains(groupKey);
                String label = AetherLang.localize(peeking ? "Hide" : "Show settings");
                float lw = c.textWidth(MEDIUM, 11.5f, label) + 34f;
                Rect peek = new Rect(right - lw, y + 14f, lw, 24f);
                boolean hover = f.hits().hovered(peekKey);
                c.roundedRect(peek, 12f, Argb.withAlpha(p.text(), hover ? 0.10f : 0.05f));
                PanelPaint.text(c, MEDIUM, 11.5f, label, peek.x() + 10f, peek.centerY(), p.textSecondary());
                if (peeking) {
                    PanelPaint.chevronUp(c, peek.right() - 13f, peek.centerY(), 7f, 1.4f, p.textMuted());
                } else {
                    PanelPaint.chevronDown(c, peek.right() - 13f, peek.centerY(), 7f, 1.4f, p.textMuted());
                }
                f.hits().add(peekKey, peek, HitHandler.click(() -> {
                    if (!peeked.remove(groupKey)) {
                        peeked.add(groupKey);
                    }
                }), Cursor.HAND);
                right = peek.x() - 8f;
            }
        }
        String summary = group.summary();
        if (summary != null && !summary.isBlank()) {
            float sw = PanelPaint.pillWidth(c, summary, MEDIUM, 11f, 9f);
            PanelPaint.pill(c, right - sw, y + 26f, summary, MEDIUM, 11f, p.textSecondary(),
                    Argb.withAlpha(p.text(), 0.06f), 9f, 22f);
            right -= sw + 8f;
        }
        int titleColor = toggleable && !enabled ? p.textSecondary() : p.text();
        PanelPaint.fitText(c, SEMIBOLD, 14.5f, group.getName(), innerX, titleY + 7f, right - innerX - 8f, titleColor);
        String description = group.getDescription();
        if (description != null && !description.isBlank()) {
            float dy = titleY + 20f;
            for (String line : limit(c.wrap(REGULAR, DESC, description, innerW - 130f), 2)) {
                c.text(REGULAR, DESC, line, innerX, dy, p.textMuted());
                dy += DESC_LINE;
            }
        }

        // rows
        float rowY = y + headerH;
        if (!visible.isEmpty()) {
            c.line(x, rowY, x + w, rowY, 1f, PanelPaint.hairline(p));
        }
        boolean dim = !pageOn || (toggleable && !enabled);
        int settingIndex = 0;
        for (Setting setting : group.getSettings()) {
            int si = settingIndex++;
            if (!visible.contains(setting)) {
                continue;
            }
            float h = rowHeight(c, setting, innerW);
            Rect row = new Rect(x, rowY, w, h);
            Key key = new Key(pageId, group.getRawName(), groupIndex, setting.getRawName(), si, "row");
            if (setting.getType() == SettingType.SECTION) {
                anchors.add(new Anchor(key.toString(), setting.getName(), rowY - contentOrigin, true));
            }
            if (c.isVisible(row)) {
                c.save();
                if (dim) {
                    c.alpha(0.55f);
                }
                if (rowY > y + headerH + 0.5f && setting.getType() != SettingType.SECTION) {
                    c.line(innerX, rowY, innerX + innerW, rowY, 1f, PanelPaint.hairline(p));
                }
                drawRow(f, setting, key, row, innerX, innerW);
                c.restore();
            }
            rowY += h;
        }
        return total;
    }

    // a few loose rows without a group container, for the orbit's inline essentials
    float drawFlat(PanelFrame f, String pageId, List<Setting> settings, float x, float y, float w, boolean on) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float innerX = x + PAD_X;
        float innerW = w - PAD_X * 2f;
        float rowY = y;
        int index = 0;
        for (Setting setting : settings) {
            float h = rowHeight(c, setting, innerW);
            Rect row = new Rect(x, rowY, w, h);
            Key key = new Key(pageId, "essentials", 0, setting.getRawName(), index++, "row");
            if (c.isVisible(row)) {
                c.save();
                if (!on) {
                    c.alpha(0.55f);
                }
                c.line(innerX, rowY, innerX + innerW, rowY, 1f, PanelPaint.hairline(p));
                drawRow(f, setting, key, row, innerX, innerW);
                c.restore();
            }
            rowY += h;
        }
        return rowY - y;
    }

    float groupHeaderHeight(GuiCanvas c, SettingGroup group, float descW) {
        String description = group.getDescription();
        int lines = description == null || description.isBlank() ? 0
                : Math.min(2, c.wrap(REGULAR, DESC, description, descW).size());
        return Math.max(54f, 38f + lines * DESC_LINE + 12f);
    }

    // -- rows -------------------------------------------------------------------

    private float controlWidth(Setting setting, float innerW) {
        PanelItems.Kind items = PanelItems.kind(setting);
        if (items != null) {
            return PanelItems.stacked(items, innerW) ? 0f : PanelItems.inlineWidth(items);
        }
        if (setting instanceof DropdownSetting dropdown && segmented(dropdown, innerW)) {
            return segmentsWidth(dropdown);
        }
        return switch (setting.getType()) {
            case TOGGLE -> 38f;
            case SLIDER, RANGE_SLIDER -> Math.max(180f, Math.min(300f, innerW * 0.42f));
            case DROPDOWN -> Math.min(200f, innerW * 0.4f);
            case TEXT -> Math.min(220f, innerW * 0.42f);
            case COLOR -> 120f;
            case KEYBIND -> 120f;
            case INFO -> Math.min(260f, innerW * 0.45f);
            case PLOT -> Math.min(230f, innerW * 0.46f);
            default -> 0f;
        };
    }

    private boolean stacked(Setting setting) {
        return switch (setting.getType()) {
            case LIST, DROPDOWN_LIST, MULTI_DROPDOWN, POSITION -> true;
            case TEXT -> ((TextSetting) setting).isMultiline();
            case INFO -> ((InfoSetting) setting).isMultiline();
            default -> false;
        };
    }

    float rowHeight(GuiCanvas c, Setting setting, float innerW) {
        if (setting.getType() == SettingType.SECTION) {
            String description = ((SectionSetting) setting).getDescription();
            int lines = description == null || description.isBlank() ? 0
                    : Math.min(2, c.wrap(REGULAR, DESC, description, innerW).size());
            return 40f + lines * DESC_LINE;
        }
        float labelW = labelWidth(setting, innerW);
        float text = labelBlock(c, setting, labelW);
        PanelItems.Kind items = PanelItems.kind(setting);
        if (items != null && PanelItems.stacked(items, innerW)) {
            return text + 18f + PanelItems.blockHeight(items);
        }
        float h = Math.max(ROW_MIN, text + 24f);
        if (setting.getType() == SettingType.PLOT) {
            h = Math.max(h, THUMB + 16f);
        }
        if (stacked(setting)) {
            h = text + 24f + stackedHeight(c, setting, innerW) + 10f;
        }
        return h;
    }

    private float labelWidth(Setting setting, float innerW) {
        float control = controlWidth(setting, innerW);
        return Math.max(80f, innerW - (control > 0f ? control + 24f : 0f));
    }

    private float labelBlock(GuiCanvas c, Setting setting, float labelW) {
        String description = setting.explicitDescription();
        int lines = description == null || description.isBlank() ? 0
                : Math.min(3, c.wrap(REGULAR, DESC, description, labelW).size());
        return 16f + lines * DESC_LINE + (lines > 0 ? 2f : 0f);
    }

    private float stackedHeight(GuiCanvas c, Setting setting, float innerW) {
        return switch (setting.getType()) {
            case TEXT -> ((TextSetting) setting).getVisibleLines() * 16f + 14f;
            case INFO -> Math.max(1, c.wrap(MONO, 11.5f, ((InfoSetting) setting).getValue(), innerW - 20f).size()) * 16f + 12f;
            case POSITION -> FIELD_H;
            default -> chipRows(c, chipLabels(setting), innerW) * 30f;
        };
    }

    private void drawRow(PanelFrame f, Setting setting, Key key, Rect row, float innerX, float innerW) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        if (setting.getType() == SettingType.SECTION) {
            drawSection(c, p, (SectionSetting) setting, row, innerX, innerW);
            return;
        }
        if (!f.frozen() && c.toRoot(row).contains(f.mouseX(), f.mouseY()) && c.rootClip().contains(f.mouseX(), f.mouseY())) {
            style.hover = new PanelView.Hover(setting, key.page(), key.group());
        }
        boolean rowHover = f.hits().hovered(key);
        PanelItems.Kind items = PanelItems.kind(setting);
        boolean wholeRow = items == null && !(setting instanceof DropdownSetting d && segmented(d, innerW)) && switch (setting.getType()) {
            case TOGGLE, ACTION, DROPDOWN, COLOR, PLOT -> true;
            default -> false;
        };
        if (wholeRow) {
            float t = f.anim().hover(key, rowHover);
            if (t > 0.01f) {
                c.rect(new Rect(row.x() + 1f, row.y() + 0.5f, row.w() - 2f, row.h() - 1f), Argb.multiplyAlpha(p.hover(), t));
            }
            f.hits().add(key, row, rowHandler(f, setting, key, row), Cursor.HAND);
        }

        float labelW = labelWidth(setting, innerW);
        boolean stacked = stacked(setting) || items != null && PanelItems.stacked(items, innerW);
        float labelTop = stacked ? row.y() + 12f : row.centerY() - labelBlock(c, setting, labelW) / 2f + 1f;
        int labelColor = setting.getType() == SettingType.ACTION ? p.accent() : p.text();
        c.text(MEDIUM, LABEL, c.ellipsize(MEDIUM, LABEL, setting.getName(), labelW), innerX, labelTop, labelColor);
        String description = setting.explicitDescription();
        if (description != null && !description.isBlank()) {
            float dy = labelTop + 18f;
            for (String line : limit(c.wrap(REGULAR, DESC, description, labelW), 3)) {
                c.text(REGULAR, DESC, line, innerX, dy, p.textMuted());
                dy += DESC_LINE;
            }
        }

        float right = innerX + innerW;
        float cy = stacked ? labelTop + 8f : row.centerY();
        if (items != null) {
            if (stacked) {
                Rect block = new Rect(innerX, labelTop + labelBlock(c, setting, labelW) + 4f, innerW,
                        PanelItems.blockHeight(items));
                PanelItems.drawBlock(f, items, setting, key.toString(), block);
            } else {
                PanelItems.drawInline(f, items, setting, key.toString(), right, cy);
            }
            return;
        }
        switch (setting.getType()) {
            case TOGGLE -> {
                ToggleSetting toggle = (ToggleSetting) setting;
                Rect sw = new Rect(right - 38f, cy - 11f, 38f, 22f);
                float on = f.anim().spring(new Key(key.page(), key.group(), key.groupIndex(), key.setting(),
                        key.settingIndex(), "knob"), toggle.getValue() ? 1f : 0f);
                // the box swells mid-change and a ring rings out when it turns on
                float t = f.anim().ease(new Key(key.page(), key.group(), key.groupIndex(), key.setting(),
                        key.settingIndex(), "pop"), toggle.getValue() ? 1f : 0f, 320f);
                float pop = 4f * t * (1f - t);
                float bx = sw.right() - 10f, by = sw.centerY();
                if (toggle.getValue() && t < 0.999f) {
                    c.strokeCircle(bx, by, 10f + 12f * t, 1.5f, Argb.withAlpha(p.accent(), 0.5f * (1f - t)));
                }
                c.save();
                c.translate(bx, by);
                c.scale(1f + 0.24f * pop);
                c.translate(-bx, -by);
                PanelPaint.toggle(c, p, sw, on, rowHover ? 1f : 0f, true);
                c.restore();
            }
            case SLIDER -> drawSlider(f, (SliderSetting) setting, key, right, cy, innerW);
            case RANGE_SLIDER -> drawRange(f, (RangeSliderSetting) setting, key, right, cy, innerW);
            case DROPDOWN -> {
                DropdownSetting dropdown = (DropdownSetting) setting;
                if (segmented(dropdown, innerW)) {
                    drawSegments(f, dropdown, key, right, cy);
                    return;
                }
                float fw = controlWidth(setting, innerW);
                Rect field = new Rect(right - fw, cy - FIELD_H / 2f, fw, FIELD_H);
                int index = dropdown.getSelectedIndex();
                List<String> options = dropdown.getOptions();
                String value = index >= 0 && index < options.size() ? options.get(index)
                        : AetherLang.localize("unknown") + ": " + index;
                drawField(c, p, field, rowHover, style.menuOpenFor(key));
                Icon icon = dropdown.optionIcon(index);
                float tx = field.x() + 11f;
                if (icon != null) {
                    PanelPaint.icon(c, icon, tx + 8f, field.centerY(), 16f, p.text());
                    tx += 22f;
                }
                PanelPaint.fitText(c, MEDIUM, 12.5f, value, tx, field.centerY(), field.right() - 26f - tx, p.text());
                PanelPaint.chevronDown(c, field.right() - 14f, field.centerY(), 7f, 1.5f, p.textMuted());
            }
            case TEXT -> drawText(f, (TextSetting) setting, key, row, right, cy, innerX, innerW, labelTop);
            case INFO -> {
                InfoSetting info = (InfoSetting) setting;
                String value = AetherLang.localize(info.getValue());
                if (info.isMultiline()) {
                    Rect box = new Rect(innerX, labelTop + labelBlock(c, setting, labelW) + 6f, innerW,
                            stackedHeight(c, setting, innerW));
                    c.roundedRect(box, 8f, Argb.withAlpha(p.text(), 0.04f));
                    float ly = box.y() + 7f;
                    for (String line : c.wrap(MONO, 11.5f, value, innerW - 20f)) {
                        c.text(MONO, 11.5f, line, box.x() + 10f, ly, p.textSecondary());
                        ly += 16f;
                    }
                } else {
                    PanelPaint.textRight(c, MEDIUM, 12.5f, c.ellipsize(MEDIUM, 12.5f, value,
                            controlWidth(setting, innerW)), right, cy, p.textSecondary());
                }
            }
            case ACTION -> PanelPaint.button(c, p, new Rect(right - 64f, cy - 12f, 64f, 24f), AetherLang.localize("Run"),
                    null, PanelPaint.ButtonKind.GHOST, rowHover ? 1f : 0f, f.hits().active(key), true);
            case COLOR -> {
                int argb = ((ColorSetting) setting).getValue();
                Rect swatch = new Rect(right - 40f, cy - 12f, 40f, 24f);
                checker(c, swatch);
                c.roundedRect(swatch, 7f, argb);
                c.strokeRect(swatch, 7f, 1f, Argb.withAlpha(p.text(), 0.18f));
                String hex = String.format(Locale.ROOT, "#%08X", argb);
                PanelPaint.textRight(c, MONO, 11.5f, hex, swatch.x() - 10f, cy, p.textMuted());
            }
            case KEYBIND -> {
                String name = keyName((KeybindSetting) setting);
                float kw = c.textWidth(MEDIUM, 12f, name) + 24f;
                Rect cap = new Rect(right - Math.max(64f, kw), cy - 14f, Math.max(64f, kw), 28f);
                boolean hover = f.hits().hovered(key);
                c.roundedRect(cap, 8f, Argb.withAlpha(p.text(), hover ? 0.10f : 0.06f));
                c.strokeRect(cap, 8f, 1f, Argb.withAlpha(p.border(), 0.45f));
                c.line(cap.x() + 6f, cap.bottom() - 1f, cap.right() - 6f, cap.bottom() - 1f, 1f,
                        Argb.withAlpha(p.border(), 0.5f));
                PanelPaint.textCentered(c, MEDIUM, 12f, name, cap.centerX(), cap.centerY(), p.text());
            }
            case PLOT -> {
                PlotSetting plot = (PlotSetting) setting;
                String text = plot.isEmpty() ? AetherLang.localize(plot.emptyMeaning() == PlotSetting.EmptyMeaning.ALL
                        ? "All plots" : "None") : AetherLang.localize(plot.selection().size() == 1 ? "Plot" : "Plots")
                        + " " + String.join(", ", plot.selection().stream().map(PlotToken::text).toList());
                float tw = Math.min(c.textWidth(SEMIBOLD, 13f, text), controlWidth(setting, innerW) - THUMB - 12f);
                PanelPaint.fitText(c, SEMIBOLD, 13f, text, right - tw, cy, tw, p.text());
                Rect thumb = new Rect(right - tw - 12f - THUMB, cy - THUMB / 2f, THUMB, THUMB);
                style.plotHooks.paintThumbnail(c, plot, thumb);
                thumbs.put(plot, c.toRoot(thumb));
            }
            case LIST, DROPDOWN_LIST, MULTI_DROPDOWN -> drawChips(f, setting, key, innerX,
                    labelTop + labelBlock(c, setting, labelW) + 6f, innerW);
            case POSITION -> drawPosition(f, (PositionSetting) setting, key, innerX,
                    labelTop + labelBlock(c, setting, labelW) + 6f, innerW);
            default -> {
            }
        }
    }

    private long lastNotch;

    // a comparator's tick as a slider moves, pitched by how far along it is and never faster than a beat
    void notch(PanelFrame f, float along) {
        style.pressSounded = true;
        long now = System.nanoTime();
        if (now - lastNotch < 45_000_000L) return;
        lastNotch = now;
        f.host().sound(PanelHost.Sound.NOTCH, 0.5f + 0.9f * along);
    }

    // a lever's clack, higher thrown on than off, the way the block sounds
    void lever(PanelFrame f, boolean on) {
        f.host().sound(on ? PanelHost.Sound.LEVER_ON : PanelHost.Sound.LEVER_OFF, on ? 0.6f : 0.5f);
        style.pressSounded = true;
    }

    private HitHandler rowHandler(PanelFrame f, Setting setting, Key key, Rect row) {
        return HitHandler.click(() -> {
            switch (setting) {
                case ToggleSetting toggle -> {
                    toggle.toggle();
                    lever(f, toggle.getValue());
                }
                case ActionSetting action -> action.execute();
                case DropdownSetting dropdown -> style.openDropdown(key, dropdown, f.canvas().toRoot(row));
                case ColorSetting color -> style.openColor(key, color, f.canvas().toRoot(row));
                case PlotSetting plot -> style.plotHooks.open(plot, thumbs.getOrDefault(plot, f.canvas().toRoot(row)));
                default -> {
                }
            }
        });
    }

    private void drawSection(GuiCanvas c, Palette p, SectionSetting section, Rect row, float innerX, float innerW) {
        float y = row.y() + 16f;
        c.line(innerX, row.y() + 0.5f, innerX + innerW, row.y() + 0.5f, 1f, PanelPaint.hairline(p));
        String title = section.getName().toUpperCase(Locale.ROOT);
        c.text(SEMIBOLD, 10.5f, title, innerX, y, Argb.mix(p.accent(), p.text(), 0.25f));
        String description = section.getDescription();
        if (description != null && !description.isBlank()) {
            float dy = y + 16f;
            for (String line : limit(c.wrap(REGULAR, DESC, description, innerW), 2)) {
                c.text(REGULAR, DESC, line, innerX, dy, p.textMuted());
                dy += DESC_LINE;
            }
        }
    }

    private void drawSlider(PanelFrame f, SliderSetting slider, Key key, float right, float cy, float innerW) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float width = controlWidth(slider, innerW);
        String value = formatValue(slider.getValue(), slider.getDecimals(), slider.getSuffix());
        float bubbleW = Math.max(52f, c.textWidth(MEDIUM, 12f, value) + 20f);
        Rect bubble = new Rect(right - bubbleW, cy - 13f, bubbleW, 26f);
        valueBox(f, key, bubble, value, NumberText.format(slider.getValue(), slider.getDecimals()), text -> {
            float[] n = numbers(text);
            if (n.length > 0) slider.setValue(quantize(n[0], slider.getDecimals()));
        });
        Rect track = new Rect(right - width, cy - 2f, width - bubbleW - 14f, 4f);
        float t = range(slider.getValue(), slider.getMin(), slider.getMax());
        drawTrack(f, key, track, t, -1f, value);
        Rect hit = new Rect(track.x() - 8f, cy - 12f, track.w() + 16f, 24f);
        f.hits().add(key, hit, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) {
                    return false;
                }
                style.pressSounded = true;
                apply(e);
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                apply(e);
            }

            private void apply(PointerEvent e) {
                Rect r = e.pressRect().inset(8f, 0f, 8f, 0f);
                float k = Math.max(0f, Math.min(1f, (e.localX() - r.x()) / Math.max(1f, r.w())));
                float before = slider.getValue();
                slider.setValue(quantize(slider.getMin() + (slider.getMax() - slider.getMin()) * k, slider.getDecimals()));
                if (slider.getValue() != before) notch(f, k);
            }
        }, Cursor.HAND);
    }

    private void drawRange(PanelFrame f, RangeSliderSetting range, Key key, float right, float cy, float innerW) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float width = controlWidth(range, innerW);
        String value = formatValue(range.getLowerValue(), range.getDecimals(), "") + " – "
                + formatValue(range.getUpperValue(), range.getDecimals(), range.getSuffix());
        float bubbleW = Math.max(52f, c.textWidth(MEDIUM, 12f, value) + 20f);
        Rect bubble = new Rect(right - bubbleW, cy - 13f, bubbleW, 26f);
        String editText = NumberText.format(range.getLowerValue(), range.getDecimals()) + " - "
                + NumberText.format(range.getUpperValue(), range.getDecimals());
        valueBox(f, key, bubble, value, editText, text -> {
            float[] n = numbers(text);
            if (n.length == 1) range.setValues(quantize(n[0], range.getDecimals()), quantize(n[0], range.getDecimals()));
            if (n.length >= 2) {
                float a = quantize(n[0], range.getDecimals()), b = quantize(n[1], range.getDecimals());
                range.setValues(Math.min(a, b), Math.max(a, b));
            }
        });
        float trackW = Math.max(60f, width - bubbleW - 14f);
        Rect track = new Rect(right - bubbleW - 14f - trackW, cy - 2f, trackW, 4f);
        float lo = range(range.getLowerValue(), range.getMin(), range.getMax());
        float hi = range(range.getUpperValue(), range.getMin(), range.getMax());
        drawTrack(f, key, track, hi, lo);
        Rect hit = new Rect(track.x() - 8f, cy - 12f, track.w() + 16f, 24f);
        f.hits().add(key, hit, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) {
                    return false;
                }
                float k = fraction(e);
                float l = range(range.getLowerValue(), range.getMin(), range.getMax());
                float u = range(range.getUpperValue(), range.getMin(), range.getMax());
                rangeHandle = Math.abs(k - l) <= Math.abs(k - u) && !(k > u) ? 0 : 1;
                style.pressSounded = true;
                apply(k);
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                apply(fraction(e));
            }

            private float fraction(PointerEvent e) {
                Rect r = e.pressRect().inset(8f, 0f, 8f, 0f);
                return Math.max(0f, Math.min(1f, (e.localX() - r.x()) / Math.max(1f, r.w())));
            }

            private void apply(float k) {
                float v = quantize(range.getMin() + (range.getMax() - range.getMin()) * k, range.getDecimals());
                float lower = range.getLowerValue(), upper = range.getUpperValue();
                if (rangeHandle == 0) {
                    range.setValues(Math.min(v, range.getUpperValue()), range.getUpperValue());
                } else {
                    range.setValues(range.getLowerValue(), Math.max(v, range.getLowerValue()));
                }
                if (range.getLowerValue() != lower || range.getUpperValue() != upper) notch(f, k);
            }
        }, Cursor.HAND);
    }

    // from < 0 draws a single-knob slider filled from the left
    // a short choice of two to four options shows them all as buttons with a sliding highlight, one click to pick
    private static boolean segmented(DropdownSetting dropdown, float innerW) {
        int n = dropdown.getOptions().size();
        return n >= 2 && n <= 4 && segmentsWidth(dropdown) <= Math.max(220f, innerW * 0.56f);
    }

    private static float segmentWidth(String option) {
        return option.length() * 6.9f + 22f;
    }

    private static float segmentsWidth(DropdownSetting dropdown) {
        float w = 4f;
        for (String option : dropdown.getOptions()) w += segmentWidth(option);
        return w;
    }

    private void drawSegments(PanelFrame f, DropdownSetting dropdown, Key key, float right, float cy) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        List<String> options = dropdown.getOptions();
        float width = segmentsWidth(dropdown);
        Rect box = new Rect(right - width, cy - 15f, width, 30f);
        c.roundedRect(box, 9f, PanelPaint.fieldFill(p));
        c.strokeRect(box, 9f, 1f, Argb.withAlpha(p.border(), 0.3f));
        int selected = dropdown.getSelectedIndex();
        float x = box.x() + 2f;
        float selX = x, selW = 0f;
        float[] lefts = new float[options.size()];
        for (int i = 0; i < options.size(); i++) {
            lefts[i] = x;
            float w = segmentWidth(options.get(i));
            if (i == selected) {
                selX = x;
                selW = w;
            }
            x += w;
        }
        Key slideX = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(), "seg.x");
        Key slideW = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(), "seg.w");
        float hx = f.anim().spring(slideX, selX);
        float hw = f.anim().spring(slideW, selW);
        if (selected >= 0 && selected < options.size()) {
            Rect highlight = new Rect(hx, box.y() + 2f, hw, box.h() - 4f);
            c.roundedRect(highlight, 7f, p.accent());
        }
        for (int i = 0; i < options.size(); i++) {
            float w = segmentWidth(options.get(i));
            Rect seg = new Rect(lefts[i], box.y() + 2f, w, box.h() - 4f);
            Key segKey = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(), "seg" + i);
            float hover = f.anim().hover(segKey, f.hits().hovered(segKey));
            boolean on = i == selected;
            if (!on && hover > 0.01f) c.roundedRect(seg, 7f, Argb.withAlpha(p.text(), 0.07f * hover));
            int color = on ? p.onAccent() : Argb.mix(p.textMuted(), p.text(), hover);
            PanelPaint.textCentered(c, on ? SEMIBOLD : MEDIUM, 12f, c.ellipsize(MEDIUM, 12f, options.get(i), w - 10f),
                    seg.centerX(), seg.centerY(), color);
            int index = i;
            f.hits().add(segKey, seg, HitHandler.click(() -> dropdown.setSelectedIndex(index)), Cursor.HAND);
        }
    }

    // a slider's value box: shows the value, and on a click becomes a field to type a new one into
    private void valueBox(PanelFrame f, Key key, Rect bubble, String shown, String editText,
                          java.util.function.Consumer<String> commit) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Key boxKey = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(), "value");
        boolean editing = style.editing(boxKey);
        boolean hover = f.hits().hovered(boxKey);
        if (editing) {
            drawField(c, p, bubble, hover, true);
            style.drawEditor(f, bubble.inset(8f, 0f, 8f, 0f), false);
        } else {
            c.roundedRect(bubble, 8f, PanelPaint.fieldFill(p));
            c.strokeRect(bubble, 8f, 1f, Argb.withAlpha(p.border(), hover ? 0.6f : 0.30f));
            PanelPaint.textCentered(c, MEDIUM, 12f, shown, bubble.centerX(), bubble.centerY(), p.text());
        }
        f.hits().add(boxKey, bubble, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) {
                    return false;
                }
                style.beginEdit(boxKey, editText, false, commit);
                style.editorPress(e.localX() - bubble.x() - 8f, 0f);
                return true;
            }
        }, Cursor.IBEAM);
    }

    // every number in what was typed, so "2.5s", "500 - 3000" and "-30" all read the way they look
    static float[] numbers(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text == null ? "" : text);
        java.util.List<Float> found = new ArrayList<>();
        while (m.find()) {
            String n = m.group();
            // a dash between two numbers is a range separator, not a minus sign
            if (n.startsWith("-") && !found.isEmpty()) n = n.substring(1);
            try {
                found.add(Float.parseFloat(n));
            } catch (NumberFormatException ignored) {
            }
        }
        float[] out = new float[found.size()];
        for (int i = 0; i < out.length; i++) out[i] = found.get(i);
        return out;
    }

    private void drawTrack(PanelFrame f, Key key, Rect track, float to, float from) {
        drawTrack(f, key, track, to, from, null);
    }

    // the track as a line of redstone dust: powered from the start of the fill up to the knob, a redstone torch,
    // glowing brightest beside it and fading a level a dot back the way wire does; the rest lies dark. label, when
    // given, shows over the torch in a minecraft tooltip while it is dragged
    private void drawTrack(PanelFrame f, Key key, Rect track, float to, float from, String label) {
        GuiCanvas c = f.canvas();
        boolean dragging = f.hits().active(key);
        float hover = f.anim().hover(key, f.hits().hovered(key) || dragging);
        float grab = f.anim().spring(new Key(key.page(), key.group(), key.groupIndex(), key.setting(),
                key.settingIndex(), "grab"), dragging ? 1f : 0f);
        float cy = track.centerY();
        float start = from < 0f ? 0f : from;
        float spacing = 7f;
        int dots = Math.max(2, (int) (track.w() / spacing) + 1);
        float step = track.w() / (dots - 1);
        float kx = track.x() + track.w() * to;
        float[] xs = new float[dots];
        int[] colors = new int[dots];
        for (int i = 0; i < dots; i++) {
            float x = track.x() + i * step;
            float u = (x - track.x()) / Math.max(1f, track.w());
            int power = u >= start - 1e-3f && u <= to + 1e-3f ? Math.max(1, 15 - (int) ((kx - x) / spacing)) : 0;
            xs[i] = x;
            colors[i] = redstone(power);
        }
        for (int i = 0; i + 1 < dots; i++) c.line(xs[i], cy, xs[i + 1], cy, 2.6f, colors[i]);
        for (int i = 0; i < dots; i++) {
            float x = xs[i];
            int color = colors[i];
            c.legacy(nvg -> nvg.guiSprite(DUST, x - 7f, cy - 7f, 14f, 14f, color));
        }
        float[] knobs = from >= 0f ? new float[]{from, to} : new float[]{to};
        for (float k : knobs) {
            float x = track.x() + track.w() * k;
            float lift = 2f * hover + 3f * grab;
            float size = 24f + 2f * hover + 4f * grab;
            c.legacy(nvg -> {
                nvg.radialGradient(x, cy - 6f - lift, 2f, 14f + 4f * hover, Argb.withAlpha(0xFFFF2A1A, 0.25f + 0.25f * hover),
                        0x00FF2A1A);
                nvg.mcIcon(dev.aether.renderer.McIcons.of("minecraft:redstone_torch"), x - size / 2f, cy - size + 5f - lift,
                        size, 0xFFFFFFFF);
            });
        }
        if (label != null && grab > 0.02f) {
            int scale = 2;
            float w = dev.aether.renderer.McBitmapFont.widthLiteral(label, scale) + 12f, h = 8f * scale + 10f;
            float by = cy - 26f - h * grab;
            c.save();
            c.alpha(Math.min(1f, grab * 1.4f));
            Rect tip = new Rect(kx - w / 2f, by, w, h);
            // minecraft's tooltip: near-black purple fill inside a fading violet frame
            c.rect(tip, 0xF0100010);
            c.verticalGradient(tip.inset(1f), 0f, 0x505000FF, 0x5028007F);
            c.rect(tip.inset(2f), 0xF0100010);
            c.legacy(nvg -> nvg.mcTextLiteral(label, tip.x() + 6f, tip.y() + 5f, scale, 0xFFFFFFFF, true));
            c.restore();
        }
    }

    private static final String DUST = "minecraft:textures/block/redstone_dust_dot.png";

    // redstone wire's colour at a power level 0 to 15, from RedStoneWireBlock
    private static int redstone(int power) {
        float f = power / 15f;
        float r = f * 0.6f + (f > 0f ? 0.4f : 0.3f);
        float g = Math.max(0f, Math.min(1f, f * f * 0.7f - 0.5f));
        float b = Math.max(0f, Math.min(1f, f * f * 0.6f - 0.7f));
        return 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }


    private void drawText(PanelFrame f, TextSetting text, Key key, Rect row, float right, float cy, float innerX,
                          float innerW, float labelTop) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Rect field;
        if (text.isMultiline()) {
            float labelW = labelWidth(text, innerW);
            field = new Rect(innerX, labelTop + labelBlock(c, text, labelW) + 6f, innerW,
                    text.getVisibleLines() * 16f + 14f);
        } else {
            float fw = controlWidth(text, innerW);
            field = new Rect(right - fw, cy - FIELD_H / 2f, fw, FIELD_H);
        }
        boolean editing = style.editing(key);
        boolean hover = f.hits().hovered(key);
        drawField(c, p, field, hover, editing);
        Rect inner = field.inset(10f, 0f, 10f, 0f);
        if (editing) {
            style.drawEditor(f, inner, text.isMultiline());
        } else {
            String value = text.getValue();
            boolean empty = value == null || value.isEmpty();
            String shown = empty ? AetherLang.localize(text.getPlaceholder()) : value;
            if (text.isMultiline()) {
                float ly = field.y() + 7f;
                for (String line : limit(c.wrap(REGULAR, 12.5f, shown, inner.w()), text.getVisibleLines())) {
                    c.text(REGULAR, 12.5f, line, inner.x(), ly, empty ? p.textMuted() : p.text());
                    ly += 16f;
                }
            } else {
                PanelPaint.fitText(c, REGULAR, 12.5f, shown, inner.x(), field.centerY(), inner.w(),
                        empty ? p.textMuted() : p.text());
            }
        }
        f.hits().add(key, field, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) {
                    return false;
                }
                style.beginEdit(key, text.getValue(), text.isMultiline(), text::setValue);
                style.editorPress(e.localX() - inner.x(), e.localY() - (text.isMultiline() ? field.y() + 7f : 0f));
                return true;
            }
        }, Cursor.IBEAM);
    }

    private void drawField(GuiCanvas c, Palette p, Rect field, boolean hover, boolean focused) {
        if (focused) {
            c.roundedRect(field.inset(-3f), 11f, Argb.withAlpha(p.accent(), 0.22f));
        }
        c.roundedRect(field, 8f, PanelPaint.fieldFill(p));
        int stroke = focused ? p.accent() : Argb.withAlpha(p.border(), hover ? 0.6f : 0.32f);
        c.strokeRect(field, 8f, 1f, stroke);
    }

    private List<String> chipLabels(Setting setting) {
        return switch (setting) {
            case ListSetting list -> list.getValues();
            case DropdownListSetting list -> list.getValues().stream().map(AetherLang::localize).toList();
            case MultiDropdownSetting multi -> {
                List<String> chosen = new ArrayList<>();
                List<String> options = multi.getOptions();
                for (int i = 0; i < options.size(); i++) {
                    if (multi.isSelected(i)) {
                        chosen.add(options.get(i));
                    }
                }
                yield chosen;
            }
            default -> List.of();
        };
    }

    private int chipRows(GuiCanvas c, List<String> labels, float innerW) {
        int rows = 1;
        float x = 0f;
        for (String label : labels) {
            float w = c.textWidth(MEDIUM, 12f, label) + 22f;
            if (x > 0f && x + w > innerW - 70f) {
                rows++;
                x = 0f;
            }
            x += w + 6f;
        }
        return rows;
    }

    private void drawChips(PanelFrame f, Setting setting, Key key, float x, float y, float innerW) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        List<String> labels = chipLabels(setting);
        float cx = x;
        float cy = y + 12f;
        if (labels.isEmpty()) {
            PanelPaint.text(c, REGULAR, 12f, AetherLang.localize("Nothing added yet"), x, cy, p.textMuted());
        }
        for (String label : labels) {
            float w = c.textWidth(MEDIUM, 12f, label) + 22f;
            if (cx > x && cx + w > x + innerW - 70f) {
                cx = x;
                cy += 30f;
            }
            c.roundedRect(new Rect(cx, cy - 12f, w, 24f), 12f, Argb.withAlpha(p.accent(), 0.14f));
            c.strokeRect(new Rect(cx, cy - 12f, w, 24f), 12f, 1f, Argb.withAlpha(p.accent(), 0.30f));
            PanelPaint.text(c, MEDIUM, 12f, label, cx + 11f, cy, p.text());
            cx += w + 6f;
        }
        Key addKey = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(), "add");
        boolean multi = setting instanceof MultiDropdownSetting;
        String addLabel = AetherLang.localize(multi ? "Edit" : "Add");
        float aw = c.textWidth(SEMIBOLD, 12f, addLabel) + 34f;
        Rect add = new Rect(x + innerW - aw, y, aw, 24f);
        boolean hover = f.hits().hovered(addKey);
        PanelPaint.button(c, p, add, addLabel, null, PanelPaint.ButtonKind.GHOST, hover ? 1f : 0f, false, true);
        if (setting instanceof MultiDropdownSetting multiDropdown) {
            f.hits().add(addKey, add, HitHandler.click(() -> style.openMulti(addKey, multiDropdown,
                    f.canvas().toRoot(add))), Cursor.HAND);
        } else if (setting instanceof DropdownListSetting list) {
            f.hits().add(addKey, add, HitHandler.click(() -> style.openAddPicker(addKey, list,
                    f.canvas().toRoot(add))), Cursor.HAND);
        }
    }

    private void drawPosition(PanelFrame f, PositionSetting position, Key key, float x, float y, float innerW) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        double[] values = {position.getX(), position.getY(), position.getZ()};
        String[] axes = {"X", "Y", "Z"};
        float fieldW = Math.min(96f, (innerW - 200f) / 3f);
        float fx = x;
        for (int i = 0; i < 3; i++) {
            Rect field = new Rect(fx, y, fieldW, FIELD_H);
            drawField(c, p, field, false, false);
            PanelPaint.text(c, SEMIBOLD, 11f, axes[i], field.x() + 9f, field.centerY(), p.textMuted());
            PanelPaint.fitText(c, MONO, 12f, NumberText.format(values[i], 1), field.x() + 24f, field.centerY(),
                    fieldW - 30f, p.text());
            fx += fieldW + 8f;
        }
        Key captureKey = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(), "capture");
        Rect capture = new Rect(fx + 4f, y, 84f, FIELD_H);
        PanelPaint.button(c, p, capture, AetherLang.localize("Capture"), null, PanelPaint.ButtonKind.SUBTLE,
                f.hits().hovered(captureKey) ? 1f : 0f, false, true);
        f.hits().add(captureKey, capture, HitHandler.click(position::capture), Cursor.HAND);
        float bx = capture.right() + 8f;
        int index = 0;
        for (PositionSetting.ActionButton button : position.getActionButtons()) {
            Key buttonKey = new Key(key.page(), key.group(), key.groupIndex(), key.setting(), key.settingIndex(),
                    "button" + index++);
            float bw = c.textWidth(SEMIBOLD, 12f, AetherLang.localize(button.label())) + 24f;
            Rect r = new Rect(bx, y, Math.max(32f, bw), FIELD_H);
            PanelPaint.button(c, p, r, AetherLang.localize(button.label()), null, PanelPaint.ButtonKind.GHOST,
                    f.hits().hovered(buttonKey) ? 1f : 0f, false, button.isEnabled());
            if (button.isEnabled()) {
                f.hits().add(buttonKey, r, HitHandler.click(button::execute), Cursor.HAND);
            }
            bx = r.right() + 6f;
        }
    }

    private static void checker(GuiCanvas c, Rect r) {
        c.save();
        c.clip(r);
        c.rect(r, 0xFFFFFFFF);
        float s = 6f;
        for (float yy = r.y(); yy < r.bottom(); yy += s) {
            for (float xx = r.x() + (((int) ((yy - r.y()) / s)) % 2 == 0 ? 0f : s); xx < r.right(); xx += s * 2f) {
                c.rect(new Rect(xx, yy, s, s), 0xFFCCCCCC);
            }
        }
        c.restore();
    }

    private static String keyName(KeybindSetting setting) {
        try {
            String name = setting.getBoundKeyName();
            return name == null || name.isBlank() ? AetherLang.localize("None") : name;
        } catch (RuntimeException | LinkageError e) {
            return AetherLang.localize("None");
        }
    }

    static String formatValue(float value, int decimals, String suffix) {
        String number = NumberText.format(value, decimals);
        return suffix == null || suffix.isEmpty() ? number : number + suffix;
    }

    private static float quantize(float value, int decimals) {
        double scale = Math.pow(10, Math.max(0, decimals));
        return (float) (Math.round(value * scale) / scale);
    }

    private static float range(float value, float min, float max) {
        return max <= min ? 0f : Math.max(0f, Math.min(1f, (value - min) / (max - min)));
    }

    private static List<String> limit(List<String> lines, int max) {
        return lines.size() <= max ? lines : lines.subList(0, max);
    }
}
