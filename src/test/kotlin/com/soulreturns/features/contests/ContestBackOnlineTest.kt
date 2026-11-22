package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ApiError
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.gui.Connection
import com.soulreturns.features.contests.gui.StoreContestsSource
import kotlinx.coroutines.runBlocking
import net.minecraft.network.chat.Component
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** "Back online" only follows a real outage, never the first connect after login. */
class ContestBackOnlineTest {
    private val time = FakeTime()
    private val queued = ArrayDeque<suspend () -> Unit>()
    private var friendsFail = false

    private fun pump() {
        while (queued.isNotEmpty()) runBlocking { queued.removeFirst()() }
    }

    @BeforeTest
    fun setUp() {
        ContestsStore.post = { it() }
        ContestsStore.launch = { queued += it }
        time.reset()
        ContestsStore.now = time::now
        ContestsStore.schedule = time::schedule
        ContestsStore.selfUuidProvider = { "0123456789abcdef0123456789abcdef" }
        ContestsStore.emit = { }
        ContestsChat.sink = { _: Component -> }
        ContestsStore.fetchFriends = {
            if (friendsFail) ContestsApi.Reply(null, -1, ApiError(ContestsApi.ERR_TRANSPORT)) else ContestsApi.Reply(FriendsBody(), 200, null)
        }
        ContestsStore.reset()
        queued.clear(); friendsFail = false
    }

    @AfterTest
    fun tearDown() {
        ContestsStore.reset()
    }

    @Test
    fun firstConnectShowsNoBanner() {
        ContestsStore.setStream(false)
        time.advance(2_000)
        ContestsStore.setStream(true)
        assertEquals(ContestConnection.LIVE, ContestsStore.connection)
        assertNull(ContestsStore.reconnectedAt)
        assertEquals(Connection.LIVE, StoreContestsSource.connection().state)
    }

    @Test
    fun streamDropAfterLiveThenRecoveredShowsBanner() {
        ContestsStore.setStream(true)
        time.advance(1_000)
        ContestsStore.setStream(false)
        time.advance(5_000)
        ContestsStore.setStream(true)
        assertNotNull(ContestsStore.reconnectedAt)
    }

    @Test
    fun restOutageThenRecoveryShowsBannerAndItClears() {
        ContestsStore.setStream(false)
        friendsFail = true
        ContestsStore.refreshFriends(); pump()
        assertEquals(ContestConnection.OFFLINE, ContestsStore.connection)
        time.advance(3_000)
        friendsFail = false
        ContestsStore.setStream(true)
        ContestsStore.refreshFriends(); pump()
        assertEquals(ContestConnection.LIVE, ContestsStore.connection)
        assertNotNull(ContestsStore.reconnectedAt)
        assertEquals(Connection.BACK, StoreContestsSource.connection().state)
        time.advance(Connection.BACK_MS + 1)
        assertEquals(Connection.LIVE, StoreContestsSource.connection().state)
    }
}
