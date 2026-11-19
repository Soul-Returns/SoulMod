package com.soulreturns.features.keybinds

import com.soulreturns.config.CommandBinding
import com.soulreturns.config.CommandKeybindsConfig
import com.soulreturns.config.PresetBinding
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.DungeonCommandGuard
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.util.KeyCombo
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.Component

object CommandKeybinds {
    /** A custom command, command preset, or a feature's own shared binding. */
    sealed interface Ref {
        data class Custom(val index: Int) : Ref

        data class Preset(val id: String) : Ref

        data object IslandChests : Ref
    }

    /** A binding that can fire: its stored key, the command, and the screen it opens for a Soul screen preset. */
    data class Active(
        val key: String,
        val command: String,
        val screen: ((parent: Screen?) -> Screen)? = null,
    )

    private class ResolvedBinding(
        val combo: KeyCombo,
        val command: String,
        val screen: ((parent: Screen?) -> Screen)?,
        val press: CommandPressState = CommandPressState(),
    )

    private var sourceBindings: List<CommandBinding>? = null
    private var sourceSettings: CommandKeybindsConfig? = null
    private var resolved = emptyArray<ResolvedBinding>()
    private var down = BooleanArray(0)
    private var shadowed = BooleanArray(0)
    private var lastConnection: ClientPacketListener? = null

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** Leading slash and surrounding blanks removed; what `sendCommand` gets. */
    fun normalize(command: String): String = command.trim().removePrefix("/").trimStart()

    fun rebind(config: SoulConfig, ref: Ref, key: String): SoulConfig = with(config) {
        when (ref) {
            is Ref.Custom -> copy(commandBindings = commandBindings.mapIndexed { i, binding -> if (i == ref.index) binding.copy(key = key) else binding })
            is Ref.Preset -> {
                val current = commandKeybinds.presets
                val next = if (key.isBlank()) current - ref.id else current + (ref.id to (current[ref.id]?.copy(key = key) ?: PresetBinding(key)))
                copy(commandKeybinds = commandKeybinds.copy(presets = next))
            }
            Ref.IslandChests -> copy(islandChests = islandChests.copy(searchKey = key))
        }
    }

    /**
     * Every binding that can fire right now: nothing while command keybinds are paused, and never a
     * disabled, unbound or empty entry.
     */
    fun active(
        bindings: List<CommandBinding> = cfg.commandBindings,
        settings: CommandKeybindsConfig = cfg.commandKeybinds,
    ): List<Active> {
        if (!settings.enabled) return emptyList()
        val out = ArrayList<Active>()
        for (binding in bindings) {
            val command = normalize(binding.command)
            if (binding.enabled && binding.key.isNotBlank() && command.isNotEmpty()) out.add(Active(binding.key, command))
        }
        for (preset in CommandPresets.all) {
            val binding = settings.presets[preset.id] ?: continue
            if (binding.enabled && binding.key.isNotBlank()) out.add(Active(binding.key, preset.command, preset.screen))
        }
        return out
    }

    /**
     * Whether a Soul screen preset may open over [screen]: an inventory or container menu (the creative
     * inventory only on its inventory tab), and never while one of its text fields takes input.
     * Every other binding fires only with no screen open.
     */
    fun opensOver(screen: Screen?): Boolean {
        if (screen !is AbstractContainerScreen<*>) return false
        if (screen is CreativeModeInventoryScreen && !screen.isInventoryOpen) return false
        return (screen.focused as? EditBox)?.canConsumeInput() != true
    }

