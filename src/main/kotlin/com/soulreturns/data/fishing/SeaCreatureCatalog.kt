package com.soulreturns.data.fishing

import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * Lookup of sea-creature catch chat messages → [SeaCreature].
 *
 * **Backend-driven (post-migration).** The catalog was originally a bundled
 * `assets/soul/sea_creatures.json` snapshot loaded once at startup. It now reads from
 * [SeaCreatureCatalogClient]'s in-memory snapshot, which the backend admin UI curates.
 * The on-startup `init` step subscribes to snapshot-change events so a Mercure invalidate
 * → re-fetch automatically rebuilds the lookup maps.
 *
 * Both the canonical `chatMessage` and every entry in `alternateMessages` (with color
 * codes stripped) feed the lookup map so a single map probe on any incoming chat line
 * detects a catch.
 */
object SeaCreatureCatalog {
    private val logger = SoulLogger("Soul/Fishing")

    @Volatile private var byCleanMessage: Map<String, SeaCreature> = emptyMap()

    @Volatile private var byName: Map<String, SeaCreature> = emptyMap()

    fun init() {
        rebuild()
        SeaCreatureCatalogClient.addListener(::rebuild)
    }

    /**
     * Rebuild the lookup maps from the current [SeaCreatureCatalogClient] snapshot. Called
     * once at init time and again on every Mercure-driven refresh. Cheap (~70 creatures).
     */
    private fun rebuild() {
        val msgIndex = mutableMapOf<String, SeaCreature>()
        val nameIndex = mutableMapOf<String, SeaCreature>()
        for (entry in SeaCreatureCatalogClient.snapshot.creatures) {
            val creature =
                SeaCreature(
                    name = entry.displayName,
                    variant = entry.variant,
                    rarity = entry.rarity,
                    rare = entry.rare,
                    fishingExperience = entry.fishingExperience ?: 0,
                )
            nameIndex[entry.displayName] = creature
            addLookup(msgIndex, entry.chatMessage, creature)
            entry.alternateMessages.forEach { alt -> addLookup(msgIndex, alt, creature) }
        }
        byCleanMessage = msgIndex
        byName = nameIndex
        logger.info("Indexed ${nameIndex.size} sea creatures (${msgIndex.size} message keys)")
    }

    private fun addLookup(
        index: MutableMap<String, SeaCreature>,
        rawMessage: String,
        creature: SeaCreature,
    ) {
        val clean = MessageDetector.stripColorCodes(rawMessage).trim()
        if (clean.isEmpty()) return
        val existing = index[clean]
        if (existing != null && existing.name != creature.name) {
            logger.warn("Sea creature catalog key collision on '$clean' — kept ${existing.name}, dropping ${creature.name}")
            return
        }
        index[clean] = creature
    }

    /** Returns the [SeaCreature] for [message] (raw chat text — color codes are stripped internally). */
    fun match(message: String): SeaCreature? {
        if (byCleanMessage.isEmpty()) return null
        val clean = MessageDetector.stripColorCodes(message).trim()
        return byCleanMessage[clean]
    }

    /** Lookup by canonical creature name (e.g. "Sea Archer"). Useful for stats display. */
    fun byName(name: String): SeaCreature? = byName[name]

    /** All creatures currently in the catalog. */
    fun all(): Collection<SeaCreature> = byName.values

    /**
     * Set of every `variant` key that has at least one creature in the catalog. Sorted by
     * display name so dropdown pickers iterate in a deterministic, user-friendly order.
     */
    fun variants(): List<String> =
        byName.values.asSequence()
            .map { it.variant }
            .distinct()
            .sortedBy { it.toDisplayName() }
            .toList()

    /**
     * Friendly display name for a variant key: underscores become spaces, each word
     * title-cased. `LAVA_CRIMSON_ISLE` → `Lava Crimson Isle`. Matches the SkyHanni
     * `allLettersFirstUppercase()` formatting so the dropdown labels read the same as
     * users already see in SkyHanni's sea-creature tracker.
     */
    fun String.toDisplayName(): String =
        split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase() }
        }
}
