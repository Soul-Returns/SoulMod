package com.soulreturns.features.profit.dragon

/**
 * Whether a dragon kill is attributed to the local player as a summoner or as a lootsharer.
 *
 * - [SUMMONED] — the player placed **at least one** Summoning Eye that contributed to the
 *   spawn. The eye cost subtracts from profit.
 * - [LOOTSHARE] — the player placed zero eyes for this spawn (joined someone else's nest
 *   after the dragon was already up). No eye cost subtracted. Default state when the
 *   tracker doesn't know — better to under-attribute eye cost than mis-charge a player
 *   who lootshared.
 *
 * Determined per-spawn by [EyePlacementTracker]: on the `"☬ The <Type> Dragon has spawned!"`
 * chat line, if `pendingOwnEyes > 0` → SUMMONED, else LOOTSHARE. Stays valid until the next
 * spawn message, so the death banner + loot scanner attribute drops to the correct
 * partition.
 */
enum class KillSource {
    SUMMONED,
    LOOTSHARE,
    ;

    /**
     * Display label used in chat / dev-command output (`"Summoned"`, `"Lootshare"`).
     * **Deliberately implemented as `if/else` rather than `when (this)`** — Kotlin compiles
     * enum-`when` into a synthetic `KillSource$WhenMappings` inner class with an ordinal
     * mapping table, and Fabric's KnotClassLoader has been seen to fail to resolve that
     * synthetic at runtime (`NoClassDefFoundError`). With only two variants the if/else is
     * also marginally clearer; if a third variant is ever added, prefer `if/else if` chain
     * over `when (this)` for the same reason.
     */
    val displayName: String
        get() = if (this == SUMMONED) "Summoned" else "Lootshare"
}