    private fun tick(client: Minecraft) {
        val bindings = cfg.commandBindings
        val settings = cfg.commandKeybinds
        if (bindings !== sourceBindings || settings !== sourceSettings) {
            resolved = active(bindings, settings).mapNotNull { binding ->
                KeyCombo.parse(binding.key)?.let { ResolvedBinding(it, binding.command, binding.screen) }
            }.toTypedArray()
            down = BooleanArray(resolved.size)
            shadowed = BooleanArray(resolved.size)
            sourceBindings = bindings
            sourceSettings = settings
        }

        for (i in resolved.indices) down[i] = resolved[i].combo.isDown(client.window)
        // Ctrl+K held also satisfies a plain K binding; only the most specific binding on a key fires.
        // A shadowed binding counts as held but ineligible, so letting go of Ctrl while still on K
        // does not fire K.
        for (i in resolved.indices) {
            shadowed[i] = false
            if (!down[i]) continue
            val mine = resolved[i].combo
            for (j in resolved.indices) {
                val other = resolved[j].combo
                if (j != i && down[j] && other.key == mine.key && other.modifiers.size > mine.modifiers.size) {
                    shadowed[i] = true
                    break
                }
            }
        }

        val connection = client.connection
        val connectionChanged = connection !== lastConnection
        lastConnection = connection
        val inWorld =
            !connectionChanged && connection != null && client.connection === connection &&
                client.player != null && client.level != null && client.isWindowActive
        val screen = client.gui.screen()
        val eligible = inWorld && screen == null
        val screenEligible = eligible || (inWorld && opensOver(screen))
        for (i in resolved.indices) {
            val binding = resolved[i]
            val open = binding.screen
            if (!binding.press.poll(down[i], (if (open != null) screenEligible else eligible) && !shadowed[i])) continue
            if (open != null) {
                // The open inventory or menu is the parent, so closing the Soul screen returns to it.
                client.setScreenAndShow(open(screen))
                continue
            }
            if (DungeonCommandGuard.blocks(binding.command)) continue
            // This is ChatScreen's route: Fabric handles client commands before vanilla sends the rest.
            connection!!.sendCommand(binding.command)
        }
    }

    /**
     * Everything else [key] would also trigger: vanilla/modded mappings (debug keys per
     * [DebugKeyConflicts]), other enabled command keybinds and presets, toggle sprint and (with
     * developer access) the dev keybinds. [self] is the entry being checked, left out of its own
     * list.
     */
    fun conflicts(key: String, self: Ref? = null): List<String> {
        val candidate = KeyCombo.parse(key) ?: return emptyList()
        val names = mutableListOf<String>()
        val client = Minecraft.getInstance()
        for (mapping in client.options.keyMappings) {
            val current = KeyCombo.cached(mapping.saveString())
            if (current != null && DebugKeyConflicts.overlaps(mapping, current, candidate)) names.add(Component.translatable(mapping.name).string)
        }
        names.addAll(soulConflicts(key, self, cfg, DevAccess.unlocked))
        return names
    }

    internal fun soulConflicts(key: String, self: Ref?, config: SoulConfig, developerAccess: Boolean): List<String> {
        val candidate = KeyCombo.cached(key) ?: return emptyList()
        val names = mutableListOf<String>()
        val bindings = config.commandBindings
        for (index in bindings.indices) {
            val binding = bindings[index]
            if (!binding.enabled || self == Ref.Custom(index)) continue
            if (KeyCombo.cached(binding.key)?.overlaps(candidate) == true) {
                val command = normalize(binding.command)
                names.add("Command #${index + 1}: ${if (command.isEmpty()) "(empty command)" else "/$command"}")
            }
        }
        val presets = config.commandKeybinds.presets
        for (preset in CommandPresets.all) {
            val binding = presets[preset.id] ?: continue
            if (!binding.enabled || self == Ref.Preset(preset.id)) continue
            if (KeyCombo.cached(binding.key)?.overlaps(candidate) == true) names.add("Preset: ${preset.name} (/${preset.command})")
        }

        fun hidden(binding: String, name: String) {
            if (KeyCombo.cached(binding)?.overlaps(candidate) == true) names.add(name)
        }
        hidden(config.general.toggleSprintKey, "Soul: Toggle sprint")
        if ((config.islandChests.enabled || config.storageOverlay.enabled || config.sacks.searchSacks) && self != Ref.IslandChests) hidden(config.islandChests.searchKey, "Soul: Search everywhere")
        // Inert without developer access, so they cannot collide with anything then.
        if (developerAccess) {
            val dev = config.dev.keybinds
            hidden(dev.copyOpenedGui, "Soul dev: Copy opened GUI")
            hidden(dev.copyItemUnderCursor, "Soul dev: Copy item under cursor")
            hidden(dev.copyHeldItem, "Soul dev: Copy held item")
            hidden(dev.copyScoreboard, "Soul dev: Copy scoreboard")
            hidden(dev.copyTablist, "Soul dev: Copy tab list")
            hidden(dev.copyNearbyEntities, "Soul dev: Copy nearby entities")
        }
        return names
    }
}
