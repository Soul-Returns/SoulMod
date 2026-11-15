package com.soulreturns.features.dungeons.terminals

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every melody terminal of the Hypixel recordings of 2026-11-12 (no-solver run, solver runs 1-3, storm, ring
 * timers, places, pre-dev check), reduced to `src/test/resources/terminals/melody-lanes.ndjson`: the board
 * states as received (`s`: ms, active lane, green column, target), our sent lane clicks (`c`), plings (`p`)
 * and error sounds (`e`); `shows` lists every lane change (lane, ms it first arrived, start column, target).
 */
class MelodyLaneFixturesTest {
    private class Terminal(val name: String, val shows: List<IntArray>, val events: List<JsonArray>)

    private val terminals: List<Terminal> by lazy {
        val stream = javaClass.getResourceAsStream("/terminals/melody-lanes.ndjson") ?: error("fixture missing")
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            Terminal(
                o["rec"]!!.jsonPrimitive.content + " cid " + o["cid"]!!.jsonPrimitive.int,
                o["shows"]!!.jsonArray.map { a -> IntArray(4) { a.jsonArray[it].jsonPrimitive.int } },
                o["events"]!!.jsonArray.map { it.jsonArray },
            )
        }
    }

    private val spec = TerminalType.recognize("Click the button on time!", 54)!!

    private fun board(active: Int, col: Int, target: Int): Board = List(54) { slot ->
        val x = slot % 9
        val row = slot / 9
        val lane = row - 1
        when {
            (row == 0 || row == 4) && x == target -> TermItem(PANE_MAGENTA, "")
            row in 1..3 && x in 1..5 -> TermItem(if (lane != active) PANE_WHITE else if (x == col) PANE_LIME else PANE_RED, "")
            row in 1..3 && x == 7 -> if (lane == active) TermItem(LOCK_ON, "Lock In Slot") else TermItem(LOCK_OFF, "Row Not Active")
            else -> TermItem(FILLER, "")
        }
    }

    /** This terminal's click-to-pling time (one round trip), the latency the solver would have estimated. */
    private fun latency(t: Terminal): Long {
        val rtts = ArrayList<Long>()
        var click = -1L
        for (e in t.events) when (e[1].jsonPrimitive.content) {
            "c" -> click = e[0].jsonPrimitive.long
            "p", "e" -> if (click >= 0) {
                rtts += e[0].jsonPrimitive.long - click
                click = -1
            }
        }
        return if (rtts.isEmpty()) LatencyEstimate.DEFAULT_MS else rtts.sorted()[rtts.size / 2]
    }

    private class Replayed {
        var shows = 0
        var alignedStarts = 0
        var nowAtAlignedStart = 0
        val blockedButAccepted = ArrayList<String>()
        val gambles = ArrayList<String>()
    }

    private fun replay(t: Terminal, r: Replayed) {
        val latency = latency(t)
        val tracker = TerminalTracker(spec, latency = { latency })
        var lane = -1
        var col = 0
        // The player's clicks in send order, waiting for their sound: what the tracker said at the click.
        val asked = ArrayDeque<Pair<String, Boolean>>()
        for (e in t.events) {
            val at = e[0].jsonPrimitive.long
            when (e[1].jsonPrimitive.content) {
                "s" -> {
                    val l = e[2].jsonPrimitive.int
                    col = e[3].jsonPrimitive.int
                    val target = e[4].jsonPrimitive.int
                    tracker.update(board(l, col, target), at)
                    val sol = tracker.solution(at)
                    if (l != lane && lane >= 0) {
                        r.shows++
                        // The lane is drawn live (active, or locked by our click on it), pane and target in place, the moment
                        // the server's board shows it.
                        assertTrue(sol.melody!!.active == l || sol.melodyLocked == l, "${t.name}: lane $l at $at ms")
                        assertEquals(col, sol.melody!!.lanes[l].moving, "${t.name}: lane $l pane")
                        if (col == target) {
                            r.alignedStarts++
                            // Already lined up: NOW right away and the click is let through.
                            if (sol.cells.getValue((l + 1) * 9 + 7).state == TerminalTracker.TARGET) r.nowAtAlignedStart++
                            assertTrue(tracker.isCorrect((l + 1) * 9 + 7, LEFT, at), "${t.name}: lane $l starts aligned")
                        }
                    }
                    lane = l
                }
                "c" -> {
                    val slot = e[2].jsonPrimitive.int
                    val name = "${t.name}: click on $slot at $at ms"
                    val ok = tracker.isCorrect(slot, LEFT, at)
                    if (ok) tracker.clicked(slot, LEFT, at)
                    if (ok && slot / 9 - 1 > lane) r.gambles += name
                    asked.addLast(name to ok)
                }
                "p", "e" -> {
                    val (name, ok) = asked.removeFirstOrNull() ?: continue
                    val accepted = e[1].jsonPrimitive.content == "p"
                    if (accepted && !ok) r.blockedButAccepted += name
                    if (!ok) continue
                    tracker.melodySound(accepted, at)
                }
            }
        }
    }

    @Test
    fun everyLaneShowsTheMomentTheServerSendsItAndNoAcceptedClickIsBlocked() {
        val r = Replayed()
        for (t in terminals) replay(t, r)
        assertEquals(59, terminals.sumOf { it.shows.size }, "lane changes in the recordings")
        assertEquals(59, r.shows)
        assertEquals(16, r.alignedStarts, "lanes first drawn on their target")
        assertEquals(r.alignedStarts, r.nowAtAlignedStart, "every one shows NOW at once")
        assertEquals(emptyList(), r.blockedButAccepted, "a click Hypixel accepted must never be blocked")
        // 22:42 recording: right after the first lock the player clicked the next lane's button in cid 16, 22 and 29
        // (the skip, accepted); all three go through.
        assertEquals(3, r.gambles.count { "22-42-47" in it }, r.gambles.toString())
    }

    @Test
    fun theNextLaneIsFirstDrawnOneStepOnOrStillInTheLockedColumnAfterAWrongClick() {
        var stayed = 0
        for (t in terminals) {
            var dir = 1
            var col = 0
            var lane = -1
            var error = false
            for (e in t.events) {
                when (e[1].jsonPrimitive.content) {
                    "e" -> error = true
                    "s" -> {
                        val l = e[2].jsonPrimitive.int
                        val c = e[3].jsonPrimitive.int
                        if (l == lane && c != col) dir = Integer.signum(c - col)
                        if (l != lane && lane >= 0) {
                            if (error && c == col) {
                                stayed++
                            } else {
                                assertEquals(MelodyClock.advance(col, dir, 1), c, "${t.name}: lane $l start")
                                dir = MelodyClock.turn(col, dir)
                            }
                        }
                        if (l != lane && lane < 0) assertEquals(1, c, "${t.name}: the first lane starts in column 1")
                        if (l != lane || c != col) error = false
                        lane = l
                        col = c
                    }
                }
            }
        }
        assertEquals(2, stayed, "22:50:15 cid 16 and 22:51:11 cid 22: the wrong click on the new lane paused it in the locked column")
    }

    @Test
    fun theNextLaneIsTheServersOnceOurLockIsOut() {
        // 22:52:10 (cid 29), latency ~140: lane 1 locked on column 4 (shown at 1462, sent 1508, pling 1650); the player
        // clicked lane 2's button at 1751, before any frame of lane 2: accepted, the skip.
        val tracker = TerminalTracker(spec, latency = { 140 })
        tracker.update(board(0, 3, 4), 964)
        tracker.update(board(0, 4, 4), 1462)
        assertFalse(tracker.isCorrect(25, LEFT, 1462), "lane 2 is not the server's before our lock")
        assertTrue(tracker.isCorrect(16, LEFT, 1508))
        tracker.clicked(16, LEFT, 1508)
        assertTrue(tracker.isCorrect(25, LEFT, 1520), "right after the lock: vanilla could win it")
        assertEquals(0, tracker.solution(1600).melodyLocked, "locked, nothing claimed before the pling")
        assertTrue(tracker.melodySound(true, 1650))
        val sol = tracker.solution(1650 + TerminalTracker.ECHO_MS + 1)
        assertEquals(TerminalTracker.DONE, sol.cells.getValue(16).state, "the pling with no completion line around it")
        assertEquals(null, sol.melodyLocked)
        assertTrue(tracker.isCorrect(25, LEFT, 1751), "next lane: its target unseen, never blocked")
        tracker.clicked(25, LEFT, 1751)
        assertFalse(tracker.isCorrect(25, LEFT, 1800), "sent once, waiting for its answer")
        assertTrue(tracker.isCorrect(34, LEFT, 1800), "and the lane after it is a guess too")
        assertTrue(tracker.melodySound(true, 1900))
        // Hypixel then drew lane 3 at column 5 and left lane 1's button lime: the lowest lime button is the live lane.
        val stale = board(2, 5, 5).toMutableList()
        stale[16] = TermItem(LOCK_ON, "Lock In Slot")
        stale[13] = TermItem(PANE_LIME, "")
        tracker.update(stale, 1960)
        val after = tracker.solution(1960)
        assertEquals(2, after.melody!!.active)
        assertEquals(1, after.remaining)
        assertTrue(tracker.isCorrect(34, LEFT, 1960), "column 5 on target 5")
    }

    @Test
    fun aWrongGuessPausesTheNewLaneAndAnUnansweredLockIsDropped() {
        // 22:50:15 (cid 16): lane 1 accepted on column 4, lane 2 clicked 340 ms later: error; lane 2 first drawn in
        // column 4 (paused), and it moves again 1 s later.
        val tracker = TerminalTracker(spec, latency = { 125 })
        tracker.update(board(0, 3, 4), 958)
        tracker.update(board(0, 4, 4), 1470)
        tracker.clicked(16, LEFT, 1475)
        assertTrue(tracker.melodySound(true, 1594))
        tracker.clicked(25, LEFT, 1818)
        assertTrue(tracker.melodySound(false, 1944))
        tracker.update(board(1, 4, 2), 1970)
        val paused = tracker.solution(2400)
        assertEquals(1, paused.melody!!.active)
        assertEquals(TerminalTracker.IDLE, paused.cells.getValue(25).state, "paused: no NOW")
        assertEquals(setOf(4), tracker.clock.columnsBetween(2400, 0, 500), "stands still for the pause")
        assertEquals(setOf(4, 5), tracker.clock.columnsBetween(2400, 0, 600), "and moves on the step loop's first step after it")
        tracker.update(board(1, 5, 2), 2967)
        assertEquals(setOf(5, 4), tracker.clock.columnsBetween(2967, 0, 600))
        // A lock the server never answers (not judged) frees its lane after LOCK_ANSWER_MS of server time.
        val silent = TerminalTracker(spec, latency = { 125 })
        silent.update(board(0, 4, 4), 0)
        silent.clicked(16, LEFT, 10)
        assertEquals(0, silent.solution(100).melodyLocked)
        assertEquals(null, silent.solution(10 + TerminalTracker.LOCK_ANSWER_MS + 1).melodyLocked)
        assertEquals(0, silent.solution(10 + TerminalTracker.LOCK_ANSWER_MS + 1).melody!!.active)
    }

    /** M7 party runs 2026-11-12 23:48 (`melody-m7.ndjson`): raw frames (`b`: lime buttons, lime panes per lane, targets). */
    private val m7: List<Terminal> by lazy {
        val stream = javaClass.getResourceAsStream("/terminals/melody-m7.ndjson") ?: error("fixture missing")
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            Terminal(o["rec"]!!.jsonPrimitive.content + " " + o["at"]!!.jsonPrimitive.content, emptyList(), o["events"]!!.jsonArray.map { it.jsonArray })
        }
    }

    private fun rawBoard(e: JsonArray): Board {
        val buttons = e[2].jsonArray.map { it.jsonPrimitive.int }.toSet()
        val panes = e[3].jsonArray.map { l -> l.jsonArray.map { it.jsonPrimitive.int }.toSet() }
        val targets = e[4].jsonArray.map { it.jsonPrimitive.int }.toSet()
        return List(54) { slot ->
            val x = slot % 9
            val row = slot / 9
            val lane = row - 1
            when {
                (row == 0 || row == 4) && x in targets -> TermItem(PANE_MAGENTA, "")
                row in 1..3 && x in 1..5 -> TermItem(if (x in panes[lane]) PANE_LIME else if (buttons.contains(lane)) PANE_RED else PANE_WHITE, "")
                row in 1..3 && x == 7 -> if (lane in buttons) TermItem(LOCK_ON, "Lock In Slot") else TermItem(LOCK_OFF, "Row Not Active")
                else -> TermItem(FILLER, "")
            }
        }
    }

    /** Green panes per lane as [TerminalScreen.drawMelody] draws them: only the server's single lime pane. */
    private fun greens(sol: TerminalTracker.Solution): List<Int> {
        val m = sol.melody ?: return listOf(0, 0, 0)
        return (0 until 3).map { l -> if (m.lanes[l].moving != null) 1 else 0 }
    }

    @Test
    fun aLaneNeverShowsTwoGreenMarkers() {
        val bad = ArrayList<String>()
        for (t in terminals + m7) {
            val latency = latency(t)
            val tracker = TerminalTracker(spec, latency = { latency })
            var lane = -1
            val events = t.events
            for ((i, e) in events.withIndex()) {
                val at = e[0].jsonPrimitive.long
                when (e[1].jsonPrimitive.content) {
                    "s" -> tracker.update(board(e[2].jsonPrimitive.int, e[3].jsonPrimitive.int, e[4].jsonPrimitive.int), at)
                    "b" -> tracker.update(rawBoard(e), at)
                    "c" -> {
                        val slot = e[2].jsonPrimitive.int
                        if (tracker.isCorrect(slot, LEFT, at)) tracker.clicked(slot, LEFT, at)
                    }
                    "p", "e" -> tracker.melodySound(e[1].jsonPrimitive.content == "p", at)
                }
                // Every 16 ms frame until the next event.
                val until = events.getOrNull(i + 1)?.get(0)?.jsonPrimitive?.long ?: (at + 1)
                var f = at
                while (f < until) {
                    val g = greens(tracker.solution(f))
                    if (g.any { it > 1 }) bad += "${t.name} at $f ms: $g"
                    f += 16
                }
            }
        }
        assertEquals(emptyList(), bad.distinctBy { it.substringBefore(" at ") }, bad.take(20).joinToString("\n"))
    }

    @Test
    fun aTeammatesCompletionPlingIsNotOurAnswer() {
        // M7 2026-11-12 00:39:28: lock on column 1 sent 424 ms in (the step to 2 came at 491); a pling 65 ms later
        // with "PuppyGirlSunny completed a device! (5/7)" 3 ms after it; our error sound at 547.
        val tracker = TerminalTracker(spec, latency = { 169 })
        tracker.update(board(0, 1, 1), 0)
        tracker.clicked(16, LEFT, 424)
        assertTrue(tracker.melodySound(true, 489))
        assertTrue(tracker.melodyChat(492), "the completion line claims the pling")
        tracker.update(board(0, 2, 1), 491)
        assertTrue(tracker.melodySound(false, 547))
        val sol = tracker.solution(547 + TerminalTracker.ECHO_MS + 1)
        assertEquals(3, sol.remaining, "not taken as accepted")
        assertEquals(null, sol.melodyLocked, "the error answered it")
        assertEquals(0, sol.melody!!.active)
        assertEquals(setOf(2), tracker.clock.columnsBetween(600, 0, 400), "paused")
        // A pling right after a completion line is that line's too.
        val t2 = TerminalTracker(spec, latency = { 169 })
        t2.update(board(0, 2, 2), 0)
        t2.clicked(16, LEFT, 100)
        t2.melodyChat(230)
        assertFalse(t2.melodySound(true, 250))
    }

    @Test
    fun m7PartyMelodiesAcceptedClicksAreNeverBlocked() {
        // Our answer = the first pling or error after the click with no completion line within ECHO_MS.
        val bad = ArrayList<String>()
        var checked = 0
        for (t in m7) {
            val ev = t.events
            val chats = ev.filter { it[1].jsonPrimitive.content == "a" }.map { it[0].jsonPrimitive.long }
            fun ours(i: Int) = ev[i][1].jsonPrimitive.content.let { it == "p" || it == "e" } &&
                chats.none { Math.abs(it - ev[i][0].jsonPrimitive.long) <= TerminalTracker.ECHO_MS }
            val latency = 170L
            val tracker = TerminalTracker(spec, latency = { latency })
            for ((i, e) in ev.withIndex()) {
                val at = e[0].jsonPrimitive.long
                when (e[1].jsonPrimitive.content) {
                    "b" -> tracker.update(rawBoard(e), at)
                    "a" -> tracker.melodyChat(at)
                    "p", "e" -> tracker.melodySound(e[1].jsonPrimitive.content == "p", at)
                    "c" -> {
                        val slot = e[2].jsonPrimitive.int
                        val ok = tracker.isCorrect(slot, LEFT, at)
                        if (ok) tracker.clicked(slot, LEFT, at)
                        val answer = (i + 1 until ev.size).firstOrNull { ours(it) }?.let { ev[it][1].jsonPrimitive.content }
                        checked++
                        if (answer == "p" && !ok) bad += "${t.name}: click on $slot at $at ms blocked, Hypixel accepted it"
                    }
                }
            }
        }
        assertTrue(checked >= 20, "clicks $checked")
        assertEquals(emptyList(), bad)
    }

    /** M7 party runs 2026-11-13 16:36 (`melody-m7-2.ndjson`, same format as `melody-m7.ndjson`). */
    private val m7b: List<Terminal> by lazy {
        val stream = javaClass.getResourceAsStream("/terminals/melody-m7-2.ndjson") ?: error("fixture missing")
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            Terminal("cid " + o["cid"]!!.jsonPrimitive.int + " " + o["at"]!!.jsonPrimitive.content, emptyList(), o["events"]!!.jsonArray.map { it.jsonArray })
        }
    }

    @Test
    fun aTeammatesPlingInTheSameWindowDoesNotHideOurs() {
        // 16:50:49 (cid 24): our lock at 3508, two plings at 3669 and Maaxnn's line at 3672; 16:56:02 (cid 16): lock at 547,
        // Maaxnn's line at 651, plings at 657 and 679. Each line claims one pling: our lane shows done before the redraw.
        // Every hand-over of the session shows the finished lane done 1 ms before the next lane is drawn, except where our
        // pling came less than ECHO_MS before the redraw (cid 14, lane 2: pling 1961, redraw 1988).
        val shown = ArrayList<String>()
        for (t in m7b) {
            val tracker = TerminalTracker(spec, latency = { 155 })
            var lane = -1
            val ev = t.events
            for ((i, e) in ev.withIndex()) {
                val at = e[0].jsonPrimitive.long
                when (e[1].jsonPrimitive.content) {
                    "b" -> {
                        val buttons = e[2].jsonArray.map { it.jsonPrimitive.int }
                        if (buttons.size == 1 && buttons[0] > lane && lane >= 0) {
                            val sol = tracker.solution(at - 1)
                            val done = sol.cells.getValue((lane + 1) * 9 + 7).state == TerminalTracker.DONE && sol.melodyLocked != lane
                            shown += "${t.name} lane ${lane + 1} ${if (done) "done" else "locked"}"
                        }
                        if (buttons.size == 1) lane = maxOf(lane, buttons[0])
                        tracker.update(rawBoard(e), at)
                    }
                    "a" -> tracker.melodyChat(at)
                    "p", "e" -> tracker.melodySound(e[1].jsonPrimitive.content == "p", at)
                    "c" -> {
                        val slot = e[2].jsonPrimitive.int
                        assertTrue(tracker.isCorrect(slot, LEFT, at), "${t.name}: click on $slot at $at ms")
                        tracker.clicked(slot, LEFT, at)
                    }
                }
                ev.getOrNull(i + 1)?.let { tracker.solution(it[0].jsonPrimitive.long) }
            }
        }
        assertEquals(
            listOf(
                "cid 14 16:43:48 lane 1 done", "cid 14 16:43:48 lane 2 locked",
                "cid 24 16:50:45 lane 1 done", "cid 24 16:50:45 lane 2 done",
                "cid 16 16:56:01 lane 1 done", "cid 16 16:56:01 lane 2 done",
                "cid 17 17:02:27 lane 1 done", "cid 17 17:02:27 lane 2 done",
                "cid 12 17:09:39 lane 1 done", "cid 12 17:09:39 lane 2 done",
            ),
            shown,
        )
    }
}
