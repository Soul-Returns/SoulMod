package com.soulreturns.features.chat

import com.soulreturns.config.AutoMeowConfig
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.gui.ConfigRegistry
import com.soulreturns.config.gui.menu.MenuSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Auto meow's rules (S-70): channel detection, the exact match, the own-line rule, the global cooldown, the hidden card. */
class AutoMeowTest {
    private var now = 0L
    private val responder = MeowResponder { now }
    private val all: (MeowChannel) -> Boolean = { true }

    private fun answer(line: String, enabled: (MeowChannel) -> Boolean = all, inParty: Boolean = true) =
        responder.answer(line, "SoulTester", enabled, inParty)

    @Test
    fun channelsFromHypixelPrefixes() {
        assertEquals(MeowLine(MeowChannel.COOP, "Kittyy"), MeowResponder.parse("Co-op > [MVP+] Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.PARTY, "Kittyy"), MeowResponder.parse("Party > [MVP+] Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.GUILD, "Kittyy"), MeowResponder.parse("Guild > [VIP] Kittyy [Member]: meow"))
        assertEquals(MeowLine(MeowChannel.GUILD, "Kittyy"), MeowResponder.parse("Guild > Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.PRIVATE, "Kittyy"), MeowResponder.parse("From [MVP++] Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.ALL, "Kittyy"), MeowResponder.parse("[312] ⚒ [YOUTUBE] Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.ALL, "Kittyy"), MeowResponder.parse("[12] Kittyy ♲: meow"))
        assertEquals(MeowLine(MeowChannel.ALL, "Kittyy"), MeowResponder.parse("Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.PRIVATE, "Kittyy", outgoing = true), MeowResponder.parse("To [MVP+] Kittyy: meow"))
        // Not chat a meow can come from, or not a meow.
        assertNull(MeowResponder.parse("Officer > [MVP+] Kittyy: meow"))
        assertNull(MeowResponder.parse("[NPC] Kat: meow"))
        assertNull(MeowResponder.parse("Guild > [VIP] Bridge [Bot]: Kittyy: meow"))
        assertEquals(MeowLine(MeowChannel.PARTY, "Kittyy"), MeowResponder.parse("Party > [MVP+] Kittyy:   MeOw  "))
        for (body in listOf("meow!", "meow meow", "meoww", "mew", "")) assertNull(MeowResponder.parse("Party > [MVP+] Kittyy: $body"), body)
    }

    @Test
    fun oneAnswerThenTwentySecondsOfSilenceEverywhere() {
        assertEquals(MeowChannel.PARTY, answer("Party > [MVP+] Kittyy: meow"))
        // The answer is decided (and sent) at 0; the other player again, in every channel: all ignored.
        now = 300
        assertNull(answer("Party > [MVP+] Kittyy: meow"))
        assertNull(answer("Guild > Puppy [Member]: meow"))
        now = MeowResponder.COOLDOWN_MS - 1
        assertNull(answer("From [VIP] Puppy: meow"))
        now = MeowResponder.COOLDOWN_MS
        assertEquals(MeowChannel.PRIVATE, answer("From [VIP] Puppy: meow"))
    }

    @Test
    fun soulsOwnEchoRestartsTheCooldown() {
        assertEquals(MeowChannel.PARTY, answer("Party > [MVP+] Kittyy: meow"))
        now = 300
        assertNull(answer("Party > [VIP] SoulTester: meow"))
        now = MeowResponder.COOLDOWN_MS + 299
        assertNull(answer("Party > [MVP+] Kittyy: meow"))
        now = MeowResponder.COOLDOWN_MS + 300
        assertEquals(MeowChannel.PARTY, answer("Party > [MVP+] Kittyy: meow"))
    }

    @Test
    fun aMeowTypedByHandIsNotAnsweredAndSilencesTheReply() {
        assertNull(answer("Party > [VIP] SoulTester: meow"))
        now = 2_000
        assertNull(answer("Party > [MVP+] Kittyy: meow"))
        // In a channel whose answers are switched off it counts the same.
        now = 100_000
        assertNull(answer("Guild > SoulTester [Member]: meow", enabled = { it != MeowChannel.GUILD }))
        now = 101_000
        assertNull(answer("Co-op > Kittyy: meow"))
        // Once the cooldown is over, a meow is answered again.
        now = 100_000 + MeowResponder.COOLDOWN_MS
        assertEquals(MeowChannel.COOP, answer("Co-op > Kittyy: meow"))
    }

    @Test
    fun ourPrivateMessageSilencesTheirMeowBack() {
        assertNull(answer("To [MVP+] Kittyy: meow"))
        now = 1_000
        assertNull(answer("From [MVP+] Kittyy: meow"))
        now = MeowResponder.COOLDOWN_MS
        assertEquals(MeowChannel.PRIVATE, answer("From [MVP+] Kittyy: meow"))
    }

    @Test
    fun ownNonMeowLinesSwitchedOffChannelsAndNoPartyNeverStartTheCooldown() {
        assertNull(answer("Party > [VIP] SoulTester: meow?"))
        assertNull(answer("To [MVP+] Kittyy: hi"))
        assertNull(answer("Guild > Kittyy: meow", enabled = { it != MeowChannel.GUILD }))
        assertNull(answer("Party > Kittyy: meow", inParty = false))
        assertEquals(MeowChannel.COOP, answer("Co-op > Kittyy: meow"))
    }

    /** Uses [ConfigManager.update], which saves nothing in tests (no config file was loaded). */
    @Test
    fun theCardExistsNowhereUntilFound() {
        val saved = ConfigManager.config
        try {
            ConfigManager.update { SoulConfig() }
            assertTrue(ConfigRegistry.cards.none { it.id == "meow" })
            assertNull(ConfigRegistry.card("meow"))
            val locked = MenuSearch.index(ConfigRegistry.cards, ConfigRegistry.settingsTabs.filter { it.id != "dev" }, ConfigRegistry.optionSynonyms)
            for (q in listOf("auto", "chat", "meow", "secret", "cat")) {
                assertTrue(MenuSearch.results(locked, q, emptyList()).flatMap { it.items }.none { it.card?.id == "meow" }, q)
            }
            // The search door: exactly "meow" (case and outer spaces aside) unlocks once and turns the switch on.
            assertFalse(AutoMeow.unlockBySearch("meow!"))
            assertFalse(AutoMeow.unlockBySearch("meo"))
            assertTrue(AutoMeow.unlockBySearch(" Meow "))
            assertEquals(AutoMeowConfig(unlocked = true, enabled = true), ConfigManager.config.meow)
            assertFalse(AutoMeow.unlockBySearch("meow"))
            assertEquals("meow", ConfigRegistry.cardsIn("everyday", "controls").first().id)
            val found = MenuSearch.index(ConfigRegistry.cards, ConfigRegistry.settingsTabs.filter { it.id != "dev" }, ConfigRegistry.optionSynonyms)
            assertEquals("Auto meow", MenuSearch.results(found, "meow", emptyList()).first().items.first().name)
        } finally {
            ConfigManager.update { saved }
        }
    }
}
