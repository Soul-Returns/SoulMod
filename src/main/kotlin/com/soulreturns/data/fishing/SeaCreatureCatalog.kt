package com.soulreturns.data.fishing

import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import java.util.Locale

/**
 * Chat line → [SeaCreature]. The lookup half of the sea-creature catalog; [SeaCreatureCatalogClient]
 * owns the data and this owns the index over it.
 *
 * ### The index is rebuilt on snapshot swap, not per lookup
 * A catch is detected by probing a map with a colour-stripped chat line, which happens for every
 * server line the player sees while fishing. Walking ~70 entries and their alternate messages per
 * line instead would be a hot path with no reason to exist. So the maps are rebuilt exactly when the
 * catalog changes: once at startup, and once per realtime-driven refresh.
 *
 * ### Colour codes are stripped on both sides
 * `alternateMessages` may carry `§<char>` codes — they are stored raw on the backend for SkyHanni
 * parity — and Hypixel emits non-standard placeholder codes (`§y`, `§u`) that only
 * [MessageDetector.stripColorCodes] handles, since it strips `§` plus *any* following character.
 * Both the index keys and the incoming line go through it, so one map probe settles the question.
 */
object SeaCreatureCatalog {
    private val logger = SoulLogger("Soul/Fishing")

    @Volatile private var byCleanMessage: Map<String, SeaCreature> = emptyMap()

    @Volatile private var byName: Map<String, SeaCreature> = emptyMap()

    fun init() {
        rebuild(SeaCreatureCatalogClient.snapshot)
        SeaCreatureCatalogClient.addListener(::rebuild)
    }

    private fun rebuild(snapshot: SeaCreatureCatalogSnapshot) {
        val messages = HashMap<String, SeaCreature>()
        val names = HashMap<String, SeaCreature>()
        for (entry in snapshot.creatures) {
            if (entry.displayName.isBlank()) continue
            val creature = SeaCreature(
                name = entry.displayName,
                variant = entry.variant,
                rarity = entry.rarity,
                rare = entry.rare,
                fishingExperience = entry.fishingExperience ?: 0,
            )
            names[entry.displayName] = creature
            index(messages, entry.chatMessage, creature)
            entry.alternateMessages.forEach { index(messages, it, creature) }
        }
        byCleanMessage = messages
        byName = names
        logger.info("Indexed ${names.size} sea creatures (${messages.size} message keys)")
    }

    /**
     * `chatMessage` carries a UNIQUE constraint on the backend precisely so this map cannot be
     * shadowed, but `alternateMessages` does not — so a collision is possible through an admin edit
     * and is worth saying out loud rather than resolving silently by insertion order.
     */
    private fun index(
        into: MutableMap<String, SeaCreature>,
        rawMessage: String,
        creature: SeaCreature,
    ) {
        val clean = MessageDetector.stripColorCodes(rawMessage).trim()
        if (clean.isEmpty()) return
        val existing = into[clean]
        if (existing != null && existing.name != creature.name) {
            logger.warn("Catalog message collision on '$clean' — kept ${existing.name}, dropped ${creature.name}")
            return
        }
        into[clean] = creature
    }

    /** The creature whose catch line this is, or null. [message] may still carry colour codes. */
    fun match(message: String): SeaCreature? {
        val index = byCleanMessage
        if (index.isEmpty()) return null
        return index[MessageDetector.stripColorCodes(message).trim()]
    }

    /** Look up by display name ("Sea Archer"). Used to resolve the name out of a cocoon line. */
    fun byName(name: String): SeaCreature? = byName[name]

    /** Every creature the catalog currently knows. */
    fun all(): Collection<SeaCreature> = byName.values

    fun isEmpty(): Boolean = byName.isEmpty()

    /**
     * Every `variant` key with at least one creature, ordered by display name so the tracker's filter
     * dropdown lists them the same way twice running.
     */
    fun variants(): List<String> =
        byName.values.asSequence().map { it.variant }.filter { it.isNotEmpty() }.distinct()
            .sortedBy { it.toDisplayName() }.toList()
}

/**
 * `LAVA_CRIMSON_ISLE` → `Lava Crimson Isle`. Matches SkyHanni's own formatting so a variant reads the
 * same in Soul as in the tracker a player may already be running beside it.
 */
fun String.toDisplayName(): String =
    split('_').joinToString(" ") { word ->
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
