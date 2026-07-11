package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorEntity;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Glass-panel HUD element that displays the player's inventory, with optional
 * armor slots and player portrait.
 */
public class InventoryHudElement extends HudElement {

    private static final float PAD = 6f;
    private static final float CONTENT_Y = PAD;

    private static final float SLOT_SIZE = 16f;
    private static final float SLOT_GAP = 2f;
    private static final float SLOT_STEP = SLOT_SIZE + SLOT_GAP;

    private static final float ARMOR_GAP = 6f;
    private static final float MODEL_GAP = 4f;
    private static final float INVENTORY_W = 9 * SLOT_STEP - SLOT_GAP;
    private static final float MODEL_W = 58f;

    private static final float SEP_Y = CONTENT_Y + 3 * SLOT_STEP;
    private static final float HOTBAR_Y = SEP_Y + SLOT_GAP + 1f;

    private static final float CONTENT_H = HOTBAR_Y + SLOT_STEP - CONTENT_Y;
    private static final float H = CONTENT_Y + CONTENT_H + PAD;
    private static final float ARMOR_STEP = (HOTBAR_Y - CONTENT_Y) / 3f;
    private static final float CORNER = 6f;

    @Override public float getX() { return AetherConfig.INVENTORY_HUD_X.get(); }
    @Override public float getY() { return AetherConfig.INVENTORY_HUD_Y.get(); }
    @Override public void setX(float x) { AetherConfig.INVENTORY_HUD_X.set((int) x); }
    @Override public void setY(float y) { AetherConfig.INVENTORY_HUD_Y.set((int) y); }
    @Override public float getScale() { return 1f; }
    @Override public void setScale(float s) { }
    @Override public float getWidth() { return computeLayoutWidth(); }
    @Override public float getHeight() { return H; }
    @Override public boolean isEnabled() { return AetherConfig.SHOW_INVENTORY_HUD.get(); }
    @Override public boolean isVisible() {
        if (!isEnabled()) return false;
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
        return screen == null || screen instanceof HudEditScreen;
    }
    @Override public String getName() { return "Inventory HUD"; }
    @Override public void savePosition() { AetherConfig.save(); }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        boolean showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get();
        boolean showPlayerModel = AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get();

        float cursorX = PAD;
        float armorX = -1f;
        if (showArmor) {
            armorX = cursorX;
            cursorX += SLOT_SIZE + ARMOR_GAP;
        }

        float inventoryX = cursorX;
        cursorX += INVENTORY_W;

        float modelX = -1f;
        if (showPlayerModel) {
            modelX = cursorX + MODEL_GAP;
            cursorX = modelX + MODEL_W;
        }

        float width = cursorX + PAD;
        int border = isDragging() ? BORDER_DRAG : isResizing() ? BORDER_RESIZE : Theme.HUD_BORDER;

        nvg.scissor(0, 0, width, H);
        nvg.blur(0, 0, width, H, 20f);
        nvg.resetScissor();
        nvg.rectOutline(0, 0, width, H, CORNER, 1f, border);

        if (showArmor) {
            for (int i = 0; i < 4; i++) {
                drawSlotBg(nvg, armorX, CONTENT_Y + i * ARMOR_STEP);
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotBg(nvg, inventoryX + col * SLOT_STEP, CONTENT_Y + row * SLOT_STEP);
            }
        }

        Minecraft mc = Minecraft.getInstance();
        int selected = mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1;
        for (int col = 0; col < 9; col++) {
            float sx = inventoryX + col * SLOT_STEP;
            if (col == selected) {
                nvg.roundedRect(sx, HOTBAR_Y, SLOT_SIZE, SLOT_SIZE, 2f,
                        Theme.withAlpha(0xFFFFFFFF, 0x18));
            } else {
                drawSlotBg(nvg, sx, HOTBAR_Y);
            }
        }

        if (showPlayerModel) {
            nvg.roundedRect(modelX, CONTENT_Y, MODEL_W, CONTENT_H, 4f,
                    Theme.withAlpha(0xFF000000, 0x30));
        }

