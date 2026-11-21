package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ApiError
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.RunUpload
import com.soulreturns.features.contests.model.RunUploadResponse
import com.soulreturns.features.mining.nucleus.NucleusLoot
import com.soulreturns.features.mining.nucleus.NucleusRunCapture
import com.soulreturns.features.mining.nucleus.NucleusRunClock
import com.soulreturns.features.mining.nucleus.NucleusRunFinished
import com.soulreturns.features.mining.nucleus.NucleusRunItem
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.soulreturns.features.mining.nucleus.NucleusChatParser
import com.soulreturns.features.mining.nucleus.NucleusEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val PROFILE = "99999999999999999999999999999999"

private fun upload(runId: String, finishedAt: Long = 1_000_000L) = RunUpload(
    schemaVersion = 1, runId = runId, seq = 1, profileId = PROFILE, finishedAt = finishedAt, startedAt = null,
    durationMs = null, partial = true, items = emptyList(), powder = emptyMap(),
)

class ContestOutboxTest {
    private lateinit var dir: Path
    private var clock = 1_000_000L
    private val sent = ArrayList<String>()
    private var answer: (RunUpload) -> ContestsApi.Reply<RunUploadResponse> = { ContestsApi.Reply(RunUploadResponse(), 201, null) }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("contest-outbox")
        ContestOutbox.resetForTest()
        ContestOutbox.path = { dir.resolve("contest_outbox.json") }
        ContestOutbox.now = { clock }
        ContestOutbox.send = { _, body -> sent += body.runId; answer(body) }
        ContestOutbox.sendingAllowed = { true }
        ContestOutbox.onChange = {}
        ContestOutbox.onUploaded = { _, _ -> }
        ContestOutbox.launcher = {}
    }

    @AfterTest
    fun tearDown() {
        ContestOutbox.resetForTest()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun submitWritesToDiskBeforeSendingAndSurvivesARestart() {
        answer = { ContestsApi.Reply(null, -1, ApiError(ContestsApi.ERR_TRANSPORT)) }
        ContestOutbox.submit(7, upload("r1"))
        assertTrue(Files.readString(dir.resolve("contest_outbox.json")).contains("r1"))
        assertTrue(sent.isEmpty(), "the send is async (launcher), never inside submit")

        ContestOutbox.resetForTest() // a restart: memory gone, file stays
        ContestOutbox.load()
        assertEquals(listOf("r1"), ContestOutbox.entries(7).map { it.runId })

        answer = { ContestsApi.Reply(RunUploadResponse(), 201, null) }
        runBlocking { ContestOutbox.drain() }
        assertEquals(listOf("r1"), sent)
        assertTrue(ContestOutbox.entries(7).isEmpty())
        // The file keeps the per-contest seq counter, never the sent run.
        assertFalse(Files.readString(dir.resolve("contest_outbox.json")).contains("r1"))
        assertEquals(2, ContestOutbox.nextSeq(7))
    }

    @Test
    fun sameRunIdIsQueuedOnce() {
        ContestOutbox.submit(7, upload("r1"))
        ContestOutbox.submit(7, upload("r1"))
        assertEquals(1, ContestOutbox.entries(7).size)
    }

    @Test
    fun transientFailureBacksOffAndStopsTheFlush() {
        answer = { ContestsApi.Reply(null, 503, ApiError(ContestsApi.ERR_TRANSPORT)) }
        ContestOutbox.submit(7, upload("r1"))
        ContestOutbox.submit(7, upload("r2"))
        runBlocking { ContestOutbox.drain() }
        assertEquals(listOf("r1"), sent, "one transient failure ends the flush")
        val head = ContestOutbox.entries(7).first()
        assertEquals(1, head.attempts)
        assertFalse(head.rejected)

        sent.clear()
        runBlocking { ContestOutbox.drain() }
        assertEquals(listOf("r2"), sent, "r1 waits for its backoff, r2 is due")

        sent.clear()
        clock += 61_000L
        answer = { ContestsApi.Reply(RunUploadResponse(), 200, null) } // replay of a stored runId answers 200
        runBlocking { ContestOutbox.drain() }
        assertEquals(setOf("r1", "r2"), sent.toSet())
        assertTrue(ContestOutbox.entries(7).isEmpty())
    }

    @Test
    fun rateLimitWaitsForRetryAfter() {
        answer = { ContestsApi.Reply(null, 429, ApiError("rate_limited"), retryAfterMs = 5_000L) }
        ContestOutbox.submit(7, upload("r1"))
        runBlocking { ContestOutbox.drain() }
        sent.clear()
        clock += 4_000L
        runBlocking { ContestOutbox.drain() }
        assertTrue(sent.isEmpty())
        clock += 2_000L
        runBlocking { ContestOutbox.drain() }
        assertEquals(listOf("r1"), sent)
    }

    @Test
    fun definitiveRejectionStaysVisibleAndIsNotRetried() {
        answer = { body -> if (body.runId == "bad") ContestsApi.Reply(null, 422, ApiError("outside_window")) else ContestsApi.Reply(RunUploadResponse(), 201, null) }
        ContestOutbox.submit(7, upload("bad"))
        ContestOutbox.submit(7, upload("good"))
        runBlocking { ContestOutbox.drain() }
        assertEquals(listOf("bad", "good"), sent)
        val rejected = ContestOutbox.entries(7).single()
        assertTrue(rejected.rejected)
        assertEquals("outside_window", rejected.rejectCode)
        assertEquals(0, ContestOutbox.pending())

        sent.clear()
        runBlocking { ContestOutbox.drain(force = true) }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun unauthorizedIsNotPermanent() {
        assertFalse(ContestOutbox.isPermanent(401))
        assertFalse(ContestOutbox.isPermanent(429))
        assertFalse(ContestOutbox.isPermanent(500))
        assertFalse(ContestOutbox.isPermanent(-1))
        assertTrue(ContestOutbox.isPermanent(403))
        assertTrue(ContestOutbox.isPermanent(409))
    }

    @Test
    fun nothingIsSentWhileSendingIsBlocked() {
        ContestOutbox.sendingAllowed = { false }
        ContestOutbox.submit(7, upload("r1"))
        runBlocking { ContestOutbox.drain(force = true) }
        assertTrue(sent.isEmpty())
        assertEquals(1, ContestOutbox.pending())
    }
}

class ContestCaptureTest {
    private lateinit var dir: Path
    private val submitted = ArrayList<Pair<Long, RunUpload>>()
    private var targets = listOf<CaptureTarget>()
    private var alpha = false
    private var profile: String? = PROFILE

    private val run = NucleusRunFinished(
        finishedAt = 2_000_000L, startedAt = 1_800_000L, durationMs = 200_000L, partial = false,
        profileId = "99999999-9999-9999-9999-999999999999",
        items = listOf(NucleusRunItem("Helix Fossil", "HELIX", 1), NucleusRunItem("Dwarven O's Gemstone Grahams", null, 1)),
        powder = mapOf("mithril" to 6327L),
    )

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("contest-capture")
        ContestOutbox.resetForTest()
        ContestOutbox.path = { dir.resolve("contest_outbox.json") }
        ContestsStore.post = { it() }
        ContestsStore.launch = {}
        ContestsStore.reset()
        ContestCaptures.alpha = { alpha }
        ContestCaptures.currentProfile = { profile }
        ContestCaptures.submit = { id, body -> submitted += id to body }
        ContestCaptures.targets = { targets }
        ContestCaptures.newRunId = { "rid" }
        ContestCaptures.add(NucleusProfitCapture)
    }

    @AfterTest
    fun tearDown() {
        ContestOutbox.resetForTest()
        dir.toFile().deleteRecursively()
    }

    private fun target(state: String = ContestStates.RUNNING, profileId: String? = PROFILE, type: String = "nucleus-profit", startedAt: Long? = 1_000_000L, endsAt: Long? = null) =
        CaptureTarget(7, type, state, profileId, startedAt, endsAt, "Nucleus Night")

    @Test
    fun participantOfARunningContestOnTheLockedProfileRecords() {
        targets = listOf(target())
        assertEquals(listOf(7L), NucleusProfitCapture.onRun(run))
        val body = submitted.single().second
        assertEquals("rid", body.runId)
        assertEquals(1, body.seq)
        assertEquals(PROFILE, body.profileId)
        assertEquals(200_000L, body.durationMs)
        assertEquals("HELIX", body.items[0].id)
        assertNull(body.items[1].id)
        assertEquals(6327L, body.powder["mithril"])
        assertEquals(CaptureStates.ACTIVE, ContestCaptures.status(7)?.state)
    }

    @Test
    fun notAParticipantRecordsNothing() {
        targets = emptyList()
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        assertNull(ContestCaptures.status(7))
    }

    @Test
    fun wrongProfileRecordsNothingAndSaysSo() {
        targets = listOf(target(profileId = "88888888888888888888888888888888"))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        assertEquals(CaptureStates.WRONG_PROFILE, ContestCaptures.status(7)?.state)
    }

    @Test
    fun contestNotRunningRecordsNothing() {
        targets = listOf(target(state = ContestStates.LOBBY))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        assertEquals(CaptureStates.NOT_RUNNING, ContestCaptures.status(7)?.state)
        targets = listOf(target(state = ContestStates.FINISHED))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
    }

    @Test
    fun alphaRecordsNothing() {
        targets = listOf(target())
        alpha = true
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        assertEquals(CaptureStates.ALPHA, ContestCaptures.status(7)?.state)
    }

    @Test
    fun outsideTheWindowAndOtherTypesRecordNothing() {
        targets = listOf(target(startedAt = 2_500_000L))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        targets = listOf(target(endsAt = 1_500_000L))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        targets = listOf(target(type = "mineshaft-corpses"))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        assertEquals(CaptureStates.UNSUPPORTED_TYPE, ContestCaptures.status(7)?.state)
    }

    @Test
    fun noLockedProfileOrUnknownProfile() {
        targets = listOf(target(profileId = null))
        assertTrue(NucleusProfitCapture.onRun(run).isEmpty())
        assertEquals(CaptureStates.NO_PROFILE_LOCKED, ContestCaptures.status(7)?.state)
        targets = listOf(target())
        profile = null
        assertEquals(CaptureStates.PROFILE_UNKNOWN, ContestCaptures.status(7)?.state)
        assertTrue(NucleusProfitCapture.onRun(run.copy(profileId = null)).isEmpty())
    }
}

