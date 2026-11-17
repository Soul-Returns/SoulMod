package com.soulreturns.features.experiments

/**
 * Recognition and the readers of the non-game menus (research §1.5, §5.2). Pure: they read a complete
 * container ([ExpItem] per slot) and return what the Soul screen shows, with the real slot behind each
 * control. A menu whose key items are missing reads as null: the vanilla menu.
 */
object ExpMenus {
    enum class Kind { TABLE, STAKES, CHRONO, ULTRA, SUPERPAIRS, OVER, REWARDS, RNG }

    /** [game] for stakes (`chrono`/`ultra`/`sp`), [tier] for the games, [page]/[pages] for the RNG meter. */
    data class Recognized(val kind: Kind, val game: String? = null, val tier: String? = null, val page: Int = 0, val pages: Int = 0)

    private val STAKES = Regex("^(Chronomatron|Ultrasequencer|Superpairs) ➜ Stakes$")
    private val GAME = Regex("^(Chronomatron|Ultrasequencer|Superpairs) \\((\\w+)\\)$")
    private val RNG = Regex("^\\((\\d+)/(\\d+)\\) Experimentation Table RNG$")
    val GAME_KEY = mapOf("Chronomatron" to "chrono", "Ultrasequencer" to "ultra", "Superpairs" to "sp")
    val GAME_NAME = mapOf("chrono" to "Chronomatron", "ultra" to "Ultrasequencer", "sp" to "Superpairs")

    /** Title (colors stripped) + rows → what it is, or null (not ours). The layout is checked later on the contents. */
    fun recognize(title: String, rows: Int): Recognized? {
        if (title == "Experimentation Table") return if (rows == 6) Recognized(Kind.TABLE) else null
        STAKES.find(title)?.let { return Recognized(Kind.STAKES, game = GAME_KEY[it.groupValues[1]]) }
        GAME.find(title)?.let {
            if (rows != 6) return null
            val kind = when (it.groupValues[1]) {
                "Chronomatron" -> Kind.CHRONO
                "Ultrasequencer" -> Kind.ULTRA
                else -> Kind.SUPERPAIRS
            }
            return Recognized(kind, game = GAME_KEY[it.groupValues[1]], tier = it.groupValues[2])
        }
        if (title == "Experiment Over") return if (rows == 3) Recognized(Kind.OVER) else null
        if (title == "Superpairs Rewards") return if (rows == 3) Recognized(Kind.REWARDS) else null
        RNG.find(title)?.let { return Recognized(Kind.RNG, page = it.groupValues[1].toInt(), pages = it.groupValues[2].toInt()) }
        return null
    }

    // ---------------------------------------------------------------- Experimentation Table

    data class Addon(val slot: Int, val done: Boolean, val bonus: Int, val status: String, val practice: Boolean, val statusSlot: Int?)

    data class Sp(val slot: Int, val charges: Int?, val max: Int, val next: String, val status: String)

    data class Renew(val slot: Int, val renewed: Int, val levels: String?, val bits: String?)

    data class Rng(val slot: Int, val drop: String, val pct: String, val amount: String)

    data class Table(val sp: Sp, val chrono: Addon, val ultra: Addon, val renew: Renew?, val rng: Rng?, val close: Int?, val bottles: Int?)

    private fun find(items: List<ExpItem?>, pred: (ExpItem) -> Boolean): Int? = items.indices.firstOrNull { i -> items[i]?.let(pred) == true }