        if (editMode) {
            String hint = isDragging() ? "moving..."
                    : isResizing() ? "resizing..."
                    : "drag - ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, H - PAD + 2f, width, PAD - 2f, 9f, Theme.HUD_LABEL);
        }
    }

    private void drawSlotBg(NVGRenderer nvg, float x, float y) {
        nvg.roundedRect(x, y, SLOT_SIZE, SLOT_SIZE, 2f, Theme.withAlpha(0xFF000000, 0x40));
    }

    @Override
    public void renderMinecraft(GuiGraphicsExtractor graphics, boolean editMode) {
        if (!isVisible() && !editMode) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        boolean showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get();
        boolean showPlayerModel = AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get();

        float cursorX = PAD;
        float armorX = -1f;
        if (showArmor) {
            armorX = cursorX;
            cursorX += SLOT_SIZE + ARMOR_GAP;
        }

        float inventoryX = cursorX;
        cursorX += INVENTORY_W;

        float modelX = -1f;
        if (showPlayerModel) {
            modelX = cursorX + MODEL_GAP;
            cursorX = modelX + MODEL_W;
        }

        float width = cursorX + PAD;
        float ox = getX();
        float oy = getY();
        float sc = getScale();

        graphics.enableScissor(
                screenX(ox, 0, sc), screenY(oy, 0, sc),
                screenX(ox, width, sc), screenY(oy, H, sc));
        try {
            if (showArmor) {
                for (int i = 0; i < 4; i++) {
                    ItemStack stack = player.getInventory().getItem(39 - i);
                    if (!stack.isEmpty()) {
                        renderItem(graphics, stack,
                                slotItemX(ox, armorX, sc),
                                slotItemY(oy, CONTENT_Y + i * ARMOR_STEP, sc));
                    }
                }
            }

            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    ItemStack stack = player.getInventory().getItem(9 + row * 9 + col);
                    if (!stack.isEmpty()) {
                        renderItem(graphics, stack,
                                slotItemX(ox, inventoryX + col * SLOT_STEP, sc),
                                slotItemY(oy, CONTENT_Y + row * SLOT_STEP, sc));
                    }
                }
            }

            for (int col = 0; col < 9; col++) {
                ItemStack stack = player.getInventory().getItem(col);
                if (!stack.isEmpty()) {
                    renderItem(graphics, stack,
                            slotItemX(ox, inventoryX + col * SLOT_STEP, sc),
                            slotItemY(oy, HOTBAR_Y, sc));
                }
            }

            if (showPlayerModel) {
                int sx1 = screenX(ox, modelX, sc);
                int sy1 = screenY(oy, CONTENT_Y, sc);
                int sx2 = screenX(ox, modelX + MODEL_W, sc);
                int sy2 = screenY(oy, CONTENT_Y + CONTENT_H, sc);
                int modelScale = (int) (32 * sc);
                renderPortrait(graphics, player, sx1, sy1, sx2, sy2, modelScale);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private static void renderPortrait(GuiGraphicsExtractor graphics, Player player,
            int x1, int y1, int x2, int y2, int scale) {
        double yawRad = Math.toRadians(player.getYRot());
        float f = (float) Math.sin(2.0 * yawRad) * 0.8f;

        float pitch = Mth.clamp(player.getXRot(), -45f, 45f);
        float g = (-pitch / 45f) * 0.6f;

        float cx = (x1 + x2) * 0.5f;
        float cy = (y1 + y2) * 0.5f;
        float mouseX = cx - 40f * (float) Math.tan(f);
        float mouseY = cy - 40f * (float) Math.tan(g);

        float targetBodyRot = 180f + f * 20f;
        float targetHeadRot = 180f + f * 40f;
        float targetXRot = -g * 20f;

        float savedYBodyRot = player.yBodyRot;
        float savedYBodyRotO = player.yBodyRotO;
        float savedYHeadRot = player.yHeadRot;
        float savedYHeadRotO = player.yHeadRotO;
        float savedXRot = player.getXRot();
        float savedXRotO = player.xRotO;

        player.yBodyRot = targetBodyRot;
        player.yBodyRotO = targetBodyRot;
        player.yHeadRot = targetHeadRot;
        player.yHeadRotO = targetHeadRot;
        player.setXRot(targetXRot);
        ((AccessorEntity) player).setXRotO(targetXRot);

        try {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    graphics, x1, y1, x2, y2, scale, 0f, mouseX, mouseY, player);
        } finally {
            player.yBodyRot = savedYBodyRot;
            player.yBodyRotO = savedYBodyRotO;
            player.yHeadRot = savedYHeadRot;
            player.yHeadRotO = savedYHeadRotO;
            player.setXRot(savedXRot);
            ((AccessorEntity) player).setXRotO(savedXRotO);
        }
    }

    private static int screenX(float originX, float localX, float scale) {
        return (int) (originX + localX * scale);
    }

    private static int screenY(float originY, float localY, float scale) {
        return (int) (originY + localY * scale);
    }

    private static int slotItemX(float originX, float localX, float scale) {
        return (int) (originX + localX * scale + (SLOT_SIZE * scale - 16f) / 2f);
    }

    private static int slotItemY(float originY, float localY, float scale) {
        return (int) (originY + localY * scale + (SLOT_SIZE * scale - 16f) / 2f);
    }

    private static void renderItem(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        graphics.item(stack, x, y);
    }

    @Override
    public void renderOverlay(NVGRenderer nvg, boolean editMode) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        boolean showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get();
        float cursorX = PAD;
        float armorX = -1f;
        if (showArmor) {
            armorX = cursorX;
            cursorX += SLOT_SIZE + ARMOR_GAP;
        }

        float inventoryX = cursorX;

        if (showArmor) {
            for (int i = 0; i < 4; i++) {
                drawCount(nvg, player.getInventory().getItem(39 - i),
                        armorX, CONTENT_Y + i * ARMOR_STEP);
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawCount(nvg, player.getInventory().getItem(9 + row * 9 + col),
                        inventoryX + col * SLOT_STEP, CONTENT_Y + row * SLOT_STEP);
            }
        }

        for (int col = 0; col < 9; col++) {
            drawCount(nvg, player.getInventory().getItem(col),
                    inventoryX + col * SLOT_STEP, HOTBAR_Y);
        }
    }

    private void drawCount(NVGRenderer nvg, ItemStack stack, float slotX, float slotY) {
        if (stack.isEmpty() || stack.getCount() <= 1) return;
        nvg.textRight(Fonts.BOLD, Integer.toString(stack.getCount()),
                slotX, slotY + SLOT_SIZE - 7f,
                SLOT_SIZE, 8f,
                0xFFCCCCCC);
    }

    private static float computeLayoutWidth() {
        boolean showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get();
        boolean showPlayerModel = AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get();

        float width = PAD;
        if (showArmor) {
            width += SLOT_SIZE + ARMOR_GAP;
        }

        width += INVENTORY_W;
        if (showPlayerModel) {
            width += MODEL_GAP + MODEL_W;
        }

        return width + PAD;
    }
}
