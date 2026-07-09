package dev.aether.ui;

import dev.aether.ui.settings.TextSetting;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;

final class MainGUIInlineTextEditor {
    private final MainGUI owner;

    MainGUIInlineTextEditor(MainGUI owner) {
        this.owner = owner;
    }

    boolean hasActiveInlineEditor() {
        return owner.activeSliderField != null || owner.activeText != null
                || owner.activeList != null || owner.activePosField != null;
    }

    boolean isSearchEditorActive() {
        return owner.searchMode && !owner.profileNameFocus && !owner.profileRenameFocus
                && owner.activeSliderField == null && owner.activeText == null
                && owner.activeList == null && owner.activePosField == null && !owner.cpHexFocus;
    }

    void syncSearchQueryFromEditor() {
        owner.searchQuery = owner.textBuf.toString();
        owner.searchScrollY = 0f;
        owner.searchTargetScrollY = 0f;
        normalizeTextCursorState();
    }

    void focusSearchField() {
        if (!owner.searchMode) {
            owner.searchMode = true;
        }
        owner.textBuf = new StringBuilder(owner.searchQuery);
        owner.textCursor = owner.textBuf.length();
        clearTextSelection();
        normalizeTextCursorState();
    }

    boolean isPointInsideSearchField(float mx, float my) {
        return mx >= owner.searchBoxX && mx <= owner.searchBoxX + owner.searchBoxW
                && my >= owner.searchBoxY && my <= owner.searchBoxY + owner.searchBoxH;
    }

    void updateSearchSelectionFromMouse(float mx) {
        String value = owner.textBuf.toString();
        float textPad = 6f;
        float fontSize = 11f;
        float visibleTextW = owner.searchBoxW - 30f - textPad * 2f;
        float textOffset = owner.inlineTextScrollOffsetApprox(value, owner.textCursor, fontSize, visibleTextW);
        float startX = owner.searchBoxX + 30f + textPad - textOffset;
        owner.textCursor = cursorFromInlineText(value, fontSize, startX, mx);
    }

    boolean hasTextSelection() {
        if (owner.textSelectionAnchor < 0) {
            return false;
        }
        int len = owner.textBuf.length();
        int cursor = Math.max(0, Math.min(owner.textCursor, len));
        int anchor = Math.max(0, Math.min(owner.textSelectionAnchor, len));
        return anchor != cursor;
    }

    int textSelectionStart() {
        int len = owner.textBuf.length();
        int cursor = Math.max(0, Math.min(owner.textCursor, len));
        int anchor = owner.textSelectionAnchor < 0 ? cursor : Math.max(0, Math.min(owner.textSelectionAnchor, len));
        return Math.min(anchor, cursor);
    }

    int textSelectionEnd() {
        int len = owner.textBuf.length();
        int cursor = Math.max(0, Math.min(owner.textCursor, len));
        int anchor = owner.textSelectionAnchor < 0 ? cursor : Math.max(0, Math.min(owner.textSelectionAnchor, len));
        return Math.max(anchor, cursor);
    }

    void clearTextSelection() {
        owner.textSelectionAnchor = -1;
        owner.textSelectionDragging = false;
        owner.textSelectionPendingDrag = false;
        owner.textSelectionPressCursor = -1;
    }

    void normalizeTextCursorState() {
        int len = owner.textBuf.length();
        owner.textCursor = Math.max(0, Math.min(owner.textCursor, len));
        if (owner.textSelectionAnchor >= 0) {
            owner.textSelectionAnchor = Math.max(0, Math.min(owner.textSelectionAnchor, len));
            if (owner.textSelectionAnchor == owner.textCursor) {
                clearTextSelection();
            }
        }
    }

    String safeSubstring(String value, int start, int end) {
        if (value == null) {
            return "";
        }
        int len = value.length();
        int safeStart = Math.max(0, Math.min(start, len));
        int safeEnd = Math.max(0, Math.min(end, len));
        if (safeEnd < safeStart) {
            int swap = safeStart;
            safeStart = safeEnd;
            safeEnd = swap;
        }
        if (safeStart == safeEnd) {
            return "";
        }
        return value.substring(safeStart, safeEnd);
    }

