package com.soulreturns.data.skyblock

/**
 * Static catalog of Hypixel SkyBlock Mythological Ritual mobs — the named entities the
 * player digs out of Griffin Burrows during Mayor Diana's "Mythological Ritual" term.
 *
 * **Match path.** [matchDigOut] takes a color-stripped chat line, returns the matching
 * [Mob] or `null`. Chat lines have a flavour prefix (`"Woah!"`, `"Oi!"`, `"Yikes!"`,
 * `"Uh oh!"`, `"Oh!"`, `"Good Grief!"`, …) that varies by rarity — we strip a single leading
 * flavour-word token (anything ending with `!` plus the trailing space) before matching the
 * tail against `"You dug out (a|an|the)? <name>!"`.
 *
 * **Article handling.** Singular mobs ship as `"a Minos Hunter"` / `"an Inquisitor"`;
 * plurals ship as `"Siamese Lynxes"` with no article. The regex accepts either form.
 *
 * **Catalog membership rule.** Every mob a player can dig out of a Griffin Burrow lives
 * here. Mobs with multiple flavour-prefix variants (Siamese Lynxes has three observed:
 * `Woah!` / `Yikes!` / `Uh oh!`) are still ONE entry — the prefix is dropped at match
 * time, not stored. Updating the catalog is a one-line addition.
 *
 * Two rare mobs (`"Manticore"`, `"King Minos"`) are included with their canonical names —
 * actual chat lines for them aren't in the source notes but the dig-out structure is
 * identical, so the same regex catches them as soon as they appear.
 */
object MythologicalMobCatalog {
    /**
     * @param name Canonical mob name — matches the noun in the dig-out chat line.
     * @param displayName User-facing label rendered in the HUD row. Same as [name] for
     *   every current mob; kept separate so future plural-name normalisation has a hook.
     * @param rarity Skyblock rarity tier — drives the row's name color via
     *   [SkyblockRarity]. Tier assignment mirrors SkyHanni's `DianaMobType`.
     */
    data class Mob(
        val name: String,
        val displayName: String,
        val rarity: String,
    )

    /**
     * Read-through accessor that resolves the catalog from
     * [MythologicalMobCatalogClient]'s in-memory snapshot. The client backs its snapshot
     * with a disk cache so even an offline first launch returns useful data (after the
     * first successful fetch ever). Rebuilds on every call — cheap (12 mobs today, no
     * realistic growth path past 50).
     *
     * `displayName` from the backend serves as the chat-line noun (the dig-out catalog
     * matches against it directly); `name` and `displayName` are the same for every mob
     * today and the dual field is kept for the optional plural-normalisation hook noted
     * in the [Mob] kdoc.
     */
    private fun currentMobs(): List<Mob> =
        MythologicalMobCatalogClient.snapshot.mobs.map { entry ->
            Mob(name = entry.displayName, displayName = entry.displayName, rarity = entry.rarity)
        }

    private fun currentByName(): Map<String, Mob> = currentMobs().associateBy { it.name }

    // "You dug out (a|an|the)? <Name>!" anchored against the post-prefix tail. Non-greedy
    // noun + no `$` anchor on purpose — third-party chat-counter mods append arbitrary
    // suffixes after the `!` (e.g. `" §e(92)"`, `" (8/10) [2]"`). Every dig-out line is
    // exactly ONE mob; the parenthetical numbers are NOT Hypixel chat-compactor counts and
    // must not be treated as a delta.
    private val DIG_OUT_TAIL = Regex("""^You dug out (?:an?|the) (.+?)!""")
    private val DIG_OUT_TAIL_NO_ARTICLE = Regex("""^You dug out (.+?)!""")

    // "CAUGHT! You cocooned (a|an|the) [<prefix> ]<Name>!" — the prefix is one of
    // [COCOON_PREFIXES] (mob strength tier) which we strip before catalog lookup. Same
    // suffix-tolerance rule as the dig-out regex (no `$` anchor).
    private val COCOON_TAIL = Regex("""^CAUGHT! You cocooned (?:an?|the) (.+?)!""")

