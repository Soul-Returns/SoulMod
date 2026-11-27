package com.soulreturns.features.scoreboard.lines.events

import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineChips
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.OptionValues
import com.soulreturns.features.scoreboard.ScoreboardActivities
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One event as its own addable line (the Events group): Hypixel's lines for that event, colored by its parts. */
class EventLine(val def: EventDef) :
    LineType(def.id, ScoreboardCatalog.EVENTS, def.name, def.description, def.example) {
    override val where: String? = def.where
    override val tag: String? = if (def.tab) "tab list" else null
    override val colors = def.colors

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = def.read(sources)?.indexes ?: emptyList()

    override fun lines(ctx: LineContext): List<String> = def.read(ctx.sources)?.lines ?: emptyList()
}

/**
 * Whatever event is running: every running event (or only the first) of the enabled ones, in their
 * priority order. The enabled ids and their order are stored in the entry's option [EVENTS_KEY] (a JSON
 * array of catalogue ids, most important first; absent = all 28 on in [EventDefs.all] order, ids not in
 * the array are off, unknown ids are ignored). The setup screen's event list reads and writes the same
 * array; the Custom Scoreboard importer writes it too.
 */
object EventsLine : LineType(
    "events", ScoreboardCatalog.EVENTS, "Events (whatever is running)",
    "Shows the running event. Pick and order the events in its options.",
    listOf("Event: §6§lRAFFLE", "Tickets: §a8 §7(17.4%)"),
) {
    const val EVENTS_KEY = "events"
    const val SHOW = "evAll"
    const val GAP = "evGap"
    const val PREFIX = "evPrefix"
    const val TAB_IN_INSTANCES = "evTabInst"

    override val options =
        listOf(
            LineChoice(SHOW, "Show", listOf(LineChoice.Choice("all", "All running"), LineChoice.Choice("top", "Only the first")), "all", segmented = true),
            LineSwitch(GAP, "Gap between events", false),
            LineSwitch(PREFIX, "\"Event:\" in front", true),
            LineSwitch(TAB_IN_INSTANCES, "Tab-list events in Dungeons & Kuudra", true),
            LineChips(EVENTS_KEY, "Events, in priority order", EventDefs.all.map { LineChoice.Choice(it.id, it.name) }, EventDefs.all.map { it.id }, ordered = true),
        )

    override val colors = LineColors.HeadingLabels(0xFFAA00, 0x55FF55)

    /** The enabled events in priority order for an entry's options. */
    fun enabled(options: OptionValues): List<EventDef> {
        val stored = options.json[EVENTS_KEY] as? JsonArray ?: return EventDefs.all
        return stored.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(EventDefs::of) }.distinct()
    }

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> {
        val out = HashSet<Int>()
        for (d in EventDefs.all) d.read(sources)?.let { out += it.indexes }
        return out
    }

    override fun lines(ctx: LineContext): List<String> {
        val s = ctx.sources
        val prefix = ctx.options.bool(PREFIX)
        val inInstance = ScoreboardActivities.of(ScoreboardActivities.DUNGEONS)?.matches(s) == true ||
            ScoreboardActivities.of(ScoreboardActivities.KUUDRA)?.matches(s) == true
        val tabAllowed = ctx.options.bool(TAB_IN_INSTANCES) || !inInstance
        val taken = HashSet<Int>()
        val out = ArrayList<String>()
        val first = ctx.options.string(SHOW) == "top"
        for (def in enabled(ctx.options)) {
            if (def.tab && !tabAllowed) continue
            val read = def.read(s, prefix) ?: continue
            if (read.lines.isEmpty()) continue
            // A line a higher event already drew (Time Left, Starting in) is not drawn twice.
            if (read.indexes.isNotEmpty() && taken.containsAll(read.indexes)) continue
            taken += read.indexes
            if (out.isNotEmpty() && ctx.options.bool(GAP)) out += ""
            out += read.lines
            if (first) break
        }
        return out
    }
}
