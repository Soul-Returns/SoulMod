package com.soulreturns.features.mining.mineshaft

/**
 * Corpse type by elimination (rule://mineshaft, Corpse locator): the tab lists one row per corpse
 * with its type. Rows not accounted for are those neither looted (tab LOOTED or a loot line, the
 * larger count, since either can lag the other) nor a located corpse whose type is known. When what
 * is left is a single type, every located corpse without a type is that type.
 */
object CorpseElimination {
    /**
     * @param rows tab corpse rows per type (any case): total and LOOTED count
     * @param lootLines types named by this stay's `<TYPE> CORPSE LOOT!` lines
     * @param known types of located, unlooted corpses whose type is known
     * @param unknown located, unlooted corpses without a type
     * @return the type all [unknown] ones are, or null when more than one type (or none) is left,
     *   or the counts do not add up
     */
    fun typeOfRest(rows: Map<String, Pair<Int, Int>>, lootLines: List<String>, known: List<String>, unknown: Int): String? {
        if (unknown <= 0 || rows.isEmpty()) return null
        val left = HashMap<String, Int>()
        for ((type, counts) in rows) {
            val (total, looted) = counts
            val lines = lootLines.count { it.equals(type, ignoreCase = true) }
            val seen = known.count { it.equals(type, ignoreCase = true) }
            val n = total - maxOf(looted, lines) - seen
            if (n < 0) return null
            if (n > 0) left[type] = n
        }
        val only = left.entries.singleOrNull() ?: return null
        return only.key.takeIf { unknown <= only.value }
    }
}