    fun readTable(items: List<ExpItem?>): Table? {
        val spSlot = find(items) { it.id == "player_head" && it.plain == "Superpairs" } ?: return null
        val chSlot = find(items) { it.id == "player_head" && it.plain == "Chronomatron" } ?: return null
        val ulSlot = find(items) { it.id == "player_head" && it.plain == "Ultrasequencer" } ?: return null
        val spL = items[spSlot]!!.plainLore
        val charges = spL.firstNotNullOfOrNull { Regex("^Charges: (\\d+)/(\\d+)$").find(it) }
        val renewSlot = find(items) { it.plain == "Renew Experiments" }
        val renew = renewSlot?.let { s ->
            val l = items[s]!!.plainLore
            Renew(s, l.firstNotNullOfOrNull { Regex("renewed (\\d+)/").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0, l.firstOrNull { it.endsWith("XP Levels") }, l.firstOrNull { it.endsWith("Bits") })
        }
        val rngSlot = find(items) { it.plain.endsWith("RNG Meter") }
        val rng = rngSlot?.let { s ->
            val l = items[s]!!.plainLore
            val sel = l.indexOf("Selected Drop")
            Rng(
                s,
                if (sel >= 0) l.getOrNull(sel + 1).orEmpty() else "",
                l.firstNotNullOfOrNull { Regex("^Progress: ([\\d.]+)%$").find(it)?.groupValues?.get(1) } ?: "",
                l.firstNotNullOfOrNull { Regex("([\\d,.]+[kKmM]?/[\\d,.]+[kKmM]?)\\s*$").find(it.trim())?.groupValues?.get(1) } ?: "",
            )
        }
        return Table(
            Sp(spSlot, charges?.groupValues?.get(1)?.toIntOrNull(), charges?.groupValues?.get(2)?.toIntOrNull() ?: 3, spL.firstOrNull { it.startsWith("Next charge in:") }?.removePrefix("Next charge in:")?.trim().orEmpty(), spL.lastOrNull { it.isNotBlank() }.orEmpty()),
            addon(items, chSlot, chSlot - 9),
            addon(items, ulSlot, ulSlot - 9),
            renew,
            rng,
            find(items) { it.id == "barrier" },
            find(items) { it.plain == "Experience Bottles" },
        )
    }

    /** An add-on's status pane sits in the row above its head (20/21 over 29, 23/24 over 33). */
    private fun addon(items: List<ExpItem?>, slot: Int, above: Int): Addon {
        val l = items[slot]!!.plainLore
        val st = listOf(above, above - 1, above + 1).firstOrNull { s -> items.getOrNull(s)?.isPane == true && items[s]!!.plain.isNotBlank() }
        val pane = st?.let { items[it] }
        val done = pane?.plain == "Experiment completed!"
        val bonus = if (done) pane!!.plainLore.firstNotNullOfOrNull { Regex("\\+(\\d+) clicks?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0 else 0
        return Addon(slot, done, bonus, l.lastOrNull { it.isNotBlank() }.orEmpty(), l.any { it == "Right-Click to practice!" }, st)
    }

    // ---------------------------------------------------------------- Stakes

    data class Tier(
        val slot: Int,
        val tier: String,
        val color: Char,
        val item: ExpItem,
        val status: String,
        val playable: Boolean,
        val colors: String?,
        val grid: String?,
        val xp: String?,
        val requires: String?,
        val cost: String?,
        val clicks: String?,
        val clickParts: List<String>,
        val rewards: List<String>,
        val pairs: List<String>,
    )

    data class Stakes(val game: String, val tiers: List<Tier>, val back: Int?, val close: Int?)

    fun readStakes(game: String, items: List<ExpItem?>): Stakes? {
        val tiers = ArrayList<Tier>()
        for (s in items.indices) {
            val it = items[s] ?: continue
            if (it.isPane || it.id == "arrow" || it.id == "barrier" || !it.plain.endsWith(" Experiment")) continue
            val l = it.plainLore
            fun v(re: String) = l.firstNotNullOfOrNull { line -> Regex(re).find(line.trim())?.groupValues?.get(1) }
            val iPairs = l.indexOf("Possible pairs:")
            val iClicks = l.indexOfFirst { line -> line.startsWith("Clicks:") }
            val status = l.lastOrNull { line -> line.isNotBlank() }.orEmpty()
            tiers +=
                Tier(
                    s, it.plain.removeSuffix(" Experiment"), it.nameColor ?: 'f', it, status, status == "Click to play!",
                    v("^Colors on board: (\\d+)$"), v("^Grid [Ss]ize: (\\S+)$"), v("^XP Reward: (\\S+) Enchanting Exp$"),
                    v("^Requires: (.+)$"), v("^Starting cost: (\\d+) XP Levels$"), v("^Clicks: (\\d+)$"),
                    if (iClicks >= 0) l.drop(iClicks + 1).takeWhile { line -> line.isNotBlank() }.map { line -> line.trim() } else emptyList(),
                    l.map { line -> line.trim() }.filter { line -> Regex("^(Chain|Series) of \\d+: \\+\\d+ Clicks?$").matches(line) }.map { line -> line.replace(Regex("^(Chain|Series) of "), "").replace(": ", " → ") },
                    if (iPairs >= 0) l.drop(iPairs + 1).takeWhile { line -> line.isNotBlank() }.map { line -> line.trim() } else emptyList(),
                )
        }
        if (tiers.isEmpty()) return null
        return Stakes(game, tiers, find(items) { it.id == "arrow" && it.plain == "Go Back" }, find(items) { it.id == "barrier" })
    }

    // ---------------------------------------------------------------- Experiment Over / Superpairs Rewards

    data class Over(val game: String, val slot: Int, val reason: String, val stakes: String, val chain: String?, val rewards: List<String>, val decline: Int?)

    private fun rewardsOf(l: List<String>): List<String> {
        val i = l.indexOf("Rewards:")
        if (i < 0) return emptyList()
        return l.drop(i + 1).takeWhile { it.isNotBlank() }.map { it.trim().removePrefix("+").trim() }
    }

    fun readOver(items: List<ExpItem?>): Over? {
        val slot = find(items) { it.id == "player_head" && GAME_KEY.containsKey(it.plain) } ?: return null
        val it = items[slot]!!
        val l = it.plainLore
        val iStakes = l.indexOfFirst { line -> line.startsWith("Stakes:") }
        if (iStakes < 0) return null
        val reason = l.take(iStakes).lastOrNull { line -> line.isNotBlank() && line != "Claim" }.orEmpty()
        return Over(
            GAME_KEY[it.plain]!!, slot, reason, l[iStakes].removePrefix("Stakes:").trim(),
            l.firstOrNull { line -> Regex("^(Longest Chain|Highest series):").containsMatchIn(line) },
            rewardsOf(l), find(items) { i -> i.plain == "Decline" },
        )
    }

    data class Rewards(val slot: Int, val stakes: String, val used: String, val pairs: String, val rewards: List<String>)

    fun readRewards(items: List<ExpItem?>): Rewards? {
        val slot = find(items) { it.id == "player_head" && it.plain == "Superpairs" } ?: return null
        val l = items[slot]!!.plainLore
        fun v(p: String) = l.firstOrNull { it.startsWith(p) }?.removePrefix(p)?.trim().orEmpty()
        if (l.none { it.startsWith("Clicks used:") }) return null
        return Rewards(slot, v("Stakes:"), v("Clicks used:"), v("Pairs found:"), rewardsOf(l))
    }
}
