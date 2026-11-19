package com.soulreturns.features.search.forge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * When each running forge process finishes, and how much to trust that moment. Pure.
 *
 * Quick Forge shortens a process by a percentage; the moment it ends is stored once as a wall-clock time
 * ([ForgeSlotRead.endsAt]) so the tab needs no percent math to count down. Sources, best first:
 * - [MENU]: The Forge's own `Time Remaining` lore, read while the menu is open. Authoritative.
 * - [START]: the player's click on Confirm: the recipe's base duration at the Quick Forge level known then.
 * - [API]: `forge.forge_processes` of the SkyBlock API: a start time only, so the CURRENT percent is applied
 *   (the level at the start is unknown): always approximate.
 * - legacy: version 1 rows (empty source): base seconds scaled by the current percent: approximate.
 * A worse source never overwrites a better one for the same process.
 */
object ForgeProcesses {
    const val MENU = "menu"
    const val START = "start"
    const val API = "api"

    /** Start times of one process differ by less than this between the game and the API (ms). */
    const val SAME_START_MS = 120_000L

    /** Menu countdowns have whole-second granularity; a re-read within this of the stored end changes nothing. */
    const val SAME_END_MS = 3_000L

    fun rank(source: String): Int = if (source == MENU) 3 else if (source == START) 2 else if (source == API) 1 else 0

    /** `Time Remaining` read from the menu now: the end is exactly that far away. */
    fun fromMenu(slot: Int, id: String, name: String?, remainingSeconds: Long, now: Long) =
        ForgeSlotRead(slot, "busy", id, name, readAt = now, endsAt = now + remainingSeconds * 1000, source = MENU)

    /** A confirmed start: the recipe's [baseSeconds] at Quick Forge [qf] (a fraction), exact when [qfKnown]. */
    fun fromStart(slot: Int, id: String, name: String?, baseSeconds: Long, qf: Double, qfKnown: Boolean, now: Long) =
        ForgeSlotRead(slot, "busy", id, name, readAt = now, endsAt = now + ForgeEngine.shownSeconds(baseSeconds, qf) * 1000,
            startedAt = now, source = START, approx = !qfKnown)

    /** One process of the API's `forge.forge_processes` (slot 1-7, output id, start in ms). */
    class ApiProcess(val slot: Int, val id: String, val startMs: Long)

    /** The finish moment of [s]; rows that stored none (version 1) scale their base seconds by [qfNow]. 0 = not busy. */
    fun endsAt(s: ForgeSlotRead, qfNow: Double): Long =
        if (s.state != "busy") 0 else if (s.endsAt > 0) s.endsAt else s.readAt + ForgeEngine.shownSeconds(s.remainingBase, qfNow) * 1000

    /** Estimated rather than read or witnessed with a known level. */
    fun approximate(s: ForgeSlotRead): Boolean = s.state == "busy" && (s.approx || rank(s.source) < rank(START))

    /** The earliest moment a busy row can have begun: its start, or when it was first seen. */
    private fun startOf(s: ForgeSlotRead) = if (s.startedAt > 0) s.startedAt else s.readAt

    /** Same item, and not a later process: starts agree, or the one with a start began before the other was last seen. */
    fun sameProcess(old: ForgeSlotRead, new: ForgeSlotRead): Boolean {
        if (old.outputId != new.outputId) return false
        if (old.startedAt > 0 && new.startedAt > 0) return abs(old.startedAt - new.startedAt) <= SAME_START_MS
        if (old.startedAt == 0L && new.startedAt == 0L) return true
        val (seen, started) = if (old.startedAt == 0L) old to new else new to old
        return started.startedAt <= seen.readAt + SAME_START_MS
    }

