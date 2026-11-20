package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.features.profileviewer.gui.ProfileViewerState
import com.soulreturns.features.profileviewer.gui.ProfileViewerState.Shell
import com.soulreturns.features.profileviewer.gui.RecentPlayers
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.Rich
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileViewerStateTest {
    private val now = 1_000_000_000L
    private val s = ProfileViewerState

    private fun shell(loading: Boolean = false, resolved: Boolean = true, hasView: Boolean = true, failure: String? = null, loadedAgo: Long = 0, retryIn: Long = 0) =
        s.shell(loading, resolved, hasView, failure, now, now - loadedAgo, if (retryIn > 0) now + retryIn else 0)

    @Test
    fun `staged loading`() {
        assertEquals(Shell(s.BODY_RESOLVING, s.BANNER_NONE, s.STATUS_LOADING, false), shell(loading = true, resolved = false, hasView = false))
        assertEquals(Shell(s.BODY_LOADING, s.BANNER_NONE, s.STATUS_LOADING, true), shell(loading = true, hasView = false))
        // Refreshing keeps the profile on screen.
        assertEquals(Shell(s.BODY_SECTION, s.BANNER_NONE, s.STATUS_LOADING, true), shell(loading = true))
    }

    @Test
    fun `fresh and stale`() {
        assertEquals(Shell(s.BODY_SECTION, s.BANNER_NONE, s.STATUS_FRESH, true), shell(loadedAgo = s.STALE_AFTER_MS - 1))
        assertEquals(Shell(s.BODY_SECTION, s.BANNER_STALE, s.STATUS_STALE, true), shell(loadedAgo = s.STALE_AFTER_MS))
    }

    @Test
    fun `player-level failures replace the body and hide the rail`() {
        assertEquals(Shell(s.BODY_NOT_FOUND, s.BANNER_NONE, s.STATUS_NONE, false), shell(failure = ProfileLookupFailure.NOT_FOUND, hasView = false))
        assertEquals(s.BODY_NOT_FOUND, shell(failure = ProfileLookupFailure.INVALID_NAME).body)
        assertEquals(Shell(s.BODY_NO_PROFILES, s.BANNER_NONE, s.STATUS_NONE, false), shell(failure = ProfileLookupFailure.NO_PROFILES))
        assertEquals(s.BODY_ERROR, shell(failure = ProfileLookupFailure.OTHER, hasView = false).body)
    }

    @Test
    fun `rate limited with and without a profile on screen`() {
        assertEquals(Shell(s.BODY_RATE_LIMITED_EMPTY, s.BANNER_NONE, s.STATUS_RATE_LIMITED, false), shell(failure = ProfileLookupFailure.RATE_LIMITED, hasView = false, retryIn = 30_000))
        assertEquals(Shell(s.BODY_SECTION, s.BANNER_RATE_LIMITED, s.STATUS_RATE_LIMITED, true), shell(failure = ProfileLookupFailure.RATE_LIMITED, retryIn = 30_000))
        // The wait is over: the kept profile is just old now.
        assertEquals(Shell(s.BODY_SECTION, s.BANNER_STALE, s.STATUS_STALE, true), shell(failure = ProfileLookupFailure.RATE_LIMITED))
    }

    @Test
    fun `offline with and without cache`() {
        assertEquals(Shell(s.BODY_OFFLINE_EMPTY, s.BANNER_NONE, s.STATUS_OFFLINE, false), shell(failure = ProfileLookupFailure.OFFLINE, hasView = false))
        assertEquals(Shell(s.BODY_SECTION, s.BANNER_OFFLINE, s.STATUS_OFFLINE, true), shell(failure = ProfileLookupFailure.OFFLINE))
    }

    @Test
    fun `soul states`() {
        val data = SoulStatsAnswer(SoulStatsEnvelope(shared = true, namespaces = mapOf("mineshaft" to JsonObject(emptyMap()))), SoulStatsClient.CODE_OK)
        assertEquals(s.SOUL_LOADING, s.soul(null, isSelf = false))
        assertEquals(s.SOUL_DATA, s.soul(data, isSelf = false))
        assertEquals(s.SOUL_PRIVATE, s.soul(SoulStatsAnswer(null, SoulStatsClient.CODE_NOT_SHARED), isSelf = false))
        assertEquals(s.SOUL_NOT_A_USER, s.soul(SoulStatsAnswer(null, SoulStatsClient.CODE_NOT_A_USER), isSelf = false))
        assertEquals(s.SOUL_SHARED_EMPTY, s.soul(SoulStatsAnswer(null, SoulStatsClient.CODE_NO_NAMESPACES), isSelf = false))
        assertEquals(s.SOUL_UNAVAILABLE, s.soul(SoulStatsAnswer(null, SoulStatsClient.CODE_UNAVAILABLE), isSelf = false))
        // You are never private or "not a user" to yourself.
        assertEquals(s.SOUL_SELF_EMPTY, s.soul(SoulStatsAnswer(null, SoulStatsClient.CODE_NOT_SHARED), isSelf = true))
        assertEquals(s.SOUL_SELF_EMPTY, s.soul(SoulStatsAnswer(null, SoulStatsClient.CODE_NO_NAMESPACES), isSelf = true))
    }

    @Test
    fun `recent players move to the front, case-insensitively, capped`() {
        var list = emptyList<RecentPlayers.Entry>()
        for (i in 1..10) list = RecentPlayers.push(list, "P$i", i.toLong())
        assertEquals(RecentPlayers.MAX, list.size)
        assertEquals("P10", list.first().name)
        list = RecentPlayers.push(list, "p5", 99)
        assertEquals(listOf("p5", "P10", "P9", "P8", "P7", "P6", "P4", "P3"), list.map { it.name })
    }

    @Test
    fun `formatting matches the mock`() {
        assertEquals("2,412,000,000", Format.num(2_412_000_000L))
        assertEquals("412.9M", Format.short(412_880_310L))
        assertEquals("2.4B", Format.short(2_412_000_000L))
        assertEquals("1k", Format.short(1_000L))
        assertEquals("999", Format.short(999L))
        assertEquals("12.5%", Format.pct(0.125))
        assertEquals("4:05", Format.dur(245_000))
        assertEquals("3 h 12 min", Format.hm(3 * 3600 + 12 * 60L))
        assertEquals("48 min", Format.hm(48 * 60L))
        assertEquals("just now", Format.ago(10_000))
        assertEquals("8 min ago", Format.ago(8 * 60_000L))
        assertEquals("26 h ago", Format.ago(26 * 3_600_000L))
        assertEquals("3 days ago", Format.ago(3 * 86_400_000L))
        assertEquals("Nov 22, 2021", Format.date(1_637_539_200_000L))
        assertEquals("XLIX", Format.roman(49))
    }

    @Test
    fun `rich text parses section codes and wraps on words`() {
        val m = object : KitMeasure {
            override fun width(text: String, size: Float, bold: Boolean) = text.length * 5
            override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean) = text.length * 6
        }
        val r = Rich.of("§6Gold §lbold§r plain")
        assertEquals(listOf("Gold ", "bold", " plain"), r.runs.map { it.text })
        assertEquals(listOf(false, true, false), r.runs.map { it.bold })
        assertEquals(Rich.mcColor('6'), r.runs[0].color)
        assertEquals(null, r.runs[2].color)
        val lines = Rich.of("one two three four").wrap(m, 12f, 45)
        assertEquals(listOf("one two", "three", "four"), lines.map { l -> l.runs.joinToString("") { it.text }.trim() })
    }
}
