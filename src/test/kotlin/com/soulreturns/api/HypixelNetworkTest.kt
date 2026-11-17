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

    private val productionSidebar = listOf(
        "§707/11/26 §8m61A",
        "",
        "Late Autumn 9th",
        "§75:20pm §e☀",
        "§7⏣ §aYour Island",
        "",
        "Purse: §61,234",
        "",
        "§ewww.hypixel.net",
    )

    @Test
    fun sidebarFooterNamesTheNetwork() {
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.sidebarNetwork(productionSidebar))
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.sidebarNetwork(productionSidebar.dropLast(1) + "§ealpha.hypixel.net"))
    }

    @Test
    fun sidebarFooterIgnoresColorCodesSpacingAndCase() {
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.sidebarNetwork(listOf("§e§lalpha§r.§ehypixel.net§r")))
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.sidebarNetwork(listOf("  §eAlpha.Hypixel.NET \u00a0")))
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.sidebarNetwork(listOf("§e www.hypixel.net\u200b", "", "  ")))
    }

    @Test
    fun noFooterIsNoSignal() {
        assertNull(HypixelNetwork.sidebarNetwork(emptyList()))
        assertNull(HypixelNetwork.sidebarNetwork(productionSidebar.dropLast(1)))
        assertNull(HypixelNetwork.sidebarNetwork(listOf("§estore.hypixel.net", "§ealpha.hypixel.net.evil", "Visit www.hypixel.net")))

        HypixelNetwork.onEnvironment("PRODUCTION")
        HypixelNetwork.onSidebar(productionSidebar.dropLast(1))
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.current)
        assertNull(HypixelNetwork.dataset, "a Hypixel connection stays unresolved until a footer names it")
    }

    @Test
    fun productionFooterConfirmsTheDatasetWhateverTheAddress() {
        val confirmed = AtomicInteger()
        Events.subscribe<HypixelNetwork.ProductionConfirmed> { confirmed.incrementAndGet() }
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.dataset, "off Hypixel is production")

        for (address in listOf("hypixel.net", "mc.hypixel.net", "eu.hypixel.net", "203.0.113.7")) {
            HypixelNetwork.reset()
            HypixelNetwork.onAddress(address)
            HypixelNetwork.onEnvironment("PRODUCTION")
            assertNull(HypixelNetwork.dataset, address)
            HypixelNetwork.onSidebar(productionSidebar)
            HypixelNetwork.onSidebar(productionSidebar)
            assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.dataset, address)
        }
        assertEquals(4, confirmed.get())
    }

    @Test
    fun alphaFooterLatchesAndNeverUnlatches() {
        HypixelNetwork.onAddress("mc.hypixel.net")
        HypixelNetwork.onEnvironment("PRODUCTION")
        HypixelNetwork.onSidebar(productionSidebar.dropLast(1) + "§ealpha.hypixel.net")
        assertTrue(HypixelNetwork.isAlpha)
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.dataset)

        HypixelNetwork.onSidebar(productionSidebar)
        HypixelNetwork.onSidebar(emptyList())
        HypixelNetwork.onAddress("mc.hypixel.net")
        assertTrue(HypixelNetwork.isAlpha)
        assertEquals(HypixelNetwork.ALPHA, HypixelNetwork.dataset)

        HypixelNetwork.reset()
        assertEquals(HypixelNetwork.PRODUCTION, HypixelNetwork.dataset)
    }
}
