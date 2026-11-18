package com.soulreturns.features.mining.mineshaft

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The visit upload's optional `layout` and `corpseSpots` (rule://backend, `POST /mineshaft/visit`). */
class MineshaftVisitFieldsTest {
    /** A queued visit exactly as 0.3.2-beta.2 writes `mineshaft_outbox.json` (schema 2, pretty-printed, profile known). */
    private val betaOutbox = """
        {
            "schemaVersion": 2,
            "visits": [
                {
                    "visitId": "6c1e0f7a-2b3d-4c5e-9f80-1a2b3c4d5e6f",
                    "mineshaftType": "RUBY",
                    "serverInstance": "RUBY_C",
                    "enteredAt": "2026-11-18T18:28:21Z",
                    "leftAt": "2026-11-18T18:30:28Z",
                    "corpses": {
                        "Lapis": {
                            "found": 1,
                            "looted": 1
                        },
                        "Umber": {
                            "found": 1,
                            "looted": 0
                        },
                        "Tungsten": {
                            "found": 1,
                            "looted": 0
                        }
                    },
                    "littlefootFound": true,
                    "source": "discovered",
                    "warped": false,
                    "profileId": "bee5bdcf-58ea-4c19-8608-fce0c9a342ea",
                    "profileName": "Banana"
                }
            ]
        }
    """.trimIndent()

    private fun visit() = MineshaftOutbox.decodeFile(betaOutbox).visits.single()

    @Test
    fun anOldQueuedVisitLoadsWithoutTheNewFields() {
        val file = MineshaftOutbox.decodeFile(betaOutbox)
        assertEquals(2, file.schemaVersion)
        val v = file.visits.single()
        assertEquals("RUBY_C", v.serverInstance)
        assertNull(v.layout)
        assertNull(v.corpseSpots)
        // And it replays byte for byte as it was queued: no new keys appear.
        val body = Json.encodeToJsonElement(MineshaftVisitPayload.serializer(), v).jsonObject
        assertFalse("layout" in body)
        assertFalse("corpseSpots" in body)
    }

    @Test
    fun theFieldsAreEncodedWhenPresent() {
        val v = visit().copy(layout = "RUBY_C", corpseSpots = listOf(SeenCorpseSpot(listOf(-161.333, 10.248, -154.467), "Tungsten"), SeenCorpseSpot(listOf(-180.5, 14.215, -188.5))))
        val body = Json.encodeToJsonElement(MineshaftVisitPayload.serializer(), v).jsonObject
        assertEquals("RUBY_C", body.getValue("layout").jsonPrimitive.content)
        val spots = body.getValue("corpseSpots").jsonArray
        assertEquals(2, spots.size)
        assertEquals("""{"pos":[-161.333,10.248,-154.467],"type":"Tungsten"}""", spots[0].toString())
        assertEquals("""{"pos":[-180.5,14.215,-188.5]}""", spots[1].toString(), "no type key when unknown")
        assertEquals(v, MineshaftOutbox.decodeFile(Json.encodeToString(OutboxFile.serializer(), OutboxFile(MineshaftOutbox.SCHEMA_VERSION, listOf(v)))).visits.single())
    }

    @Test
    fun aLayoutTheBackendWouldRejectIsLeftOut() {
        assertEquals("RUBY_C", visitLayout("RUBY_C"))
        assertEquals("LITT_L", visitLayout("LITT_L"))
        assertNull(visitLayout(null))
        assertNull(visitLayout(""))
        assertNull(visitLayout("RUBY C"))
        assertNull(visitLayout("A".repeat(25)))
    }

    @Test
    fun atMostTwelveDistinctStandsWithinTheBackendsBounds() {
        val seen = ArrayList<CorpseSighting>()
        for (i in 0 until 15) seen += CorpseSighting(i, -100.0 - 2 * i, 10.0, -150.0, "Lapis")
        seen.add(1, CorpseSighting(100, -100.2, 10.1, -150.1, "Lapis")) // the first stand again, re-sent with a new entity id
        val out = CorpseSightings.forUpload(seen)
        assertEquals(CorpseSightings.MAX_UPLOAD, out.size)
        assertEquals(listOf(-100.0, 10.0, -150.0), out[0].pos)
        assertEquals(listOf(-102.0, 10.0, -150.0), out[1].pos)
        val bad = listOf(
            CorpseSighting(1, Double.NaN, 10.0, -150.0, "Lapis"),
            CorpseSighting(2, -1025.0, 10.0, -150.0, "Lapis"),
            CorpseSighting(3, -100.0, 321.0, -150.0, "Lapis"),
            CorpseSighting(4, -100.0, -65.0, -150.0, "Lapis"),
            CorpseSighting(5, -100.0, 10.0, -150.0, "Lapis Lazuli"),
        )
        val kept = CorpseSightings.forUpload(bad)
        assertEquals(1, kept.size)
        assertNull(kept[0].type, "a type of the wrong shape is left out, the stand kept")
    }
}
