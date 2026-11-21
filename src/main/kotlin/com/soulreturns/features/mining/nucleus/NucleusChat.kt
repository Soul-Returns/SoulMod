package com.soulreturns.features.mining.nucleus

import com.soulreturns.util.MessageDetector

/** One reward line of the loot bundle: name as printed (colors and icon glyphs stripped) and amount. */
data class NucleusLoot(val name: String, val amount: Long)

/** What a Crystal Nucleus chat line means. Produced by [NucleusChatParser]. */
sealed interface NucleusEvent {
    /** `✦ CRYSTAL FOUND (n/total)` + the crystal line under it. */
    data class CrystalFound(val crystal: String, val index: Int, val total: Int) : NucleusEvent

    data class CrystalPlaced(val crystal: String) : NucleusEvent

    /** The `CRYSTAL NUCLEUS LOOT BUNDLE` block, emitted at its closing bar: the run boundary. */
    data class LootBundle(val items: List<NucleusLoot>) : NucleusEvent

    /** A consumable handed to an NPC for a crystal, by item name (Divan tools: [NucleusLines.kitOnFound]). */
    data class KitUsed(val name: String) : NucleusEvent

    /** `The boss looks weak and tired and retreats into the lava...`: Topaz available. */
    data object BalDown : NucleusEvent

    /** `The ground starts to shake and the lava bubbles...`: Bal spawning. */
    data object BalSpawning : NucleusEvent

    /** Any `[BOSS] Bal:` line except the death cry. */
    data object BalActive : NucleusEvent

    /** `You haven't earned this Crystal!` (Topaz while Bal is up; also seen in the temple and the den). */
    data object CrystalLocked : NucleusEvent
}

/**
 * Line patterns of a nucleus run, from capture `2026-11-07_17-13-51` (50 runs). Everything matches on
 * [clean] text: colors and Hypixel's private-use icon glyphs stripped, whitespace collapsed.
 */
object NucleusLines {
    const val LOOT_HEADER = "CRYSTAL NUCLEUS LOOT BUNDLE"
    const val LOOT_FOOTER = "Pick it up near the Nucleus Vault!"
    const val ALREADY_OBTAINED = "You have already obtained this Crystal!"
    const val BLOCKS_IN_WAY = "There are blocks in the way!"
    const val BAL_DOWN = "The boss looks weak and tired and retreats into the lava..."
    const val BAL_SPAWNING = "The ground starts to shake and the lava bubbles..."
    const val CRYSTAL_LOCKED = "You haven't earned this Crystal! Come back when you have! >:-)"

    val FOUND_HEADER = Regex("""^✦ CRYSTAL FOUND \((\d+)/(\d+)\)$""")
    val CRYSTAL_NAME = Regex("""^(.+) Crystal$""")
    val PLACED = Regex("""^✦ You placed the (.+) Crystal!$""")
    val LOOT_LINE = Regex("""^(.+?)(?: x([\d,]+))?$""")
    private val BAL_BOSS = Regex("""^\[BOSS] Bal: (.*)$""")
    private val BAL_DEATH_CRY = Regex("""^AGG+H+!$""")
    private const val APPARATUS = "[NPC] Professor Robot: Wait a minute. This will work just fine."
    private val ROBOT_PART = Regex("""^(?:\[NPC] Professor Robot: )?Thanks for bringing me the (.+?)! Bring me .*$""")
    private const val ROBOT_DONE = "You've brought me all of the components"
    private const val JUNGLE_KEY = "[NPC] Kalhuiki Door Guardian: A Jungle Key! I will open the door for you."
    private const val GOBLIN_EGG = "[NPC] King Yolkar: I'm covering you in my foul stench"

    /** Kit name of the egg line before the inventory says which kind it was (see [GoblinEggs]). */
    const val GOBLIN_EGG_ANY = "Goblin Egg"

    /** The six Precursor parts Professor Robot takes one by one (SkyHanni's list); together they replace the Apparatus. */
    val ROBOT_PARTS: Set<String> =
        linkedSetOf("Control Switch", "Electron Transmitter", "FTX 3070", "Robotron Reflector", "Superlite Motor", "Synthetic Heart")

    /** A part named in a `Thanks for bringing me the X!` line, or null. */
    fun robotPart(clean: String): String? = ROBOT_PART.matchEntire(clean)?.groupValues?.get(1)?.takeIf { it in ROBOT_PARTS }

    /** The Robot's closing line of a parts hand-in (it does not name the last part). */
    fun isRobotDone(clean: String): Boolean = clean.contains(ROBOT_DONE)

    /** Colors, private-use glyphs and repeated whitespace off; trimmed. */
    fun clean(raw: String): String =
        MessageDetector.stripColorCodes(raw).filterNot { it in '\uE000'..'\uF8FF' }.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    /** A run of 20+ `▬` and nothing else: the block delimiter. */
    fun isBar(clean: String): Boolean = clean.length >= 20 && clean.all { it == '▬' }

