package com.soulreturns.features.dungeons.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real (map marker, true entity position) pairs from the 2026-11-11 M4 recording, at rest. */
class MapMarkerConversionTest {
    private fun world(marker: Int, offsetPx: Int, gapPx: Int, corner: Int) =
        corner + MapPlayerPos.fromMarker(marker, offsetPx, gapPx) * DungeonGeometry.HALF_COMBINED

    private fun check(mx: Int, mz: Int, offX: Int, offZ: Int, gap: Int, trueX: Double, trueZ: Double) {
        val x = world(mx, offX, gap, DungeonGeometry.CORNER_X)
        val z = world(mz, offZ, gap, DungeonGeometry.CORNER_Z)
        // One map pixel is 1.6 blocks at gap 20, so a truncated marker can be ~1 block off either way;
        // the previous +126 conversion was 1.6+ blocks off on every sample.
        assertTrue(abs(x - trueX) < 1.2, "x $x vs $trueX")
        assertTrue(abs(z - trueZ) < 1.2, "z $z vs $trueZ")
    }

    @Test
    fun roomSize16Runs() {
        check(-98, -86, 5, 16, 20, -183.63, -191.76)
        check(-106, -74, 5, 16, 20, -189.84, -181.57)
        check(50, 84, 5, 16, 20, -64.65, -54.67)
    }

    @Test
    fun roomSize18Run() {
        check(-88, -74, 11, 11, 22, -186.97, -176.59)
        check(-80, -88, 11, 11, 22, -181.33, -187.23)
    }
}
