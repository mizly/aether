package dev.aether.ui

import dev.aether.ui.settings.TextSetting
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import java.util.ArrayList

internal class MainGUIInlineTextEditor(private val owner: MainGUI) {
    fun hasActiveInlineEditor(): Boolean = owner.activeSliderField != null || owner.activeText != null
            || owner.activeList != null || owner.activePosField != null

    fun isSearchEditorActive(): Boolean = owner.searchMode && !owner.profileNameFocus && !owner.profileRenameFocus
            && owner.activeSliderField == null && owner.activeText == null
            && owner.activeList == null && owner.activePosField == null && !owner.cpHexFocus

    fun syncSearchQueryFromEditor() {
        owner.searchQuery = owner.textBuf.toString()
        owner.searchScrollY = 0f
        owner.searchTargetScrollY = 0f
        normalizeTextCursorState()
    }

    fun focusSearchField() {
        if (!owner.searchMode) owner.searchMode = true
        owner.textBuf = StringBuilder(owner.searchQuery)
        owner.textCursor = owner.textBuf.length
        clearTextSelection()
        normalizeTextCursorState()
    }

    fun isPointInsideSearchField(mx: Float, my: Float): Boolean =
        mx >= owner.searchBoxX && mx <= owner.searchBoxX + owner.searchBoxW
                && my >= owner.searchBoxY && my <= owner.searchBoxY + owner.searchBoxH

    fun updateSearchSelectionFromMouse(mx: Float) {
        val value = owner.textBuf.toString()
        val textPad = 6f
        val fontSize = 11f
        val visibleTextW = owner.searchBoxW - 30f - textPad * 2f
        val textOffset = owner.inlineTextScrollOffsetApprox(value, owner.textCursor, fontSize, visibleTextW)
        val startX = owner.searchBoxX + 30f + textPad - textOffset
        owner.textCursor = cursorFromInlineText(value, fontSize, startX, mx)
    }

    fun hasTextSelection(): Boolean {
        if (owner.textSelectionAnchor < 0) return false
        val len = owner.textBuf.length
        val cursor = Math.max(0, Math.min(owner.textCursor, len))
        val anchor = Math.max(0, Math.min(owner.textSelectionAnchor, len))
        return anchor != cursor
    }

    fun textSelectionStart(): Int {
        val len = owner.textBuf.length
        val cursor = Math.max(0, Math.min(owner.textCursor, len))
        val anchor = if (owner.textSelectionAnchor < 0) cursor else Math.max(0, Math.min(owner.textSelectionAnchor, len))
        return Math.min(anchor, cursor)
    }

    fun textSelectionEnd(): Int {
        val len = owner.textBuf.length
        val cursor = Math.max(0, Math.min(owner.textCursor, len))
        val anchor = if (owner.textSelectionAnchor < 0) cursor else Math.max(0, Math.min(owner.textSelectionAnchor, len))
        return Math.max(anchor, cursor)
    }

    fun clearTextSelection() {
        owner.textSelectionAnchor = -1
        owner.textSelectionDragging = false
        owner.textSelectionPendingDrag = false
        owner.textSelectionPressCursor = -1
    }

    fun normalizeTextCursorState() {
        val len = owner.textBuf.length
        owner.textCursor = Math.max(0, Math.min(owner.textCursor, len))
        if (owner.textSelectionAnchor >= 0) {
            owner.textSelectionAnchor = Math.max(0, Math.min(owner.textSelectionAnchor, len))
            if (owner.textSelectionAnchor == owner.textCursor) clearTextSelection()
        }
    }

    fun safeSubstring(value: String?, start: Int, end: Int): String {
        if (value == null) return ""
        val len = value.length
        var safeStart = Math.max(0, Math.min(start, len))
        var safeEnd = Math.max(0, Math.min(end, len))
        if (safeEnd < safeStart) {
            val swap = safeStart
            safeStart = safeEnd
            safeEnd = swap
        }
        if (safeStart == safeEnd) return ""
        return value.substring(safeStart, safeEnd)
    }

