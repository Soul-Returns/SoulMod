package com.soulreturns.util

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.Window
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * A key or mouse button plus the modifier keys held with it, as every Soul keybind stores it:
 * `InputConstants` names joined by `+`, modifiers first (`key.keyboard.left.control+key.keyboard.k`).
 * A plain name with no `+` is a single key, which is what every binding saved before combos was.
 *
 * Left and right modifiers are distinct keys (Right Alt is not Left Alt), because the user binds
 * the physical key they pressed.
 *
 * **Matching:** a combo is down when its key and every modifier are down, and — when it has
 * modifiers — no *other* modifier is, so `Ctrl+K` does not fire on `Ctrl+Shift+K`. A plain key
 * ignores modifiers, like vanilla's own bindings, so holding Shift to sneak never blocks one; a
 * caller holding several bindings on one key prefers the most specific (command keybinds do).
 */
data class KeyCombo(val key: InputConstants.Key, val modifiers: Set<InputConstants.Key>) {
    fun serialize(): String = (sortedModifiers() + key).joinToString(SEPARATOR) { it.name }

    fun label(): String = (sortedModifiers() + key).joinToString(" + ") { it.displayName.string }

    /** One short label per key for key chips, modifiers first: `RCtrl`, `K`. */
    fun shortParts(): List<String> = (sortedModifiers() + key).map(::shortName)

    fun isDown(window: Window): Boolean {
        if (!isDown(window, key)) return false
        for (modifier in modifiers) if (!isDown(window, modifier)) return false
        if (modifiers.isEmpty()) return true
        for (code in MODIFIER_CODES) {
            if (code == key.value && key.type == InputConstants.Type.KEYSYM) continue
            if (modifiers.none { it.value == code } && InputConstants.isKeyDown(window, code)) return false
        }
        return true
    }

    /**
     * Whether pressing this would also trigger [other]: same key, and either side is a plain key
     * (plain keys ignore modifiers) or both need the same modifiers.
     */
    fun overlaps(other: KeyCombo): Boolean =
        key == other.key && (modifiers.isEmpty() || other.modifiers.isEmpty() || modifiers == other.modifiers)

    private fun sortedModifiers(): List<InputConstants.Key> = modifiers.sortedBy { it.value }

