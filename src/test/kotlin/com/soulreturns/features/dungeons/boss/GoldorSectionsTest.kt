package com.soulreturns.features.dungeons.boss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldorSectionsTest {
    @Test
    fun aSectionBeginsWithItsPredecessorsCounterFullAndGateDown() {
        val s = GoldorSections()
        s.chat("go9o activated a terminal! (1/7)")
        assertEquals(0, s.open, "nothing before `Who dares`")
        s.chat(StormModel.WHO_DARES)
        assertEquals(1, s.open)
        assertEquals(2, s.ahead(70.0, 125.0, 136.0), "S2 area while S1 is open")
        assertNull(s.ahead(100.0, 120.0, 90.0), "in the open section")
        s.chat(GoldorSections.GATE_DOWN)
        assertEquals(1, s.open, "the gate alone opens nothing")
        s.chat("Maaxnn activated a lever! (6/7)")
        s.chat("Waccoons completed a device! (7/7)")
        assertEquals(2, s.open)
        assertNull(s.ahead(70.0, 125.0, 136.0))
        s.chat("go9o activated a terminal! (8/8)")
        assertEquals(2, s.open, "full, the gate still up")
        assertEquals(8, s.done)
        s.chat(GoldorSections.GATE_DOWN)
        assertEquals(3, s.open)
        assertEquals("Section 3 · 0 done · gate up", SectionWarning.statusOf(s))
        s.chat("x activated a terminal! (7/7)")
        s.chat(GoldorSections.GATE_DOWN)
        assertEquals(4, s.open)
        assertNull(s.ahead(55.0, 115.0, 51.0), "the core platform is no section")
        s.chat("x activated a lever! (7/7)")
        assertEquals(4, s.open, "S4 has no gate: the core opens on its line")
        s.chat(StormModel.CORE_OPEN)
        assertEquals(GoldorSections.CORE, s.open)
        assertNull(s.ahead(48.0, 115.0, 40.0), "nobody is ahead once the core is open")
        s.chat("[BOSS] Necron: You went further than any human before, congratulations.")
        assertEquals(0, s.open)
    }

    @Test
    fun areasOfTheCapture() {
        assertEquals(1, GoldorSections.area(108.0, 120.0, 90.0))
        assertEquals(2, GoldorSections.area(42.0, 109.0, 140.0))
        assertEquals(3, GoldorSections.area(2.0, 109.0, 102.0))
        assertEquals(4, GoldorSections.area(49.0, 115.0, 40.0))
        assertEquals(0, GoldorSections.area(54.0, 115.0, 51.0), "core platform")
        assertEquals(0, GoldorSections.area(54.0, 64.0, 51.0), "below: P4")
    }

    @Test
    fun theDropIntoGoldorsAreaBelongsToNoSection() {
        assertEquals(0, GoldorSections.area(100.0, 135.0, 43.0), "F7 landing, falling")
        assertEquals(0, GoldorSections.area(100.0, 115.0, 46.0), "F7 landing, on the floor")
        assertEquals(0, GoldorSections.area(104.0, 117.0, 59.0), "F7 walk toward section 1")
    }

    @Test
    fun noWarningInTheFirstSecondsAfterLandingInAnyRecordedRun() {
        val runs = (1..8).map { "m7-$it" } + listOf("f7-0027", "f7-0144", "f7-0219", "f7-0249", "f7-0404", "f7-0432", "f7-0620-1", "f7-0620-2", "f7-1726", "f7-1918", "f7-2242")
        for (name in runs) {
            val s = GoldorSections()
            var start = 0L
            var samples = 0
            for (e in lateFixture("p3/$name")) {
                if (e.k == "chat") {
                    s.chat(e.s)
                    if (e.s == StormModel.WHO_DARES) start = e.nanos
                    continue
                }
                if (e.k != "self" || start == 0L || e.nanos - start > 6_000_000_000L) continue
                samples++
                assertNull(s.ahead(e.x, e.y, e.z), "$name: warned at (${e.x}, ${e.y}, ${e.z}) ${(e.nanos - start) / 1_000_000} ms after Who dares")
            }
            assertTrue(samples > 20, "$name: only $samples positions in the first 6 s")
        }
    }

    @Test
    fun theWarningWasUpAtEveryDeathZoneHitOnTheUserInTheM7Runs() {
        val hits = ArrayList<String>()
        for (n in 1..8) {
            val s = GoldorSections()
            var pos: DoubleArray? = null
            var platformChecks = 0
            for (e in lateFixture("m7-$n-late")) {
                if (e.k == "self") {
                    pos = doubleArrayOf(e.x, e.y, e.z)
                    if (s.open == 4 && GoldorSections.area(e.x, e.y, e.z) == 0 && e.y in 110.0..120.0) platformChecks++
                    continue
                }
                if (e.k != "chat") continue
                val p = pos
                if (s.open in 1..4 && p != null && (OWN_SAVE.matches(e.s) || e.s == "☠ You died and became a ghost.")) {
                    val a = s.ahead(p[0], p[1], p[2])
                    hits += "M$n ${e.s.take(20)} at ${p.joinToString { "%.0f".format(it) }} in S${GoldorSections.area(p[0], p[1], p[2])}, S${s.open} open: ${if (a != null) "warned" else "NOT warned"}"
                }
                s.chat(e.s)
            }
            assertTrue(platformChecks > 0, "M$n: the user stood on the core platform while S4 was open (no warning there)")
        }
        assertEquals(12, hits.size, hits.joinToString("\n"))
        assertTrue(hits.all { it.endsWith("warned") && !it.endsWith("NOT warned") }, hits.joinToString("\n"))
    }

    private companion object {
        val OWN_SAVE = Regex("^(Your .* saved your life!|Second Wind Activated! .*saved your life!|Your Phoenix Pet saved you from certain death!)$")
    }
}
