package com.soulreturns.features.experiments

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seven recorded games (2026-11-17, real Hypixel packets) replayed packet by packet, plus the fault
 * cases built from them: duplicate resends, a split last-note tick, a reopen mid-game, and SkyHanni's
 * race (a click Hypixel counted while the last note was still arriving).
 */
class ExperimentFixturesTest {
    private class Trace {
        /** Per completed round: the pattern the model knew and how many answers Hypixel confirmed. */
        val rounds = ArrayList<Pair<List<String>, Int>>()
        val ultraRounds = ArrayList<List<Int>>()
        var offs = HashSet<String>()
        var failed = HashSet<String>()
        var lastChrono: ChronoModel? = null
    }

    private fun run(events: List<ExpReplay.Event>, flow: ExpFlow = ExpFlow()): Trace {
        val t = Trace()
        var round = 0
        var notes: List<String> = emptyList()
        var k = 0
        var ultraNums: List<Int> = emptyList()
        ExpReplay.feed(flow, events) {
            val m = flow.menu ?: return@feed
            if (m.state == ExpFlow.State.FAILED) t.failed += "${m.title}: ${m.why}"
            when (val model = m.model) {
                is ChronoModel -> {
                    t.lastChrono = model
                    model.off?.let { t.offs += it }
                    if (model.round != round) {
                        if (round > 0) t.rounds += notes to k
                        round = model.round
                    }
                    notes = model.notes.toList()
                    k = model.k
                }
                is UltraModel -> {
                    model.off?.let { t.offs += it }
                    if (model.round != round) {
                        if (round > 0) t.ultraRounds += ultraNums
                        round = model.round
                    }
                    if (model.phase == UltraModel.Phase.INPUT) ultraNums = (1..model.round).map { model.nums[it] ?: -1 }
                    k = model.k
                }
                else -> {}
            }
        }
        return t
    }

    @Test
    fun chronomatronGamesNeverDesync() {
        for (g in listOf("exp-6", "exp-22", "exp-26")) {
            val t = run(ExpReplay.events(g))
            assertTrue(t.offs.isEmpty(), "$g: solver off: ${t.offs}")
            assertTrue(t.failed.isEmpty(), "$g: ${t.failed}")
            t.rounds.forEachIndexed { i, (notes, k) ->
                assertEquals(i + 1, notes.size, "$g round ${i + 1}: $notes")
                assertEquals(i + 1, k, "$g round ${i + 1}: confirmed")
                if (i > 0) assertEquals(t.rounds[i - 1].first, notes.dropLast(1), "$g round ${i + 1} replays the pattern")
            }
        }
    }

    @Test
    fun container26PatternIsTheRecordedOne() {
        val t = run(ExpReplay.events("exp-26"))
        assertEquals(listOf("Yellow", "Lime", "Orange", "Green", "Red", "Lime", "Purple", "Yellow", "Red"), t.rounds.last().first)
    }

    @Test
    fun ultrasequencerGamesNeverDesync() {
        val t30 = run(ExpReplay.events("exp-30"))
        assertTrue(t30.offs.isEmpty(), "${t30.offs}")
        val rec = listOf(listOf(28), listOf(28, 37), listOf(26, 27, 23), listOf(37, 20, 10, 11), listOf(44, 14, 38, 40, 22), listOf(26, 11, 24, 38, 28, 14), listOf(27, 12, 42, 11, 38, 19, 18))
        assertEquals(rec, t30.ultraRounds.take(7))
        val t10 = run(ExpReplay.events("exp-10"))
        assertTrue(t10.offs.isEmpty(), "${t10.offs}")
        assertEquals(5, t10.ultraRounds.size)
    }