    companion object {
        /** `LCtrl`, `RShift`, … for modifiers; the key's display name otherwise. */
        fun shortName(key: InputConstants.Key): String {
            if (!isModifier(key)) return key.displayName.string
            val side = if (key.value >= GLFW.GLFW_KEY_RIGHT_SHIFT) "R" else "L"
            return side + when (key.value) {
                GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "Shift"
                GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "Ctrl"
                GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "Alt"
                else -> "Super"
            }
        }

        const val SEPARATOR = "+"
        private val MODIFIER_CODES = GLFW.GLFW_KEY_LEFT_SHIFT..GLFW.GLFW_KEY_RIGHT_SUPER

        fun isModifier(key: InputConstants.Key): Boolean =
            key.type == InputConstants.Type.KEYSYM && key.value in MODIFIER_CODES

        private val parsedCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<KeyCombo>>()

        /** [parse], memoized per stored string, for pollers that read their binding every tick. */
        fun cached(stored: String): KeyCombo? {
            if (parsedCache.size > 256) parsedCache.clear()
            return parsedCache.getOrPut(stored) { java.util.Optional.ofNullable(parse(stored)) }.orElse(null)
        }

        /** The stored combo, with every key resolved to something pollable; null when blank or invalid. */
        fun parse(stored: String): KeyCombo? {
            if (stored.isBlank() || stored == InputConstants.UNKNOWN.name) return null
            val keys = stored.split(SEPARATOR).map { name -> pollable(name) ?: return null }
            val modifiers = keys.dropLast(1).toSet()
            if (modifiers.any { !isModifier(it) }) return null
            return KeyCombo(keys.last(), modifiers - keys.last())
        }

        /** "Unbound", "Invalid key", or the combo's display label. */
        fun label(stored: String): String {
            if (stored.isBlank() || stored == InputConstants.UNKNOWN.name) return "Unbound"
            return parse(stored)?.label() ?: "Invalid key"
        }

        private fun isDown(window: Window, key: InputConstants.Key): Boolean =
            if (key.type == InputConstants.Type.MOUSE) {
                GLFW.glfwGetMouseButton(window.handle(), key.value) == GLFW.GLFW_PRESS
            } else {
                InputConstants.isKeyDown(window, key.value)
            }

        private fun pollable(name: String): InputConstants.Key? {
            val key = try {
                InputConstants.getKey(name)
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: IndexOutOfBoundsException) {
                return null
            }
            if (key.type == InputConstants.Type.KEYSYM) return if (keyboardCode(key.value)) key else null
            if (key.type == InputConstants.Type.MOUSE) {
                return if (key.value in GLFW.GLFW_MOUSE_BUTTON_1..GLFW.GLFW_MOUSE_BUTTON_LAST) key else null
            }
            if (key.type == InputConstants.Type.SCANCODE && key.value >= 0) {
                // Polling is keysym-based; resolve layout-specific scancodes once, when parsed.
                for (code in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_LAST) {
                    if (keyboardCode(code) && GLFW.glfwGetKeyScancode(code) == key.value) {
                        return InputConstants.Type.KEYSYM.getOrCreate(code)
                    }
                }
            }
            return null
        }

        private fun keyboardCode(code: Int): Boolean =
            code == GLFW.GLFW_KEY_SPACE || code == GLFW.GLFW_KEY_APOSTROPHE ||
                code in GLFW.GLFW_KEY_COMMA..GLFW.GLFW_KEY_9 || code == GLFW.GLFW_KEY_SEMICOLON ||
                code == GLFW.GLFW_KEY_EQUAL || code in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_RIGHT_BRACKET ||
                code == GLFW.GLFW_KEY_GRAVE_ACCENT || code in GLFW.GLFW_KEY_WORLD_1..GLFW.GLFW_KEY_WORLD_2 ||
                code in GLFW.GLFW_KEY_ESCAPE..GLFW.GLFW_KEY_END || code in GLFW.GLFW_KEY_CAPS_LOCK..GLFW.GLFW_KEY_PAUSE ||
                code in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25 || code in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_EQUAL ||
                code in GLFW.GLFW_KEY_LEFT_SHIFT..GLFW.GLFW_KEY_MENU
    }
}

/**
 * Records a combo the way people press one: everything held is collected, and the binding is
 * committed on the **first release**. So Ctrl, then K, then letting go binds `Ctrl + K`; Right Alt
 * pressed and released alone binds Right Alt. A mouse button commits immediately, with whatever
 * modifiers are held. Escape clears the binding.
 */
class KeyComboCapture {
    private val pressed = LinkedHashSet<InputConstants.Key>()

    /** What is held so far, for the "press a key" button: `Left Control + ...`. */
    fun preview(): String? =
        if (pressed.isEmpty()) null else pressed.joinToString(" + ") { it.displayName.string } + " + ..."

    /** What is held so far as [KeyCombo.shortName] labels, for an inline key chip. */
    fun previewParts(): List<String> = pressed.map(KeyCombo::shortName)

    /** A key went down. Returns `""` to clear the binding, or null to keep capturing. */
    fun keyPressed(event: KeyEvent): String? {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) return ""
        pressed.add(InputConstants.getKey(event))
        return null
    }

    /** A key went up. Returns the serialized combo once a held key is released, else null. */
    fun keyReleased(event: KeyEvent): String? {
        val released = InputConstants.getKey(event)
        if (released !in pressed) return null
        val main = pressed.lastOrNull { !KeyCombo.isModifier(it) } ?: released
        val modifiers = pressed.filter { KeyCombo.isModifier(it) && it != main }.toSet()
        pressed.clear()
        return KeyCombo(main, modifiers).serialize()
    }

    /** A mouse button: binds it at once with the modifiers held right now. */
    fun mouseClicked(button: Int): String {
        val modifiers = pressed.filter { KeyCombo.isModifier(it) }.toSet()
        pressed.clear()
        return KeyCombo(InputConstants.Type.MOUSE.getOrCreate(button), modifiers).serialize()
    }
}
