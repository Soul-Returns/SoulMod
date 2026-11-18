package com.soulreturns.features.mining.mineshaft

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CorpseSpotMemoryTest {
    private val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())

    @Test
    fun aStandOnAListedSpotIsNotLearned() {
        val empty = LearnedCorpseSpots()
        assertSame(empty, empty.withSighting("AMBE_1", -171.7869, 16.4411, -185.3481, "Lapis", 1L, data.spots("AMBE_1")))
        // The RUBY_C Tungsten (recording mineshaft-corpses-4) stands on the shared crystal list.
        assertSame(empty, empty.withSighting("RUBY_C", -161.3332, 10.2475, -154.4665, "Tungsten", 1L, data.spots("RUBY_C")))
    }

    @Test
    fun sightingsWithinHalfABlockAreOneSpot() {
        val a = LearnedCorpseSpots().withSighting("LITT_L", -150.3, 12.2, -170.6, null, 1L, null)
        val b = a.withSighting("LITT_L", -150.1, 12.3, -170.4, "Umber", 2L, null)
        assertEquals(1, b.layouts.getValue("LITT_L").size)
        assertEquals(LearnedCorpseSpot(listOf(-150.3, 12.2, -170.6), "Umber", 2, 1L, 2L), b.layouts.getValue("LITT_L")[0])
        val c = b.withSighting("LITT_L", -140.0, 12.0, -170.0, "Lapis", 3L, null)
        assertEquals(2, c.layouts.getValue("LITT_L").size)
    }

    @Test
    fun crystalShaftsShareTheirLearnedSpots() {
        val learned = LearnedCorpseSpots()
            .withSighting("RUBY_C", -130.0, 20.0, -180.0, "Lapis", 1L, data.spots("RUBY_C"))
            .withSighting("ONYX_C", -130.2, 20.1, -180.1, "Lapis", 2L, data.spots("ONYX_C"))
        // Counted on RUBY_C's entry (the same spot), applied to every crystal shaft, not to others.
        assertEquals(setOf("RUBY_C"), learned.layouts.keys)
        assertEquals(2, learned.layouts.getValue("RUBY_C")[0].seen)
        assertEquals(1, learned.forLayout("XXXX_C").size)
        assertTrue(learned.forLayout("AMBE_1").isEmpty())
    }

    @Test
    fun theStoreIsBounded() {
        var learned = LearnedCorpseSpots()
        for (i in 0 until LearnedCorpseSpots.MAX_PER_LAYOUT + 5) learned = learned.withSighting("LITT_L", -100.0 - 2 * i, 10.0, -150.0, null, 1L, null)
        assertEquals(LearnedCorpseSpots.MAX_PER_LAYOUT, learned.layouts.getValue("LITT_L").size)
        for (i in 0 until LearnedCorpseSpots.MAX_LAYOUTS + 5) learned = learned.withSighting("NEW${i}_1", -100.0, 10.0, -150.0, null, 1L, null)
        assertEquals(LearnedCorpseSpots.MAX_LAYOUTS, learned.layouts.size)
    }

    @Test
    fun thePayloadRoundTrips() {
        val learned = LearnedCorpseSpots().withSighting("LITT_L", -150.3456, 12.2, -170.6, "Umber", 5L, null)
        val json = Json.encodeToString(LearnedCorpseSpots.serializer(), learned)
        assertEquals(learned, Json.decodeFromString(LearnedCorpseSpots.serializer(), json))
        assertEquals(listOf(-150.346, 12.2, -170.6), learned.layouts.getValue("LITT_L")[0].pos, "exact stand positions, to the millimeter")
    }

    @Test
    fun theDevOutputIsTheDataFilesForm() {
        val learned = LearnedCorpseSpots()
            .withSighting("LITT_L", -150.3, 12.2, -170.6, null, 1L, null)
            .withSighting("RUBY_C", -130.0, 20.0, -180.0, null, 1L, null)
        assertEquals(
            listOf("{", "  \"crystal\": [[-130,20,-180]],", "  \"layouts\": {", "    \"LITT_L\": [[-151,12,-171]]", "  }", "}"),
            CorpseSpotsDebug.bundleJson(learned.layouts),
        )
        // And it parses as the data file.
        val parsed = CorpseSpotData.parse(CorpseSpotsDebug.bundleJson(learned.layouts).joinToString("\n"))
        assertEquals(listOf(CorpseSpot(-151, 12, -171)), parsed.spots("LITT_L"))
        assertEquals(listOf(CorpseSpot(-130, 20, -180)), parsed.spots("RUBY_C"))
    }
}
