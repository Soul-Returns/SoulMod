package com.soulreturns.features.contests

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.platform.http.AlphaGate
import com.soulreturns.platform.http.BackendResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Nothing of Friends & Contests may leave the client on Hypixel's Alpha network (default deny). */
class ContestAlphaTest {
    @AfterTest
    fun reset() = HypixelNetwork.reset()

    private val gets = listOf("/friends", "/contests", "/contests/types", "/contests/history?limit=20", "/contests/7", "/contests/7/feed?before=9&limit=50", "/contests/7/members/abc/runs")
    private val posts = listOf(
        "/friends/request", "/friends/abc/accept", "/friends/abc/block", "/friends/settings", "/contests", "/contests/7/join", "/contests/7/runs",
        "/contests/7/runs/r/void", "/contests/7/start", "/contests/7/members/abc/remove",
    )

    @Test
    fun everyRouteIsRefusedOnAlpha() {
        HypixelNetwork.onAddress("alpha.hypixel.net")
        for (p in gets) assertEquals(BackendResult.Failure.ALPHA_BLOCKED, AlphaGate.refusal("GET", p)?.status, p)
        for (p in posts) assertEquals(BackendResult.Failure.ALPHA_BLOCKED, AlphaGate.refusal("POST", p)?.status, p)
    }

    @Test
    fun productionSendsThem() {
        HypixelNetwork.onAddress("mc.hypixel.net")
        for (p in gets) assertNull(AlphaGate.refusal("GET", p), p)
        for (p in posts) assertNull(AlphaGate.refusal("POST", p), p)
    }
}