    /** Container 22, tick 5625: a click sent 45 ms before the last pling reached the client was Hypixel's first answer. */
    @Test
    fun skyHanniRaceDoesNotDesync() {
        val flow = ExpFlow()
        var verdictAtLastPling: Verdict? = null
        var kAfter = -1
        ExpReplay.feed(flow, ExpReplay.events("exp-22")) { e ->
            val model = flow.menu?.model as? ChronoModel ?: return@feed
            if (e.k == "sound" && model.round == 5 && model.plings == 5 && verdictAtLastPling == null) {
                // The turn opens on the last pling, before the clock: the right first answer goes out.
                assertEquals(ChronoModel.Phase.INPUT, model.phase)
                verdictAtLastPling = model.press("Aqua")
            }
            if (model.round == 5) kAfter = model.k
        }
        assertIs<Verdict.Send>(verdictAtLastPling)
        assertEquals(1, kAfter, "the server's echo of its counted click confirms one answer, the press stays pending until it times out")
    }

    @Test
    fun superpairsMemoryAndPairs() {
        val flow = ExpFlow()
        var plus3Open: Int? = null
        var instantPair = false
        ExpReplay.feed(flow, ExpReplay.events("exp-36")) { e ->
            val sp = flow.menu?.model as? SuperpairsModel ?: return@feed
            if (e.tick in 8195L..8205L && plus3Open == null) {
                sp.settle(e.t)
                plus3Open = sp.open
            }
            if (e.tick in 8499L..8510L && sp.matched.size >= 6) instantPair = true
        }
        assertEquals(14, plus3Open, "+3 Clicks as the second card keeps the first card open (8194)")
        assertTrue(instantPair, "Instant Find reveals a pair")
        val flow17 = ExpFlow()
        ExpReplay.feed(flow17, ExpReplay.events("exp-17"))
        assertEquals("Superpairs Rewards", flow17.menu?.title)
    }