class NucleusRunCaptureTest {
    @Test
    fun clockFollowsTheTrackerRules() {
        val c = NucleusRunClock()
        c.onFound(1, 100)
        val first = c.onBundle(500)
        assertNull(first.second, "first run after a start is untimed")
        assertTrue(first.third)

        c.onFound(1, 600)
        val second = c.onBundle(900)
        assertEquals(500L, second.first)
        assertEquals(400L, second.second)
        assertFalse(second.third)

        c.onFound(1, 1_000)
        c.onServerChange()
        assertTrue(c.onBundle(1_200).third, "a server change mid-run makes it partial")

        c.onServerChange() // no crystal yet: the start is forgotten
        c.onFound(1, 1_500)
        assertTrue(c.onBundle(1_700).third)

        c.onFound(3, 1_800)
        c.onFound(2, 1_900) // the count did not grow: a bundle was missed
        assertTrue(c.onBundle(2_000).third)

        c.onLeftHollows() // left with no crystal found
        assertNull(c.startedAt)
    }

    /** The 2026-11-08 capture: the first bundle ends a run joined mid-way (untimed), the second is timed bundle to bundle. */
    @Test
    fun capturedRunsProduceOneEventPerBundle() {
        val parser = NucleusChatParser()
        val clock = NucleusRunClock()
        val events = ArrayList<NucleusRunFinished>()
        val bundleTimes = ArrayList<Long>()
        javaClass.getResource("/nucleus/2026-11-08_04-25-39.ndjson")!!.readText().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val chat = o["chat"]?.jsonPrimitive?.content ?: return@forEach
            val t = o.getValue("t").jsonPrimitive.long
            val e = parser.feed(chat) ?: return@forEach
            if (e is NucleusEvent.CrystalFound) clock.onFound(e.index, t)
            if (e is NucleusEvent.LootBundle) {
                bundleTimes += t
                events += NucleusRunCapture.build(e.items, t, clock.onBundle(t), "p") { null }
            }
        }
        assertEquals(2, events.size)
        assertTrue(events[0].partial)
        assertNull(events[0].durationMs)
        assertFalse(events[1].partial)
        assertEquals(bundleTimes[1] - bundleTimes[0], events[1].durationMs)
        assertEquals(4005L, events[0].powder["mithril"])
        assertTrue(events[0].items.none { it.name.endsWith(" Powder") && it.name.substringBefore(' ').lowercase() in setOf("mithril", "gemstone", "glacite") })
    }

    @Test
    fun buildSplitsPowderAndKeepsNamesAsPrinted() {
        val e = NucleusRunCapture.build(
            listOf(NucleusLoot("Helix Fossil", 1), NucleusLoot("Mithril Powder", 6327), NucleusLoot("Gemstone Powder", 4761), NucleusLoot("Dwarven O's Gemstone Grahams", 1)),
            finishedAt = 10, timing = Triple(1L, 9L, false), profileId = "p",
        ) { name -> if (name == "Helix Fossil") "HELIX" else null }
        assertEquals(listOf("Helix Fossil", "Dwarven O's Gemstone Grahams"), e.items.map { it.name })
        assertEquals("HELIX", e.items[0].id)
        assertEquals(mapOf("mithril" to 6327L, "gemstone" to 4761L), e.powder)
        assertEquals(9L, e.durationMs)
    }
}
