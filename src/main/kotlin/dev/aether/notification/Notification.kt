package dev.aether.notification

import dev.aether.ui.theme.Theme

class Notification {

    enum class Type(val iconPath: String) {
        INFO("/assets/aether/icons/info.svg"),
        SUCCESS("/assets/aether/icons/success.svg"),
        WARNING("/assets/aether/icons/warning.svg"),
        ERROR("/assets/aether/icons/error.svg");

        fun color(): Int {
            return when (this) {
                INFO -> Theme.HUD_ACCENT
                SUCCESS -> Theme.HUD_SUCCESS
                WARNING -> Theme.HUD_WARNING
                ERROR -> Theme.HUD_ERROR
            }
        }
    }

    private var title: String
    private var message: String?
    private var type: Type
    private var createdAt: Long
    private val durationMs: Long
    private val dismissible: Boolean

    // Animation state - using single float for smooth interpolation
    private var animProgress = 0f
    private var dismissing = false

    // durationMs of 0 means no auto-dismiss
    constructor(title: String, message: String?, type: Type, durationMs: Long, dismissible: Boolean) {
        this.title = title
        this.message = message
        this.type = type
        this.createdAt = System.currentTimeMillis()
        this.durationMs = durationMs
        this.dismissible = dismissible
    }

    // Convenience constructors
    constructor(title: String, message: String?, type: Type, durationMs: Long) : this(title, message, type, durationMs, true)

    constructor(title: String, message: String?, type: Type) : this(title, message, type, 4000, true)

    constructor(title: String, type: Type) : this(title, null, type, 4000, true)

    // -- Getters ---------------------------------------------------------------

    fun getTitle(): String { return title }
    fun getMessage(): String? { return message }
    fun getType(): Type { return type }
    fun getCreatedAt(): Long { return createdAt }
    fun getDurationMs(): Long { return durationMs }
    fun isDismissible(): Boolean { return dismissible }
    fun hasMessage(): Boolean {
        val m = message ?: return false
        return m.isNotEmpty()
    }

    // -- Animation state (single progress value for efficiency) ----------------

    // 0 hidden, 1 fully shown
    fun getAnimProgress(): Float { return animProgress }
    fun setAnimProgress(progress: Float) { this.animProgress = progress }

    fun isDismissing(): Boolean { return dismissing }
    fun setDismissing(dismissing: Boolean) { this.dismissing = dismissing }

    // -- Lifecycle --------------------------------------------------------------

    fun isExpired(): Boolean {
        if (durationMs <= 0) return false
        return System.currentTimeMillis() - createdAt >= durationMs
    }

    // 0.0 to 1.0 through the notification's lifetime
    fun getLifetimeProgress(): Float {
        if (durationMs <= 0) return 0f
        return Math.min(1f, (System.currentTimeMillis() - createdAt).toFloat() / durationMs)
    }

    fun dismiss() {
        if (!dismissing) {
            dismissing = true
        }
    }

    // updates the visible content in place and restarts the countdown
    fun update(title: String, message: String?, type: Type) {
        this.title = title
        this.message = message
        this.type = type
        this.createdAt = System.currentTimeMillis()
        this.dismissing = false
    }
}