    @Test
    fun superpairsBlocksOnlyAKnownMismatch() {
        val flow = ExpFlow()
        var checked = 0
        ExpReplay.feed(flow, ExpReplay.events("exp-17")) { e ->
            val sp = flow.menu?.model as? SuperpairsModel ?: return@feed
            sp.settle(e.t)
            if (sp.state != SuperpairsModel.State.FIRST || sp.open == null || sp.final) return@feed
            val openKey = sp.mem[sp.open!!]!!.key
            for (s in sp.grid) {
                val v = sp.press(s)
                val known = sp.mem[s]
                val expectBlock = sp.hidden(s) && known != null && known.key != openKey
                assertEquals(expectBlock, v is Verdict.Block, "slot $s at ${e.tick}")
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    // ---------------------------------------------------------------- fault cases

    /** After every slot packet, a full content resend of the same state (only once the menu has fully loaded, as Hypixel's resends are). */
    private fun withDuplicateResends(events: List<ExpReplay.Event>): List<ExpReplay.Event> {
        val out = ArrayList<ExpReplay.Event>()
        val state = HashMap<Int, HashMap<String, kotlinx.serialization.json.JsonElement>>()
        val loaded = HashMap<Int, HashSet<Int>>()
        for (e in events) {
            out += e
            if (e.k == "content") {
                val cid = e.o["cid"]!!.jsonPrimitive.int
                val m = state.getOrPut(cid) { HashMap() }
                m.clear()
                m.putAll(e.o["items"]!!.jsonObject)
                loaded.getOrPut(cid) { HashSet() }.addAll(0 until 54)
            }
            if (e.k == "slot") {
                val cid = e.o["cid"]!!.jsonPrimitive.int
                val slot = e.o["slot"]!!.jsonPrimitive.int
                val m = state.getOrPut(cid) { HashMap() }
                val item = e.o["item"]
                if (item != null && item !is kotlinx.serialization.json.JsonNull) m[slot.toString()] = item else m.remove(slot.toString())
                val seen = loaded.getOrPut(cid) { HashSet() }.also { it += slot }
                if ((0 until 54).all { it in seen }) out += ExpReplay.Event(e.t, e.tick, "content", JsonObject(mapOf("k" to JsonPrimitive("content"), "cid" to JsonPrimitive(cid), "items" to JsonObject(HashMap(m)))))
            }
        }
        return out
    }

    @Test
    fun duplicateResendsChangeNothing() {
        for (g in listOf("exp-26", "exp-30", "exp-36")) {
            val plain = run(ExpReplay.events(g))
            val dup = run(withDuplicateResends(ExpReplay.events(g)))
            assertEquals(plain.rounds, dup.rounds, g)
            assertEquals(plain.ultraRounds, dup.ultraRounds, g)
            assertTrue(dup.offs.isEmpty() && dup.failed.isEmpty(), "$g: ${dup.offs} ${dup.failed}")
        }
    }

    /** Every tick's slot packets with slot 49 first (the clock before the last note's cells), the rest a tick late. */
    @Test
    fun lateSlotUpdatesResync() {
        val events = ExpReplay.events("exp-26")
        val out = ArrayList<ExpReplay.Event>()
        val byTick = events.groupBy { it.tick }
        for ((tick, list) in byTick) {
            val clock = list.filter { it.k == "slot" && it.o["slot"]!!.jsonPrimitive.int == 49 }
            val cells = list.filter { it.k == "slot" && it.o["slot"]!!.jsonPrimitive.int != 49 }
            val rest = list.filter { it.k != "slot" }
            out += rest.filter { it.k == "sound" || it.k == "open" }
            out += clock
            out += cells.map { ExpReplay.Event(it.t + 50, tick, it.k, it.o) }
            out += rest.filter { it.k != "sound" && it.k != "open" }
        }
        val t = run(out)
        assertTrue(t.offs.isEmpty(), "${t.offs}")
        assertEquals(listOf("Yellow", "Lime", "Orange", "Green", "Red", "Lime", "Purple", "Yellow", "Red"), t.rounds.last().first)
    }

    /** A duplicate open_screen of the same game mid-round (new container id, all slots again): the model continues. */
    @Test
    fun reopenMidGameResyncs() {
        for (g in listOf("exp-26", "exp-30")) {
            val events = ExpReplay.events(g)
            val gameCid = events.first { it.k == "open" }.o["cid"]!!.jsonPrimitive.int
            val title = events.first { it.k == "open" }.o["title"]!!
            val cut = events.indexOfFirst { it.tick >= events.first().tick + 400 }
            val flow = ExpFlow()
            ExpReplay.feed(flow, events.subList(0, cut))
            val before = flow.menu!!.model!!
            val view = before.view.toList()
            val newCid = 999
            val reopen = ArrayList<ExpReplay.Event>()
            val e0 = events[cut]
            reopen += ExpReplay.Event(e0.t, e0.tick, "open", JsonObject(mapOf("k" to JsonPrimitive("open"), "cid" to JsonPrimitive(newCid), "title" to title, "size" to JsonPrimitive(54))))
            // The burst re-sends what the server shows: the same items as the old container's view.
            val oldItems = HashMap<Int, kotlinx.serialization.json.JsonElement>()
            for (e in events.subList(0, cut)) if (e.k == "slot" && e.o["cid"]!!.jsonPrimitive.int == gameCid) e.o["item"]?.let { oldItems[e.o["slot"]!!.jsonPrimitive.int] = it }
            for (s in 0 until 54) oldItems[s]?.let { reopen += ExpReplay.Event(e0.t, e0.tick, "slot", JsonObject(mapOf("k" to JsonPrimitive("slot"), "cid" to JsonPrimitive(newCid), "slot" to JsonPrimitive(s), "item" to it))) }
            val rest = events.subList(cut, events.size).map { e ->
                if (e.o["cid"]?.jsonPrimitive?.int == gameCid) ExpReplay.Event(e.t, e.tick, e.k, JsonObject(e.o + ("cid" to JsonPrimitive(newCid)))) else e
            }
            val full = run(events)
            val t = run(events.subList(0, cut) + reopen + rest)
            assertTrue(t.offs.isEmpty() && t.failed.isEmpty(), "$g: ${t.offs} ${t.failed}")
            assertEquals(full.rounds, t.rounds, g)
            assertEquals(full.ultraRounds, t.ultraRounds, g)
            assertEquals(view.size, 54)
        }
    }

    @Test
    fun unknownTierFallsBackToVanilla() {
        val flow = ExpFlow()
        flow.open(1, "Chronomatron (High)", 6, 0)
        val items = MutableList<ExpItem?>(54) { ExpItem("black_stained_glass_pane") }
        items[4] = ExpItem("bookshelf", 1, "Round: 1")
        items[49] = ExpItem("glowstone", 1, "Remember the pattern!")
        // The guessed single row: one slot per color, not the captured stacked pairs.
        listOf("Red" to 21, "Blue" to 22, "Lime" to 23).forEach { (c, s) -> items[s] = ExpItem("${c.lowercase()}_stained_glass", 1, c) }
        flow.contents(1, items, 0)
        assertEquals(ExpFlow.State.FAILED, flow.menu!!.state)
        assertNull(flow.menu!!.model)
    }

    @Test
    fun surpriseItemDropsToVanilla() {
        val flow = ExpFlow()
        var dropped = false
        ExpReplay.feed(flow, ExpReplay.events("exp-30").take(200)) {
            val m = flow.menu ?: return@feed
            if (m.state == ExpFlow.State.ACTIVE && !dropped) {
                flow.slot(m.cid, 0, ExpItem("diamond", 1, "Surprise"), 0)
                dropped = true
                assertEquals(ExpFlow.State.FAILED, m.state)
            }
        }
        assertTrue(dropped)
    }

    @Test
    fun menusRead() {
        val events = ExpReplay.events("menus")
        val flow = ExpFlow()
        val seen = HashMap<String, ExpFlow.Menu>()
        for (e in events) {
            if (e.k == "open") flow.open(e.o["cid"]!!.jsonPrimitive.int, ExpReplay.title(e.o), ExpReplay.rows(e.o), 0)
            if (e.k == "content") {
                flow.contents(e.o["cid"]!!.jsonPrimitive.int, ExpReplay.contents(e.o, flow.menu!!.size), 0)
                seen[flow.menu!!.title] = flow.menu!!
            }
        }
        val table = seen["Experimentation Table"]!!.table()!!
        assertEquals(22, table.sp.slot)
        assertEquals(29, table.chrono.slot)
        assertEquals(33, table.ultra.slot)
        assertEquals(0, table.sp.charges)
        assertEquals(31, table.renew?.slot)
        assertEquals("Looting V", table.rng?.drop)
        assertEquals("5.3", table.rng?.pct)
        assertTrue(table.chrono.practice)
        val st = seen["Superpairs ➜ Stakes"]!!.stakes()!!
        assertEquals(listOf(21, 22, 23, 30, 31, 32), st.tiers.map { it.slot })
        assertEquals("Transcendent", st.tiers[4].tier)
        assertEquals("23", st.tiers[4].clicks)
        assertEquals(false, st.tiers[5].playable)
        assertEquals(48, st.back)
        val ch = seen["Chronomatron ➜ Stakes"]!!.stakes()!!
        assertEquals("10", ch.tiers.last().colors)
        assertEquals(listOf("2 → +1 Click", "6 → +2 Clicks", "9 → +3 Clicks"), ch.tiers.last().rewards)
        val over = seen["Experiment Over"]!!.over()!!
        assertEquals("You closed the game!", over.reason)
        assertEquals("Metaphysical", over.stakes)
        assertEquals(listOf("36,000 Enchanting Exp", "2 Superpairs clicks"), over.rewards)
        assertEquals(15, over.decline)
        val rw = seen["Superpairs Rewards"]!!.rewards()!!
        assertEquals("25", rw.used)
        assertEquals("Titanic Experience Bottle", rw.rewards.last())
        for (m in seen.values) assertEquals(ExpFlow.State.ACTIVE, m.state, m.title)
    }
}
