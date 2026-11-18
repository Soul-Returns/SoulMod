package com.soulreturns.features.mining.mineshaft

import com.soulreturns.util.MojangUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CorpseSharePayloadTest {
    private val self = UUID.fromString("dbef4c0c-71b4-480a-a790-0b7ec9ef1295")
    private val mate = UUID.fromString("0f7a1c2e-3b4d-4e5f-8a9b-0c1d2e3f4a5b")
    private val stranger = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val party = setOf(self, mate)
    private val topic = "user/${MojangUuid.undashed(self)}"

    private fun stamped(message: JsonObject, sender: UUID = mate, server: String = "mini117BA") =
        JsonObject(message + mapOf("uuid" to JsonPrimitive(MojangUuid.undashed(sender)), "serverId" to JsonPrimitive(server)))

    private fun parse(data: JsonObject, layout: String = "AMBE_1", server: String? = "mini117BA") = CorpseSharePayload.parse(topic, data, self, server, layout, party)

    private fun drop(p: CorpseSharePayload.Parsed) = assertIs<CorpseSharePayload.Parsed.Drop>(p).reason

    private val corpses =
        listOf(
            SharedCorpse(CorpseSpot(-172, 16, -186), doubleArrayOf(-171.81, 16.44, -185.37), "Lapis"),
            SharedCorpse(null, doubleArrayOf(-150.3, 12.0, -170.6), null),
        )

    @Test
    fun stateRoundTrips() {
        val sent = CorpseSharePayload.state("AMBE_1", corpses)
        val ok = assertIs<CorpseSharePayload.Parsed.Ok>(parse(stamped(sent)))
        val state = assertIs<CorpseSharePayload.State>(ok.message)
        assertEquals(mate, state.sender)
        assertEquals(corpses, state.corpses)
        assertEquals("""{"v":1,"kind":"hello","layout":"AMBE_1"}""", CorpseSharePayload.hello("AMBE_1").toString())
        assertIs<CorpseSharePayload.Hello>(assertIs<CorpseSharePayload.Parsed.Ok>(parse(stamped(CorpseSharePayload.hello("AMBE_1")))).message)
    }

    @Test
    fun bodyGoesToThePartyButNotThePlayer() {
        val body = CorpseSharePayload.body("mini117BA", listOf(self, mate, mate), self, CorpseSharePayload.hello("AMBE_1"))!!
        assertEquals(listOf(MojangUuid.undashed(mate)), body["recipients"]!!.jsonArray.map { (it as JsonPrimitive).content })
        assertEquals("mini117BA", (body["serverId"] as JsonPrimitive).content)
        assertNull(CorpseSharePayload.body("mini117BA", listOf(self), self, CorpseSharePayload.hello("AMBE_1")), "alone: nothing to send")
        val many = (1..15).map { UUID.randomUUID() } + self
        assertEquals(10, CorpseSharePayload.body("mini117BA", many, self, CorpseSharePayload.hello("AMBE_1"))!!["recipients"]!!.jsonArray.size)
    }

    @Test
    fun acceptRulesDropWithAReason() {
        val good = CorpseSharePayload.state("AMBE_1", corpses)
        assertEquals("sender not in party", drop(parse(stamped(good, sender = stranger))))
        assertEquals("own echo", drop(parse(stamped(good, sender = self))))
        assertEquals("other server", drop(parse(stamped(good, server = "mini1A"))))
        assertEquals("other layout", drop(parse(stamped(good), layout = "JADE_1")))
        assertEquals("not in a mineshaft", drop(parse(stamped(good), server = null)))
        assertEquals("topic", drop(CorpseSharePayload.parse("user/${MojangUuid.undashed(mate)}", stamped(good), self, "mini117BA", "AMBE_1", party)))
        assertEquals("no sender", drop(parse(JsonObject(good + mapOf("serverId" to JsonPrimitive("mini117BA"))))))
        assertEquals("version", drop(parse(stamped(JsonObject(good + mapOf("v" to JsonPrimitive(2)))))))
        assertEquals("kind", drop(parse(stamped(JsonObject(good + mapOf("kind" to JsonPrimitive("loot")))))))
    }

    @Test
    fun malformedOrOversizedStatesAreDropped() {
        val nine = (0 until 9).map { SharedCorpse(null, doubleArrayOf(-150.0 - it, 12.0, -170.0), "Lapis") }
        val oversized = Json.parseToJsonElement(
            CorpseSharePayload.state("AMBE_1", nine.take(8)).toString().replace("]}", ",{\"spot\":null,\"pos\":[-140.0,12.0,-170.0],\"type\":null}]}"),
        ).jsonObject
        assertEquals("too many corpses", drop(parse(stamped(oversized))))
        fun withCorpse(json: String) = stamped(Json.parseToJsonElement("""{"v":1,"kind":"state","layout":"AMBE_1","corpses":[$json]}""").jsonObject)
        assertEquals("malformed corpse", drop(parse(withCorpse("""{"spot":null,"pos":[-150.0,12.0],"type":null}"""))))
        assertEquals("malformed corpse", drop(parse(withCorpse("""{"spot":null,"pos":[-150.0,12.0,9000.0],"type":null}"""))), "outside the mineshaft")
        assertEquals("malformed corpse", drop(parse(withCorpse("""{"spot":null,"pos":["a",12.0,-170.0],"type":null}"""))))
        assertEquals("malformed corpse", drop(parse(withCorpse("""{"spot":[1,2],"pos":[-150.0,12.0,-170.0],"type":null}"""))))
        assertEquals("malformed corpse", drop(parse(withCorpse("""{"spot":null,"pos":[-150.0,12.0,-170.0],"type":"Lapis Corpse!"}"""))))
        assertEquals("malformed corpse", drop(parse(withCorpse("""{"spot":null,"pos":[-150.0,12.0,-170.0],"type":"AVeryLongTypeNameIndeed"}"""))))
        assertEquals("malformed corpses", drop(parse(stamped(buildJsonObject {
            put("v", JsonPrimitive(1)); put("kind", JsonPrimitive("state")); put("layout", JsonPrimitive("AMBE_1")); put("corpses", JsonPrimitive("x"))
        }))))
        assertEquals("malformed layout", drop(parse(stamped(buildJsonObject { put("v", JsonPrimitive(1)); put("kind", JsonPrimitive("hello")) }))))
    }

    @Test
    fun aReceivedCorpseMergesWithMine() {
        val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())
        val s = CorpseLocatorSolver(data.spots("AMBE_1")!!)
        val book = CorpseBook()
        val lapis = CorpseSpot(-172, 16, -186)
        val umber = CorpseSpot(-192, 25, -161)
        val third = CorpseSpot(-123, 23, -160)
        // Mine: the Umber seen; the third looted.
        assertEquals(umber, s.locate(-191.5, 25.9, -160.5, "Umber"))
        book.see(umber, "Umber")
        s.loot(-122.3, 25.0, -160.7, "LAPIS")
        val touched =
            book.receive(
                s, "Luckyking1105",
                listOf(
                    SharedCorpse(lapis, doubleArrayOf(-171.8, 16.4, -185.4), "Lapis"),
                    SharedCorpse(umber, doubleArrayOf(-191.0, 25.0, -160.0), "Lapis"),
                    SharedCorpse(third, doubleArrayOf(-122.3, 23.5, -159.2), "Lapis"),
                    SharedCorpse(null, doubleArrayOf(-150.3, 12.0, -170.6), null),
                ),
            )
        // Their Lapis is shown at their position with their type.
        assertEquals("Lapis", book.typeOf(lapis))
        assertEquals(listOf(-171.8, 16.4, -185.4), s.position(lapis)!!.toList())
        // My sighting keeps its type and position.
        assertEquals("Umber", book.typeOf(umber))
        assertEquals(listOf(-191.5, 25.9, -160.5), s.position(umber)!!.toList())
        // What I looted stays looted.
        assertNull(s.shown().firstOrNull { it == third })
        // A corpse outside the data becomes an extra, without a type.
        val extra = s.extras.single()
        assertEquals(setOf(lapis, umber, extra), s.shown().toSet())
        assertNull(book.typeOf(extra))
        assertEquals(listOf(lapis, umber, extra), touched)
        assertEquals("Luckyking1105", book.sharedBy[lapis])
        // A later sighting of a different type overrules theirs (and says so).
        assertEquals("Lapis", book.see(lapis, "Umber"))
        assertEquals("Umber", book.typeOf(lapis))
    }
}
