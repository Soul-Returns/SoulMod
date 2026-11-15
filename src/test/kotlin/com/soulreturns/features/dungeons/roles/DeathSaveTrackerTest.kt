package com.soulreturns.features.dungeons.roles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeathSaveTrackerTest {
    private val spiritProc = "Second Wind Activated! Your Spirit Mask saved your life!"
    private val bonzoProc = "Your \uE068 Bonzo's Mask saved your life!"

    private fun spirit(t: DeathSaveTracker, active: Boolean = true, identity: String? = "STARRED_SPIRIT_MASK:own-uuid", duration: Long? = 600) =
        t.observe(DeathSaveTracker.SPIRIT, active, identity, duration)

    @Test
    fun actualCapturedLoreIsADurationNotInitialReadiness() {
        // B recording, tick 3482: these are the masks' own static lore, not remaining cooldown fields.
        assertEquals(600L, DeathSaveTracker.advertisedCooldown("§r§8Cooldown: §r§a30s"))
        assertEquals(3600L, DeathSaveTracker.advertisedCooldown("§r§8Cooldown: §r§a180s"))
        assertNull(DeathSaveTracker.advertisedCooldown("Cooldown: unknown"))
        val t = DeathSaveTracker()
        spirit(t)
        t.advance(100, true)
        assertNull(t.spirit.available, "equipped and owned never implies ready")
        assertEquals(DeathSaveTracker.UNKNOWN, t.status())
    }

    @Test
    fun aVerifiedOwnProcAndTheMatchingItemDurationRecoverAtTheExactServerTickBoundary() {
        val t = DeathSaveTracker()
        spirit(t)
        t.advance(100, true)
        assertTrue(t.chat(spiritProc, 100))
        assertEquals(false, t.spirit.available)
        t.advance(699, true)
        assertEquals(false, t.spirit.available)
        t.advance(700, true)
        assertEquals(true, t.spirit.available)
        assertEquals(DeathSaveTracker.USABLE, t.status())
    }

    @Test
    fun recoveredButUnequippedMaskRequiresAManualSwap() {
        val t = DeathSaveTracker()
        spirit(t)
        t.advance(100, true)
        t.chat(spiritProc, 100)
        spirit(t, active = false)
        t.advance(700, true)
        assertEquals(true, t.spirit.available)
        assertEquals(DeathSaveTracker.SWAP, t.status())
        spirit(t, active = true)
        assertEquals(DeathSaveTracker.USABLE, t.status())
    }

    @Test
    fun missingOrChangedIdentityOrDurationCannotBorrowAnotherMasksProc() {
        val t = DeathSaveTracker()
        spirit(t, duration = null)
        t.advance(100, true)
        t.chat(spiritProc, 100)
        spirit(t)
        t.advance(700, true)
        assertNull(t.spirit.available, "lore learned afterwards does not prove which item procced")
        t.chat(spiritProc, 700)
        t.advance(1300, true)
        assertEquals(true, t.spirit.available)
        spirit(t, identity = "STARRED_SPIRIT_MASK:different-uuid")
        assertNull(t.spirit.available)
        t.chat(spiritProc, 1300)
        spirit(t, identity = "STARRED_SPIRIT_MASK:different-uuid", duration = 800)
        t.advance(2100, true)
        assertNull(t.spirit.available, "a changed advertised duration invalidates the old anchor")
        spirit(t, identity = null)
        t.chat(spiritProc, 2100)
        t.advance(2700, true)
        assertNull(t.spirit.available)
    }

    @Test
    fun aServerStallNeverTurnsElapsedWallTimeIntoAReadySaveAndClockLossIsPermanentUntilAnotherProc() {
        val t = DeathSaveTracker()
        spirit(t)
        t.advance(100, true)
        t.chat(spiritProc, 100)
        t.advance(699, false)
        assertNull(t.spirit.available)
        t.advance(699, true)
        assertEquals(false, t.spirit.available)
        t.advance(null, false)
        t.advance(900, true)
        assertNull(t.spirit.available, "continuity was lost; advancing again cannot recover its anchor")
        t.chat(spiritProc, 900)
        t.advance(1500, true)
        assertEquals(true, t.spirit.available)
        t.advance(1, true)
        assertNull(t.spirit.available, "a backwards/replaced server clock also invalidates the anchor")
        t.reset()
        spirit(t)
        t.advance(10000, true)
        assertNull(t.spirit.available)
        assertNull(t.spirit.consumedAt)
    }

    @Test
    fun bonzoPhoenixAndSpiritAreSeparateAndPartyRelaysCannotConsumeThem() {
        val t = DeathSaveTracker()
        spirit(t)
        t.observe(DeathSaveTracker.BONZO, true, "STARRED_BONZO_MASK:bonzo-uuid", 3600)
        t.observe(DeathSaveTracker.PHOENIX, true, null, null)
        t.advance(100, true)
        assertFalse(t.chat("Party > Friend: $spiritProc", 100))
        assertFalse(t.chat("Party > Friend: $bonzoProc", 100))
        assertFalse(t.chat("Party > Friend: Your Phoenix Pet saved you from certain death!", 100))
        assertNull(t.spirit.consumedAt)
        assertNull(t.bonzo.consumedAt)
        assertNull(t.phoenix.consumedAt)
        assertTrue(t.chat(bonzoProc, 100))
        assertNull(t.spirit.available)
        assertEquals(false, t.bonzo.available)
        t.chat("Your Phoenix Pet saved you from certain death!", 100)
        t.advance(3700, true)
        assertEquals(true, t.bonzo.available)
        assertNull(t.phoenix.available, "a mask duration is never evidence of Phoenix recovery")
        assertEquals(100L, t.phoenix.consumedAt)
    }

    @Test
    fun procWithoutAnEquippedMatchingSourceOrAFreshClockIsNotAReadinessAnchor() {
        val t = DeathSaveTracker()
        spirit(t, active = false)
        t.advance(100, true)
        t.chat(spiritProc, 100)
        spirit(t, active = true)
        t.advance(700, true)
        assertNull(t.spirit.available)
        t.advance(800, false)
        t.chat(spiritProc, 800)
        t.advance(1400, true)
        assertNull(t.spirit.available)
    }
}
