package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.CheckStates
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.ContestUpdate
import com.soulreturns.features.contests.model.ContestsBody
import com.soulreturns.features.contests.model.CreateContestRequest
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.FeedKinds
import com.soulreturns.features.contests.model.FeedPage
import com.soulreturns.features.contests.model.FriendUpdate
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.Goal
import com.soulreturns.features.contests.model.GoalInput
import com.soulreturns.features.contests.model.HistoryPage
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.contests.model.Member
import com.soulreturns.features.contests.model.MemberRuns
import com.soulreturns.features.contests.model.PlayerRef
import com.soulreturns.features.contests.model.Presence
import com.soulreturns.features.contests.model.Run
import com.soulreturns.features.contests.model.RunUpload
import com.soulreturns.features.contests.model.RunUploadResponse
import com.soulreturns.features.contests.model.StandingRow
import com.soulreturns.features.contests.model.UploadItem
import com.soulreturns.features.contests.model.ContestEdit
import com.soulreturns.platform.http.BackendResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContestWireTest {
    private val json = ContestsApi.json
    /** BackendClient's encoder: defaults omitted, nulls kept. */
    private val encoder = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val examples: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/contests/contract-examples.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun <T> ex(name: String, s: KSerializer<T>): T = json.decodeFromJsonElement(s, examples[name]!!)

    @Test
    fun everyContractExampleDecodes() {
        val d = ex("descriptor", ContestTypeDescriptor.serializer())
        assertEquals("nucleus-profit", d.id)
        assertEquals(50, d.goalKinds.single().defaultUnits)
        assertEquals(100_000L, d.notableUnitValue)
        assertTrue(d.runFields.partial)
        assertEquals(5, d.rules.size)

        assertEquals("Example_One", ex("playerRef", PlayerRef.serializer()).name)
        assertEquals(50, ex("goal", Goal.serializer()).units)
        assertEquals("Jungle Temple", ex("presence", Presence.serializer()).subLocation)

        val row = ex("standingRow", StandingRow.serializer())
        assertEquals(1, row.rank)
        assertEquals(2, row.rankBefore)
        assertEquals(18_450_000L, row.total)
        assertEquals(28, row.checks.verified)
        assertNull(row.goalReachedAt)

        val m = ex("member", Member.serializer())
        assertEquals("Example_Owner", m.invitedBy?.name)
        assertTrue(m.profileLocked)
        assertTrue(m.presence.online)

        val run = ex("run", Run.serializer())
        assertEquals(CheckStates.FLAGGED, run.check.state)
        assertEquals("too_fast", run.check.reason)
        assertNull(run.items[1].id)
        assertNull(run.items[1].unitPrice)
        assertEquals(6327L, run.powder["mithril"])
        assertNull(run.voided)

        val feed = json.decodeFromJsonElement(ListSerializer(FeedEntry.serializer()), examples["feed"]!!)
        assertEquals(19, feed.size)
        assertTrue(feed.all { it.kind in FeedKinds.KNOWN })
        val pass = feed.first { it.kind == FeedKinds.PASS }
        assertEquals("Example_Owner", pass.ref("passed")?.name)
        assertEquals(2, pass.int("rank"))
        assertEquals(1_450_000L, feed.first { it.kind == FeedKinds.DROP }.long("value"))
        assertNull(feed.first { it.kind == FeedKinds.RESTORE }.str("reason"))
        assertEquals(false, feed.first { it.kind == FeedKinds.RUN }.bool("late"))

        val friends = ex("friends", FriendsBody.serializer())
        assertEquals(7L, friends.friends.single().contests.single().id)
        assertEquals("Example_Two", friends.incoming.single().name)
        assertNull(friends.result)
        assertEquals("sent", ex("friendRequestReply", FriendsBody.serializer()).result)

        val contests = ex("contests", ContestsBody.serializer())
        assertEquals(133L, contests.active.single().rev)
        assertEquals(2, contests.active.single().me.rank)
        assertNull(contests.invitations.single().me.rank)
        assertEquals("Example_Owner", contests.invitations.single().invitedBy?.name)

        assertNull(ex("history", HistoryPage.serializer()).next)

        val snap = ex("snapshot", ContestSnapshot.serializer())
        assertEquals(ContestStates.RUNNING, snap.contest.state)
        assertTrue(snap.me.can("upload"))
        assertFalse(snap.me.can("start"))
        assertEquals("99999999999999999999999999999999", snap.me.profileId)
        assertTrue(snap.feedMore)

        assertTrue(ex("feedPage", FeedPage.serializer()).more)
        assertNull(ex("memberRuns", MemberRuns.serializer()).standing)
        assertEquals(134L, ex("runUploadResponse", RunUploadResponse.serializer()).rev)

        val update = ex("contestUpdate", ContestUpdate.serializer())
        assertEquals(134L, update.rev)
        assertEquals(2, update.ranks.size)
        assertNull(update.run)
        assertEquals("request", ex("friendUpdate", FriendUpdate.serializer()).kind)
    }

    @Test
    fun requestBodiesEncodeExactlyAsTheContract() {
        val create = CreateContestRequest(
            type = "nucleus-profit", name = "Nucleus Night", goal = GoalInput("units", 50), startsAt = null,
            endsAt = 1795050000000, allowLateJoin = false, draft = false, profileId = "99999999999999999999999999999999",
            invites = listOf(InviteSpec("0123456789abcdef0123456789abcdef", "participant")),
        )
        assertEquals(examples["createRequest"], encoder.encodeToJsonElement(CreateContestRequest.serializer(), create))

        val upload = RunUpload(
            schemaVersion = 1, runId = "aaaaaaaabbbbccccddddeeeeeeeeeeee", seq = 12, profileId = "99999999999999999999999999999999",
            finishedAt = 1795000000000, startedAt = 1794999804000, durationMs = 196000, partial = false,
            items = listOf(UploadItem("Helix Fossil", "HELIX", 1), UploadItem("Dwarven O's Gemstone Grahams", null, 1)),
            powder = linkedMapOf("mithril" to 6327L, "gemstone" to 4761L, "glacite" to 0L),
        )
        assertEquals(examples["runUpload"], encoder.encodeToJsonElement(RunUpload.serializer(), upload))
    }

    @Test
    fun editSendsOnlyChangedFieldsAndExplicitNulls() {
        val body = ContestsApi.editBody(ContestEdit(name = " New ", clearStartsAt = true, allowLateJoin = true))
        assertEquals(setOf("name", "startsAt", "allowLateJoin"), body.keys)
        assertEquals(JsonNull, body["startsAt"])
        assertEquals("New", (body["name"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun unknownEnumsFieldsAndKindsNeverFailADecode() {
        val raw = """
            {"schemaVersion":2,"serverTime":1,"rev":5,"brandNew":{"x":1},
             "contest":{"id":9,"type":"mineshaft-corpses","name":"X","state":"paused","owner":{"uuid":"a","name":"b","extra":1},
                        "goal":{"kind":"first_to","label":"First to 10M","target":10000000},"newField":true},
             "type":{"id":"mineshaft-corpses","title":"Corpses","metric":{"label":"Corpses","format":"percent","higherIsBetter":false},
                     "goalKinds":[{"kind":"first_to","label":"First to"}],"runFields":{"duration":false,"items":false,"powder":false,"partial":false,"corpses":true}},
             "me":{"role":"referee","status":"spectating","ready":false,"profileId":null,"plays":false,"can":["teleport"]},
             "members":[{"uuid":"a","name":"b","role":"referee","status":"pending_review","presence":{"online":false,"inGame":false,"lastOnlineAt":null,"areaHidden":true,"area":null,"subLocation":null}}],
             "standings":[{"uuid":"a","name":"b","role":"referee","status":"joined","rank":null,"shared":false,"rankBefore":null,"units":0,"goal":0,"total":0,"best":0,"avg":null,
                           "lastRunAt":null,"lastValue":null,"unpriced":0,"voided":0,"extra":0,"checks":{"verified":0,"pending":0,"flagged":0,"disputed":3},"medianDurationMs":null,"untimed":0}],
             "feed":[{"seq":1,"at":1,"kind":"teleport","player":null,"actor":null,"data":{"anything":[1,2]}}],"feedMore":false}
        """.trimIndent()
        val snap = json.decodeFromString(ContestSnapshot.serializer(), raw)
        assertEquals("paused", snap.contest.state)
        assertFalse(snap.contest.state in ContestStates.KNOWN)
        assertNull(snap.contest.goal.units)
        assertEquals("First to 10M", snap.contest.goal.label)
        assertEquals("referee", snap.me.role)
        assertNull(snap.me.profileId)
        assertEquals("teleport", snap.feed.single().kind)
        assertNull(snap.standings.single().rank)

        val run = json.decodeFromString(Run.serializer(), """{"runId":"r","finishedAt":1,"check":{"state":"disputed","reason":"new_reason"},"futureKey":[]}""")
        assertEquals("disputed", run.check.state)
        assertTrue(run.items.isEmpty())
    }

    @Test
    fun errorCodesAreReadFromTheFailureText() {
        val full = BackendResult.Failure(409, "HTTP 409 — {\"error\":\"profile_mismatch\",\"message\":\"x\",\"lockedProfileId\":\"99\"}")
        val e = ContestsApi.parseError(full)
        assertEquals("profile_mismatch", e.error)
        assertEquals("99", e.lockedProfileId)

        // BackendClient keeps 160 chars: a cut body is scanned by key.
        val cut = BackendResult.Failure(400, "HTTP 400 — {\"error\":\"invalid_body\",\"field\":\"items\",\"message\":\"items[3].name must be 1..100 chars and this sentence goes on and on until it is cut off by the cl")
        val c = ContestsApi.parseError(cut)
        assertEquals("invalid_body", c.error)
        assertEquals("items", c.field)

        assertEquals(ContestsApi.ERR_TRANSPORT, ContestsApi.parseError(BackendResult.Failure(BackendResult.Failure.TRANSPORT, "refused")).error)
        assertEquals(ContestsApi.ERR_ALPHA, ContestsApi.parseError(BackendResult.Failure(BackendResult.Failure.ALPHA_BLOCKED, "alpha")).error)
        assertEquals(ContestsApi.ERR_TRANSPORT, ContestsApi.parseError(BackendResult.Failure(502, "HTTP 502 — <html>")).error)
        assertEquals("rate_limited", ContestsApi.parseError(BackendResult.Failure(429, "HTTP 429")).error)
    }
}