    void setTextCursor(int cursor, boolean extendSelection) {
        int clamped = Math.max(0, Math.min(cursor, owner.textBuf.length()));
        if (extendSelection) {
            if (!hasTextSelection()) {
                owner.textSelectionAnchor = owner.textCursor;
            }
        } else {
            clearTextSelection();
        }
        owner.textCursor = clamped;
        if (!extendSelection && owner.textSelectionAnchor >= 0 && owner.textSelectionAnchor == owner.textCursor) {
            clearTextSelection();
        }
    }

    void selectAllText() {
        owner.textSelectionAnchor = 0;
        owner.textCursor = owner.textBuf.length();
    }

    boolean deleteSelectedText() {
        if (!hasTextSelection()) {
            return false;
        }
        int start = textSelectionStart();
        int end = textSelectionEnd();
        owner.textBuf.delete(start, end);
        owner.textCursor = start;
        clearTextSelection();
        normalizeTextCursorState();
        return true;
    }

    void insertTextAtCursor(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (deleteSelectedText()) {
            owner.textBuf.insert(owner.textCursor, text);
            owner.textCursor += text.length();
            return;
        }
        owner.textBuf.insert(owner.textCursor, text);
        owner.textCursor += text.length();
        normalizeTextCursorState();
    }

    void updateInlineSelectionFromMouse(float mx) {
        if (!hasActiveInlineEditor()) {
            return;
        }
        if (owner.activeSliderField != null) {
            String value = owner.textBuf.toString();
            float fieldTextSize = 12.5f;
            float drawX = owner.activeEditorX + (owner.activeEditorW - value.length() * fieldTextSize * 0.52f) / 2f;
            owner.textCursor = cursorFromInlineText(value, fieldTextSize, drawX, mx);
            return;
        }

        if (owner.activePosField != null || owner.activeText != null || owner.activeList != null) {
            String value = owner.textBuf.toString();
            float textPad = 6f;
            float fieldTextSize = 12.5f;
            float visibleTextW = owner.activeEditorW - textPad * 2f;
            float textOffset = owner.inlineTextScrollOffsetApprox(value, owner.textCursor, fieldTextSize, visibleTextW);
            float startX = owner.activeEditorX + textPad - textOffset;
            owner.textCursor = cursorFromInlineText(value, fieldTextSize, startX, mx);
        }
    }

    void beginInlineCaretPress(float mx, float my) {
        owner.textSelectionPendingDrag = true;
        owner.textSelectionDragging = false;
        owner.textSelectionPressCursor = owner.textCursor;
        owner.textSelectionPressX = mx;
        owner.textSelectionPressY = my;
    }

    boolean shouldBeginInlineSelectionDrag(float mx, float my) {
        if (!owner.textSelectionPendingDrag || (!hasActiveInlineEditor() && !isSearchEditorActive())) {
            return false;
        }
        float dx = mx - owner.textSelectionPressX;
        float dy = my - owner.textSelectionPressY;
        return dx * dx + dy * dy >= 9f;
    }

    void setActiveEditorBounds(float x, float y, float w, float h) {
        owner.activeEditorBoundsSet = true;
        owner.activeEditorX = x;
        owner.activeEditorY = y;
        owner.activeEditorW = w;
        owner.activeEditorH = h;
    }

    boolean isPointInsideActiveEditor(float mx, float my) {
        return owner.activeEditorBoundsSet
                && mx >= owner.activeEditorX && mx <= owner.activeEditorX + owner.activeEditorW
                && my >= owner.activeEditorY && my <= owner.activeEditorY + owner.activeEditorH;
    }

    float textSettingHeight(TextSetting setting) {
        return 26f + textFieldHeight(setting);
    }

    float textFieldHeight(TextSetting setting) {
        if (!setting.isMultiline()) {
            return MainGUI.TEXT_SINGLE_FIELD_H;
        }
        return MainGUI.TEXT_MULTI_PAD_Y * 2f + setting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f;
    }

    int getMultilineViewportStart(TextSetting setting, String text, int cursor) {
        int totalLines = splitLines(text).length;
        int maxStart = Math.max(0, totalLines - setting.getVisibleLines());
        int cursorLine = getCursorLine(text, cursor);
        return Math.max(0, Math.min(maxStart, cursorLine - setting.getVisibleLines() + 1));
    }

