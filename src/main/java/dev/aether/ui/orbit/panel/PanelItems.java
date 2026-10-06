package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.util.AetherLang;

import java.util.List;

import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// settings you pick with minecraft items instead of a field: pest threshold as a stack of silverfish eggs and the
// humanization preset as three chestplates
final class PanelItems {
    enum Kind { STACK, TIERS }

    private record Choice(String label, String item, String hint) {
    }

    private static final List<Choice> TIERS = List.of(
            new Choice("Extra Legit", "diamond_chestplate", "Slowest, most human"),
            new Choice("Legit", "iron_chestplate", "Balanced"),
            new Choice("Blatant", "leather_chestplate", "Fast, least careful"));
    private static final String EGG = "silverfish_spawn_egg";
    private static final float EGG_STEP = 20f;

    static final float WIDE = 470f;
    private static final float TIERS_H = 74f;

    private PanelItems() {
    }

    static Kind kind(Setting setting) {
        if (setting instanceof DropdownSetting dropdown) {
            List<String> options = dropdown.getOptions();
            if (labels(TIERS).equals(options)) return Kind.TIERS;
        }
        if (setting instanceof SliderSetting && setting.getRawName().equals("Pest Threshold")) return Kind.STACK;
        return null;
    }

    // dropdown options arrive localized, so compare against the localized labels
    private static List<String> labels(List<Choice> choices) {
        return choices.stream().map(choice -> AetherLang.localize(choice.label())).toList();
    }

    // a block under the label on a wide page, otherwise an inline control at the row's right
    static boolean stacked(Kind kind, float innerW) {
        return kind != Kind.STACK && innerW >= WIDE;
    }

    static float blockHeight(Kind kind) {
        return TIERS_H;
    }

    static float inlineWidth(Kind kind) {
        return switch (kind) {
            case STACK -> 36f + 10f + 8 * EGG_STEP + 6f;
            case TIERS -> 3 * 92f;
        };
    }

    // -- drawing ----------------------------------------------------------------------------------------------

    static void drawBlock(PanelFrame f, Kind kind, Setting setting, String key, Rect area) {
        tiers(f, (DropdownSetting) setting, key, area);
    }

    static void drawInline(PanelFrame f, Kind kind, Setting setting, String key, float right, float cy) {
        switch (kind) {
            case STACK -> stack(f, (SliderSetting) setting, key, right, cy);
            case TIERS -> miniTiers(f, (DropdownSetting) setting, key, right, cy);
        }
    }

    private static void stack(PanelFrame f, SliderSetting setting, String key, float right, float cy) {
        GuiCanvas c = f.canvas();
        int min = Math.round(setting.getMin());
        int max = Math.round(setting.getMax());
        int value = Math.round(setting.getValue());
        float x = right - inlineWidth(Kind.STACK);
        Rect slot = new Rect(x, cy - 18f, 36f, 36f);
        mcSlot(c, slot);
        float bump = Math.min(1f, Math.abs(f.anim().ease(key + "/count", value, 260f) - value));
        PanelPaint.icon(c, Icon.item(EGG), slot.centerX(), slot.centerY(), 26f + bump * 4f, 0xFFFFFFFF);
        String count = Integer.toString(value);
        c.legacy(nvg -> {
            float tw = count.length() * 12f - 2f;
            nvg.mcTextLiteral(count, slot.right() - 3f - tw, slot.bottom() - 18f, 2, 0xFFFFFFFF, true);
        });

        float ex = slot.right() + 10f;
        float step = EGG_STEP;
        int slots = max - min + 1;
        Rect eggs = new Rect(ex, cy - 14f, slots * step + 6f, 28f);
        boolean hover = f.hits().hovered(key + "/eggs");
        if (hover) c.roundedRect(eggs, 8f, Argb.withAlpha(f.palette().text(), 0.05f));
        for (int i = 0; i < slots; i++) {
            int n = min + i;
            boolean lit = n <= value;
            float on = f.anim().ease(key + "/egg/" + i, lit ? 1f : 0f, 240f);
            float px = ex + 3f + i * step + step / 2f;
            if (on > 0.01f) c.circle(px, cy + 2f, 10f, Argb.withAlpha(f.palette().accent(), 0.16f * on));
            c.save();
            c.alpha(0.22f + 0.78f * on);
            PanelPaint.icon(c, Icon.item(EGG), px, cy + 1f - pop(on, lit) * 3f, 24f + pop(on, lit) * 5f, 0xFFFFFFFF);
            c.restore();
        }
        f.hits().add(key + "/eggs", eggs, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) return false;
                apply(e);
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                apply(e);
            }

