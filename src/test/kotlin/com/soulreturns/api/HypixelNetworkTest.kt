package com.soulreturns.api

import com.soulreturns.core.events.Events
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HypixelNetworkTest {
    @AfterTest
    fun clear() = HypixelNetwork.reset()

    @Test
    fun alphaAddressesIgnoreCasePortAndRootDot() {
        assertTrue(HypixelNetwork.isAlphaAddress("ALPHA.hypixel.net:25565"))
        assertTrue(HypixelNetwork.isAlphaAddress("alpha.hypixel.net."))
        assertTrue(HypixelNetwork.isAlphaAddress("alpha.hypixel.net.:25565"))
        assertTrue(HypixelNetwork.isAlphaAddress("  Alpha.Hypixel.Net  "))
        assertTrue(HypixelNetwork.isAlphaAddress("eu.alpha.hypixel.net"))
    }

    @Test
    fun otherAddressesAreNotAlpha() {
        assertFalse(HypixelNetwork.isAlphaAddress("mc.hypixel.net"))
        assertFalse(HypixelNetwork.isAlphaAddress("hypixel.net"))
        assertFalse(HypixelNetwork.isAlphaAddress("notalpha.hypixel.net"))
        assertFalse(HypixelNetwork.isAlphaAddress("notalpha.hypixel.net.evil.com"))
        assertFalse(HypixelNetwork.isAlphaAddress("alpha.hypixel.net.evil.com"))
        assertFalse(HypixelNetwork.isAlphaAddress("[::1]:25565"))
        assertFalse(HypixelNetwork.isAlphaAddress(""))
        assertFalse(HypixelNetwork.isAlphaAddress(null))
    }

    @Test
    fun hypixelAddressesMatchOnlyTheDomain() {
        assertTrue(HypixelNetwork.isHypixelAddress("mc.hypixel.net"))
        assertTrue(HypixelNetwork.isHypixelAddress("HYPIXEL.NET:25565"))
        assertFalse(HypixelNetwork.isHypixelAddress("hypixel.net.evil.com"))
        assertFalse(HypixelNetwork.isHypixelAddress("nothypixel.net"))
        assertNull(HypixelNetwork.host(":25565"))
    }

    @Test
    fun eitherSignalMeansAlpha() {
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.classify(HypixelNetwork.NONE, "mc.hypixel.net", "BETA"))
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.classify(HypixelNetwork.NONE, "mc.hypixel.net", "TEST"))
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.classify(HypixelNetwork.NONE, null, "BETA"))
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.classify(HypixelNetwork.NONE, "alpha.hypixel.net", "PRODUCTION"))
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.classify(HypixelNetwork.NONE, "alpha.hypixel.net", null))
    }

    @Test
    fun productionNeedsAHypixelSignalAndNoAlphaOne() {
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.classify(HypixelNetwork.NONE, "mc.hypixel.net", null))
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.classify(HypixelNetwork.NONE, "mc.hypixel.net", "PRODUCTION"))
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.classify(HypixelNetwork.NONE, "203.0.113.7", "PRODUCTION"))
        assertEquals(HypixelNetwork.NONE, HypixelNetwork.classify(HypixelNetwork.NONE, "play.example.org", null))
        assertEquals(HypixelNetwork.NONE, HypixelNetwork.classify(HypixelNetwork.NONE, null, null))
    }

    @Test
    fun alphaStaysForTheConnectionAndClearsOnDisconnect() {
        val detected = AtomicInteger()
        Events.subscribe<HypixelNetwork.AlphaDetected> { detected.incrementAndGet() }

        HypixelNetwork.onEnvironment("PRODUCTION")
        HypixelNetwork.onAddress("alpha.hypixel.net:25565")
        val firstConnection = HypixelNetwork.alphaConnection
        assertTrue(HypixelNetwork.isAlpha)

        // A later production-looking signal on the same connection must not reopen the gate.
        HypixelNetwork.onEnvironment("PRODUCTION")
        HypixelNetwork.onAddress("mc.hypixel.net")
        assertTrue(HypixelNetwork.isAlpha)
        assertEquals(1, detected.get())

        HypixelNetwork.reset()
        assertEquals(HypixelNetwork.NONE, HypixelNetwork.current)
        HypixelNetwork.onAddress("mc.hypixel.net")
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.current)

        HypixelNetwork.reset()
        HypixelNetwork.onEnvironment("BETA")
        assertTrue(HypixelNetwork.isAlpha)
        assertEquals(2, detected.get())
        assertTrue(HypixelNetwork.alphaConnection > firstConnection)
    }
}
