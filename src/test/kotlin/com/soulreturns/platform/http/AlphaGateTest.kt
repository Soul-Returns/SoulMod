package com.soulreturns.platform.http

import com.soulreturns.api.HypixelNetwork
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlphaGateTest {
    private lateinit var server: HttpServer
    private val hits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val body = JsonObject(mapOf("serverId" to JsonPrimitive("mini62V")))

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits += "${exchange.requestMethod} ${exchange.requestURI}"
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        System.setProperty("soul.backendUrl", "http://127.0.0.1:${server.address.port}")
    }

    @AfterTest
    fun stop() {
        HypixelNetwork.reset()
        System.clearProperty("soul.backendUrl")
        server.stop(0)
    }

    private fun post(endpoint: String) = runBlocking {
        BackendClient.post(endpoint, body, JsonObject.serializer(), intent = "test", authenticated = false)
    }

    private fun get(endpoint: String) = runBlocking {
        BackendClient.get(endpoint, JsonObject.serializer(), intent = "test", bypassCache = true, authenticated = false)
    }

    @Test
    fun alphaRefusesAnUploadWithoutTouchingTheNetwork() {
        HypixelNetwork.onAddress("alpha.hypixel.net")
        val before = BackendClient.status()

        val result = post("/dungeon/sync")

        val failure = assertIs<BackendResult.Failure>(result)
        assertEquals(BackendResult.Failure.ALPHA_BLOCKED, failure.status)
        assertEquals(emptyList(), hits.toList())
        val after = BackendClient.status()
        assertEquals(before.requests, after.requests)
        assertEquals(before.alphaRefused + 1, after.alphaRefused)
        assertFalse("/dungeon/sync" in after.cooldowns)
        assertFalse("/dungeon/sync" in after.lastErrors)
    }

    @Test
    fun alphaRefusesAGameKeyedReadToo() {
        HypixelNetwork.onEnvironment("BETA")

        val result = get("/mineshaft/stats?profile=0123456789abcdef0123456789abcdef")

        assertEquals(BackendResult.Failure.ALPHA_BLOCKED, assertIs<BackendResult.Failure>(result).status)
        assertEquals(emptyList(), hits.toList())
    }

    @Test
    fun alphaStillSendsAllowlistedRequests() {
        HypixelNetwork.onAddress("alpha.hypixel.net")

        assertIs<BackendResult.Ok<*>>(post("/presence/state"))
        assertIs<BackendResult.Ok<*>>(get("/items"))

        assertEquals(listOf("POST /presence/state", "GET /items"), hits.toList())
    }

    @Test
    fun productionSendsWhatAlphaRefuses() {
        HypixelNetwork.onAddress("mc.hypixel.net")

        assertIs<BackendResult.Ok<*>>(post("/dungeon/sync"))

        assertEquals(listOf("POST /dungeon/sync"), hits.toList())
    }

    @Test
    fun allowlistMatchesWholePathsOnly() {
        assertTrue(AlphaGate.allows("GET", "/skyblock/profiles?uuid=0123456789abcdef0123456789abcdef"))
        assertTrue(AlphaGate.allows("GET", "/player/0123456789abcdef0123456789abcdef/soul-stats"))
        assertTrue(AlphaGate.allows("GET", "/mod/update/0.2.1/jar"))
        assertTrue(AlphaGate.allows("GET", "/market/values?since=42"))

        // A path under an allowed one is a different endpoint: island chests carry the profile id.
        assertFalse(AlphaGate.allows("GET", "/skyblock/profiles/0123456789abcdef0123456789abcdef/island-chests?format=2"))
        assertFalse(AlphaGate.allows("POST", "/skyblock/profiles/0123456789abcdef0123456789abcdef/island-chests"))
        assertFalse(AlphaGate.allows("GET", "/player/0123456789abcdef0123456789abcdef/other"))
        assertFalse(AlphaGate.allows("POST", "/mineshaft/visit"))
        assertFalse(AlphaGate.allows("GET", "/realtime/token"))
        // Allowed as a read is not allowed as a write.
        assertFalse(AlphaGate.allows("POST", "/items"))
        assertFalse(AlphaGate.allows("GET", "/presence/state"))
    }
}