    boolean handleControlShortcut(int key) {
        var keyboard = Minecraft.getInstance().keyboardHandler;
        if (key == GLFW.GLFW_KEY_A) {
            if (hasActiveInlineEditor() || isSearchEditorActive()) {
                selectAllText();
                if (isSearchEditorActive()) {
                    syncSearchQueryFromEditor();
                }
                return true;
            }
            return false;
        }

        if (key == GLFW.GLFW_KEY_C) {
            if (owner.hoveredColor != null) {
                keyboard.setClipboard(String.format("%08X", owner.hoveredColor.getValue()));
                return true;
            }
            if ((hasActiveInlineEditor() || isSearchEditorActive()) && hasTextSelection()) {
                keyboard.setClipboard(owner.textBuf.substring(textSelectionStart(), textSelectionEnd()));
                return true;
            }
            if (owner.activeText != null || owner.activeList != null || owner.activePosField != null
                    || owner.activeSliderField != null || isSearchEditorActive()) {
                keyboard.setClipboard(owner.textBuf.toString());
                return true;
            }
            return false;
        }

        if (key != GLFW.GLFW_KEY_V) {
            return false;
        }

        String clip = keyboard.getClipboard();
        if (clip == null || clip.isEmpty()) {
            return false;
        }
        if (owner.hoveredColor != null) {
            try {
                String hex = clip.trim().replaceFirst("^#", "");
                int argb = hex.length() == 8
                        ? (int) Long.parseLong(hex, 16)
                        : (int) (0xFF000000L | Long.parseLong(hex, 16));
                owner.hoveredColor.setValue(argb);
                if (owner.activeColor == owner.hoveredColor) {
                    owner.argbToHsv(argb);
                }
            } catch (NumberFormatException ignored) {
            }
            return true;
        }
        if (owner.activeSliderField != null || owner.activeText != null || owner.activeList != null || owner.activePosField != null) {
            insertTextAtCursor(clip);
            return true;
        }
        if (isSearchEditorActive()) {
            insertTextAtCursor(clip);
            syncSearchQueryFromEditor();
            return true;
        }
        if (owner.profileNameFocus) {
            owner.profileNameInput += clip;
            return true;
        }
        if (owner.profileRenameFocus) {
            owner.profileRenameInput += clip;
            return true;
        }
        return false;
    }

    boolean handleSearchEditorKeyPressed(int key, boolean ctrl, boolean shift) {
        if (!isSearchEditorActive()) {
            return false;
        }
        boolean hasSelection = hasTextSelection();
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!deleteSelectedText() && owner.textCursor > 0) {
                    owner.textBuf.deleteCharAt(--owner.textCursor);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (!deleteSelectedText() && owner.textCursor < owner.textBuf.length()) {
                    owner.textBuf.deleteCharAt(owner.textCursor);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                int nextCursor = ctrl
                        ? moveCursorWord(owner.textBuf.toString(), owner.textCursor, -1)
                        : Math.max(0, owner.textCursor - 1);
                if (shift) {
                    setTextCursor(nextCursor, true);
                } else if (hasSelection) {
                    setTextCursor(ctrl ? nextCursor : textSelectionStart(), false);
                } else {
                    setTextCursor(nextCursor, false);
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                int nextCursor = ctrl
                        ? moveCursorWord(owner.textBuf.toString(), owner.textCursor, 1)
                        : Math.min(owner.textBuf.length(), owner.textCursor + 1);
                if (shift) {
                    setTextCursor(nextCursor, true);
                } else if (hasSelection) {
                    setTextCursor(ctrl ? nextCursor : textSelectionEnd(), false);
                } else {
                    setTextCursor(nextCursor, false);
                }
            }
            case GLFW.GLFW_KEY_HOME -> setTextCursor(0, shift);
            case GLFW.GLFW_KEY_END -> setTextCursor(owner.textBuf.length(), shift);
            default -> {
                return false;
            }
        }
        syncSearchQueryFromEditor();
        return true;
    }

