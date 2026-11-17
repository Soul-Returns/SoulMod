package com.soulreturns.features.combo

/** One bonus a milestone line names, e.g. `+4☯ Combat Wisdom` or `+8 coins per kill`. */
data class ComboBonus(val kind: Kind, val amount: Double) {
    enum class Kind { COMBAT_WISDOM, COINS_PER_KILL }
}

/**
 * Pure parser of the combo chat lines (color codes already stripped). Hypixel's bonus text changes
 * between SkyBlock versions (Magic Find until 0.27.2, Combat Wisdom and coins since 0.27.3), so a
 * milestone is recognised by its `+N Kill Combo` head alone; the rest is searched for the bonuses
 * we know and anything else is ignored, never a reason to drop the milestone.
 */
object ComboChat {
    sealed interface Line

    /** `+N Kill Combo …`: [bonuses] is empty for a bare line or text we do not know. */
    data class Milestone(val count: Int, val bonuses: List<ComboBonus>) : Line

    /** `Your Kill Combo has expired! You reached a N Kill Combo!` */
    data class Expired(val reached: Int) : Line

    private val MILESTONE = Regex("^\\+(\\d+) Kill Combo")
    private val EXPIRE = Regex("Your Kill Combo has expired! You reached a (\\d+) Kill Combo!")
    private const val NUMBER = "(\\d[\\d,]*(?:\\.\\d+)?)"
    private val WISDOM = Regex("\\+\\s*$NUMBER\\s*☯?\\s*Combat Wisdom", RegexOption.IGNORE_CASE)
    private val COINS = Regex("\\+\\s*$NUMBER\\s*coins per kill", RegexOption.IGNORE_CASE)

    fun parse(clean: String): Line? {
        EXPIRE.find(clean)?.let { return Expired(it.groupValues[1].toIntOrNull() ?: 0) }
        val head = MILESTONE.find(clean) ?: return null
        val count = head.groupValues[1].toIntOrNull() ?: return null
        val rest = clean.substring(head.range.last + 1)
        val bonuses = ArrayList<ComboBonus>(2)
        WISDOM.find(rest)?.let { amount(it)?.let { a -> bonuses += ComboBonus(ComboBonus.Kind.COMBAT_WISDOM, a) } }
        COINS.find(rest)?.let { amount(it)?.let { a -> bonuses += ComboBonus(ComboBonus.Kind.COINS_PER_KILL, a) } }
        return Milestone(count, bonuses)
    }

    private fun amount(m: MatchResult): Double? = m.groupValues[1].replace(",", "").toDoubleOrNull()

    /** Whether [clean] is a combo milestone or expiry line (for hiding it). */
    fun isMilestone(clean: String): Boolean = MILESTONE.containsMatchIn(clean)

    fun isExpiry(clean: String): Boolean = EXPIRE.containsMatchIn(clean)
}

/**
 * The bonuses of the running combo. Effects stack as the combo grows, so each milestone line's bonus
 * is added to the totals; they reset when the combo expires or a lower milestone shows a new one began.
 */
class ComboRun {
    @Volatile
    var combatWisdom: Double = 0.0
        private set

    @Volatile
    var coinsPerKill: Double = 0.0
        private set

    @Volatile
    private var lastMilestone = 0

    fun onMilestone(count: Int, bonuses: List<ComboBonus>) {
        if (count <= lastMilestone) reset() // the previous combo ended without us seeing its expiry
        lastMilestone = count
        for (b in bonuses) {
            if (b.kind == ComboBonus.Kind.COMBAT_WISDOM) combatWisdom += b.amount else coinsPerKill += b.amount
        }
    }

    fun reset() {
        combatWisdom = 0.0
        coinsPerKill = 0.0
        lastMilestone = 0
    }
}