    /** Kit consumption by one line (Apparatus, Jungle Key, Goblin Egg), or null. Robot parts are stateful: [NucleusChatParser]. */
    fun kit(clean: String): NucleusEvent.KitUsed? {
        if (clean == APPARATUS) return NucleusEvent.KitUsed("Precursor Apparatus")
        if (clean == JUNGLE_KEY) return NucleusEvent.KitUsed("Jungle Key")
        if (clean.startsWith(GOBLIN_EGG)) return NucleusEvent.KitUsed(GOBLIN_EGG_ANY)
        return null
    }

    /** Single-line events outside the two blocks, or null. */
    fun single(clean: String): NucleusEvent? {
        PLACED.matchEntire(clean)?.let { return NucleusEvent.CrystalPlaced(it.groupValues[1]) }
        kit(clean)?.let { return it }
        if (clean == BAL_DOWN) return NucleusEvent.BalDown
        if (clean == BAL_SPAWNING) return NucleusEvent.BalSpawning
        if (clean == CRYSTAL_LOCKED) return NucleusEvent.CrystalLocked
        BAL_BOSS.matchEntire(clean)?.let { return if (BAL_DEATH_CRY.matches(it.groupValues[1])) null else NucleusEvent.BalActive }
        return null
    }

    /** `Flawed Ruby Gemstone x82` -> (name, 82); no amount = 1. Null for blank lines and `REWARDS`. */
    fun lootLine(clean: String): NucleusLoot? {
        if (clean.isEmpty() || clean == "REWARDS") return null
        val m = LOOT_LINE.matchEntire(clean) ?: return null
        val amount = m.groupValues[2].takeIf { it.isNotEmpty() }?.replace(",", "")?.toLongOrNull() ?: 1L
        return NucleusLoot(m.groupValues[1].trim(), amount)
    }

    /**
     * Hypixel's reward line indent: exactly four spaces, then the item (`§r    §r§6Helix Fossil` raw,
     * `    Helix Fossil` as a Component's plain text). The header has two, other mods' and players' lines none.
     */
    private val LOOT_INDENT = Regex("""^(?:§r)? {4}(?:§r§[0-9a-fk-or])?[^\s§]""")

    /** [lootLine] for a raw or plain chat line, only when it has Hypixel's indent; any other line is null. */
    fun lootLineRaw(raw: String): NucleusLoot? = if (LOOT_INDENT.containsMatchIn(raw)) lootLine(clean(raw)) else null
}

/**
 * Turns the chat stream into [NucleusEvent]s. Stateful only for the two multi-line blocks: the found
 * block (header, then the crystal line) and the loot bundle (header ... closing bar). Pure: no
 * Minecraft classes, fed with raw lines in arrival order.
 */
class NucleusChatParser {
    private var foundHeader: IntArray? = null
    private var foundWait = 0
    private var loot: ArrayList<NucleusLoot>? = null
    private val robotParts = LinkedHashSet<String>()

    fun feed(raw: String): NucleusEvent? {
        val line = NucleusLines.clean(raw)
        val items = loot
        if (items != null) {
            if (NucleusLines.isBar(line)) {
                loot = null
                return NucleusEvent.LootBundle(items)
            }
            NucleusLines.lootLineRaw(raw)?.let { items += it }
            // A bundle is ~20 lines; anything far longer lost its closing bar.
            if (items.size > MAX_LOOT_LINES) loot = null
            return null
        }
        if (line == NucleusLines.LOOT_HEADER) {
            loot = ArrayList()
            foundHeader = null
            return null
        }
        NucleusLines.FOUND_HEADER.matchEntire(line)?.let {
            foundHeader = intArrayOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            foundWait = 0
            return null
        }
        val header = foundHeader
        if (header != null) {
            if (line.isEmpty()) return null
            NucleusLines.CRYSTAL_NAME.matchEntire(line)?.let {
                foundHeader = null
                return NucleusEvent.CrystalFound(it.groupValues[1], header[0], header[1])
            }
            if (++foundWait >= 2) foundHeader = null
        }
        NucleusLines.robotPart(line)?.let {
            robotParts += it
            return NucleusEvent.KitUsed(it)
        }
        if (NucleusLines.isRobotDone(line) && robotParts.isNotEmpty()) {
            // The closing line does not name the last part: it is one not seen in this hand-in.
            val missing = NucleusLines.ROBOT_PARTS - robotParts
            robotParts.clear()
            return missing.firstOrNull()?.let { NucleusEvent.KitUsed(it) }
        }
        return NucleusLines.single(line)
    }

    private companion object {
        const val MAX_LOOT_LINES = 60
    }
}