    fun setTextCursor(cursor: Int, extendSelection: Boolean) {
        val clamped = Math.max(0, Math.min(cursor, owner.textBuf.length))
        if (extendSelection) {
            if (!hasTextSelection()) owner.textSelectionAnchor = owner.textCursor
        } else {
            clearTextSelection()
        }
        owner.textCursor = clamped
        if (!extendSelection && owner.textSelectionAnchor >= 0 && owner.textSelectionAnchor == owner.textCursor) clearTextSelection()
    }

    fun selectAllText() {
        owner.textSelectionAnchor = 0
        owner.textCursor = owner.textBuf.length
    }

    fun deleteSelectedText(): Boolean {
        if (!hasTextSelection()) return false
        val start = textSelectionStart()
        val end = textSelectionEnd()
        owner.textBuf.delete(start, end)
        owner.textCursor = start
        clearTextSelection()
        normalizeTextCursorState()
        return true
    }

    fun insertTextAtCursor(text: String?) {
        if (text == null || text.isEmpty()) return
        if (deleteSelectedText()) {
            owner.textBuf.insert(owner.textCursor, text)
            owner.textCursor += text.length
            return
        }
        owner.textBuf.insert(owner.textCursor, text)
        owner.textCursor += text.length
        normalizeTextCursorState()
    }

    fun updateInlineSelectionFromMouse(mx: Float) {
        if (!hasActiveInlineEditor()) return
        if (owner.activeSliderField != null) {
            val value = owner.textBuf.toString()
            val fieldTextSize = 12.5f
            val drawX = owner.activeEditorX + (owner.activeEditorW - value.length * fieldTextSize * 0.52f) / 2f
            owner.textCursor = cursorFromInlineText(value, fieldTextSize, drawX, mx)
            return
        }
        if (owner.activePosField != null || owner.activeText != null || owner.activeList != null) {
            val value = owner.textBuf.toString()
            val textPad = 6f
            val fieldTextSize = 12.5f
            val visibleTextW = owner.activeEditorW - textPad * 2f
            val textOffset = owner.inlineTextScrollOffsetApprox(value, owner.textCursor, fieldTextSize, visibleTextW)
            val startX = owner.activeEditorX + textPad - textOffset
            owner.textCursor = cursorFromInlineText(value, fieldTextSize, startX, mx)
        }
    }

    fun beginInlineCaretPress(mx: Float, my: Float) {
        owner.textSelectionPendingDrag = true
        owner.textSelectionDragging = false
        owner.textSelectionPressCursor = owner.textCursor
        owner.textSelectionPressX = mx
        owner.textSelectionPressY = my
    }

    fun shouldBeginInlineSelectionDrag(mx: Float, my: Float): Boolean {
        if (!owner.textSelectionPendingDrag || (!hasActiveInlineEditor() && !isSearchEditorActive())) return false
        val dx = mx - owner.textSelectionPressX
        val dy = my - owner.textSelectionPressY
        return dx * dx + dy * dy >= 9f
    }

    fun setActiveEditorBounds(x: Float, y: Float, w: Float, h: Float) {
        owner.activeEditorBoundsSet = true
        owner.activeEditorX = x
        owner.activeEditorY = y
        owner.activeEditorW = w
        owner.activeEditorH = h
    }

    fun isPointInsideActiveEditor(mx: Float, my: Float): Boolean =
        owner.activeEditorBoundsSet
                && mx >= owner.activeEditorX && mx <= owner.activeEditorX + owner.activeEditorW
                && my >= owner.activeEditorY && my <= owner.activeEditorY + owner.activeEditorH

    fun textSettingHeight(setting: TextSetting): Float = 26f + textFieldHeight(setting)

    fun textFieldHeight(setting: TextSetting): Float {
        if (!setting.isMultiline()) return MainGUI.TEXT_SINGLE_FIELD_H
        return MainGUI.TEXT_MULTI_PAD_Y * 2f + setting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f
    }

    fun getMultilineViewportStart(setting: TextSetting, text: String, cursor: Int): Int {
        val totalLines = splitLines(text).size
        val maxStart = Math.max(0, totalLines - setting.getVisibleLines())
        val cursorLine = getCursorLine(text, cursor)
        return Math.max(0, Math.min(maxStart, cursorLine - setting.getVisibleLines() + 1))
    }

