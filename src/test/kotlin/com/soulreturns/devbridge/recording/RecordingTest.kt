package com.soulreturns.devbridge.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the committed test-client recording (`/hx` server: a walking v4 fake player, a v2 NPC, a
 * mark via `/soul dev mark`, the SkyBlock Menu, a Storm chat line, per-tick ping on) the way a
 * future rule engine will read real runs.
 */
class RecordingTest {
    private val frames = Recording.read(javaClass.getResourceAsStream("/devbridge/testclient-2026-11-11.rec.ndjson.gz")!!)

    @Test
    fun headerAndEnd() {
        val header = frames.first() as Header
        assertEquals(Recording.FORMAT, header.format)
        assertTrue(header.version in 1..Recording.VERSION, "reads v1 and v2, got ${header.version}")
        assertEquals("SoulTester", header.player)
        assertTrue(frames.last() is End)
        assertTrue(frames.any { it is Snapshot }, "a recording starts with a full snapshot")
    }

    @Test
    fun framesAreOrderedAndTicked() {
        val live = frames.filter { it !is Header }
        assertTrue(live.zipWithNext().all { (a, b) -> b.t >= a.t - 50 }, "time order (writer order, ms jitter allowed)")
        val selves = frames.filterIsInstance<Self>()
        assertTrue(selves.size >= 60, "self at 10 Hz for 7 s, got ${selves.size}")
        // The fixture is a v1 recording: its `gt` (client game time, snapped to set_time) is the clock, its `tick` the ping count.
        val ticks = selves.mapNotNull { it.stamp.tick }
        assertEquals(selves.size, ticks.size, "every frame has a server tick")
        assertTrue(ticks.zipWithNext().all { (a, b) -> b >= a })
        // 10 Hz self frames are 2 server ticks apart on a 20 TPS server.
        val gaps = ticks.zipWithNext().map { (a, b) -> b - a }
        assertTrue(gaps.count { it in 1..3 } >= gaps.size * 0.9, "tick gaps $gaps")
        assertTrue(selves.all { it.stamp.ping != null }, "the test server sends the per-tick ping, so v1 `tick` is the ping count")
    }

    @Test
    fun v2LinesCarryTheClockAsTickAndThePingCount() {
        val line = """{"t":5,"tick":12345,"ping":14000,"kind":"chat","data":{"text":"hi"}}"""
        val frame = Recording.parse(kotlinx.serialization.json.Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject)
        assertEquals(12345L, frame.clock)
        assertEquals(14000L, frame.stamp.ping)
        val v1 = Recording.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"t":5,"tick":14000,"gt":12345,"kind":"chat","data":{"text":"hi"}}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(12345L, v1.clock)
        assertEquals(14000L, v1.stamp.ping)
    }

    @Test
    fun playersRealAndNpc() {
        val players = frames.filterIsInstance<Players>().flatMap { it.players }
        assertTrue(players.any { it.name == "BuilderBob" && it.real })
        assertTrue(players.any { it.name == "Keeper_NPC" && !it.real }, "v2 uuid = NPC")
        val bob = players.filter { it.name == "BuilderBob" }
        assertTrue(bob.map { it.pos }.distinct().size > 10, "the walking fake player moves between frames")
    }

    @Test
    fun markMatchesWherePlayerStood() {
        val mark = frames.filterIsInstance<Mark>().single()
        assertEquals("fixture spot", mark.label)
        val near = frames.filterIsInstance<Self>().minBy { Math.abs(it.t - mark.t) }
        assertTrue(near.pos.distance(mark.pos) < 0.5, "mark at ${mark.pos}, self at ${near.pos}")
        assertTrue(mark.lookingAt?.get("block") != null)
    }

    @Test
    fun menuAndChat() {
        val menus = frames.filterIsInstance<Menu>()
        assertTrue(menus.any { it.open && it.title?.contains("SkyBlock Menu") == true })
        assertTrue(menus.any { !it.open })
        assertTrue(frames.filterIsInstance<Chat>().any { it.stripped.startsWith("[BOSS] Storm: Pathetic Maxor") })
    }
}
