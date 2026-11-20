package com.soulreturns.features.profileviewer.gui.sections.mining

import com.soulreturns.data.hypixel.HotmNodeState
import com.soulreturns.data.hypixel.HotmProgress
import com.soulreturns.data.hypixel.HotmTree
import com.soulreturns.features.profileviewer.gui.kit.Format
import kotlin.math.max

/**
 * A Heart of the Mountain node as the game's menu item (mock `hotmItem`): the icon by state, the name
 * colored by progress, and lore with the level, the effect, the next upgrade and its cost, the
 * requirement and the SELECTED / DISABLED marks. Pure strings; [HotmItems] turns a [Spec] into a stack.
 */
object HotmLore {
    /** Menu icons: diamond = maxed, emerald = leveling, coal = locked; abilities are blocks; the Core is red. */
    const val DIAMOND = "diamond"
    const val EMERALD = "emerald"
    const val COAL = "coal"
    const val EMERALD_BLOCK = "emerald_block"
    const val COAL_BLOCK = "coal_block"
    const val CORE = "core"

    /** Everything an item is made of; equal specs share one stack ([HotmItems]). [count] is drawn by the tree. */
    data class Spec(val name: String, val lore: List<String>, val icon: String, val glint: Boolean = false, val count: Int = 1)

    private const val UPGRADE = "§a=====[ §a§lUPGRADE §a] ====="

    /** Visible characters per tooltip line, as the mock wraps. */
    private const val WRAP = 38

    fun item(tree: HotmTree, p: HotmProgress, n: HotmNodeState): Spec {
        val x = n.def
        val level = n.level
        val lore = ArrayList<String>()
        val icon: String
        if (x.isCore) {
            icon = if (level > 0) CORE else COAL_BLOCK
            val shown = max(level, 1)
            val levels = tree.core?.levels.orEmpty()
            lore += "§7Level $shown§8/${x.max}"
            lore += ""
            lore += "§7Rewards so far:"
            for (l in levels.take(shown)) lore += "  ${l.reward}"
            if (level > 0 && level < x.max) {
                lore += ""
                lore += UPGRADE
                lore += "§7Level ${level + 1}§8/${x.max}"
                levels.getOrNull(level)?.let { lore += "  ${it.reward}" }
                costLines(tree, n, lore)
            }
        } else {
            icon = if (x.isAbility) (if (level > 0) EMERALD_BLOCK else COAL_BLOCK) else if (level == 0) COAL else if (n.maxed) DIAMOND else EMERALD
            val shown = max(level, 1)
            if (x.isAbility) {
                lore += "§6Pickaxe Ability"
                lore += ""
            } else if (x.max > 1) {
                lore += "§7Level $shown${if (n.maxed) "" else "§8/${x.max}"}"
                lore += ""
            }
            lore += wrap(tree.effect(x, shown, p.tier))
            if (level > 0 && !n.maxed) {
                lore += ""
                lore += UPGRADE
                lore += "§7Level ${level + 1}§8/${x.max}"
                lore += wrap(tree.effect(x, level + 1, p.tier))
                costLines(tree, n, lore)
            }
            if (level == 0) {
                lore += ""
                lore += "§7Cost"
                lore += "§51 Token of the Mountain"
            }
        }
        if (!n.open) {
            lore += ""
            lore += "§cRequires Tier ${x.tier}"
        } else if (n.missing != null) {
            lore += ""
            lore += "§cRequires ${n.missing.name}"
        } else if (level == 0) {
            lore += ""
            lore += "§8Not unlocked"
        }
        if (n.selected) {
            lore += ""
            lore += "§a§lSELECTED"
        }
        if (n.disabled) {
            lore += ""
            lore += "§c§lDISABLED"
        }
        val nameColor = if (level == 0) "§c" else if (n.maxed) "§a" else "§e"
        val count = if (x.isCore) max(level, 1) else if (level > 1 && !n.maxed) level else 1
        return Spec("$nameColor${x.name}", lore, icon, glint = n.selected, count = count)
    }

    private fun costLines(tree: HotmTree, n: HotmNodeState, lore: MutableList<String>) {
        val cost = tree.costToReach(n.def, n.level + 1) ?: return
        val powder = tree.powderToReach(n.def, n.level + 1) ?: return
        lore += ""
        lore += "§7Cost"
        lore += "§${powder.color}${Format.num(cost)} ${powder.name} Powder"
    }

    /**
     * Splits `§` text into lines of at most [width] visible characters, the color carried across breaks
     * (mock `wrapLore`); `\n` starts a new paragraph.
     */
    fun wrap(text: String, width: Int = WRAP): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            val line = StringBuilder()
            var vis = 0
            var color = "§7"
            for (word in para.split(' ')) {
                val len = visibleLength(word)
                if (vis > 0 && vis + 1 + len > width) {
                    out += line.toString()
                    line.setLength(0)
                    line.append(color)
                    vis = 0
                }
                line.append(if (vis > 0) " " else if (line.isNotEmpty()) "" else "§7").append(word)
                vis += (if (vis > 0) 1 else 0) + len
                lastColor(word)?.let { color = it }
            }
            out += line.toString()
        }
        return out
    }

    private fun visibleLength(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i] == '§' && i + 1 < s.length) i += 2 else {
                n++
                i++
            }
        }
        return n
    }

    /** The last color code (`§0`..`§f`) in [s]; formatting codes (`§l`) do not count, as in the mock. */
    private fun lastColor(s: String): String? {
        var found: String? = null
        for (i in 0 until s.length - 1) {
            if (s[i] == '§' && s[i + 1] in "0123456789abcdef") found = s.substring(i, i + 2)
        }
        return found
    }

    /** `Tier N` tooltip of a tier label: the rewards (plain) and the state line (mock `.ht` `data-tip`). */
    fun tierTip(tree: HotmTree, p: HotmProgress, tier: Int): List<String> {
        val lines = ArrayList<String>()
        lines += "Tier $tier"
        tree.tiers.getOrNull(tier - 1)?.rewards?.forEach { lines += it.replace(CODE, "") }
        lines += if (tier <= p.tier) "Unlocked"
        else if (tier == p.tier + 1) "Progress: ${Format.num(p.xpIntoTier.toLong())} / ${Format.num(p.xpToNext ?: 0)} XP"
        else "Locked"
        return lines
    }

    private val CODE = Regex("§.")
}
