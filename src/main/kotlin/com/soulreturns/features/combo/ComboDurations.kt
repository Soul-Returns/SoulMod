package com.soulreturns.features.combo

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.pets.PetState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.toLegacyText
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/** Stats payload (namespace `combo`, one bucket per SkyBlock profile): what the pet's lore said. */
@Serializable
data class ComboPetData(
    /** Payload version; a change gets a load-time step here. */
    val version: Int = 1,
    /** Milestone → seconds the combo lasts after a kill at that milestone, from the Grandma Wolf lore. */
    val durations: Map<Int, Float> = emptyMap(),
)

/**
 * How long the combo lasts after a kill. The pet's lore lists it per milestone (`5 Combo (lasts 9.5s)`)
 * and it depends on the pet's level and rarity, so the lore is the source: whenever the Pets menu
 * shows it, the numbers are stored per profile and used for the countdown; until then (or for a
 * milestone the lore does not list) [ComboData.defaultDurations] stands in. Only reads while the
 * combo timer is on.
 */
object ComboDurations {
    const val NAMESPACE = "combo"

    private val LINE = Regex("^(\\d+) Combo \\(lasts (\\d+(?:\\.\\d+)?)s\\)")

    private val handle: StatsHandle<ComboPetData> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = ComboPetData.serializer(),
            default = { ComboPetData() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    /** Menu stacks already read, by container slot (identity: a slot update replaces the instance). */
    private val parsedFrom = HashMap<Int, ItemStack>()
    private var screenSeen: AbstractContainerScreen<*>? = null

    /** `N Combo (lasts X.Xs)` lines of a pet's color-stripped [lore], by milestone N. */
    fun parse(lore: List<String>): Map<Int, Float> {
        val out = LinkedHashMap<Int, Float>()
        for (line in lore) {
            val m = LINE.find(line.trim()) ?: continue
            val milestone = m.groupValues[1].toIntOrNull() ?: continue
            val seconds = m.groupValues[2].toFloatOrNull() ?: continue
            if (seconds > 0f) out[milestone] = seconds
        }
        return out
    }

    /** Seconds the combo lasts after a kill at [combo] (its milestone, 5..30), learned else the default. */
    fun seconds(combo: Int): Float {
        val milestone = ComboData.milestoneOf(combo)
        return handle.read().durations[milestone] ?: ComboData.defaultDurations.getValue(milestone)
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { scanMenu() })
    }

    private fun scanMenu() {
        if (!cfg.combo.enabled || !cfg.combo.showExpireTimer || !SkyblockState.onSkyblock) return
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        if (screen !== screenSeen) {
            parsedFrom.clear()
            screenSeen = screen
        }
        if (screen == null || !PetState.isPetsMenu(MessageDetector.stripColorCodes(screen.title.string).trim())) return
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory) continue
            val stack = slot.item
            if (parsedFrom[slot.index] === stack) continue
            parsedFrom[slot.index] = stack
            if (stack.isEmpty) continue
            val lore = stack.get(DataComponents.LORE)?.lines()?.map { MessageDetector.stripColorCodes(it.toLegacyText()) }.orEmpty()
            val learned = parse(lore)
            if (learned.isNotEmpty() && learned != handle.read().durations.filterKeys { it in learned }) {
                handle.update { copy(durations = durations + learned) }
            }
        }
    }
}