    boolean handleProfileNameKeyPressed(int key) {
        if (owner.profileRenameFocus) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!owner.profileRenameInput.isEmpty()) {
                        owner.profileRenameInput = owner.profileRenameInput.substring(0, owner.profileRenameInput.length() - 1);
                    }
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> owner.commitProfileRename();
                case GLFW.GLFW_KEY_DELETE -> owner.profileRenameInput = "";
                default -> {
                    return false;
                }
            }
            return true;
        }

        if (!owner.profileNameFocus) {
            return false;
        }
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!owner.profileNameInput.isEmpty()) {
                    owner.profileNameInput = owner.profileNameInput.substring(0, owner.profileNameInput.length() - 1);
                }
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> owner.profileNameFocus = false;
            case GLFW.GLFW_KEY_DELETE -> owner.profileNameInput = "";
            default -> {
                return false;
            }
        }
        return true;
    }

    boolean handleInlineEditorKeyPressed(int key, boolean ctrl, boolean shift) {
        if (!hasActiveInlineEditor()) {
            return false;
        }
        boolean multilineText = owner.activeText != null && owner.activeText.isMultiline();
        boolean hasSelection = hasTextSelection();
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!deleteSelectedText() && owner.textCursor > 0) {
                    owner.textBuf.deleteCharAt(--owner.textCursor);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (!deleteSelectedText() && owner.textCursor < owner.textBuf.length()) {
                    owner.textBuf.deleteCharAt(owner.textCursor);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                int nextCursor = ctrl
                        ? moveCursorWord(owner.textBuf.toString(), owner.textCursor, -1)
                        : Math.max(0, owner.textCursor - 1);
                if (shift) {
                    setTextCursor(nextCursor, true);
                } else if (hasSelection) {
                    setTextCursor(ctrl ? nextCursor : textSelectionStart(), false);
                } else {
                    setTextCursor(nextCursor, false);
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                int nextCursor = ctrl
                        ? moveCursorWord(owner.textBuf.toString(), owner.textCursor, 1)
                        : Math.min(owner.textBuf.length(), owner.textCursor + 1);
                if (shift) {
                    setTextCursor(nextCursor, true);
                } else if (hasSelection) {
                    setTextCursor(ctrl ? nextCursor : textSelectionEnd(), false);
                } else {
                    setTextCursor(nextCursor, false);
                }
            }
            case GLFW.GLFW_KEY_UP -> {
                if (!multilineText) {
                    return false;
                }
                if (shift) {
                    setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, -1), true);
                } else if (hasSelection) {
                    setTextCursor(textSelectionStart(), false);
                } else {
                    setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, -1), false);
                }
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (!multilineText) {
                    return false;
                }
                if (shift) {
                    setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, 1), true);
                } else if (hasSelection) {
                    setTextCursor(textSelectionEnd(), false);
                } else {
                    setTextCursor(moveCursorVertical(owner.textBuf.toString(), owner.textCursor, 1), false);
                }
            }
            case GLFW.GLFW_KEY_HOME -> {
                int nextCursor = multilineText ? getLineStart(owner.textBuf.toString(), owner.textCursor) : 0;
                setTextCursor(nextCursor, shift);
            }
            case GLFW.GLFW_KEY_END -> {
                int nextCursor = multilineText ? getLineEnd(owner.textBuf.toString(), owner.textCursor) : owner.textBuf.length();
                setTextCursor(nextCursor, shift);
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (multilineText) {
                    insertTextAtCursor("\n");
                } else {
                    commitText();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    boolean handleCharTyped(char ch) {
        if (isSearchEditorActive()) {
            insertTextAtCursor(String.valueOf(ch));
            syncSearchQueryFromEditor();
            return true;
        }
        if (owner.profileNameFocus) {
            owner.profileNameInput += ch;
            return true;
        }
        if (owner.profileRenameFocus) {
            owner.profileRenameInput += ch;
            return true;
        }
        if (hasActiveInlineEditor()) {
            insertTextAtCursor(String.valueOf(ch));
            return true;
        }
        return false;
    }

    void commitText() {
        owner.profileNameFocus = false;
        if (owner.profileRenameFocus) {
            owner.commitProfileRename();
        }
        clearTextSelection();
        if (owner.activeSliderField != null) {
            try {
                float parsed = Float.parseFloat(owner.textBuf.toString().trim());
                owner.activeSliderField.setValue(parsed);
                owner.textBuf = new StringBuilder(owner.fmtSliderInput(owner.activeSliderField));
                owner.textCursor = owner.textBuf.length();
            } catch (NumberFormatException ignored) {
            }
            owner.activeSliderField = null;
        }
        if (owner.activeText != null) {
            owner.activeText.setValue(owner.textBuf.toString());
            owner.activeText = null;
        }
        if (owner.activeList != null) {
            List<String> values = owner.activeList.getValues();
            String value = owner.textBuf.toString().trim();
            if (owner.activeListIndex >= 0 && owner.activeListIndex < values.size()) {
                if (value.isEmpty()) {
                    values.remove(owner.activeListIndex);
                } else {
                    values.set(owner.activeListIndex, value);
                }
                owner.activeList.setValues(values);
            }
            owner.activeList = null;
            owner.activeListIndex = -1;
        }
        if (owner.activePosField != null) {
            try {
                double value = Double.parseDouble(owner.textBuf.toString());
                switch (owner.activePosIdx) {
                    case 0 -> owner.activePosField.setX(value);
                    case 1 -> owner.activePosField.setY(value);
                    case 2 -> owner.activePosField.setZ(value);
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
            }
            owner.activePosField = null;
        }
        normalizeTextCursorState();
    }

    static boolean isWordChar(char c) {
        return !Character.isWhitespace(c);
    }

    static int moveCursorWord(String text, int cursor, int direction) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int pos = Math.max(0, Math.min(cursor, text.length()));
        if (direction < 0) {
            if (pos == 0) {
                return 0;
            }
            pos--;
            while (pos > 0 && !isWordChar(text.charAt(pos))) {
                pos--;
            }
            while (pos > 0 && isWordChar(text.charAt(pos - 1))) {
                pos--;
            }
            return pos;
        }
        while (pos < text.length() && isWordChar(text.charAt(pos))) {
            pos++;
        }
        while (pos < text.length() && !isWordChar(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    static int cursorFromInlineText(String text, float fontSize, float startX, float mx) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        float relX = mx - startX;
        if (relX <= 0f) {
            return 0;
        }

        int len = text.length();
        float avgCharWidth = fontSize * 0.52f;
        float totalWidth = len * avgCharWidth;
        if (relX >= totalWidth) {
            return len;
        }

        int lo = 0;
        int hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            float width = mid * avgCharWidth;
            if (width < relX) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        int idx = Math.max(0, Math.min(len, lo));
        if (idx > 0 && idx < len) {
            float left = (idx - 1) * avgCharWidth;
            float right = idx * avgCharWidth;
            if (relX - left < right - relX) {
                idx--;
            }
        }
        return Math.max(0, Math.min(len, idx));
    }

    static String[] splitLines(String text) {
        return text.split("\n", -1);
    }

    static int getLineStart(String text, int cursor) {
        int pos = Math.max(0, Math.min(cursor, text.length()));
        while (pos > 0 && text.charAt(pos - 1) != '\n') {
            pos--;
        }
        return pos;
    }

    static int getLineEnd(String text, int cursor) {
        int pos = Math.max(0, Math.min(cursor, text.length()));
        while (pos < text.length() && text.charAt(pos) != '\n') {
            pos++;
        }
        return pos;
    }

    static int getCursorLine(String text, int cursor) {
        int pos = Math.max(0, Math.min(cursor, text.length()));
        int line = 0;
        for (int i = 0; i < pos; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static int getCursorColumn(String text, int cursor) {
        return Math.max(0, Math.min(cursor, text.length()) - getLineStart(text, cursor));
    }

    static int moveCursorVertical(String text, int cursor, int delta) {
        int currentStart = getLineStart(text, cursor);
        int currentCol = cursor - currentStart;

        if (delta < 0) {
            if (currentStart == 0) {
                return cursor;
            }
            int previousEnd = currentStart - 1;
            int previousStart = getLineStart(text, previousEnd);
            return Math.min(previousStart + currentCol, previousEnd);
        }

        int currentEnd = getLineEnd(text, cursor);
        if (currentEnd >= text.length()) {
            return cursor;
        }
        int nextStart = currentEnd + 1;
        int nextEnd = getLineEnd(text, nextStart);
        return Math.min(nextStart + currentCol, nextEnd);
    }
}
