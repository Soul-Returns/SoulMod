package com.soulreturns.ui.screen

import net.minecraft.client.KeyboardHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

/**
 * One single-line text field's state and editing: the buffer, the caret, the selection anchor and
 * the horizontal scroll. [SoulScreenBase.drawTextField] draws it and places the caret on click; the
 * screen routes keys and typed characters to whichever field is [focused].
 *
 * Line breaks are refused rather than flattened, because a field holds a command or an id and a
 * pasted multi-line block is almost always the wrong clipboard: [onRejected] lets the screen say so.
 */
class SoulTextField(text: String = "") {
    var text: String = text
        private set
    var cursor = text.length
    var anchor = cursor
    var visibleStart = 0
    var focused = false

    /** A password field: drawn as bullets, and copy/cut never put the text on the clipboard. */
    var masked = false

    /** Called after every edit that changed [text]. */
    var onChange: (String) -> Unit = {}

    /** Called when an insert was refused because it contained a line break. */
    var onRejected: () -> Unit = {}

    /** Replace the whole buffer and put the caret at its end. Does not call [onChange]. */
    fun set(value: String) {
        text = value
        cursor = value.length
        anchor = cursor
        visibleStart = 0
    }

    /**
     * Apply an editing key. Returns true when the key was one this field understands; the caller
     * decides whether unhandled keys still count as consumed while the field has focus.
     */
    fun keyPressed(event: KeyEvent, keyboard: KeyboardHandler?): Boolean {
        val start = min(cursor, anchor)
        val end = max(cursor, anchor)
        if (event.isSelectAll) {
            anchor = 0
            cursor = text.length
        } else if (event.isCopy || event.isCut) {
            if (start != end && !masked) keyboard?.setClipboard(text.substring(start, end))
            if (event.isCut) replaceSelection("")
        } else if (event.isPaste) {
            replaceSelection(keyboard?.clipboard.orEmpty())
        } else if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
            if (start == end) {
                if (event.key() == GLFW.GLFW_KEY_BACKSPACE && cursor > 0) anchor = movePosition(-1, event.hasControlDown())
                if (event.key() == GLFW.GLFW_KEY_DELETE && cursor < text.length) anchor = movePosition(1, event.hasControlDown())
            }
            replaceSelection("")
        } else if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
            val direction = if (event.key() == GLFW.GLFW_KEY_LEFT) -1 else 1
            cursor = if (!event.hasShiftDown() && start != end) {
                if (direction < 0) start else end
            } else {
                movePosition(direction, event.hasControlDown())
            }
            if (!event.hasShiftDown()) anchor = cursor
        } else if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
            cursor = if (event.key() == GLFW.GLFW_KEY_HOME) 0 else text.length
            if (!event.hasShiftDown()) anchor = cursor
        } else {
            return false
        }
        return true
    }

    /** Insert a typed character. Returns true when it was taken. */
    fun charTyped(event: CharacterEvent): Boolean {
        if (!event.isAllowedChatCharacter()) return false
        replaceSelection(event.codepointAsString())
        return true
    }

    private fun movePosition(direction: Int, word: Boolean): Int {
        var position = cursor
        if (!word) {
            val canMove = direction < 0 && position > 0 || direction > 0 && position < text.length
            return if (canMove) text.offsetByCodePoints(position, direction) else position
        }
        if (direction < 0) {
            while (position > 0 && text[position - 1].isWhitespace()) position--
            while (position > 0 && !text[position - 1].isWhitespace()) position--
        } else {
            while (position < text.length && !text[position].isWhitespace()) position++
            while (position < text.length && text[position].isWhitespace()) position++
        }
        return position
    }

    private fun replaceSelection(insert: String) {
        if (insert.any { it == '\r' || it == '\n' }) {
            onRejected()
            return
        }
        val start = min(cursor, anchor)
        val end = max(cursor, anchor)
        val before = text
        text = text.replaceRange(start, end, insert)
        cursor = start + insert.length
        anchor = cursor
        visibleStart = visibleStart.coerceAtMost(start)
        if (text != before) onChange(text)
    }
}
