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

// settings you pick with minecraft items instead of a field: farm type as a fan of crops, pest threshold as a
// stack of silverfish eggs and the humanization preset as three chestplates
final class PanelItems {
    enum Kind { FARM, STACK, TIERS }

    private record Choice(String label, String item, String hint) {
    }

    private static final List<Choice> FARM = List.of(
            new Choice("S-Shape", "wheat", "Wheat · Carrot · Potato"),
            new Choice("S-Shape (Cane)", "sugar_cane", "Sugar Cane"),
            new Choice("SDS (Mushroom)", "red_mushroom", "Mushroom"),
            new Choice("Cocoa Beans", "cocoa_beans", "Cocoa Beans"),
            new Choice("A/D", "carrot", "Melon · Pumpkin · Carrot"),
            new Choice("W/S", "nether_wart", "Nether Wart · Cactus"),
            new Choice("Custom", "compass", "Your own lane keys"));
    private static final List<Choice> TIERS = List.of(
            new Choice("Extra Legit", "diamond_chestplate", "Slowest, most human"),
            new Choice("Legit", "iron_chestplate", "Balanced"),
            new Choice("Blatant", "leather_chestplate", "Fast, least careful"));
    private static final String EGG = "silverfish_spawn_egg";
    private static final float EGG_STEP = 20f;

    static final float WIDE = 470f;
    private static final float FAN_H = 112f;
    private static final float TIERS_H = 74f;
    private static final float FLIGHT_MS = 420f;

    private PanelItems() {
    }

    static Kind kind(Setting setting) {
        if (setting instanceof DropdownSetting dropdown) {
            List<String> options = dropdown.getOptions();
            if (labels(FARM).equals(options)) return Kind.FARM;
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
        return kind == Kind.FARM ? FAN_H : TIERS_H;
    }

    static float inlineWidth(Kind kind) {
        return switch (kind) {
            case FARM -> FARM.size() * 26f - 2f;
            case STACK -> 36f + 10f + 8 * EGG_STEP + 6f;
            case TIERS -> 3 * 92f;
        };
    }

    // -- drawing ----------------------------------------------------------------------------------------------

    static void drawBlock(PanelFrame f, Kind kind, Setting setting, String key, Rect area) {
        if (kind == Kind.FARM) fan(f, (DropdownSetting) setting, key, area);
        else tiers(f, (DropdownSetting) setting, key, area);
    }

    static void drawInline(PanelFrame f, Kind kind, Setting setting, String key, float right, float cy) {
        switch (kind) {
            case FARM -> miniFan(f, (DropdownSetting) setting, key, right, cy);
            case STACK -> stack(f, (SliderSetting) setting, key, right, cy);
            case TIERS -> miniTiers(f, (DropdownSetting) setting, key, right, cy);
        }
    }

    // the selected crop sits in a slot; the others float on an arc and fly into the slot when picked
    private static void fan(PanelFrame f, DropdownSetting setting, String key, Rect area) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        int selected = setting.getSelectedIndex();
        float slotSize = 50f;
        Rect slot = new Rect(area.x() + 14f, area.y() + 8f, slotSize, slotSize);
        mcSlot(c, slot);
        Choice current = choice(FARM, selected);
        PanelPaint.textCentered(c, SEMIBOLD, 12.5f, AetherLang.localize(current.label()), slot.centerX(), slot.bottom() + 14f, p.text());

        float arcX = slot.right() + 46f;
        float arcW = area.right() - arcX - 26f;
        float time = f.seconds();
        int n = FARM.size();
        c.save();
        c.beginPath();
        for (int s = 0; s <= 24; s++) {
            float u = s / 24f;
            float x = arcX - 10f + (arcW + 20f) * u;
            float k = u * 2f - 1f;
            if (s == 0) c.moveTo(x, area.y() + 20f + k * k * 28f);
            else c.lineTo(x, area.y() + 20f + k * k * 28f);
        }
        c.strokePath(1.2f, Argb.withAlpha(p.border(), 0.4f));
        c.restore();
        float[] ghost = spot(arcX, arcW, area.y(), selected, n);
        c.strokeCircle(ghost[0], ghost[1], 15f, 1.2f, Argb.withAlpha(p.border(), 0.55f));

        int hovered = -1;
        for (int i = 0; i < n; i++) {
            Choice choice = FARM.get(i);
            String itemKey = key + "/fan/" + i;
            boolean picked = i == selected;
            float t = f.anim().ease(itemKey + "/sel", picked ? 1f : 0f, FLIGHT_MS);
            float k = picked ? backOut(t) : 1f - backOut(1f - t);
            float[] from = spot(arcX, arcW, area.y(), i, n);
            float x = from[0] + (slot.centerX() - from[0]) * k;
            float y = from[1] + (slot.centerY() - from[1]) * k;
            boolean hover = f.hits().hovered(itemKey);
            float lift = f.anim().hover(itemKey, hover);
            if (hover) hovered = i;
            float bob = (float) Math.sin(time * 2.2f + i * 0.9f) * 2.5f * (1f - k * 0.7f);
            float size = 30f + 6f * k + 5f * lift + pop(t, picked) * 8f;
            if (lift > 0.01f && !picked) {
                c.circle(x, y + bob - lift * 6f, 19f, Argb.withAlpha(p.accent(), 0.12f * lift));
            }
            PanelPaint.icon(c, Icon.item(choice.item()), x, y + bob - lift * 6f, size, 0xFFFFFFFF);
            Rect hit = new Rect(x - 18f, y - 18f, 36f, 36f);
            int index = i;
            f.hits().add(itemKey, hit, HitHandler.click(() -> setting.setSelectedIndex(index)), Cursor.HAND);
        }
        Choice shown = hovered >= 0 ? FARM.get(hovered) : current;
        String hint = AetherLang.localize(shown.label()) + " · " + AetherLang.localize(shown.hint());
        c.text(REGULAR, 11f, hint, arcX - 10f, area.bottom() - 18f, p.textMuted());
    }

