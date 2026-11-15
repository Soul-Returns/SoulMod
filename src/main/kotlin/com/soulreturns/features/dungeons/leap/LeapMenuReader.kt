package com.soulreturns.features.dungeons.leap

/**
 * Recognizes Hypixel's Spirit Leap menu from its title, size and slots (research `spirit-leap-2026-11-13.md` §1):
 * `Spirit Leap`, 36 slots, black glass panes and one `player_head` per teammate whose first lore line is
 * `Click to teleport!` or `This player is currently dead!`. A disconnected teammate's head is `Unknown Player` with
 * `This player is offline!` (no name, no skin; M7 2026-11-13, two menus while the Tank was disconnected): nobody to
 * leap to, so it is skipped like a pane. Anything else is not a menu we know: the vanilla screen stays. Pure: slots
 * come in as [Item]s.
 */
object LeapMenuReader {
    const val SIZE = 36

    /** Odin and SkyHanni also take `Teleport to Player` (no recording has it). */
    private val TITLES = setOf("Spirit Leap", "Teleport to Player")
    const val ALIVE = "Click to teleport!"
    const val DEAD = "This player is currently dead!"
    const val OFFLINE = "This player is offline!"
    private const val PANE = "black_stained_glass_pane"
    private const val HEAD = "player_head"
    private val NAME = Regex("^\\w{1,16}$")

    /** One container slot: item id path, color-stripped name, first lore line (stripped). */
    data class Item(val id: String, val name: String, val lore0: String?)

    data class Head(val slot: Int, val name: String, val dead: Boolean)

    sealed class Result {
        /** Not all slots there yet; [heads] = the heads that have arrived, each already checked. */
        data class Pending(val heads: List<Head>) : Result()
        data class Ok(val heads: List<Head>) : Result()
        data class Fail(val why: String) : Result()
    }

    fun isLeapMenu(strippedTitle: String, size: Int): Boolean = strippedTitle.trim() in TITLES && size == SIZE

    /** [slots] = the 36 container slots, null = empty (not arrived yet). */
    fun read(slots: List<Item?>): Result {
        val heads = ArrayList<Head>()
        var missing = false
        for ((i, it) in slots.withIndex()) {
            if (it == null) {
                missing = true
                continue
            }
            if (it.id == PANE || (it.id == HEAD && it.lore0 == OFFLINE)) continue
            if (it.id != HEAD) return Result.Fail("slot $i holds ${it.id}")
            val dead = when (it.lore0) {
                ALIVE -> false
                DEAD -> true
                else -> return Result.Fail("slot $i head \"${it.name}\" has lore \"${it.lore0}\"")
            }
            if (!NAME.matches(it.name)) return Result.Fail("slot $i head name \"${it.name}\"")
            heads.add(Head(i, it.name, dead))
        }
        if (missing) return Result.Pending(heads)
        if (heads.isEmpty()) return Result.Fail("no teammates in the menu")
        return Result.Ok(heads)
    }
}
