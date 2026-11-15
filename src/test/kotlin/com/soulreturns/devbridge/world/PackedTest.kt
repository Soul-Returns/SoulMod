package com.soulreturns.devbridge.world

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The section encoding of the world capture format (WorldFormat.kt); tools/devbridge/worldfile.py and the plugin read it. */
class PackedTest {
    private val scratch = Packed.Scratch()

    private fun ids(palette: Int, seed: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(WorldFormat.BLOCKS) { rnd.nextInt(palette) * 3 + 7 }
    }

    @Test
    fun uniformSectionNeedsNoData() {
        val p = Packed.encode(IntArray(WorldFormat.BLOCKS) { 42 }, scratch)
        assertEquals(0, p.bits)
        assertEquals(0, p.longs.size)
        assertContentEquals(IntArray(WorldFormat.BLOCKS) { 42 }, p.decode())
        assertEquals(42, p.at(1234))
    }

    @Test
    fun roundTripsEveryPaletteSizeBoundary() {
        for (n in listOf(2, 3, 4, 5, 8, 9, 16, 17, 31, 32, 33, 255, 256, 257, 1000, 4096)) {
            val src = ids(n, n)
            val p = Packed.encode(src, scratch)
            assertContentEquals(src, p.decode(), "palette $n")
            assertTrue(p.bits == maxOf(1, 32 - Integer.numberOfLeadingZeros(p.palette.size - 1)), "bits for ${p.palette.size} entries")
            for (i in listOf(0, 1, 15, 16, 255, 256, 4095)) assertEquals(src[i], p.at(i))
        }
    }

    @Test
    fun entriesNeverSpanTwoLongs() {
        val p = Packed.encode(ids(5, 1), scratch)
        assertEquals(3, p.bits)
        assertEquals((WorldFormat.BLOCKS + 64 / 3 - 1) / (64 / 3), p.longs.size)
    }

    @Test
    fun scratchSurvivesLargeIdsAndReuse() {
        val a = IntArray(WorldFormat.BLOCKS) { if (it % 2 == 0) 100_000 else 3 }
        val b = IntArray(WorldFormat.BLOCKS) { it % 3 }
        assertContentEquals(a, Packed.encode(a, scratch).decode())
        assertContentEquals(b, Packed.encode(b, scratch).decode())
        assertContentEquals(a, Packed.encode(a, scratch).decode())
    }
}
