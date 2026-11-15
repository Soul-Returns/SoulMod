package com.soulreturns.features.inventorybuttons

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryButtonSpotsTest {
    /** The GUI size Minecraft uses for a window at a GUI scale (it rounds up). */
    private fun layout(windowW: Int, windowH: Int, scale: Int): InventoryButtonSpots.Layout =
        InventoryButtonSpots.layout(ceil(windowW / scale.toDouble()).toInt(), ceil(windowH / scale.toDouble()).toInt())

    private fun ringIds(r: Int): Set<String> {
        val lo = 1 - r
        val hi = 9 + r
        val ids = HashSet<String>()
        for (n in lo..hi) for (m in lo..hi) if (n == lo || n == hi || m == lo || m == hi) ids.add("G:$n:$m")
        return ids
    }

    private fun InventoryButtonSpots.Layout.ids(prefix: String): List<String> = spots.map { it.id }.filter { it.startsWith(prefix) }

    @Test
    fun ringOneSitsThreePixelsFromTheFrameAndCloses() {
        val l = layout(1920, 1080, 3)
        val invX = (640 - 176) / 2
        val invY = (360 - 166) / 2
        assertEquals(ringIds(1), l.spots.filter { it.ring == 1 }.map { it.id }.toSet())
        assertEquals(40, l.spots.count { it.ring == 1 })
        // Corners: 3 px to the frame on both axes.
        val tl = l.at("G:0:0")!!
        assertEquals(invX - 3 - 18, tl.x)
        assertEquals(invY - 3 - 18, tl.y)
        val br = l.at("G:10:10")!!
        assertEquals(invX + 176 + 3, br.x)
        assertEquals(invY + 166 + 3, br.y)
        // 2 px gaps along a row, 1 px down a column.
        assertEquals(20, l.at("G:1:0")!!.x - tl.x)
        assertEquals(19, l.at("G:0:1")!!.y - tl.y)
        // Ring 2 one pitch further out.
        assertEquals(ringIds(2), l.spots.filter { it.ring == 2 }.map { it.id }.toSet())
        assertEquals(tl.x - 20, l.at("G:-1:-1")!!.x)
        assertEquals(tl.y - 19, l.at("G:-1:-1")!!.y)
    }

    @Test
    fun scale3At1080pHasColumnsAndRows() {
        val l = layout(1920, 1080, 3) // 640×360
        assertEquals(listOf(true, true), l.rowsFit)
        assertEquals(listOf("L:0", "L:1", "R:0", "R:1"), l.columns)
        assertEquals(3, l.at("L:0:0")!!.x)
        assertEquals(23, l.at("L:1:0")!!.x)
        assertEquals(640 - 21, l.at("R:0:0")!!.x)
        assertEquals(3, l.at("T:0:4")!!.y)
        assertEquals(22, l.at("T:1:4")!!.y)
        assertEquals(360 - 21, l.at("B:0:4")!!.y)
        // Columns at the lattice's heights, rows at its columns.
        assertEquals(l.at("G:0:3")!!.y, l.at("L:0:3")!!.y)
        assertEquals(l.at("G:4:0")!!.x, l.at("T:0:4")!!.x)
        // Corners belong to the columns: no row cell within a gap of a column.
        val columnXs = listOf(3, 23, 619, 599)
        for (spot in l.spots.filter { it.id[0] == 'T' || it.id[0] == 'B' }) {
            assertTrue(columnXs.none { cx -> spot.x < cx + 20 && cx - 2 < spot.x + 18 }, spot.id)
        }
        assertNoOverlap(l)
    }

    @Test
    fun scale2At1080pHasEverything() {
        val l = layout(1920, 1080, 2) // 960×540
        assertEquals(listOf(true, true), l.rowsFit)
        assertEquals(4, l.columns.size)
        assertEquals(88, l.spots.count { it.ring > 0 })
        assertNoOverlap(l)
    }

    @Test
    fun scale4At1080pDropsTheRows() {
        val l = layout(1920, 1080, 4) // 480×270
        assertEquals(listOf(false, false), l.rowsFit)
        assertTrue(l.ids("T:").isEmpty() && l.ids("B:").isEmpty())
        assertEquals(4, l.columns.size)
        assertEquals(88, l.spots.count { it.ring > 0 }) // both rings still fully on screen
        assertNull(l.at("T:0:4"))
        assertNoOverlap(l)
    }

    @Test
    fun scale2At720pMatches1080pAtScale3() {
        val a = layout(1280, 720, 2)
        val b = layout(1920, 1080, 3)
        assertEquals(b.spots.map { Triple(it.id, it.x, it.y) }, a.spots.map { Triple(it.id, it.x, it.y) })
    }

    @Test
    fun scale3At720pLosesRingTwoTopAndBottom() {
        val l = layout(1280, 720, 3) // 427×240: ring 2's top row would start at y -3
        assertEquals(listOf(false, false), l.rowsFit)
        assertEquals(40, l.spots.count { it.ring == 1 })
        assertNull(l.at("G:3:-1"))
        assertNull(l.at("G:3:11"))
        assertNull(l.at("G:-1:-1"))
        assertNotNull(l.at("G:-1:5"))
        assertNotNull(l.at("G:11:0"))
        assertEquals(4, l.columns.size)
        assertEquals((0..10).map { "L:0:$it" }, l.ids("L:0:"))
        assertNoOverlap(l)
    }

    @Test
    fun scale4At720pHidesWhatDoesNotFit() {
        // Minecraft caps 1280×720 at GUI scale 3; a 320×180 screen still lays out without throwing.
        val l = layout(1280, 720, 4)
        assertTrue(l.spots.all { it.x >= 0 && it.y >= 0 && it.x + 18 <= 320 && it.y + 18 <= 180 })
        assertNull(l.at("G:0:0"))
        assertNoOverlap(l)
    }

    @Test
    fun idsKeepTheirPlaceRelativeToTheInventoryAcrossScales() {
        for ((w, h, k) in listOf(Triple(1920, 1080, 2), Triple(1920, 1080, 3), Triple(1920, 1080, 4), Triple(1280, 720, 2))) {
            val gw = ceil(w / k.toDouble()).toInt()
            val gh = ceil(h / k.toDouble()).toInt()
            val l = layout(w, h, k)
            val invX = (gw - 176) / 2
            val invY = (gh - 166) / 2
            val spot = l.at("G:3:0")!!
            assertEquals(39, spot.x - invX)
            assertEquals(-21, spot.y - invY)
            val right = l.at("R:1:5")!!
            assertEquals(gw - 41, right.x)
            assertEquals(l.at("G:0:5")!!.y, right.y)
        }
    }

    @Test
    fun tallerContainerOpensAGapInTheSideColumns() {
        // A large chest (176×222) centered on 640×360: the lower half follows the bottom edge.
        val l = InventoryButtonSpots.layout(640, 360, 232, 69, 176, 222)
        assertEquals(69 - 21, l.at("G:0:0")!!.y)
        assertEquals(69 + 222 + 3, l.at("G:0:10")!!.y)
        assertEquals(l.at("G:0:5")!!.y + 19 + 56, l.at("G:0:6")!!.y)
        assertNoOverlap(l)
    }

    @Test
    fun shorterContainerDropsOverlappingCells() {
        // A one-row chest (176×132): cells 5 and 6 of a side column would overlap.
        val l = InventoryButtonSpots.layout(640, 360, 232, 114, 176, 132)
        assertNotNull(l.at("G:0:5"))
        assertNull(l.at("G:0:6"))
        assertNoOverlap(l)
    }

    @Test
    fun describesSpots() {
        assertEquals("Ring 1, top-left corner", InventoryButtonSpots.describe("G:0:0"))
        assertEquals("Ring 2, right", InventoryButtonSpots.describe("G:11:4"))
        assertEquals("Left screen edge, column 2", InventoryButtonSpots.describe("L:1:3"))
        assertEquals("Bottom screen edge, row 1", InventoryButtonSpots.describe("B:0:7"))
    }

    private fun assertNoOverlap(l: InventoryButtonSpots.Layout) {
        val spots = l.spots
        assertEquals(spots.size, spots.map { it.id }.toSet().size, "duplicate ids")
        for (i in spots.indices) for (j in i + 1 until spots.size) {
            val a = spots[i]
            val b = spots[j]
            assertTrue(a.x + 18 <= b.x || b.x + 18 <= a.x || a.y + 18 <= b.y || b.y + 18 <= a.y, "${a.id} overlaps ${b.id}")
        }
    }

    @Test
    fun outerSpotsDisappearWithTheToggleOffAndDoNotCountAsHidden() {
        val on = InventoryButtonSpots.layout(640, 360)
        val off = InventoryButtonSpots.layout(640, 360, showOuter = false)
        assertTrue(on.spots.any { it.ring == 0 })
        assertTrue(off.spots.none { it.ring == 0 })
        assertEquals(on.spots.filter { it.ring != 0 }.map { it.id }, off.spots.map { it.id })
        for (id in listOf("L:0:3", "R:1:3", "T:0:5", "B:1:5")) {
            assertNotNull(on.at(id), id)
            assertNull(off.at(id), id)
        }
        val spots = listOf("G:0:0", "L:0:3", "T:1:99", "G:99:99")
        assertEquals(listOf("T:1:99", "G:99:99"), spots.filter { on.isHidden(it) })
        assertEquals(listOf("G:99:99"), spots.filter { off.isHidden(it) })
        assertEquals(listOf("L:0:3", "T:1:99"), spots.filter { off.isSwitchedOff(it) })
        assertTrue(on.spots.none { on.isSwitchedOff(it.id) })
    }

    @Test
    fun blockedRectRemovesOverlappingSpotsOnly() {
        val free = InventoryButtonSpots.layout(640, 360)
        val left = (640 - 176) / 2
        val top = (360 - 166) / 2
        val blocked = InventoryButtonSpots.layout(640, 360, blocked = intArrayOf(left - 18, top, left, top + 86))
        val gone = free.spots.map { it.id }.toSet() - blocked.spots.map { it.id }.toSet()
        assertTrue(gone.isNotEmpty())
        assertTrue(gone.all { id -> free.at(id)!!.let { it.x < left && it.x + 18 > left - 18 && it.y < top + 86 && it.y + 18 > top } })
        assertTrue(blocked.isHidden(gone.first()))
    }

    /**
     * Duplicate placement, every row computed by the mock's `nextToSpot` (mocks/inventory-buttons-editor/)
     * on the same 533×300 GUI-px screen (1600×900 at GUI scale 3) with the same taken spots.
     */
    @Test
    fun duplicateGoesNextToTheOriginalAsTheMockDoes() {
        val l = InventoryButtonSpots.layout(533, 300)
        val table = listOf(
            // source, taken spots (the source included), expected copy
            Triple("G:3:0", listOf("G:3:0"), "G:4:0"), // top row: right
            Triple("G:3:0", listOf("G:3:0", "G:4:0", "G:5:0"), "G:6:0"), // taken ones skipped
            Triple("G:0:0", listOf("G:0:0"), "G:1:0"), // top-left corner: its row
            Triple("G:10:0", listOf("G:10:0"), "G:10:1"), // top-right corner: down the right side
            Triple("G:10:10", listOf("G:10:10"), "G:10:9"), // bottom-right: no run, nearest free
            Triple("G:0:10", listOf("G:0:10"), "G:1:10"), // bottom-left corner: its row
            Triple("G:0:4", listOf("G:0:4"), "G:0:5"), // left side: down
            Triple("G:10:4", listOf("G:10:4"), "G:10:5"), // right side: down
            Triple("G:3:10", listOf("G:3:10"), "G:4:10"), // bottom row: right
            Triple("G:9:0", listOf("G:9:0", "G:10:0"), "G:9:-1"), // run full: nearest free
            Triple("G:-1:-1", listOf("G:-1:-1"), "G:0:-1"), // ring 2 corner
            Triple("G:11:3", listOf("G:11:3"), "G:11:4"), // ring 2 right side
            Triple("G:11:11", listOf("G:11:11"), "G:11:10"), // ring 2 bottom-right: nearest
            Triple("G:10:9", listOf("G:10:9"), "G:10:10"), // right side into its corner
            Triple("L:0:4", listOf("L:0:4"), "L:0:5"), // screen column: down
            Triple("R:1:2", listOf("R:1:2", "R:1:3"), "R:1:4"),
            Triple("T:0:5", listOf("T:0:5"), "T:0:6"), // screen row: right
            Triple("B:0:5", listOf("B:0:5", "B:0:6"), "B:0:7"),
            Triple("T:0:15", listOf("T:0:15"), "T:0:14"), // last of its row: nearest
            Triple("L:0:12", listOf("L:0:12"), "L:0:11"), // last of its column: nearest
            Triple("G:5:-5", listOf("G:3:0"), "G:0:0"), // no such spot: first free in placement order
        )
        for ((source, taken, expected) in table) {
            assertEquals(expected, InventoryButtonSpots.nextTo(l, source) { it !in taken }, "duplicate of $source with $taken taken")
        }
        assertNull(InventoryButtonSpots.nextTo(l, "G:3:0") { false })
    }
}
