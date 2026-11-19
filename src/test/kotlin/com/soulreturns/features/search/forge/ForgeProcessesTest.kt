package com.soulreturns.features.search.forge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForgeProcessesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = 1_800_000_000_000L
    private val base12h = 12 * 3600L

    // ---------------------------------------------------------------- lore times (formats of the 2026-11-16 captures)

    @Test
    fun loreTimesInEveryCapturedFormat() {
        assertEquals(5 * 3600L + 36 * 60, ForgeParsing.seconds("5h 36m"))
        assertEquals(5 * 3600L + 35 * 60 + 57, ForgeParsing.seconds("5h 35m 57s"))
        assertEquals(35 * 60L + 5, ForgeParsing.seconds("35m 5s"))
        assertEquals(4 * 86400L + 21 * 3600 + 36 * 60, ForgeParsing.seconds("4d 21h 36m"))
        assertEquals(21L, ForgeParsing.seconds("21s"))
        assertNull(ForgeParsing.seconds("soon"))
        val busy = ForgeParsing.forgeSlot("REFINED_DIAMOND", "Refined Diamond",
            listOf("Currently making: Refined Diamond", "Time Remaining: 35m 5s")) as ForgeParsing.Busy
        assertEquals(2105, busy.remainingSeconds)
    }

    // ---------------------------------------------------------------- end times

    @Test
    fun startedProcessEndsAtTheRecipeTimeAtThatQuickForge() {
        val s = ForgeProcesses.fromStart(2, "REFINED_TITANIUM", "Refined Titanium", base12h, 0.30, true, now)
        assertEquals(now + 8 * 3600_000L + 24 * 60_000L, s.endsAt) // 12h × 0.7 = 8h 24m
        assertEquals(now, s.startedAt)
        assertFalse(ForgeProcesses.approximate(s))
        // Level unknown at the click: computed at 0 %, and marked as a guess.
        val guess = ForgeProcesses.fromStart(2, "REFINED_TITANIUM", null, base12h, 0.0, false, now)
        assertEquals(now + base12h * 1000, guess.endsAt)
        assertTrue(ForgeProcesses.approximate(guess))
    }

    @Test
    fun menuReadIsExactAndNeedsNoQuickForge() {
        val s = ForgeProcesses.fromMenu(1, "X", null, 2829, now)
        assertEquals(now + 2_829_000L, s.endsAt)
        assertFalse(ForgeProcesses.approximate(s))
    }

    // ---------------------------------------------------------------- precedence

    @Test
    fun aWorseSourceNeverOverwritesABetterOneForTheSameProcess() {
        val menu = ForgeProcesses.fromMenu(1, "REFINED_DIAMOND", null, 3000, now)
        val api = ForgeSlotRead(1, "busy", "REFINED_DIAMOND", readAt = now + 5, endsAt = now + 9_999_000, startedAt = now - 600_000, source = ForgeProcesses.API, approx = true)
        assertSame(menu, ForgeProcesses.merge(menu, api))
        val start = ForgeProcesses.fromStart(1, "REFINED_DIAMOND", null, 3000, 0.0, true, now - 1000)
        assertSame(start, ForgeProcesses.merge(start, api.copy(startedAt = now - 1000)))
        // ...and the menu corrects a start click's computed time.
        val corrected = ForgeProcesses.merge(start, ForgeProcesses.fromMenu(1, "REFINED_DIAMOND", null, 2000, now))
        assertEquals(now + 2_000_000L, corrected.endsAt)
        assertEquals(ForgeProcesses.MENU, corrected.source)
        assertEquals(now - 1000, corrected.startedAt)
    }

    @Test
    fun aMenuReadThatAgreesChangesNothing() {
        val menu = ForgeProcesses.fromMenu(1, "X", null, 3000, now)
        val again = ForgeProcesses.fromMenu(1, "X", null, 2999, now + 1000)
        assertSame(menu, ForgeProcesses.merge(menu, again))
    }

    @Test
    fun aLaterProcessReplacesAnEarlierOneButStaleApiNewsDoesNot() {
        val menu = ForgeProcesses.fromMenu(1, "A", null, 3000, now)
        val newer = ForgeSlotRead(1, "busy", "B", readAt = now + 10_000, endsAt = now + 99_000, startedAt = now + 5_000, source = ForgeProcesses.API, approx = true)
        assertEquals("B", ForgeProcesses.merge(menu, newer).outputId)
        val older = newer.copy(startedAt = now - 5_000)
        assertSame(menu, ForgeProcesses.merge(menu, older)) // began before we last looked at the slot: old news
    }

    @Test
    fun menuSaysIdleClearsAndAnUnreadableFinishedSlotKeepsItsProcess() {
        val busy = ForgeProcesses.fromStart(3, "X", null, 600, 0.0, true, now)
        val idle = ForgeSlotRead(3, "idle", readAt = now + 700_000, source = ForgeProcesses.MENU)
        assertEquals("idle", ForgeProcesses.merge(busy, idle).state)
        val unreadable = ForgeSlotRead(3, "unknown", "X", readAt = now + 700_000, source = ForgeProcesses.MENU)
        assertSame(busy, ForgeProcesses.merge(busy, unreadable))
    }

    // ---------------------------------------------------------------- API

    private val apiMember = json.parseToJsonElement(
        """{"forge":{"forge_processes":{"forge_1":{"1":{"type":"FORGE","id":"REFINED_DIAMOND","startTime":1799999000000,"slot":1,"notified":false},
            "4":{"type":"FORGE","id":"GOLDEN_PLATE","startTime":1799990000000.0,"slot":4,"notified":true}}}},
            "skill_tree":{"nodes":{"mining":{"quick_forge":20}}}}""",
    ).jsonObject

    @Test
    fun apiPayloadIsReadTolerantly() {
        val p = ForgeProcesses.apiProcesses(apiMember)
        assertEquals(listOf(1 to "REFINED_DIAMOND", 4 to "GOLDEN_PLATE"), p.map { it.slot to it.id })
        assertEquals(1799990000000L, p[1].startMs)
        assertEquals(20, ForgeProcesses.apiQuickForgeLevel(apiMember))
        assertEquals(30.0, ForgeProcesses.percentOfLevel(20))
        assertEquals(10.5, ForgeProcesses.percentOfLevel(1))
        assertEquals(19.5, ForgeProcesses.percentOfLevel(19))
        assertTrue(ForgeProcesses.apiProcesses(json.parseToJsonElement("{}").jsonObject).isEmpty())
        assertNull(ForgeProcesses.apiQuickForgeLevel(json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun apiFillsUnwitnessedSlotsApproximatelyAtTheCurrentPercent() {
        val bases = mapOf("REFINED_DIAMOND" to 8 * 3600L, "GOLDEN_PLATE" to 6 * 3600L)
        val merged = ForgeProcesses.mergeApi(emptyList(), ForgeProcesses.apiProcesses(apiMember), now, 0.30) { bases[it] }
        assertEquals(7, merged.size)
        val s1 = merged[0]
        assertEquals(1799999000000L + 5 * 3600_000L + 36 * 60_000L, s1.endsAt) // 8h × 0.7 = 5h 36m after the start
        assertTrue(ForgeProcesses.approximate(s1))
        assertEquals("idle", merged[1].state)
    }

    @Test
    fun apiNeverOverwritesWitnessedSlotsAndOnlyWithdrawsItsOwn() {
        val bases = mapOf("REFINED_DIAMOND" to 8 * 3600L)
        val menu = ForgeProcesses.fromMenu(1, "REFINED_DIAMOND", null, 3000, now)
        val witnessed = ForgeProcesses.fromStart(2, "GOLDEN_PLATE", null, 600, 0.0, true, now)
        val fromApi = ForgeSlotRead(3, "busy", "REFINED_DIAMOND", readAt = now - 1, endsAt = now + 1000, startedAt = now - 5000, source = ForgeProcesses.API, approx = true)
        val api = listOf(ForgeProcesses.ApiProcess(1, "REFINED_DIAMOND", now - 30_000))
        val merged = ForgeProcesses.mergeApi(listOf(menu, witnessed, fromApi), api, now + 1, 0.30) { bases[it] }
        assertSame(menu, merged[0])
        assertSame(witnessed, merged[1]) // the API does not list slot 2 (it lags), the start we saw stays
        assertEquals("idle", merged[2].state) // an API-sourced row the API no longer lists is withdrawn
    }

    // ---------------------------------------------------------------- stored data

    @Test
    fun aVersion1FileStillLoadsAndIsReadAsAnApproximation() {
        val data = json.decodeFromString(ForgeProfileData.serializer(), javaClass.getResource("/forge/forge-profile-v1.json")!!.readText())
        assertEquals(1, data.version)
        val busy = data.slots.first { it.state == "busy" }
        assertEquals("", busy.source)
        assertEquals(0L, busy.endsAt)
        assertTrue(ForgeProcesses.approximate(busy))
        // Interpreted like before: base seconds scaled by the percent that applies now.
        assertEquals(busy.readAt + ForgeEngine.shownSeconds(busy.remainingBase, 0.30) * 1000, ForgeProcesses.endsAt(busy, 0.30))
        // A menu read replaces the legacy row.
        val read = ForgeProcesses.merge(busy, ForgeProcesses.fromMenu(busy.slot, busy.outputId!!, null, 3000, now))
        assertEquals(ForgeProcesses.MENU, read.source)
        assertFalse(ForgeProcesses.approximate(read))
        // The shape round-trips as version 2.
        val round = json.decodeFromString(ForgeProfileData.serializer(), json.encodeToString(ForgeProfileData.serializer(), data.copy(slots = listOf(read))))
        assertEquals(read, round.slots.single())
    }

    @Test
    fun theReportedWrongTime() {
        // The owner's tile showed 1h 7m 21s while the tooltip's second line said 47m 9s for the same process.
        // The old read stored BASE seconds (menu time ÷ (1 − the percent known then)); the display multiplied by the
        // percent known NOW. With 30 % at the read and 0 % at display, 2829 s became 4041 s.
        val shownAtRead = 47 * 60L + 9
        val base = Math.round(shownAtRead / (1 - 0.30))
        assertEquals(4041, base)
        assertEquals(4041, ForgeEngine.shownSeconds(base, 0.0))
        assertEquals(2829, ForgeEngine.shownSeconds(base, 0.30))
        // Now: the stored end is the menu time itself, whatever percent the tab believes.
        assertEquals(now + 2829_000L, ForgeProcesses.fromMenu(1, "X", null, shownAtRead, now).endsAt)
    }
}
