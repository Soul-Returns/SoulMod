package com.soulreturns.features.dungeons.leap

import com.soulreturns.features.dungeons.DungeonClass

/**
 * Which teammate goes into which corner of the leap menu (spec `mocks/leap-menu/SPEC.md` §2). Places are
 * 0 top left, 1 top right, 2 bottom left, 3 bottom right; the result always has 4 entries, null = empty.
 */
object LeapSort {
    const val ODIN = "odin"
    const val CLASS = "class"
    const val NAME = "name"
    const val MENU = "menu"

    val MODES = listOf(ODIN, CLASS, NAME, MENU)

    /**
     * Home corner per class, ported from Odin's `DungeonClass.defaultQuadrant` (Odin, BSD-3-Clause): every class
     * always sits in the same corner, so the hand learns where to flick. -1 = no home (class not known yet).
     */
    fun home(c: DungeonClass): Int = when (c.name) {
        "ARCHER" -> 0
        "BERSERK" -> 1
        "HEALER" -> 2
        "MAGE", "TANK" -> 3
        else -> -1
    }

    /** Who claims a home first, ported from Odin's `DungeonClass.priority` (Berserk, then Tank, then the rest). */
    fun priority(c: DungeonClass): Int = when (c.name) {
        "BERSERK" -> 0
        "TANK" -> 1
        "ARCHER", "HEALER", "MAGE" -> 2
        else -> 3
    }

    /** The place a point picks (Odin): the whole screen quadrant, 0 TL, 1 TR, 2 BL, 3 BR, split at the centre. */
    fun quadrant(x: Double, y: Double, w: Double, h: Double): Int = (if (y >= h / 2) 2 else 0) + (if (x >= w / 2) 1 else 0)

    private val CLASS_ORDER = listOf("ARCHER", "BERSERK", "HEALER", "MAGE", "TANK")

    /**
     * [mates] = the teammates without yourself, in tab-list order. Odin sorting (ported from Odin's `odinSorting`):
     * stable sort by [priority], each takes its [home] if free, the rest fill the free corners 0..3 in order;
     * with fewer teammates the other corners stay empty. The list modes fill the corners 0..3.
     */
    fun <T> sort(mates: List<T>, mode: String, cls: (T) -> DungeonClass, name: (T) -> String, slot: (T) -> Int): List<T?> {
        val out = arrayOfNulls<Any>(4)
        if (mode == ODIN || mode !in MODES) {
            val second = ArrayList<T>()
            for (p in mates.sortedBy { priority(cls(it)) }) {
                val h = home(cls(p))
                if (h >= 0 && out[h] == null) out[h] = p else second.add(p)
            }
            for (i in 0 until 4) if (out[i] == null && second.isNotEmpty()) out[i] = second.removeAt(0)
        } else {
            val list = when (mode) {
                CLASS -> mates.sortedWith(compareBy<T>({ CLASS_ORDER.indexOf(cls(it).name).let { i -> if (i < 0) 99 else i } }, { name(it).lowercase() }))
                NAME -> mates.sortedBy { name(it).lowercase() }
                else -> mates.sortedBy(slot)
            }
            for (i in 0 until minOf(4, list.size)) out[i] = list[i]
        }
        @Suppress("UNCHECKED_CAST")
        return out.toList() as List<T?>
    }
}
