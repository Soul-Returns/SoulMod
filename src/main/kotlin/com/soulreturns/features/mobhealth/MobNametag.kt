package com.soulreturns.features.mobhealth

import java.util.Locale

/** A SkyBlock mob's nametag, parsed. [max] is null for tags that only show current health. */
data class MobHealth(
    val name: String,
    val level: Int?,
    val current: Double,
    val max: Double?,
    val currentText: String,
    val maxText: String?,
) {
    /** Remaining health as 0..1, or null without a max. */
    val fraction: Float?
        get() = max?.takeIf { it > 0 }?.let { (current / it).toFloat().coerceIn(0f, 1f) }
}

/**
 * Parses Hypixel's mob nametags. Shapes from a packet log (custom name of the armor stand above the
 * mob, legacy codes inline):
 * - `§8[§7Lv51§8] §5<U+E078> §cEnderman§r §a6,000§f/§a6,000§c❤`
 * - `§8[§7Lv183§8] §5<U+E078> §cVoidling Fanatic§r §a394.9k§f/§a750k§c❤`
 * - `§8[§7Lv655§8] §5<U+E078> §5Voidgloom Seraph IV §e68.6M§f/§a210M§c❤`
 * - `§8[§7Lv21§8] §9<U+E072>§e<U+E07B> §5§ka§r§5Corrupted Dumpster Diver§r§5§ka§r §a2,125§f/§a2,500§c❤`
 *
 * The private-use glyphs are mob-type icons from Hypixel's resource pack; the `§k` runs are
 * obfuscated filler. Both are dropped. Numbers use commas below 100k and `k`/`M`/`B` above.
 * Damage indicators (`✧12,345✧❤`) have no name and do not match.
 */
object MobNametag {
    private val CODE = Regex("§.")
    private val OBFUSCATED = Regex("§[kK][^§]*")
    private val SPACES = Regex("\\s+")
    private const val AMOUNT = """\d[\d,]*(?:\.\d+)?[kMB]?"""
    private val PATTERN = Regex("""^(?:\[Lv(\d+)]\s+)?(.+?)\s+($AMOUNT)(?:/($AMOUNT))?❤$""")

    /** Legacy-formatted text without codes, obfuscated runs and resource-pack icons. */
    fun plain(legacy: String): String {
        val noCodes = legacy.replace(OBFUSCATED, "").replace(CODE, "")
        val sb = StringBuilder(noCodes.length)
        for (c in noCodes) if (c !in '\uE000'..'\uF8FF') sb.append(c)
        return sb.toString().replace(SPACES, " ").trim()
    }

    /** Parses a tag already passed through [plain]; null when it is not a mob health tag. */
    fun parse(plain: String): MobHealth? {
        val m = PATTERN.matchEntire(plain) ?: return null
        val name = m.groupValues[2].trim()
        if (name.isEmpty()) return null
        val current = amount(m.groupValues[3]) ?: return null
        val maxText = m.groups[4]?.value
        val max = maxText?.let { amount(it) ?: return null }
        return MobHealth(name, m.groups[1]?.value?.toIntOrNull(), current, max, m.groupValues[3], maxText)
    }

    private fun amount(text: String): Double? {
        val s = text.replace(",", "")
        val mul =
            when (s.last()) {
                'k' -> 1e3
                'M' -> 1e6
                'B' -> 1e9
                else -> 1.0
            }
        val digits = if (mul == 1.0) s else s.dropLast(1)
        return digits.toDoubleOrNull()?.times(mul)
    }

    /** `93%` style percentage for [fraction]. */
    fun percent(fraction: Float): String = String.format(Locale.ROOT, "%d%%", (fraction * 100).toInt())
}