    /**
     * Strength-tier prefixes that may appear between the article and the base mob name on
     * a cocoon line — e.g. `"CAUGHT! You cocooned a Empyrean Cretan Bull!"`. The base mob
     * is what we count; the prefix only conveys how strong this individual encounter was
     * and is intentionally discarded.
     *
     * "Nothing" in the user-provided list means no prefix at all (the catalog name appears
     * directly), so it's not in this set.
     */
    private val COCOON_PREFIXES = setOf("Blessed", "Stalwart", "Venerable", "Exalted", "Empyrean")

    /**
     * Match a color-stripped Hypixel chat line to a mob. Returns null if the line doesn't
     * look like a Mythological dig-out line OR if the named mob isn't in [mobs].
     *
     * Each line is **exactly one mob** by Hypixel design — players can only dig one mob at
     * a time. Any trailing parenthetical / bracket suffix after the `!` is appended by
     * third-party chat mods (counters, compacting overlays) and is intentionally ignored.
     *
     * Unmatched named mobs (catalog out of date vs a Hypixel addition) are deliberately
     * dropped — the [MythologicalMobTracker] caller can log them so the catalog can be
     * extended in a later release.
     */
    fun matchDigOut(strippedLine: String): Mob? {
        val tail = stripFlavourPrefix(strippedLine)
        val match =
            DIG_OUT_TAIL.find(tail)
                ?: DIG_OUT_TAIL_NO_ARTICLE.find(tail)
                ?: return null
        val name = match.groupValues[1]
        return currentByName()[name]
    }

    /**
     * Match a Hypixel "cocooned" chat line to its base mob. Returns null if the line
     * isn't a cocoon line OR if the base mob name isn't in the catalog.
     *
     * Strips the optional [COCOON_PREFIXES] strength-tier word before lookup so
     * `"CAUGHT! You cocooned a Empyrean Cretan Bull!"` and
     * `"CAUGHT! You cocooned a Cretan Bull!"` both resolve to the `Cretan Bull` entry.
     */
    fun matchCocoon(strippedLine: String): Mob? {
        val match = COCOON_TAIL.find(strippedLine) ?: return null
        val raw = match.groupValues[1]
        val name = stripCocoonPrefix(raw)
        return currentByName()[name]
    }

    private fun stripCocoonPrefix(raw: String): String {
        val space = raw.indexOf(' ')
        if (space <= 0) return raw
        val first = raw.substring(0, space)
        return if (first in COCOON_PREFIXES) raw.substring(space + 1) else raw
    }

    /**
     * True if the given color-stripped line looks like ANY "You dug out a ..." Hypixel
     * line — mob OR burrow OR rare drop. Used by the activity timer's start trigger, which
     * fires on every dig-out regardless of the noun (so a long burrow chain still counts
     * as "actively engaged" even between mob digs).
     */
    fun isDigOutLine(strippedLine: String): Boolean {
        val tail = stripFlavourPrefix(strippedLine)
        return DIG_OUT_TAIL.containsMatchIn(tail) || DIG_OUT_TAIL_NO_ARTICLE.containsMatchIn(tail)
    }

    /** All canonical mob names — used to seed empty-HUD rows so the panel isn't blank pre-first-kill. */
    fun all(): List<Mob> = currentMobs()

    fun byName(name: String): Mob? = currentByName()[name]

    private val FLAVOUR_PREFIX = Regex("""^[A-Za-z][A-Za-z !']*?!\s+(?=You dug out)""")

    /**
     * Strip a single leading flavour-word token (anything ending with `!` followed by
     * whitespace) IF it precedes "You dug out". Returns the original string when no prefix
     * is present (e.g. a `"RARE DROP! You dug out ..."` line) — those start with a known
     * Hypixel keyword and we let the tail regex reject the noun if it's not a mob.
     *
     * Examples:
     *  - `"Woah! You dug out a Stranded Nymph!"` → `"You dug out a Stranded Nymph!"`
     *  - `"You dug out a Griffin Burrow! (9/10)"` → unchanged (no prefix)
     *  - `"RARE DROP! You dug out a Griffin Feather!"` → unchanged (we keep `RARE DROP!`
     *    so the tail regex matches; the noun isn't in the mob catalog so it gets dropped
     *    at lookup, which is the right outcome).
     */
    private fun stripFlavourPrefix(line: String): String {
        val match = FLAVOUR_PREFIX.find(line) ?: return line
        return line.substring(match.range.last + 1)
    }
}
