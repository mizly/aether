package dev.aether.notification

import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.util.AetherLang
import java.util.concurrent.CopyOnWriteArrayList

class NotificationManager private constructor() {
    companion object {
        // -- Configuration ---------------------------------------------------------

        @JvmField var MAX_VISIBLE = 5

        @JvmField var DEFAULT_DURATION: Long = 4000

        @JvmField var ANIMATION_DURATION_MS: Long = 320

        @JvmField var SPACING = 8f

        @JvmField var MARGIN = 16f

        // -- State ------------------------------------------------------------------

        private val notifications: MutableList<Notification> = CopyOnWriteArrayList()

        // -- Public API -------------------------------------------------------------

        @JvmStatic
        fun show(title: String, message: String?, type: Notification.Type, durationMs: Long): Notification? {
            if (StreamerModeManager.isEnabled()) {
                return null
            }
            val notification = Notification(AetherLang.localize(title),
                if (message == null) null else AetherLang.localize(message),
                type, durationMs, true)
            notifications.add(notification)
            return notification
        }

        @JvmStatic
        fun show(title: String, message: String?, type: Notification.Type): Notification? {
            return show(title, message, type, DEFAULT_DURATION)
        }

        @JvmStatic
        fun replace(existing: Notification?, title: String, message: String?, type: Notification.Type): Notification? {
            if (StreamerModeManager.isEnabled()) {
                if (existing != null) {
                    notifications.remove(existing)
                }
                return null
            }

            if (existing != null && notifications.contains(existing) && !existing.isExpired() && !existing.isDismissing()) {
                existing.update(AetherLang.localize(title), if (message == null) null else AetherLang.localize(message), type)
                return existing
            }

            if (existing != null) {
                notifications.remove(existing)
            }

            return show(title, message, type)
        }

        // -- Convenience methods ----------------------------------------------------

        @JvmStatic
        fun info(title: String): Notification? {
            return show(title, null, Notification.Type.INFO)
        }

        @JvmStatic
        fun info(title: String, message: String?): Notification? {
            return show(title, message, Notification.Type.INFO)
        }

        @JvmStatic
        fun info(title: String, message: String?, durationMs: Long): Notification? {
            return show(title, message, Notification.Type.INFO, durationMs)
        }

        @JvmStatic
        fun success(title: String): Notification? {
            return show(title, null, Notification.Type.SUCCESS)
        }

        @JvmStatic
        fun success(title: String, message: String?): Notification? {
            return show(title, message, Notification.Type.SUCCESS)
        }

        @JvmStatic
        fun success(title: String, message: String?, durationMs: Long): Notification? {
            return show(title, message, Notification.Type.SUCCESS, durationMs)
        }

        @JvmStatic
        fun warning(title: String): Notification? {
            return show(title, null, Notification.Type.WARNING)
        }

        @JvmStatic
        fun warning(title: String, message: String?): Notification? {
            return show(title, message, Notification.Type.WARNING)
        }

        @JvmStatic
        fun warning(title: String, message: String?, durationMs: Long): Notification? {
            return show(title, message, Notification.Type.WARNING, durationMs)
        }

        @JvmStatic
        fun error(title: String): Notification? {
            return show(title, null, Notification.Type.ERROR)
        }

        @JvmStatic
        fun error(title: String, message: String?): Notification? {
            return show(title, message, Notification.Type.ERROR)
        }

        @JvmStatic
        fun error(title: String, message: String?, durationMs: Long): Notification? {
            return show(title, message, Notification.Type.ERROR, durationMs)
        }

        // -- Management -------------------------------------------------------------

        @JvmStatic
        fun getNotifications(): MutableList<Notification> {
            return notifications
        }

        @JvmStatic
        fun getCount(): Int {
            return notifications.size
        }

        @JvmStatic
        fun clearAll() {
            for (n in notifications) {
                n.dismiss()
            }
        }

        @JvmStatic
        fun remove(notification: Notification) {
            notifications.remove(notification)
        }

        // -- Update (called each frame by renderer) ---------------------------------

        // deltaTime is in seconds
        @JvmStatic
        fun update(deltaTime: Float) {
            if (notifications.isEmpty()) return

            // Convert to ms for animation calculations
            val deltaMs = Math.max(0f, deltaTime) * 1000f
            val progressDelta = deltaMs / Math.max(1L, ANIMATION_DURATION_MS)

            // Use a list to collect items to remove (CopyOnWriteArrayList doesn't support iterator.remove)
            val toRemove: MutableList<Notification> = java.util.ArrayList()

            for (n in notifications) {
                var progress = n.getAnimProgress()

                if (n.isDismissing()) {
                    // Dismiss animation - progress goes from current to 0
                    progress -= progressDelta
                    if (progress <= 0f) {
                        toRemove.add(n)
                        continue
                    }
                } else {
                    // Entry animation - progress goes from 0 to 1
                    if (progress < 1f) {
                        progress = Math.min(1f, progress + progressDelta)
                    }
                    // Check for auto-dismiss after entry animation completes
                    if (progress >= 1f && n.isExpired()) {
                        n.setDismissing(true)
                    }
                }

                n.setAnimProgress(progress)
            }

            // Remove completed notifications
            if (!toRemove.isEmpty()) {
                notifications.removeAll(toRemove)
            }
        }
    }
}