            private void apply(PointerEvent e) {
                Rect r = e.pressRect();
                int i = (int) Math.floor((e.localX() - r.x() - 3f) / step);
                setting.setValue(min + Math.max(0, Math.min(slots - 1, i)));
            }
        }, Cursor.HAND);
    }

    private static void tiers(PanelFrame f, DropdownSetting setting, String key, Rect area) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        int selected = setting.getSelectedIndex();
        float x = area.x() + 6f;
        for (int i = 0; i < TIERS.size(); i++) {
            Choice choice = TIERS.get(i);
            String itemKey = key + "/tier/" + i;
            boolean picked = i == selected;
            float on = f.anim().ease(itemKey + "/sel", picked ? 1f : 0f, 300f);
            float hover = f.anim().hover(itemKey, f.hits().hovered(itemKey));
            Rect cell = new Rect(x + i * 92f, area.y() + 4f, 84f, TIERS_H - 8f);
            Rect slot = new Rect(cell.centerX() - 21f, cell.y() + 2f, 42f, 42f);
            float lift = hover * 2f;
            Rect lifted = new Rect(slot.x(), slot.y() - lift, slot.w(), slot.h());
            mcSlot(c, lifted);
            if (on > 0.01f) {
                c.strokeRect(lifted.inset(-3f), 4f, 2f, Argb.withAlpha(p.accent(), on));
                c.rect(lifted.inset(2f), Argb.withAlpha(0xFFFFFFFF, 0.28f * on));
            }
            PanelPaint.icon(c, Icon.item(choice.item()), lifted.centerX(), lifted.centerY(), 30f + pop(on, picked) * 6f, 0xFFFFFFFF);
            PanelPaint.textCentered(c, picked ? SEMIBOLD : MEDIUM, 11.5f, AetherLang.localize(choice.label()), cell.centerX(),
                    slot.bottom() + 12f, Argb.mix(p.textMuted(), p.text(), Math.max(on, hover)));
            int index = i;
            f.hits().add(itemKey, cell, HitHandler.click(() -> setting.setSelectedIndex(index)), Cursor.HAND);
        }
        Choice current = choice(TIERS, selected);
        float hx = x + TIERS.size() * 92f + 10f;
        if (hx + 80f < area.right()) {
            PanelPaint.text(c, SEMIBOLD, 12f, AetherLang.localize(current.label()), hx, area.y() + 22f, p.text());
            PanelPaint.fitText(c, REGULAR, 11f, AetherLang.localize(current.hint()), hx, area.y() + 39f,
                    area.right() - hx - 8f, p.textMuted());
        }
    }

    private static void miniTiers(PanelFrame f, DropdownSetting setting, String key, float right, float cy) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        int selected = setting.getSelectedIndex();
        float x = right - inlineWidth(Kind.TIERS);
        for (int i = 0; i < TIERS.size(); i++) {
            Choice choice = TIERS.get(i);
            String itemKey = key + "/mtier/" + i;
            boolean picked = i == selected;
            float on = f.anim().ease(itemKey + "/sel", picked ? 1f : 0f, 260f);
            float hover = f.anim().hover(itemKey, f.hits().hovered(itemKey));
            Rect cell = new Rect(x + i * 92f, cy - 14f, 88f, 28f);
            if (on > 0.01f) c.roundedRect(cell, 7f, Argb.withAlpha(p.accent(), 0.18f * on));
            else if (hover > 0.01f) c.roundedRect(cell, 7f, Argb.withAlpha(p.text(), 0.06f * hover));
            Rect slot = new Rect(cell.x() + 3f, cell.y() + 3f, 22f, 22f);
            mcSlot(c, slot);
            PanelPaint.icon(c, Icon.item(choice.item()), slot.centerX(), slot.centerY(), 16f + pop(on, picked) * 4f, 0xFFFFFFFF);
            PanelPaint.fitText(c, picked ? SEMIBOLD : MEDIUM, 10.5f, AetherLang.localize(choice.label()), slot.right() + 5f,
                    cell.centerY(), cell.right() - slot.right() - 7f, Argb.mix(p.textMuted(), p.text(), Math.max(on, hover)));
            int index = i;
            f.hits().add(itemKey, cell, HitHandler.click(() -> setting.setSelectedIndex(index)), Cursor.HAND);
        }
    }

    // -- helpers ----------------------------------------------------------------------------------------------

    // an inventory slot: grey well, dark top-left edge, white bottom-right edge
    static void mcSlot(GuiCanvas c, Rect r) {
        float b = Math.max(1f, Math.round(r.w() / 18f));
        c.rect(r, 0xFF8B8B8B);
        c.rect(new Rect(r.x(), r.y(), r.w() - b, b), 0xFF373737);
        c.rect(new Rect(r.x(), r.y(), b, r.h() - b), 0xFF373737);
        c.rect(new Rect(r.x() + b, r.bottom() - b, r.w() - b, b), 0xFFFFFFFF);
        c.rect(new Rect(r.right() - b, r.y() + b, b, r.h() - b), 0xFFFFFFFF);
    }

    private static Choice choice(List<Choice> choices, int index) {
        return choices.get(Math.max(0, Math.min(choices.size() - 1, index)));
    }

    // a quick swell while something becomes picked, zero once it settles
    private static float pop(float t, boolean rising) {
        return rising && t < 1f ? (float) Math.sin(Math.PI * t) : 0f;
    }
}