    private static float[] spot(float arcX, float arcW, float top, int i, int n) {
        float u = n <= 1 ? 0.5f : (float) i / (n - 1);
        float k = u * 2f - 1f;
        return new float[]{arcX + arcW * u, top + 20f + k * k * 28f};
    }

    private static void miniFan(PanelFrame f, DropdownSetting setting, String key, float right, float cy) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        int selected = setting.getSelectedIndex();
        float x = right - inlineWidth(Kind.FARM);
        for (int i = 0; i < FARM.size(); i++) {
            String itemKey = key + "/mini/" + i;
            Rect tile = new Rect(x + i * 26f, cy - 12f, 24f, 24f);
            boolean picked = i == selected;
            float on = f.anim().ease(itemKey + "/sel", picked ? 1f : 0f, 260f);
            float hover = f.anim().hover(itemKey, f.hits().hovered(itemKey));
            if (on > 0.01f) {
                c.roundedRect(tile, 6f, Argb.withAlpha(p.accent(), 0.22f * on));
                c.strokeRect(tile, 6f, 1f, Argb.withAlpha(p.accent(), 0.7f * on));
            } else if (hover > 0.01f) {
                c.roundedRect(tile, 6f, Argb.withAlpha(p.text(), 0.07f * hover));
            }
            c.save();
            if (!picked) c.alpha(0.55f + 0.45f * hover);
            PanelPaint.icon(c, Icon.item(FARM.get(i).item()), tile.centerX(), tile.centerY() - hover * 1.5f,
                    18f + pop(on, picked) * 5f, 0xFFFFFFFF);
            c.restore();
            int index = i;
            f.hits().add(itemKey, tile, HitHandler.click(() -> setting.setSelectedIndex(index)), Cursor.HAND);
        }
    }

    // pest threshold: one egg per pest, the lit ones are the threshold; click, drag or scroll across them
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

            @Override
            public boolean scroll(PointerEvent e, double dy) {
                int next = Math.max(min, Math.min(max, Math.round(setting.getValue()) + (dy > 0 ? 1 : -1)));
                setting.setValue(next);
                return true;
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

    private static float backOut(float t) {
        float s = 1.70158f;
        float u = t - 1f;
        return 1f + u * u * ((s + 1f) * u + s);
    }
}
