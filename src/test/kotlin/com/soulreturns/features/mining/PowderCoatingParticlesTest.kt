package com.soulreturns.features.mining

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Packets from the Dwarven Mines captures of 2026-11-15 (`docs/research/commissions-2026-11-15.md`). */
class PowderCoatingParticlesTest {
    private fun coating(
        rgb: Int,
        x: Float = 1f,
        y: Float = 1f,
        z: Float = 1f,
        count: Int = 0,
        speed: Float = 1f,
        scale: Float = 1f,
        flags: Boolean = true,
    ) = PowderCoatingParticles.isCoatingDust(rgb, scale, count, x, y, z, speed, flags, flags)

    @Test
    fun capturedSignatures() {
        assertTrue(coating(0xFFFFFF))
        assertTrue(coating(0xFF9900, 1f, 0.6f, 0f))
    }

    @Test
    fun otherDustIsKept() {
        assertFalse(coating(0xFF9900))
        assertFalse(coating(0xFFFFFF, 1f, 0.6f, 0f))
        assertFalse(coating(0xFF0000))
        assertFalse(coating(0xFFFFFF, count = 1))
        assertFalse(coating(0xFFFFFF, 0.5f, 0.5f, 0.5f))
        assertFalse(coating(0xFFFFFF, speed = 0f))
        assertFalse(coating(0xFFFFFF, scale = 2f))
        assertFalse(coating(0xFFFFFF, flags = false))
    }

    // Wearer at (-121.7252, 171.0, -71.0666), a standing player's box is 0.6 wide and 1.8 tall.
    private fun near(x: Double, y: Double, z: Double) =
        PowderCoatingParticles.nearBody(-122.0252, 171.0, -71.3666, -121.4252, 172.8, -70.7666, x, y, z)

    @Test
    fun capturedPositionsAreNearTheWearer() {
        assertTrue(near(-121.39676666259766, 172.0981903076172, -70.77555084228516))
        assertTrue(near(-122.0475082397461, 171.3251190185547, -71.05909729003906))
        assertTrue(near(-122.11514282226562, 171.43800354003906, -71.12774658203125))
        // 0.53 out horizontally, the farthest in the second capture.
        assertTrue(near(-121.7252 + 0.53, 171.6, -71.0666))
    }

    @Test
    fun farDustIsKept() {
        assertFalse(near(-125.0, 172.0, -71.0))
        assertFalse(near(-121.7, 175.0, -71.0))
        assertFalse(near(-121.7, 169.0, -71.0))
    }
}
