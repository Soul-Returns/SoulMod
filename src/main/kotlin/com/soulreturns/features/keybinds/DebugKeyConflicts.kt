package com.soulreturns.features.keybinds

import com.soulreturns.config.cfg
import com.soulreturns.util.KeyCombo
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Vanilla's debug keys (`Options.debugKeys`: F3+B, F3+G, F3+F6, …) only act while the Debug
 * Modifier is held, so sharing their key with a plain binding is no conflict. With
 * `commandKeybinds.ignoreDebugKeyConflicts` this hides such pairs from conflict *reporting*
 * (vanilla Controls via `KeyMappingMixin`, other mods that ask `KeyMapping.same`, the command
 * keybind editor); input handling is untouched.
 *
 * The Debug Modifier and the overlay key are not in `debugKeys`, so they still conflict normally,
 * and so do two debug keys sharing a key.
 */
object DebugKeyConflicts {
    private fun active(): Boolean = cfg.commandKeybinds.ignoreDebugKeyConflicts

    private fun isDebugKey(mapping: KeyMapping): Boolean =
        Minecraft.getInstance().options?.debugKeys?.any { it === mapping } == true

    /** Whether vanilla's `a.same(b)` pair is one the option hides. */
    @JvmStatic
    fun ignored(a: KeyMapping, b: KeyMapping): Boolean {
        if (!active()) return false
        val options = Minecraft.getInstance().options ?: return false
        val debugA = isDebugKey(a)
        val debugB = isDebugKey(b)
        if (debugA == debugB) return false
        // A debug key on the modifier's own key really clashes with it (F3 + F3 cannot be pressed).
        return (if (debugA) b else a) !== options.keyDebugModifier
    }

    /**
     * Whether a Soul combo [candidate] collides with the debug [mapping], whose key is [current].
     * Only when the Debug Modifier is a Shift/Ctrl/Alt/Super key can a Soul combo hold it: then
     * exactly `modifier + key` collides. Called only for debug mappings while [active].
     */
    private fun collides(current: KeyCombo, candidate: KeyCombo): Boolean {
        val modifier = KeyCombo.cached(Minecraft.getInstance().options.keyDebugModifier.saveString()) ?: return false
        if (modifier.modifiers.isNotEmpty() || !KeyCombo.isModifier(modifier.key)) return false
        return current.key == candidate.key && candidate.modifiers == setOf(modifier.key)
    }

    /** For [CommandKeybinds.conflicts]: whether [mapping] (key [current]) clashes with [candidate]. */
    fun overlaps(mapping: KeyMapping, current: KeyCombo, candidate: KeyCombo): Boolean =
        if (active() && isDebugKey(mapping)) collides(current, candidate) else current.overlaps(candidate)
}