    fun handleControlShortcut(key: Int): Boolean {
        val keyboard = Minecraft.getInstance().keyboardHandler
        if (key == InputConstants.KEY_A) {
            if (hasActiveInlineEditor() || isSearchEditorActive()) {
                selectAllText()
                if (isSearchEditorActive()) syncSearchQueryFromEditor()
                return true
            }
            return false
        }
        if (key == InputConstants.KEY_C) {
            val hovered = owner.hoveredColor
            if (hovered != null) {
                keyboard.setClipboard(String.format("%08X", hovered.getValue()))
                return true
            }
            if ((hasActiveInlineEditor() || isSearchEditorActive()) && hasTextSelection()) {
                keyboard.setClipboard(owner.textBuf.substring(textSelectionStart(), textSelectionEnd()))
                return true
            }
            if (owner.activeText != null || owner.activeList != null || owner.activePosField != null
                || owner.activeSliderField != null || isSearchEditorActive()) {
                keyboard.setClipboard(owner.textBuf.toString())
                return true
            }
            return false
        }
        if (key != InputConstants.KEY_V) return false
        val clip = keyboard.getClipboard() ?: return false
        if (clip.isEmpty()) return false
        val hovered = owner.hoveredColor
        if (hovered != null) {
            try {
                val hex = clip.trim().replaceFirst("^#".toRegex(), "")
                val argb = if (hex.length == 8) java.lang.Long.parseLong(hex, 16).toInt() else (0xFF000000L or java.lang.Long.parseLong(hex, 16)).toInt()
                hovered.setValue(argb)
                if (owner.activeColor == hovered) owner.argbToHsv(argb)
            } catch (_: NumberFormatException) {
            }
            return true
        }
        if (owner.activeSliderField != null || owner.activeText != null || owner.activeList != null || owner.activePosField != null) {
            insertTextAtCursor(clip)
            return true
        }
        if (isSearchEditorActive()) {
            insertTextAtCursor(clip)
            syncSearchQueryFromEditor()
            return true
        }
        if (owner.profileNameFocus) {
            owner.profileNameInput += clip
            return true
        }
        if (owner.profileRenameFocus) {
            owner.profileRenameInput += clip
            return true
        }
        return false
    }

    fun handleSearchEditorKeyPressed(key: Int, ctrl: Boolean, shift: Boolean): Boolean {
        if (!isSearchEditorActive()) return false
        val hasSelection = hasTextSelection()
        when (key) {
            InputConstants.KEY_BACKSPACE -> {
                if (!deleteSelectedText() && owner.textCursor > 0) owner.textBuf.deleteCharAt(--owner.textCursor)
            }
            InputConstants.KEY_DELETE -> {
                if (!deleteSelectedText() && owner.textCursor < owner.textBuf.length) owner.textBuf.deleteCharAt(owner.textCursor)
            }
            InputConstants.KEY_LEFT -> {
                val nextCursor = if (ctrl) moveCursorWord(owner.textBuf.toString(), owner.textCursor, -1) else Math.max(0, owner.textCursor - 1)
                if (shift) setTextCursor(nextCursor, true)
                else if (hasSelection) setTextCursor(if (ctrl) nextCursor else textSelectionStart(), false)
                else setTextCursor(nextCursor, false)
            }
            InputConstants.KEY_RIGHT -> {
                val nextCursor = if (ctrl) moveCursorWord(owner.textBuf.toString(), owner.textCursor, 1) else Math.min(owner.textBuf.length, owner.textCursor + 1)
                if (shift) setTextCursor(nextCursor, true)
                else if (hasSelection) setTextCursor(if (ctrl) nextCursor else textSelectionEnd(), false)
                else setTextCursor(nextCursor, false)
            }
            InputConstants.KEY_HOME -> setTextCursor(0, shift)
            InputConstants.KEY_END -> setTextCursor(owner.textBuf.length, shift)
            else -> return false
        }
        syncSearchQueryFromEditor()
        return true
    }

