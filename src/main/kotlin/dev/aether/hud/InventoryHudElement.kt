package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorEntity
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

open class InventoryHudElement : HudElement() {
    override fun rendersBeforeMinecraft(): Boolean = true
    override fun getX(): Float = AetherConfig.INVENTORY_HUD_X.get().toFloat()
    override fun getY(): Float = AetherConfig.INVENTORY_HUD_Y.get().toFloat()
    override fun setX(x: Float) { AetherConfig.INVENTORY_HUD_X.set(x.toInt()) }
    override fun setY(y: Float) { AetherConfig.INVENTORY_HUD_Y.set(y.toInt()) }
    override fun getScale(): Float = 1f
    override fun setScale(s: Float) {}
    override fun getWidth(): Float = computeLayoutWidth()
    override fun getHeight(): Float = H
    override fun isEnabled(): Boolean = AetherConfig.SHOW_INVENTORY_HUD.get()
    override fun isVisible(): Boolean {
        if (!isEnabled()) return false
        val screen = Minecraft.getInstance().gui.screen()
        return screen == null || screen is HudEditScreen
    }
    override fun getName(): String = "Inventory HUD"
    override fun savePosition() { AetherConfig.save() }

    protected override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        val showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get()
        val showPlayerModel = AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get()
        var cursorX = PAD
        var armorX = -1f
        if (showArmor) { armorX = cursorX; cursorX += SLOT_SIZE + ARMOR_GAP }
        val inventoryX = cursorX
        cursorX += INVENTORY_W
        var modelX = -1f
        if (showPlayerModel) { modelX = cursorX + MODEL_GAP; cursorX = modelX + MODEL_W }
        val width = cursorX + PAD
        HudStyle.panel(nvg, width, H)
        HudStyle.accent(nvg, width, Theme.HUD_ACCENT, Theme.HUD_ACCENT)
        nvg.text(Fonts.BOLD, "Inventory", PAD + 3f, 12f, 11f, Theme.HUD_TITLE)
        val client = Minecraft.getInstance()
        val player = client.player
        var used = 0
        if (player != null) {
            for (slot in 0 until 36) {
                if (!player.inventory.getItem(slot).isEmpty) used++
            }
        }
        nvg.textRight(Fonts.MONO, "$used/36", PAD, 13f, width - PAD * 2, 9f, Theme.HUD_LABEL)
        nvg.rect(inventoryX, SEP_Y, INVENTORY_W, 0.7f, Theme.HUD_SEP)
        if (showArmor) for (i in 0 until 4) drawSlotBg(nvg, armorX, CONTENT_Y + i * ARMOR_STEP)
        for (row in 0 until 3) for (col in 0 until 9) drawSlotBg(nvg, inventoryX + col * SLOT_STEP, CONTENT_Y + row * SLOT_STEP)
        val mc = Minecraft.getInstance()
        val selected = mc.player?.inventory?.selectedSlot ?: -1
        for (col in 0 until 9) {
            val sx = inventoryX + col * SLOT_STEP
            if (col == selected) {
                nvg.roundedRect(sx, HOTBAR_Y, SLOT_SIZE, SLOT_SIZE, 2f, HudStyle.alpha(Theme.HUD_ACCENT, 0.2f))
                nvg.rectOutline(sx, HOTBAR_Y, SLOT_SIZE, SLOT_SIZE, 3f, 1f, Theme.HUD_ACCENT)
            } else drawSlotBg(nvg, sx, HOTBAR_Y)
        }
        if (showPlayerModel) nvg.roundedRect(modelX, CONTENT_Y, MODEL_W, CONTENT_H, 4f, Theme.HUD_BAR_BG)
    }

    private fun drawSlotBg(nvg: NVGRenderer, x: Float, y: Float) { nvg.roundedRect(x, y, SLOT_SIZE, SLOT_SIZE, 2f, Theme.HUD_BAR_BG) }

    override fun renderMinecraft(graphics: GuiGraphicsExtractor, editMode: Boolean) {
        if (!isVisible() && !editMode) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get()
        val showPlayerModel = AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get()
        var cursorX = PAD
        var armorX = -1f
        if (showArmor) { armorX = cursorX; cursorX += SLOT_SIZE + ARMOR_GAP }
        val inventoryX = cursorX
        cursorX += INVENTORY_W
        var modelX = -1f
        if (showPlayerModel) { modelX = cursorX + MODEL_GAP; cursorX = modelX + MODEL_W }
        val width = cursorX + PAD
        val ox = getX(); val oy = getY(); val sc = getScale()
        graphics.enableScissor(screenX(ox, 0f, sc), screenY(oy, 0f, sc), screenX(ox, width, sc), screenY(oy, H, sc))
        try {
            if (showArmor) for (i in 0 until 4) {
                val stack = player.inventory.getItem(39 - i)
                if (!stack.isEmpty) renderItem(graphics, stack, slotItemX(ox, armorX, sc), slotItemY(oy, CONTENT_Y + i * ARMOR_STEP, sc))
            }
            for (row in 0 until 3) for (col in 0 until 9) {
                val stack = player.inventory.getItem(9 + row * 9 + col)
                if (!stack.isEmpty) renderItem(graphics, stack, slotItemX(ox, inventoryX + col * SLOT_STEP, sc), slotItemY(oy, CONTENT_Y + row * SLOT_STEP, sc))
            }
            for (col in 0 until 9) {
                val stack = player.inventory.getItem(col)
                if (!stack.isEmpty) renderItem(graphics, stack, slotItemX(ox, inventoryX + col * SLOT_STEP, sc), slotItemY(oy, HOTBAR_Y, sc))
            }
            if (showPlayerModel) renderPortrait(graphics, player, screenX(ox, modelX, sc), screenY(oy, CONTENT_Y, sc), screenX(ox, modelX + MODEL_W, sc), screenY(oy, CONTENT_Y + CONTENT_H, sc), (32 * sc).toInt())
        } finally { graphics.disableScissor() }
    }

    override fun renderOverlay(nvg: NVGRenderer, editMode: Boolean) {
        val player = Minecraft.getInstance().player ?: return
        val showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get()
        var cursorX = PAD
        var armorX = -1f
        if (showArmor) { armorX = cursorX; cursorX += SLOT_SIZE + ARMOR_GAP }
        val inventoryX = cursorX
        if (showArmor) for (i in 0 until 4) drawCount(nvg, player.inventory.getItem(39 - i), armorX, CONTENT_Y + i * ARMOR_STEP)
        for (row in 0 until 3) for (col in 0 until 9) drawCount(nvg, player.inventory.getItem(9 + row * 9 + col), inventoryX + col * SLOT_STEP, CONTENT_Y + row * SLOT_STEP)
        for (col in 0 until 9) drawCount(nvg, player.inventory.getItem(col), inventoryX + col * SLOT_STEP, HOTBAR_Y)
    }

    private fun drawCount(nvg: NVGRenderer, stack: ItemStack, slotX: Float, slotY: Float) {
        if (stack.isEmpty || stack.count <= 1) return
        nvg.textRight(Fonts.BOLD, stack.count.toString(), slotX, slotY + SLOT_SIZE - 7f, SLOT_SIZE, 8f, Theme.HUD_VALUE)
    }

    companion object {
        private const val PAD = 8f
        private const val CONTENT_Y = 34f
        private const val SLOT_SIZE = 18f
        private const val SLOT_GAP = 2f
        private const val SLOT_STEP = SLOT_SIZE + SLOT_GAP
        private const val ARMOR_GAP = 6f
        private const val MODEL_GAP = 4f
        private const val INVENTORY_W = 9 * SLOT_STEP - SLOT_GAP
        private const val MODEL_W = 58f
        private const val SEP_Y = CONTENT_Y + 3 * SLOT_STEP
        private const val HOTBAR_Y = SEP_Y + SLOT_GAP + 1f
        private const val CONTENT_H = HOTBAR_Y + SLOT_STEP - CONTENT_Y
        private const val H = CONTENT_Y + CONTENT_H + PAD
        private const val ARMOR_STEP = (HOTBAR_Y - CONTENT_Y) / 3f
        @JvmStatic private fun renderPortrait(graphics: GuiGraphicsExtractor, player: Player, x1: Int, y1: Int, x2: Int, y2: Int, scale: Int) {
            val yawRad = Math.toRadians(player.yRot.toDouble())
            val f = Math.sin(2.0 * yawRad).toFloat() * 0.8f
            val pitch = Mth.clamp(player.xRot, -45f, 45f)
            val g = -pitch / 45f * 0.6f
            val cx = (x1 + x2) * 0.5f; val cy = (y1 + y2) * 0.5f
            val mouseX = cx - 40f * kotlin.math.tan(f.toDouble()).toFloat()
            val mouseY = cy - 40f * kotlin.math.tan(g.toDouble()).toFloat()
            val targetBodyRot = 180f + f * 20f; val targetHeadRot = 180f + f * 40f; val targetXRot = -g * 20f
            val savedYBodyRot = player.yBodyRot; val savedYBodyRotO = player.yBodyRotO
            val savedYHeadRot = player.yHeadRot; val savedYHeadRotO = player.yHeadRotO
            val savedXRot = player.xRot; val savedXRotO = player.xRotO
            player.yBodyRot = targetBodyRot; player.yBodyRotO = targetBodyRot
            player.yHeadRot = targetHeadRot; player.yHeadRotO = targetHeadRot
            player.xRot = targetXRot
            (player as AccessorEntity).setXRotO(targetXRot)
            try { InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x1, y1, x2, y2, scale, 0f, mouseX, mouseY, player) }
            finally {
                player.yBodyRot = savedYBodyRot; player.yBodyRotO = savedYBodyRotO
                player.yHeadRot = savedYHeadRot; player.yHeadRotO = savedYHeadRotO
                player.xRot = savedXRot; (player as AccessorEntity).setXRotO(savedXRotO)
            }
        }
        @JvmStatic private fun screenX(originX: Float, localX: Float, scale: Float): Int = (originX + localX * scale).toInt()
        @JvmStatic private fun screenY(originY: Float, localY: Float, scale: Float): Int = (originY + localY * scale).toInt()
        @JvmStatic private fun slotItemX(originX: Float, localX: Float, scale: Float): Int = (originX + localX * scale + (SLOT_SIZE * scale - 16f) / 2f).toInt()
        @JvmStatic private fun slotItemY(originY: Float, localY: Float, scale: Float): Int = (originY + localY * scale + (SLOT_SIZE * scale - 16f) / 2f).toInt()
        @JvmStatic private fun renderItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) { graphics.item(stack, x, y) }
        @JvmStatic private fun computeLayoutWidth(): Float {
            val showArmor = AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get()
            val showPlayerModel = AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get()
            var width = PAD
            if (showArmor) width += SLOT_SIZE + ARMOR_GAP
            width += INVENTORY_W
            if (showPlayerModel) width += MODEL_GAP + MODEL_W
            return width + PAD
        }
    }
}
