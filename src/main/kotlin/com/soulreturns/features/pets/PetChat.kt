package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.util.MessageDetector

/** A held pet item as shown: its display name and the legacy color code of that name. */
@kotlinx.serialization.Serializable
data class PetHeld(val name: String, val color: Char = 'f')

/**
 * Pet chat lines, as legacy `§` strings (formats captured 2026-11, docs/research/pet-hud-2026-11-09.md §2).
 * Pure string logic: the caller hands in `Component.toLegacyText()` of the line and its hover texts.
 */
object PetChat {
    sealed interface Line

    /** `Autopet equipped your [Lvl 100] Flying Fish! VIEW RULE`; the hover holds the rule's held item. */
    data class Autopet(val name: String, val level: Int, val rarity: SkyblockRarity?, val skinned: Boolean, val held: PetHeld?) : Line

    /** `You summoned your Mole!`: no level. */
    data class Summon(val name: String, val rarity: SkyblockRarity?, val skinned: Boolean) : Line

    /** `You despawned your Mole!` (wiki wording; not captured yet). */
    data class Despawn(val name: String) : Line

    /** `Your Orchid Mantis leveled up to level 95!`: Exp Share pets too, so match the active name. */
    data class LevelUp(val name: String, val level: Int) : Line

    /** `Your pet is now holding Hephaestus Relic.` */
    data class Holding(val held: PetHeld) : Line

    private val AUTOPET = Regex("Autopet equipped your \\[Lvl ([\\d,]+)] (?:\\[\\d+(✦)] )?(.+?)( ✦)?! VIEW RULE")
    private val SUMMON = Regex("You summoned your (.+?)( ✦)?!")
    private val DESPAWN = Regex("You despawned your (.+?)( ✦)?!")
    private val LEVEL_UP = Regex("Your (.+?) leveled up to level ([\\d,]+)!")
    private val HOLDING = Regex("Your pet is now holding (.+)\\.")
    private val HELD_ITEM = Regex("Held Item: (.+)")

    fun parse(legacy: String, hovers: List<String> = emptyList()): Line? {
        val plain = MessageDetector.stripColorCodes(legacy).trim()
        AUTOPET.matchEntire(plain)?.let { m ->
            val name = m.groupValues[3]
            return Autopet(
                name = name,
                level = m.groupValues[1].replace(",", "").toIntOrNull() ?: return null,
                rarity = rarityOf(legacy, name),
                skinned = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty(),
                held = hovers.firstNotNullOfOrNull(::heldIn),
            )
        }
        SUMMON.matchEntire(plain)?.let { m ->
            val name = m.groupValues[1]
            return Summon(name, rarityOf(legacy, name), m.groupValues[2].isNotEmpty())
        }
        DESPAWN.matchEntire(plain)?.let { return Despawn(it.groupValues[1]) }
        LEVEL_UP.matchEntire(plain)?.let { m ->
            return LevelUp(m.groupValues[1], m.groupValues[2].replace(",", "").toIntOrNull() ?: return null)
        }
        HOLDING.matchEntire(plain)?.let { m ->
            val name = m.groupValues[1].trim()
            return Holding(PetHeld(name, colorOf(legacy, name) ?: 'f'))
        }
        return null
    }

    /** The `Held Item: X` line of an autopet rule hover (or a pet item's lore), or null. */
    fun heldIn(legacyHover: String): PetHeld? {
        for (line in legacyHover.split('\n')) {
            val m = HELD_ITEM.matchEntire(MessageDetector.stripColorCodes(line).trim()) ?: continue
            val name = m.groupValues[1].trim()
            return PetHeld(name, colorOf(line, name) ?: 'f')
        }
        return null
    }

    fun rarityOf(legacy: String, name: String): SkyblockRarity? = colorOf(legacy, name)?.let(SkyblockRarity::forColorCode)

    /** The legacy color in effect where [text] starts (its last occurrence) in the color-stripped [legacy]. */
    fun colorOf(legacy: String, text: String): Char? {
        val at = MessageDetector.stripColorCodes(legacy).lastIndexOf(text)
        return if (at < 0) null else colorAt(legacy, at)
    }

    /** The legacy color code active at [strippedIndex] of the color-stripped text. */
    private fun colorAt(legacy: String, strippedIndex: Int): Char? {
        var color: Char? = null
        var visible = 0
        var i = 0
        while (i < legacy.length) {
            val c = legacy[i]
            if (c == '§' && i + 1 < legacy.length) {
                val code = legacy[i + 1].lowercaseChar()
                if (code in "0123456789abcdef") color = code else if (code == 'r') color = null
                i += 2
                continue
            }
            if (visible == strippedIndex) return color
            visible++
            i++
        }
        return null
    }
}