    fun handleProfileNameKeyPressed(key: Int): Boolean {
        if (owner.profileRenameFocus) {
            when (key) {
                InputConstants.KEY_BACKSPACE -> {
                    if (owner.profileRenameInput.isNotEmpty()) owner.profileRenameInput = owner.profileRenameInput.substring(0, owner.profileRenameInput.length - 1)
                }
                InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> owner.commitProfileRename()
                InputConstants.KEY_DELETE -> owner.profileRenameInput = ""
                else -> return false
            }
            return true
        }
        if (!owner.profileNameFocus) return false
        when (key) {
            InputConstants.KEY_BACKSPACE -> {
                if (owner.profileNameInput.isNotEmpty()) owner.profileNameInput = owner.profileNameInput.substring(0, owner.profileNameInput.length - 1)
            }
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> owner.profileNameFocus = false
            InputConstants.KEY_DELETE -> owner.profileNameInput = ""
            else -> return false
        }
        return true
    }

    fun handleInlineEditorKeyPressed(key: Int, ctrl: Boolean, shift: Boolean): Boolean {
        if (!hasActiveInlineEditor()) return false
        val multilineText = owner.activeText != null && owner.activeText?.isMultiline() == true
        val hasSelection = hasTextSelection()
        when (key) {
            InputConstants.KEY_BACKSPACE -> {
                if (!deleteSelectedText() && owner.textCursor > 0) owner.textBuf.deleteCharAt(--owner.textCursor)
            }
            InputConstants.KEY_DELETE -> {
                if (!deleteSelectedText() && owner.textCursor < owner.textBuf.length) owner.textBuf.deleteCharAt(owner.textCursor)
            }
            InputConstants.KEY_LEFT -> {
                val nextCursor = if (ctrl) moveCursorWord(owner.textBuf.toString(), owner.textCursor, -1) else Math.max(0, owner.textCursor - 1)
                if (shift) setTextCursor(nextCursor, true)
                else if (hasSelection) setTextCursor(if (ctrl) nextCursor else textSelectionStart(), false)
                else setTextCursor(nextCursor, false)
            }
            InputConstants.KEY_RIGHT -> {
                val nextCursor = if (ctrl) moveCursorWord(owner.textBuf.toString(), owner.textCursor, 1) else Math.min(owner.textBuf.length, owner.textCursor + 1)
                if (shift) setTextCursor(nextCursor, true)
                else if (hasSelection) setTextCursor(if (ctrl) nextCursor else textSelectionEnd(), false)
                else setTextCursor(nextCursor, false)
            }
            InputConstants.KEY_UP -> {
                if (!multilineText) return false
                if (shift) setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, -1), true)
                else if (hasSelection) setTextCursor(textSelectionStart(), false)
                else setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, -1), false)
            }
            InputConstants.KEY_DOWN -> {
                if (!multilineText) return false
                if (shift) setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, 1), true)
                else if (hasSelection) setTextCursor(textSelectionEnd(), false)
                else setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, 1), false)
            }
            InputConstants.KEY_HOME -> {
                val nextCursor = if (multilineText) getLineStart(owner.textBuf.toString(), owner.textCursor) else 0
                setTextCursor(nextCursor, shift)
            }
            InputConstants.KEY_END -> {
                val nextCursor = if (multilineText) getLineEnd(owner.textBuf.toString(), owner.textCursor) else owner.textBuf.length
                setTextCursor(nextCursor, shift)
            }
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (multilineText) insertTextAtCursor("\n") else commitText()
            }
            else -> return false
        }
        return true
    }

    fun handleCharTyped(ch: Char): Boolean {
        if (isSearchEditorActive()) {
            insertTextAtCursor(ch.toString())
            syncSearchQueryFromEditor()
            return true
        }
        if (owner.profileNameFocus) {
            owner.profileNameInput += ch
            return true
        }
        if (owner.profileRenameFocus) {
            owner.profileRenameInput += ch
            return true
        }
        if (hasActiveInlineEditor()) {
            insertTextAtCursor(ch.toString())
            return true
        }
        return false
    }

    fun commitText() {
        owner.profileNameFocus = false
        if (owner.profileRenameFocus) owner.commitProfileRename()
        clearTextSelection()
        if (owner.activeSliderField != null) {
            try {
                val parsed = owner.textBuf.toString().trim().toFloat()
                val slider = owner.activeSliderField ?: return
                slider.setValue(parsed)
                owner.textBuf = StringBuilder(owner.fmtSliderInput(slider))
                owner.textCursor = owner.textBuf.length
            } catch (_: NumberFormatException) {
            }
            owner.activeSliderField = null
        }
        if (owner.activeText != null) {
            val text = owner.activeText ?: return
            text.setValue(owner.textBuf.toString())
            owner.activeText = null
        }
        if (owner.activeList != null) {
            val list = owner.activeList ?: return
            val values = list.getValues()
            val value = owner.textBuf.toString().trim()
            if (owner.activeListIndex >= 0 && owner.activeListIndex < values.size) {
                if (value.isEmpty()) values.removeAt(owner.activeListIndex) else values[owner.activeListIndex] = value
                list.setValues(values)
            }
            owner.activeList = null
            owner.activeListIndex = -1
        }
        if (owner.activePosField != null) {
            try {
                val value = owner.textBuf.toString().toDouble()
                val field = owner.activePosField ?: return
                when (owner.activePosIdx) {
                    0 -> field.setX(value)
                    1 -> field.setY(value)
                    2 -> field.setZ(value)
                }
            } catch (_: NumberFormatException) {
            }
            owner.activePosField = null
        }
        normalizeTextCursorState()
    }

    companion object {
        @JvmStatic fun isWordChar(c: Char): Boolean = !Character.isWhitespace(c)

        @JvmStatic fun moveCursorWord(text: String?, cursor: Int, direction: Int): Int {
            if (text == null || text.isEmpty()) return 0
            var pos = Math.max(0, Math.min(cursor, text.length))
            if (direction < 0) {
                if (pos == 0) return 0
                pos--
                while (pos > 0 && !isWordChar(text[pos])) pos--
                while (pos > 0 && isWordChar(text[pos - 1])) pos--
                return pos
            }
            while (pos < text.length && isWordChar(text[pos])) pos++
            while (pos < text.length && !isWordChar(text[pos])) pos++
            return pos
        }

        @JvmStatic fun cursorFromInlineText(text: String?, fontSize: Float, startX: Float, mx: Float): Int {
            if (text == null || text.isEmpty()) return 0
            val relX = mx - startX
            if (relX <= 0f) return 0
            val len = text.length
            val avgCharWidth = fontSize * 0.52f
            val totalWidth = len * avgCharWidth
            if (relX >= totalWidth) return len
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val width = mid * avgCharWidth
                if (width < relX) lo = mid + 1 else hi = mid
            }
            var idx = Math.max(0, Math.min(len, lo))
            if (idx > 0 && idx < len) {
                val left = (idx - 1) * avgCharWidth
                val right = idx * avgCharWidth
                if (relX - left < right - relX) idx--
            }
            return Math.max(0, Math.min(len, idx))
        }

        @JvmStatic fun splitLines(text: String): Array<String> = text.split("\n".toRegex(), -1).toTypedArray()

        @JvmStatic fun getLineStart(text: String, cursor: Int): Int {
            var pos = Math.max(0, Math.min(cursor, text.length))
            while (pos > 0 && text[pos - 1] != '\n') pos--
            return pos
        }

        @JvmStatic fun getLineEnd(text: String, cursor: Int): Int {
            var pos = Math.max(0, Math.min(cursor, text.length))
            while (pos < text.length && text[pos] != '\n') pos++
            return pos
        }

        @JvmStatic fun getCursorLine(text: String, cursor: Int): Int {
            val pos = Math.max(0, Math.min(cursor, text.length))
            var line = 0
            for (i in 0 until pos) if (text[i] == '\n') line++
            return line
        }

        @JvmStatic fun getCursorColumn(text: String, cursor: Int): Int = Math.max(0, Math.min(cursor, text.length) - getLineStart(text, cursor))

        @JvmStatic fun moveCursorVertical(text: String, cursor: Int, delta: Int): Int {
            val currentStart = getLineStart(text, cursor)
            val currentCol = cursor - currentStart
            if (delta < 0) {
                if (currentStart == 0) return cursor
                val previousEnd = currentStart - 1
                val previousStart = getLineStart(text, previousEnd)
                return Math.min(previousStart + currentCol, previousEnd)
            }
            val currentEnd = getLineEnd(text, cursor)
            if (currentEnd >= text.length) return cursor
            val nextStart = currentEnd + 1
            val nextEnd = getLineEnd(text, nextStart)
            return Math.min(nextStart + currentCol, nextEnd)
        }
    }
}
