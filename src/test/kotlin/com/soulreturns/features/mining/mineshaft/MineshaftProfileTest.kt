package com.soulreturns.features.mining.mineshaft

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MineshaftProfileTest {
    private val v1Outbox = """
        {
            "visits": [
                {
                    "visitId": "0f4b7e8a-1c2d-4e5f-8a9b-0c1d2e3f4a5b",
                    "mineshaftType": "ONYX",
                    "serverInstance": "ONYX_1",
                    "enteredAt": "2026-11-17T01:00:00Z",
                    "leftAt": "2026-11-17T01:05:00Z",
                    "corpses": { "Lapis": { "found": 2, "looted": 1 } },
                    "littlefootFound": false,
                    "source": "discovered",
                    "warped": false
                }
            ]
        }
    """.trimIndent()

    @Test
    fun v1OutboxKeepsItsVisitsWithoutAProfile() {
        val file = MineshaftOutbox.decodeFile(v1Outbox)
        assertEquals(1, file.schemaVersion)
        val visit = file.visits.single()
        assertEquals("0f4b7e8a-1c2d-4e5f-8a9b-0c1d2e3f4a5b", visit.visitId)
        assertEquals(CorpseCount(found = 2, looted = 1), visit.corpses["Lapis"])
        assertNull(visit.profileId)
        assertNull(visit.profileName)
    }

    @Test
    fun v2OutboxRoundTripsTheProfile() {
        val visit = MineshaftOutbox.decodeFile(v1Outbox).visits.single()
            .copy(profileId = "bee5bdcf-58ea-4c19-8608-fce0c9a342ea", profileName = "Strawberry")
        val text = Json.encodeToString(OutboxFile.serializer(), OutboxFile(MineshaftOutbox.SCHEMA_VERSION, listOf(visit)))
        val file = MineshaftOutbox.decodeFile(text)
        assertEquals(MineshaftOutbox.SCHEMA_VERSION, file.schemaVersion)
        assertEquals(visit, file.visits.single())
    }

    @Test
    fun unknownProfileIsOmittedFromTheWire() {
        val visit = MineshaftOutbox.decodeFile(v1Outbox).visits.single()
        val body = Json.encodeToJsonElement(MineshaftVisitPayload.serializer(), visit).jsonObject
        assertFalse("profileId" in body)
        assertFalse("profileName" in body)
    }

    private fun stats(text: String): MineshaftStats = MineshaftStats.from(Json.parseToJsonElement(text).jsonObject)!!

    @Test
    fun picksTheProfileInViewAndKeepsTheTotal() {
        val stats = stats(
            """
            {"totalVisits": 5, "littlefootFound": 2, "unknownProfileVisits": 1, "byCorpse": [],
             "profiles": {
                "bee5bdcf58ea4c198608fce0c9a342ea": {"profileName": "Strawberry", "totalVisits": 3, "byType": []},
                "fd180d464d154fe59da3d2b827943256": {"profileName": "Blueberry", "totalVisits": 1}
             }}
            """,
        )
        assertTrue(stats.splitsProfiles())
        assertEquals(5, stats.totalVisits)
        assertEquals(1, stats.unknownProfileVisits)

        // Dashed (Hypixel / chat form) and undashed both find the undashed key.
        val own = stats.forProfile("BEE5BDCF-58EA-4C19-8608-FCE0C9A342EA")!!
        assertEquals(3, own.totalVisits)
        assertEquals("Strawberry", own.profileName)
        assertEquals(1, stats.forProfile("fd180d464d154fe59da3d2b827943256")!!.totalVisits)

        // A profile with nothing recorded is zero, not absent; an unknown id is no selection at all.
        assertEquals(0, stats.forProfile("0123456789abcdef0123456789abcdef")!!.totalVisits)
        assertNull(stats.forProfile(null))
    }

    @Test
    fun aBackendWithoutTheSplitSelectsNothing() {
        val stats = stats("""{"totalVisits": 4}""")
        assertFalse(stats.splitsProfiles())
        assertNull(stats.forProfile("bee5bdcf58ea4c198608fce0c9a342ea"))
    }

    @Test
    fun emptyProfilesArrayIsAnEmptyMap() {
        val stats = stats("""{"totalVisits": 2, "profiles": [], "unknownProfileVisits": 2}""")
        assertTrue(stats.splitsProfiles())
        assertEquals(0, stats.forProfile("bee5bdcf58ea4c198608fce0c9a342ea")!!.totalVisits)
        assertEquals(emptyMap<String, JsonObject>(), stats.profiles)
    }
}
