package dev.aether.notification

import dev.aether.hud.HudStyle
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import java.util.HashMap
import java.util.List

class NotificationRenderer private constructor() {
    companion object {
        private const val WIDTH = 320f
        private const val ICON_SIZE = 16f
        private const val ICON_TILE = 22f
        private val ICON_TILE_X = HudStyle.PAD - 3f
        private const val ICON_TILE_Y = 11f
        private val TEXT_X = ICON_TILE_X + ICON_TILE + 8f
        private const val TITLE_SIZE = 12f
        private val TITLE_Y = ICON_TILE_Y + (ICON_TILE - TITLE_SIZE) / 2f
        private const val MESSAGE_SIZE = 11f
        private val MESSAGE_Y = TITLE_Y + TITLE_SIZE + 3f
        private const val LINE_STEP = 15f
        private const val BOTTOM_PAD = 13f
        private const val TITLE_ONLY_HEIGHT = 44f
        private val layouts: MutableMap<Notification, ToastLayout> = HashMap()
        private var lastFrameNanos: Long = 0L

        @JvmStatic
        fun render(nvg: NVGRenderer, screenWidth: Float, screenHeight: Float) {
            // Minecraft tick deltas and GUI partial ticks use different units; share one real-time clock.
            val now = System.nanoTime()
            val deltaTime = if (lastFrameNanos == 0L) 0f
                else Math.clamp((now - lastFrameNanos) / 1_000_000_000f, 0f, 0.1f)
            lastFrameNanos = now
            NotificationManager.update(deltaTime)

            val notifications: MutableList<Notification> = NotificationManager.getNotifications()
            if (notifications.isEmpty()) {
                layouts.clear()
                lastFrameNanos = 0L
                return
            }

            val width = Math.min(WIDTH, screenWidth - NotificationManager.MARGIN * 2)
            if (width <= TEXT_X + HudStyle.PAD) return
            var targetY = NotificationManager.MARGIN
            val follow = 1f - Math.exp(-18.0 * deltaTime).toFloat()
            var visible = 0
            for (notification in notifications) {
                if (visible++ >= NotificationManager.MAX_VISIBLE) break
                var layout = layouts[notification]
                if (layout == null) {
                    layout = ToastLayout(targetY)
                    layouts[notification] = layout
                }
                layout.measure(nvg, notification, width, screenHeight)
                layout.y += (targetY - layout.y) * follow
                renderNotification(nvg, notification, layout, screenWidth)
                targetY += layout.height + NotificationManager.SPACING
            }
            layouts.keys.removeIf { n -> !notifications.contains(n) }
        }

        private fun renderNotification(nvg: NVGRenderer, notification: Notification,
                                       layout: ToastLayout, screenWidth: Float) {
            val progress = Math.clamp(notification.getAnimProgress(), 0f, 1f)
            val visibility = progress * progress * (3f - 2f * progress)
            if (visibility <= 0f) return
            val x = screenWidth - NotificationManager.MARGIN - layout.width + (1f - visibility) * 28f
            val accent = notification.getType().color()

            nvg.save()
            nvg.translate(x, layout.y)
            nvg.globalAlpha(visibility)
            HudStyle.panel(nvg, layout.width, layout.height)
            HudStyle.accent(nvg, layout.width, accent, accent)

            val iconInset = (ICON_TILE - ICON_SIZE) / 2f
            nvg.roundedRect(ICON_TILE_X, ICON_TILE_Y, ICON_TILE, ICON_TILE, 5f, HudStyle.alpha(accent, 0.12f))
            nvg.renderSVG(notification.getType().iconPath, ICON_TILE_X + iconInset, ICON_TILE_Y + iconInset,
                ICON_SIZE, ICON_SIZE, accent)

            val textWidth = layout.width - TEXT_X - HudStyle.PAD
            HudStyle.text(nvg, Fonts.BOLD, layout.title ?: "", TEXT_X, TITLE_Y, textWidth, TITLE_SIZE, Theme.HUD_TITLE)
            for (i in layout.lines.indices) {
                HudStyle.text(nvg, Fonts.REGULAR, layout.lines[i], TEXT_X, MESSAGE_Y + i * LINE_STEP,
                    textWidth, MESSAGE_SIZE, Theme.HUD_LABEL)
            }

            if (notification.getDurationMs() > 0) {
                val barWidth = layout.width - HudStyle.PAD * 2
                val barY = layout.height - 7f
                nvg.roundedRect(HudStyle.PAD, barY, barWidth, 2f, 1f, Theme.HUD_BAR_BG)
                val filled = barWidth * (1f - notification.getLifetimeProgress())
                if (filled > 0f) nvg.roundedRect(HudStyle.PAD, barY, filled, 2f, 1f, HudStyle.alpha(accent, 0.7f))
            }
            nvg.restore()
        }

        private fun heightForLines(lines: Int): Float {
            if (lines <= 0) return TITLE_ONLY_HEIGHT
            return MESSAGE_Y + MESSAGE_SIZE + (lines - 1) * LINE_STEP + BOTTOM_PAD
        }

        private class ToastLayout(@JvmField var y: Float) {
            @JvmField var width: Float = 0f
            @JvmField var height: Float = 0f
            @JvmField var screenHeight: Float = 0f
            @JvmField var title: String? = null
            @JvmField var message: String? = null
            @JvmField var lines: MutableList<String> = ArrayList()
            fun measure(nvg: NVGRenderer, notification: Notification, width: Float, screenHeight: Float) {
                if (this.width == width && this.screenHeight == screenHeight
                    && java.util.Objects.equals(title, notification.getTitle())
                    && java.util.Objects.equals(message, notification.getMessage())) return
                this.width = width
                this.screenHeight = screenHeight
                title = notification.getTitle()
                message = notification.getMessage()
                lines = if (notification.hasMessage()) nvg.wrapTextToWidth(Fonts.REGULAR, notification.getMessage()!!, MESSAGE_SIZE, width - TEXT_X - HudStyle.PAD).toMutableList() else ArrayList()
                val available = screenHeight - NotificationManager.MARGIN * 2 - heightForLines(1)
                val maxLines = Math.max(1, (available / LINE_STEP).toInt() + 1)
                if (lines.size > maxLines) {
                    lines = java.util.ArrayList<String>(lines.subList(0, maxLines))
                    lines = lines.toMutableList()
                    lines = lines.toMutableList().also { it[maxLines - 1] = it[ it.size - 1] + "..." }
                }
                height = heightForLines(lines.size)
            }
        }
    }
}
