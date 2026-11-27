package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.toLegacyText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/** One perk of a mayor or minister; [description] keeps Hypixel's color codes. */
class MayorPerk(val name: String, val description: String, val minister: Boolean = false)

/** A mayor candidate: [perks] are every perk the candidate can have (a mayor's active perks live in [Election.mayor]). */
class MayorCandidate(val key: String, val name: String, val perks: List<MayorPerk>)

/**
 * The SkyBlock election as Hypixel's public `resources/skyblock/election` endpoint reports it: the mayor
 * in office with the perks active this term, the minister with the one perk it brings, and the
 * candidates of the last and the running election (their perk lists tell which mayor a perk belongs to).
 */
class Election(val mayor: MayorCandidate?, val minister: MayorCandidate?, val candidates: List<MayorCandidate>) {
    /** The candidate (any election) that has a perk called [perkName], for the Perkpocalypse mayor read from the calendar. */
    fun mayorWithPerk(perkName: String): MayorCandidate? = candidates.firstOrNull { c -> c.perks.any { it.name == perkName } }
}

/**
 * The Mayor & minister line's data: the election fetched from Hypixel's public API (about 5 KB, no key,
 * not the Soul backend) at most every 20 minutes and only once a Mayor line is drawn, plus the
 * Perkpocalypse mayor Jerry brings, which only the calendar menu shows (read when it is open).
 */
object MayorData {
    private val logger = SoulLogger("Soul/Scoreboard")
    private const val URL = "https://api.hypixel.net/v2/resources/skyblock/election"
    private const val REFRESH_MS = 20 * 60_000L
    private const val RETRY_MS = 60_000L

    /** Hypixel's SkyBlock calendar: day 1 of year 0, a year is 124 real hours of 12 months x 31 days. */
    const val EPOCH_MS = 1_559_829_300_000L
    const val YEAR_MS = 124 * 60 * 60 * 1000L
    private const val MONTH_MS = YEAR_MS / 12
    private const val DAY_MS = MONTH_MS / 31
    private const val ELECTION_END_MONTH = 3
    private const val ELECTION_END_DAY = 27

    /** The election in force; tests assign it. */
    @Volatile
    var election: Election? = null

    /** The Perkpocalypse mayor from the calendar and when it ends (real time). */
    @Volatile
    var jerryMayor: MayorCandidate? = null
        private set

    @Volatile
    var jerryUntil: Long = 0
        private set

    @Volatile
    private var lastFetch = 0L

    @Volatile
    private var fetching = false

    @Volatile
    private var tickHooked = false

    /** Off in tests: no network, no tick hook. */
    @Volatile
    var autoFetch: Boolean = true

