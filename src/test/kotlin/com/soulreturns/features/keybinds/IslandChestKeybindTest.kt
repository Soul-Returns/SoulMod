package com.soulreturns.features.keybinds

import com.soulreturns.config.CommandBinding
import com.soulreturns.config.CommandKeybindsConfig
import com.soulreturns.config.IslandChestsConfig
import com.soulreturns.config.PresetBinding
import com.soulreturns.config.SoulConfig
import com.soulreturns.util.KeyCombo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandChestKeybindTest {
    @Test
    fun featureRebindingChangesOnlyTheExistingSearchSetting() {
        val original = SoulConfig(
            islandChests = IslandChestsConfig(enabled = true, shareWithCoop = true, searchKey = "key.keyboard.i"),
            commandBindings = listOf(CommandBinding("soul chests", "key.keyboard.j")),
            commandKeybinds = CommandKeybindsConfig(enabled = false, presets = mapOf("storage" to PresetBinding("key.keyboard.b"))),
        )
        val key = "key.keyboard.right.control+key.keyboard.k"
        val changed = CommandKeybinds.rebind(original, CommandKeybinds.Ref.IslandChests, key)
        assertEquals(original.copy(islandChests = original.islandChests.copy(searchKey = key)), changed)
        assertTrue(CommandKeybinds.active(changed.commandBindings, changed.commandKeybinds).isEmpty())
    }

    @Test
    fun commandRebindingKeepsExistingEnabledStateAndUnbindRemovesOnlyItsPreset() {
        val original = SoulConfig(
            islandChests = IslandChestsConfig(searchKey = "key.mouse.4"),
            commandBindings = listOf(CommandBinding("pets", "key.keyboard.p", enabled = false)),
            commandKeybinds = CommandKeybindsConfig(presets = mapOf("storage" to PresetBinding("key.keyboard.b", enabled = false))),
        )
        val custom = CommandKeybinds.rebind(original, CommandKeybinds.Ref.Custom(0), "key.keyboard.k")
        assertEquals(CommandBinding("pets", "key.keyboard.k", enabled = false), custom.commandBindings.single())
        val preset = CommandKeybinds.rebind(original, CommandKeybinds.Ref.Preset("storage"), "key.keyboard.k")
        assertEquals(PresetBinding("key.keyboard.k", enabled = false), preset.commandKeybinds.presets["storage"])
        val unbound = CommandKeybinds.rebind(preset, CommandKeybinds.Ref.Preset("storage"), "")
        assertTrue(unbound.commandKeybinds.presets.isEmpty())
        assertEquals(original.islandChests, unbound.islandChests)
    }

    @Test
    fun sharedSearchBindingReportsConflictsInBothDirectionsWithoutConflictingWithItself() {
        val key = "key.keyboard.right.control+key.mouse.4"
        val config = SoulConfig(
            islandChests = IslandChestsConfig(enabled = true, searchKey = key),
            commandBindings = listOf(CommandBinding("test", "key.mouse.4")),
        )
        assertEquals(listOf("Command #1: /test"), CommandKeybinds.soulConflicts(key, CommandKeybinds.Ref.IslandChests, config, false))
        assertEquals(listOf("Soul: Island chest search"), CommandKeybinds.soulConflicts("key.mouse.4", CommandKeybinds.Ref.Custom(0), config, false))
        val disabled = config.copy(islandChests = config.islandChests.copy(enabled = false))
        assertTrue(CommandKeybinds.soulConflicts(key, CommandKeybinds.Ref.Custom(0), disabled, false).isEmpty())
        val unbound = CommandKeybinds.rebind(config, CommandKeybinds.Ref.IslandChests, "")
        assertTrue(CommandKeybinds.soulConflicts(key, CommandKeybinds.Ref.Custom(0), unbound, false).isEmpty())
    }

    @Test
    fun distinctModifierCombosAndMouseButtonsDoNotConflict() {
        val key = "key.keyboard.right.control+key.mouse.4"
        assertEquals(key, KeyCombo.parse(key)?.serialize())
        val config = SoulConfig(islandChests = IslandChestsConfig(enabled = true, searchKey = key))
        assertTrue(CommandKeybinds.soulConflicts("key.keyboard.right.control+key.mouse.4", null, config, false).isNotEmpty())
        assertFalse(CommandKeybinds.soulConflicts("key.keyboard.left.control+key.mouse.4", null, config, false).contains("Soul: Island chest search"))
        assertFalse(CommandKeybinds.soulConflicts("key.keyboard.right.control+key.mouse.5", null, config, false).contains("Soul: Island chest search"))
    }
}
