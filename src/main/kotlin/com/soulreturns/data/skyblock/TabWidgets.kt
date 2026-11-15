package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events

/** `SB Level: [607] 72/100 XP`: the level and the XP into it (Hypixel's flat 100 per level). */
data class TabSkyblockLevel(val level: Int, val xp: Int?, val xpForNext: Int?) {
    /** 0..1 through the current level, or null when the widget showed only the level. */
    val progress: Double?
        get() = if (xp != null && xpForNext != null && xpForNext > 0) (xp.toDouble() / xpForNext).coerceIn(0.0, 1.0) else null
}

/**
 * `Bank: 1B`, or `Bank: 1.2B / 34M` on a co-op (shared, then personal). [text] is the line as shown;
 * the amounts are parsed best-effort from Hypixel's compact form and null when it changes shape.
 */
data class TabBank(val text: String, val coins: Double?, val personalCoins: Double?)

/**
 * `Interest: 14 Hours (993.3k)`: time until the next payout and its size. [seconds] is parsed from
 * [timeText] when it reads as `<n> <unit>` pairs, as of [readAtMs].
 */
data class TabInterest(val timeText: String, val seconds: Long?, val amountText: String?, val amount: Double?, val readAtMs: Long)

/** `Fairy Souls: 12/12` from the Area widget: souls found on the **current island**. */
data class TabFraction(val current: Int, val total: Int)

/**
 * A `Skills:` line, `Combat 60: MAX` or `Farming 45: 32.1%`. [level] is decimal even when Hypixel
 * renders Roman numerals; [percent] is null when [maxed].
 */
data class TabSkill(val name: String, val level: Int, val percent: Double?, val maxed: Boolean)

/**
 * A `Stats:` line, `Strength: <U+E00D>1420`. [icon] is whatever precedes the number (Hypixel's
 * private-use stat glyph today); [value] is null when the text holds no number.
 */
data class TabStat(val name: String, val text: String, val icon: String, val value: Double?)

/**
 * The `Pet:` widget: `[Lvl 200] Golden Dragon ✦` and the XP line under it. [rarity] comes from the
 * name's color; [skinned] is the trailing (or `[122✦]`-style) skin star. XP is either progress
 * (`33,915/179.7k XP (18.9%)`), or overflow at max level (`+335,977,671.1 XP`, [maxed]), or `MAX LEVEL`.
 */
data class TabPet(
    val level: Int,
    val name: String,
    val rarity: SkyblockRarity?,
    val skinned: Boolean,
    val xpText: String?,
    val xp: Double?,
    val xpForNext: Double?,
    val percent: Double?,
    val maxed: Boolean,
)

/**
 * Everything [TabWidgets] knows. Each value is the **last one the tab list showed on this profile**:
 * a widget missing from the current tab list (not enabled, not shown on this island, tab list not
 * synced yet) keeps its last reading; see [TabWidgets] for when values clear.
 */
data class TabSnapshot(
    val skyblockLevel: TabSkyblockLevel? = null,
    val bank: TabBank? = null,
    val interest: TabInterest? = null,
    val gems: Long? = null,
    val fairySouls: TabFraction? = null,
    /** Every skill seen so far on this profile, by name in first-seen order. */
    val skills: Map<String, TabSkill> = emptyMap(),
    /** `Skills: 55.3` header form, when Hypixel shows the average instead of a skill. */
    val skillAverage: Double? = null,
    /** The `Stats:` widget as last shown, in widget order. */
    val stats: List<TabStat> = emptyList(),
    /** The active pet; null when unknown or when the widget reads `No pet selected`. */
    val pet: TabPet? = null,
    /** Whether the Pet widget has been seen at all, so a null [pet] can be told apart from "unknown". */
    val petKnown: Boolean = false,
) {
    fun stat(name: String): TabStat? = stats.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun skill(name: String): TabSkill? = skills.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = TabSnapshot()
    }
}

/**
 * SkyBlock's tab list widgets, parsed: SkyBlock Level + XP, bank, interest, gems, island fairy souls,
 * skill levels, combat stats and the active pet. Fed by [SkyblockStateReader]'s single tab walk
 * (parsing in [TabWidgetParser]); read [snapshot], subscribe to [TabWidgetsChanged].
 *
 * Which widgets exist is the **player's choice** (Hypixel's `/tab` settings) and varies by island,
 * so a missing widget is never a reset:
 *  - Line values (SB Level, Bank, Interest, Gems, Fairy Souls) replace when read, else stay.
 *  - Stats replace whenever the widget shows lines; the pet whenever the Pet widget shows a line (so
 *    `No pet selected` clears it). Skills merge by name: the widget shows a chosen subset. A header
 *    with nothing under it yet (Hypixel fills the tab one entry at a time) changes nothing.
 *  - Fairy souls are per island: cleared on every server change.
 *  - Everything clears on a profile switch, on leaving SkyBlock and on disconnect.
 */
object TabWidgets {
    /** Written on the client thread; immutable, safe to read anywhere. */
    @Volatile
    var snapshot: TabSnapshot = TabSnapshot.EMPTY
        private set

    /** Widget headers in the last read tab list (`Profile`, `Skills`, `Stats`, …), for diagnostics. */
    @Volatile
    var lastHeaders: List<String> = emptyList()
        private set

    /** Last tab list in display order, with legacy colors kept; diagnostics escape pack glyphs. */
    @Volatile
    var lastLines: List<String> = emptyList()
        private set

    fun register() {
        Events.subscribe<ProfileChanged> { if (it.previous != null) clear() }
    }

    internal fun apply(lines: List<String>) {
        lastLines = lines
        val read = TabWidgetParser.parse(lines)
        lastHeaders = read.headers
        val old = snapshot
        val skills = if (read.skills.isEmpty()) old.skills else LinkedHashMap(old.skills).apply { for (s in read.skills) put(s.name, s) }
        // Keep the old instance while the text is unchanged: its readAtMs is when this countdown
        // value first showed, which is what a timer needs (and polls would otherwise always differ).
        val interest = read.interest?.takeUnless { old.interest != null && it.timeText == old.interest.timeText && it.amountText == old.interest.amountText }
            ?: old.interest
        publish(
            old.copy(
                skyblockLevel = read.skyblockLevel ?: old.skyblockLevel,
                bank = read.bank ?: old.bank,
                interest = interest,
                gems = read.gems ?: old.gems,
                fairySouls = read.fairySouls ?: old.fairySouls,
                skills = skills,
                skillAverage = read.skillAverage ?: old.skillAverage,
                stats = read.stats.ifEmpty { old.stats },
                pet = if (read.petSeen) read.pet else old.pet,
                petKnown = old.petKnown || read.petSeen,
            ),
        )
    }

    /** Server change: drop what belongs to the island. */
    internal fun clearIsland() {
        publish(snapshot.copy(fairySouls = null))
    }

    internal fun clear() {
        lastHeaders = emptyList()
        lastLines = emptyList()
        publish(TabSnapshot.EMPTY)
    }

    private fun publish(new: TabSnapshot) {
        val previous = snapshot
        if (new == previous) return
        snapshot = new
        Events.publish(TabWidgetsChanged(previous, new))
    }
}

/**
 * The tab widgets changed. Fires per poll (¼ s) at most, on any difference: stats move with every
 * buff, so compare the field you care about (`previous.pet != current.pet`) before doing work.
 */
data class TabWidgetsChanged(val previous: TabSnapshot, val current: TabSnapshot) : Event
