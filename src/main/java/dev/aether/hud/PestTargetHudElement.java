package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.modules.pest.helpers.PestDisplayTracker;
import dev.aether.modules.pest.helpers.PestDisplayTracker.PestDisplay;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PestTargetHudElement extends HudElement {
    private static final float COLUMN_WIDTH = PestHudLayout.COLUMN_WIDTH;
    private static final float ROW_HEIGHT = PestHudLayout.ROW_HEIGHT;
    private static final float HEADER_HEIGHT = PestHudLayout.HEADER_HEIGHT;
    private static final float ICON_TILE = 27f;
    private static final float ICON_SIZE = 20f;
    private final Map<Integer, Float> bars = new HashMap<>();
    private long lastFrame;
    private Object lastLevel;

    private List<PestDisplay> pests() { return PestDisplayTracker.getPests(Minecraft.getInstance()); }
    private PestHudLayout layout() {
        var window = Minecraft.getInstance().getWindow();
        int savedX = AetherConfig.PEST_TARGET_HUD_X.get();
        float width = window.getGuiScaledWidth() - (savedX >= 0 ? savedX + 8f : 16f);
        float height = window.getGuiScaledHeight() - getY() - 8f;
        return PestHudLayout.fit(pests().size(), width, height, AetherConfig.PEST_TARGET_HUD_SCALE.get());
    }
    private int rows() { return layout().rows(); }

    @Override public float getX() {
        int saved = AetherConfig.PEST_TARGET_HUD_X.get();
        return saved >= 0 ? saved : Math.max(0, (Minecraft.getInstance().getWindow().getGuiScaledWidth() - getWidth() * getScale()) / 2f);
    }
    @Override public float getY() {
        int saved = AetherConfig.PEST_TARGET_HUD_Y.get();
        return saved >= 0 ? saved : Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2f + 28f;
    }
    @Override public void setX(float x) { AetherConfig.PEST_TARGET_HUD_X.set(Math.round(x)); }
    @Override public void setY(float y) { AetherConfig.PEST_TARGET_HUD_Y.set(Math.round(y)); }
    @Override public float getScale() { return layout().scale(); }
    @Override public void setScale(float scale) { AetherConfig.PEST_TARGET_HUD_SCALE.set(scale); }
    @Override public float getWidth() { return layout().width(); }
    @Override public float getHeight() { return layout().height(); }
    @Override public boolean isEnabled() { return AetherConfig.SHOW_PEST_TARGET_HUD.get(); }
    @Override public boolean isVisible() {
        return isEnabled() && Minecraft.getInstance().screen == null
                && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN && !pests().isEmpty();
    }
    @Override public String getName() { return "Pest Target HUD"; }
    @Override public void savePosition() { AetherConfig.save(); }
    @Override public boolean rendersBeforeMinecraft() { return true; }

    public static void resetLayout() {
        AetherConfig.PEST_TARGET_HUD_X.set(-1);
        AetherConfig.PEST_TARGET_HUD_Y.set(-1);
        AetherConfig.PEST_TARGET_HUD_SCALE.set(1f);
        AetherConfig.save();
    }

    @Override protected void renderElement(NVGRenderer nvg, boolean editMode) {
        List<PestDisplay> entries = pests();
        HudStyle.panel(nvg, getWidth(), getHeight());
        HudStyle.accent(nvg, getWidth(), Theme.HUD_ACCENT, Theme.HUD_ACCENT);
        nvg.text(Fonts.BOLD, "Pests", 10f, 9f, 11f, Theme.HUD_TITLE);
        nvg.textRight(Fonts.MONO, Integer.toString(entries.size()), 10f, 10f, getWidth() - 20f, 9f, Theme.HUD_LABEL);

        long now = System.nanoTime();
        float blend = lastFrame == 0 ? 1f : (float) (1 - Math.exp(-(now - lastFrame) / 120_000_000.0));
        lastFrame = now;
        Object level = Minecraft.getInstance().level;
        if (level != lastLevel) { bars.clear(); lastLevel = level; }
        bars.keySet().removeIf(id -> entries.stream().noneMatch(pest -> pest.entity().getId() == id));
        if (entries.isEmpty() && editMode) {
            renderRow(nvg, 0, "Pest", "600 HP", 0.75f, false);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            PestDisplay pest = entries.get(i);
            float target = pest.hunting() ? pest.progress() : pest.healthFraction();
            float shown = target;
            if (target >= 0) {
                shown = bars.getOrDefault(pest.entity().getId(), target);
                shown += (target - shown) * blend;
                bars.put(pest.entity().getId(), shown);
            } else bars.remove(pest.entity().getId());
            String detail = pest.hunting()
                    ? target >= 1 ? "REEL" : target < 0 ? "Hunting…" : !pest.attached() && target == 0 ? "Ready" : Math.round(target * 100) + "% reel"
                    : pest.health() < 0 ? "Health unknown" : String.format(java.util.Locale.ROOT, "%,.0f HP", pest.health());
            renderRow(nvg, i, pest.name(), detail, shown, pest.hunting());
        }
    }

    private void renderRow(NVGRenderer nvg, int index, String name, String detail, float fraction, boolean hunting) {
        float x = index / rows() * COLUMN_WIDTH;
        float y = HEADER_HEIGHT + index % rows() * ROW_HEIGHT;
        int accent = hunting ? Theme.HUD_ACCENT : Theme.blend(Theme.HUD_ERROR, Theme.HUD_SUCCESS, Math.max(0, fraction));
        nvg.roundedRect(x + 8f, y, ICON_TILE, ICON_TILE, 6f, HudStyle.alpha(Theme.HUD_BORDER, 0.5f));
        float detailWidth = nvg.textWidth(Fonts.MONO, detail, 8f);
        HudStyle.text(nvg, Fonts.BOLD, name, x + 42f, y + 1f, COLUMN_WIDTH - 58f - detailWidth, 10f, Theme.HUD_TITLE);
        nvg.textRight(Fonts.MONO, detail, x + 42f, y + 2f, COLUMN_WIDTH - 52f, 8f, Theme.HUD_LABEL);
        float barWidth = COLUMN_WIDTH - 52f;
        nvg.roundedRect(x + 42f, y + 18f, barWidth, 5f, 2.5f, Theme.HUD_BORDER);
        if (fraction >= 0) {
            if (fraction > 0) nvg.roundedRect(x + 42f, y + 18f, barWidth * fraction, 5f, 2.5f, accent);
        } else {
            float phase = (System.nanoTime() % 1_600_000_000L) / 1_600_000_000f;
            float offset = (float) (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)) * barWidth * 0.75f;
            nvg.roundedRect(x + 42f + offset, y + 18f, barWidth * 0.25f, 5f, 2.5f, Theme.HUD_LABEL);
        }
    }

    @Override public void renderMinecraft(GuiGraphicsExtractor graphics, boolean editMode) {
        if (editMode ? !isEnabled() : !isVisible()) return;
        if (Minecraft.getInstance().level == null) return;
        List<PestDisplay> entries = pests();
        graphics.pose().pushMatrix();
        graphics.pose().translate(getX(), getY());
        graphics.pose().scale(getScale());
        try {
            float inset = (ICON_TILE - ICON_SIZE) / 2f;
            for (int i = 0; i < Math.max(editMode ? 1 : 0, entries.size()); i++) {
                ItemStack icon = entries.isEmpty() ? new ItemStack(Items.PLAYER_HEAD) : entries.get(i).icon();
                graphics.pose().pushMatrix();
                graphics.pose().translate(i / rows() * COLUMN_WIDTH + 8f + inset,
                        HEADER_HEIGHT + i % rows() * ROW_HEIGHT + inset);
                graphics.pose().scale(ICON_SIZE / 16f);
                graphics.item(icon, 0, 0);
                graphics.pose().popMatrix();
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }
}