    /** Called from [MayorLine.lines] each time it is drawn: starts the fetch (and the calendar reader) when due. */
    fun touch(nowMs: Long) {
        if (!autoFetch) return
        if (!tickHooked) {
            tickHooked = true
            ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { scanCalendar(it, System.currentTimeMillis()) })
        }
        val due = if (election == null) RETRY_MS else REFRESH_MS
        if (fetching || nowMs - lastFetch < due) return
        fetching = true
        lastFetch = nowMs
        SoulScope.io.launch {
            try {
                val response = SoulHttp.get(URL)
                if (response.statusCode() == 200) parse(response.body())?.let { election = it }
                else logger.warn("Election API answered ${response.statusCode()}")
            } catch (t: Throwable) {
                logger.warn("Election API failed: ${t.message}")
            } finally {
                fetching = false
            }
        }
    }

    // ----------------------------------------------------------------------------------- time

    /** The real time the mayor in office at [nowMs] hands over: Late Spring 27 of the next election year. */
    fun nextMayorAt(nowMs: Long): Long {
        val sinceEpoch = nowMs - EPOCH_MS
        val year = Math.floorDiv(sinceEpoch, YEAR_MS)
        val inYear = Math.floorMod(sinceEpoch, YEAR_MS)
        val month = inYear / MONTH_MS + 1
        val day = (inYear % MONTH_MS) / DAY_MS + 1
        val electionYear = if (month < ELECTION_END_MONTH || (month == ELECTION_END_MONTH.toLong() && day < ELECTION_END_DAY)) year - 1 else year
        return EPOCH_MS + (electionYear + 1) * YEAR_MS + (ELECTION_END_MONTH - 1) * MONTH_MS + (ELECTION_END_DAY - 1) * DAY_MS
    }

    /** `4d 12h`, `3h 20m`, `45m`, `50s`: the two largest units, as Hypixel's own timers. */
    fun duration(ms: Long): String {
        var s = (ms / 1000).coerceAtLeast(0)
        val d = s / 86_400
        s %= 86_400
        val h = s / 3600
        s %= 3600
        val m = s / 60
        s %= 60
        val parts = listOf(d to "d", h to "h", m to "m", s to "s").dropWhile { it.first == 0L }.take(2)
        return if (parts.isEmpty()) "0s" else parts.joinToString(" ") { "${it.first}${it.second}" }
    }

    // ----------------------------------------------------------------------------------- parse

    /** The election endpoint's JSON as an [Election]; null when it has no mayor (a failed or changed answer). */
    fun parse(json: String): Election? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val mayorObj = root["mayor"] as? JsonObject ?: return null
        val mayor = candidate(mayorObj) ?: return null
        val minister = (mayorObj["minister"] as? JsonObject)?.let { m ->
            val perk = (m["perk"] as? JsonObject)?.let(::perk)
            val key = m.str("key") ?: return@let null
            MayorCandidate(key, m.str("name") ?: key, listOfNotNull(perk))
        }
        val candidates = ArrayList<MayorCandidate>()
        ((mayorObj["election"] as? JsonObject)?.get("candidates") as? JsonArray)?.mapNotNullTo(candidates) { (it as? JsonObject)?.let(::candidate) }
        ((root["current"] as? JsonObject)?.get("candidates") as? JsonArray)?.mapNotNullTo(candidates) { (it as? JsonObject)?.let(::candidate) }
        return Election(mayor, minister, candidates)
    }

    private fun candidate(o: JsonObject): MayorCandidate? {
        val key = o.str("key") ?: return null
        val perks = (o["perks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::perk) } ?: emptyList()
        return MayorCandidate(key, o.str("name") ?: key, perks)
    }

    private fun perk(o: JsonObject): MayorPerk? {
        val name = o.str("name") ?: return null
        return MayorPerk(name, o.str("description") ?: "", (o["minister"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // ----------------------------------------------------------------------------------- calendar

    private val JERRY_NAME = Regex("§.Mayor Jerry")
    private var seenStack: ItemStack? = null

    /** `Calendar and Events`: Mayor Jerry's head lists the Perkpocalypse mayor's perks under `Perkpocalypse Perks:`. */
    private fun scanCalendar(mc: Minecraft, nowMs: Long) {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!LegacyText.strip(screen.title.string).trim().equals("Calendar and Events", ignoreCase = true)) return
        val election = election ?: return
        for (slot in screen.menu.slots) {
            if (slot.container is net.minecraft.world.entity.player.Inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val name = stack.hoverName.toLegacyText()
            if (!JERRY_NAME.containsMatchIn(name)) continue
            if (seenStack === stack) return
            seenStack = stack
            val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.toLegacyText() }.orEmpty()
            readJerry(lore, election, nowMs)
            return
        }
    }

    /** Sets the Perkpocalypse mayor from Jerry's lore (the first perk, two lines under its heading). */
    fun readJerry(lore: List<String>, election: Election, nowMs: Long) {
        val at = lore.indexOfFirst { LegacyText.strip(it).trim() == "Perkpocalypse Perks:" }
        if (at < 0) return
        // The first perk is a line or two under the heading (SkyHanni reads the second); take the first one an election knows.
        val mayor = lore.drop(at + 1).take(3).firstNotNullOfOrNull { election.mayorWithPerk(LegacyText.strip(it).trim()) } ?: return
        val handOver = nextMayorAt(nowMs)
        // Jerry swaps the Perkpocalypse mayor every 6 real hours counted from the start of his term.
        val termStart = handOver - YEAR_MS
        val next = (1..21).map { termStart + it * 6 * 60 * 60 * 1000L }.firstOrNull { it > nowMs } ?: handOver
        jerryMayor = mayor
        jerryUntil = minOf(next, handOver)
    }
}
