package com.soulreturns.data.fishing

import com.google.gson.JsonParser
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import java.io.InputStreamReader

/**
 * Lookup of sea-creature catch chat messages → [SeaCreature].
 *
 * Loaded once from the bundled `assets/soul/sea_creatures.json` snapshot of SkyHanni's
 * `SeaCreatures.json` repo data. The catalog indexes both the canonical `chat_message` and
 * every entry in `alternate_messages` (with color codes stripped) so a single map probe on
 * any incoming chat line is enough to detect a catch.
 *
 * Refresh the snapshot manually when SkyHanni adds new creatures — there is no live repo
 * fetch by design.
 */
object SeaCreatureCatalog {
    private val logger = SoulLogger("Soul/Fishing")
    private const val RESOURCE_PATH = "/assets/soul/sea_creatures.json"

    private var byCleanMessage: Map<String, SeaCreature> = emptyMap()
    private var byName: Map<String, SeaCreature> = emptyMap()

    fun init() {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
        if (stream == null) {
            logger.warn("Sea creature catalog missing at $RESOURCE_PATH — catches will not be detected")
            return
        }
        try {
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                val msgIndex = mutableMapOf<String, SeaCreature>()
                val nameIndex = mutableMapOf<String, SeaCreature>()
                for ((variantName, variantElement) in root.entrySet()) {
                    val variant = variantElement.asJsonObject
                    val seaCreatures = variant.getAsJsonObject("sea_creatures") ?: continue
                    for ((creatureName, creatureElement) in seaCreatures.entrySet()) {
                        val obj = creatureElement.asJsonObject
                        val chatMessage = obj.get("chat_message")?.asString ?: continue
                        val creature =
                            SeaCreature(
                                name = creatureName,
                                variant = variantName,
                                rarity = obj.get("rarity")?.asString ?: "UNKNOWN",
                                rare = obj.get("rare")?.asBoolean ?: false,
                                fishingExperience = obj.get("fishing_experience")?.asInt ?: 0,
                            )
                        nameIndex[creatureName] = creature
                        addLookup(msgIndex, chatMessage, creature)
                        val alts = obj.getAsJsonArray("alternate_messages")
                        alts?.forEach { altElement ->
                            altElement.asString?.let { addLookup(msgIndex, it, creature) }
                        }
                    }
                }
                byCleanMessage = msgIndex
                byName = nameIndex
                logger.info("Loaded ${nameIndex.size} sea creatures (${msgIndex.size} message keys)")
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse sea creature catalog", e)
        }
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
}
