package com.soulreturns.features.profit.dragon

/**
 * Whether a dragon kill is attributed to the local player as a summoner or as a lootsharer.
 *
 * - [SUMMONED] — the player placed at least one Summoning Eye toward this spawn. The eye cost is
 *   subtracted from the profit.
 * - [LOOTSHARE] — the player placed none, having joined a nest where the dragon was already up. No
 *   eye cost.
 *
 * [EyePlacementTracker] decides per spawn: at the `☬ The <Type> Dragon has spawned!` line, one or
 * more own eyes pending means SUMMONED, otherwise LOOTSHARE.
 *
 * **[LOOTSHARE] is the default**, and that is a deliberate asymmetry. If the player misses the spawn
 * message — lag, a late join, a relog mid-kill — lootshare only declines to charge an eye cost.
 * Defaulting the other way would invent one.
 */
enum class KillSource {
    SUMMONED,
    LOOTSHARE,
    ;

    /**
     * The label chat output and the panel's Source picker show.
     *
     * **`if/else`, not `when (this)`**: a subject `when` over an enum makes Kotlin emit a synthetic
     * `KillSource$WhenMappings` class carrying an ordinal table, and Fabric's KnotClassLoader has been
     * seen to fail to resolve that synthetic at runtime with a bare `NoClassDefFoundError`. If a third
     * variant is ever added, extend this as an `if/else if` chain for the same reason.
     */
    val displayName: String
        get() = if (this == SUMMONED) "Summoned" else "Lootshare"
}
