package com.soulreturns.features.mining.mineshaft

/**
 * What the corpse locator knows about each located corpse besides where it is (pure, one per stay):
 * its type by source and which party member shared it. A seen (or fixed) type wins over a party
 * member's, which wins over one by elimination ([CorpseElimination]); all are sticky for the stay.
 */
class CorpseBook {
    val seenTypes = HashMap<CorpseSpot, String>()
    val sharedTypes = HashMap<CorpseSpot, String>()
    val inferredTypes = HashMap<CorpseSpot, String>()

    /** First party member who sent each corpse. */
    val sharedBy = LinkedHashMap<CorpseSpot, String>()

    fun typeOf(spot: CorpseSpot): String? = seenTypes[spot] ?: sharedTypes[spot] ?: inferredTypes[spot]

    /** The stand was seen as [type]; returns the shared or inferred type it overrules, if any. */
    fun see(spot: CorpseSpot, type: String): String? {
        val before = sharedTypes[spot] ?: inferredTypes[spot]
        seenTypes[spot] = type
        return before?.takeIf { it != type }
    }

    /**
     * Merges a party member's located corpses: the same listed spot (or, outside the data, a stand
     * within [CorpseLocatorSolver.SAME_CORPSE]) is the same corpse; their type fills a missing one;
     * their position is drawn only while the player has neither seen nor narrowed it; corpses the
     * player looted stay hidden (looting is per player). Returns the corpses it touched.
     */
    fun receive(s: CorpseLocatorSolver, from: String, corpses: List<SharedCorpse>): List<CorpseSpot> {
        val touched = ArrayList<CorpseSpot>()
        for (c in corpses) {
            val listed = c.spot?.takeIf { it in s.spots }
            val key =
                if (listed != null) {
                    if (!s.locateSpot(listed)) continue
                    s.hint(listed, c.pos[0], c.pos[1], c.pos[2])
                    listed
                } else {
                    s.locate(c.pos[0], c.pos[1], c.pos[2], c.type, own = false) ?: continue
                }
            if (c.type != null && key !in seenTypes) sharedTypes[key] = c.type
            sharedBy.putIfAbsent(key, from)
            touched += key
        }
        return touched
    }

    fun clear() {
        seenTypes.clear()
        sharedTypes.clear()
        inferredTypes.clear()
        sharedBy.clear()
    }
}