    /**
     * Merges one observation of a slot into what is stored. A menu read always wins. A start or an API result
     * replaces the stored slot only when it describes a different, later process, or the same one from an equal
     * or better source; an API process that began before the slot was last observed is old news and loses.
     */
    fun merge(old: ForgeSlotRead?, incoming: ForgeSlotRead): ForgeSlotRead {
        if (old == null) return incoming
        if (incoming.source == MENU) {
            // A menu that cannot tell (a finished slot's encoding was never captured) keeps a stored process of that item.
            if (incoming.state == "unknown" && old.state == "busy" && incoming.outputId != null && incoming.outputId == old.outputId) return old
            if (incoming.state == "busy" && old.state == "busy" && sameProcess(old, incoming) && old.source == MENU &&
                abs(incoming.endsAt - old.endsAt) <= SAME_END_MS
            ) return old
            return if (incoming.state == "busy" && old.state == "busy" && sameProcess(old, incoming) && old.startedAt > 0) incoming.copy(startedAt = old.startedAt) else incoming
        }
        if (incoming.state != "busy") return if (rank(incoming.source) >= rank(old.source)) incoming else old
        if (old.state != "busy") return if (old.source == MENU && old.state == "idle" && incoming.startedAt <= old.readAt) old else incoming
        if (!sameProcess(old, incoming)) return if (incoming.startedAt > startOf(old)) incoming else old
        if (rank(incoming.source) < rank(old.source)) return old
        return if (incoming.source == old.source && abs(incoming.endsAt - old.endsAt) <= SAME_END_MS && incoming.approx == old.approx) old
        else incoming.copy(startedAt = if (incoming.startedAt > 0) incoming.startedAt else old.startedAt)
    }

    /**
     * Applies the SkyBlock API to the 7 slots. [qf] is the fraction applied to every listed process (the current
     * one: the level at the start is unknown). A slot the API does not list becomes idle only where its stored row
     * came from the API too: anything the client saw stays until a menu says so (the API lags the game).
     * @param baseSeconds the recipe's base duration for an output id, null = unknown (the process cannot be timed).
     */
    fun mergeApi(
        stored: List<ForgeSlotRead>,
        api: List<ApiProcess>,
        loadedAt: Long,
        qf: Double,
        baseSeconds: (String) -> Long?,
    ): List<ForgeSlotRead> {
        val bySlot = stored.associateBy { it.slot }
        val listed = api.associateBy { it.slot }
        return (1..ForgeEngine.SLOTS).map { n ->
            val old = bySlot[n]
            val p = listed[n]
            val base = p?.let { baseSeconds(it.id) }
            if (p != null && base != null) {
                merge(old, ForgeSlotRead(n, "busy", p.id, null, readAt = loadedAt, endsAt = p.startMs + ForgeEngine.shownSeconds(base, qf) * 1000,
                    startedAt = p.startMs, source = API, approx = true))
            } else if (p != null) old ?: ForgeSlotRead(n, "unknown", p.id, readAt = loadedAt, source = API)
            else if (old == null || old.source == API && old.state == "busy") ForgeSlotRead(n, "idle", readAt = loadedAt, source = API)
            else old
        }
    }

    // ------------------------------------------------------------------ the API payload

    /** `forge.forge_processes.<forge_N>.<slot>` = `{type, id, startTime, slot, notified}`; tolerant of absence. */
    fun apiProcesses(member: JsonObject): List<ApiProcess> {
        val groups = (member["forge"] as? JsonObject)?.get("forge_processes") as? JsonObject ?: return emptyList()
        val out = ArrayList<ApiProcess>()
        for ((_, group) in groups) {
            val entries = group as? JsonObject ?: continue
            for ((key, value) in entries) {
                val o = value as? JsonObject ?: continue
                val slot = (o["slot"] as? JsonPrimitive)?.longOrNull?.toInt() ?: key.toIntOrNull() ?: continue
                val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val start = (o["startTime"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: continue
                out += ApiProcess(slot, id, start)
            }
        }
        return out
    }

    /**
     * Quick Forge level from the API (`skill_tree.nodes.mining.quick_forge`, else the older
     * `mining_core.nodes.forge_time`); null when neither is there. UNVERIFIED whether it follows the selected HotM
     * loadout, so it is only used while no menu has told the mod the percent.
     */
    fun apiQuickForgeLevel(member: JsonObject): Int? {
        fun level(vararg path: String): Int? {
            var cur: JsonElement = member
            for (key in path) cur = (cur as? JsonObject)?.get(key) ?: return null
            return (cur as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.toInt()
        }
        return level("skill_tree", "nodes", "mining", "quick_forge") ?: level("mining_core", "nodes", "forge_time")
    }

    /** 10 % + 0.5 % per level for levels 1–19, 30 % at 20 (research §4a), 0 below. */
    fun percentOfLevel(level: Int): Double = if (level <= 0) 0.0 else if (level >= 20) 30.0 else 10.0 + level * 0.5
}
