package com.soulreturns.features.inventory

import com.soulreturns.data.hypixel.SkillCaps
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.calc.LevelCalculator
import com.soulreturns.data.hypixel.calc.LevelInfo
import com.soulreturns.data.hypixel.calc.XpTables
import com.soulreturns.data.skyblock.SelfProfileSnapshot
import com.soulreturns.data.skyblock.TabSkyblockLevel
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * One rendered stat: a label, a value, an optional 0..1 progress bar, the tooltip shown on hover
 * ([details], empty = no tooltip) and an optional [valueColor] (the SkyBlock Level bracket color).
 */
data class InfoLine(
    val label: String,
    val value: String,
    val progress: Double? = null,
    val maxed: Boolean = false,
    val details: List<Component> = emptyList(),
    val valueColor: Int? = null,
)

/** What an entry is computed from: the local player's snapshot plus their skill caps, if known. */
class InfoContext(val snapshot: SelfProfileSnapshot, val caps: SkillCaps?) {
    val member: SkyblockProfileMember get() = snapshot.member
}

/**
 * One thing the inventory can show. [id] is what the config stores, so it must never change;
 * [title] is the name in the settings list; [sample] is what the preview draws without real data.
 * [compute] returns null when the profile has nothing to say (API off, never played it).
 */
class InfoEntry(
    val id: String,
    val title: String,
    val sample: InfoLine,
    val compute: (InfoContext) -> InfoLine?,
)

/**
 * Every available entry, in the order the settings list offers them. Labels are short on purpose:
 * the free area is about 90 vanilla-font pixels wide, so levels are shown as bare numbers.
 */
object InventoryInfoEntries {
    const val PROFILE = "profile"

    val ALL: List<InfoEntry> by lazy { build() }

    private val byId: Map<String, InfoEntry> by lazy { ALL.associateBy { it.id } }

    fun byId(id: String): InfoEntry? = byId[id]

    private val SLAYER_LABELS = mapOf(
        "zombie" to "Revenant",
        "spider" to "Tarantula",
        "wolf" to "Sven",
        "enderman" to "Voidgloom",
        "blaze" to "Inferno",
        "vampire" to "Vampire",
    )

    /**
     * Hypixel's SkyBlock Level bracket colors, 40 levels each (the table SkyHanni's
     * `SkyBlockXPApi` carries), as the chat color's RGB.
     */
    private val LEVEL_COLORS = intArrayOf(
        0xAAAAAA, 0xFFFFFF, 0xFFFF55, 0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA,
        0x5555FF, 0xFF55FF, 0xAA00AA, 0xFFAA00, 0xFF5555, 0xAA0000,
    )

    fun levelColor(level: Int): Int = 0xFF000000.toInt() or LEVEL_COLORS[(level / 40).coerceIn(0, LEVEL_COLORS.lastIndex)]

    /** The tab arrives before REST and stays current as XP changes; the API entry remains fallback. */
    fun tabLevel(info: TabSkyblockLevel): InfoLine {
        val progress = info.progress
        if (progress == null) return InfoLine("SB Level", info.level.toString(), valueColor = levelColor(info.level))
        val start = info.level * 100L
        return level("SB Level", "SkyBlock Level", LevelInfo(info.level, progress, start, start + 100), null, " XP")
            .copy(valueColor = levelColor(info.level))
    }

    private fun build(): List<InfoEntry> {
        val out = ArrayList<InfoEntry>()
        out += InfoEntry("skyblock_level", "SkyBlock Level", InfoLine("SB Level", "287", 0.42, valueColor = levelColor(287))) { ctx ->
            ctx.member.skyblockLevel?.let { level("SB Level", "SkyBlock Level", it, null, unit = " XP").copy(valueColor = levelColor(it.level)) }
        }
        out += InfoEntry(PROFILE, "Profile", InfoLine("Profile", "Banana")) { ctx ->
            InfoLine("Profile", ctx.snapshot.profileName)
        }
        out += InfoEntry("skill_average", "Skill Average", InfoLine("Skill Avg", "47.25")) { ctx ->
            ctx.member.skillAverageCapped(ctx.caps)?.let { average ->
                val value = String.format(Locale.ROOT, "%.2f", average)
                val details = mutableListOf(title("Skill Average", value), line("Across ", "${ctx.member.skillCount} skills"))
                if (ctx.caps == null) details += Component.literal("Farming and Taming caps unknown").withStyle(ChatFormatting.DARK_GRAY)
                InfoLine("Skill Avg", value, details = details)
            }
        }
        out += InfoEntry("catacombs", "Catacombs Level", InfoLine("Catacombs", "42", 0.67)) { ctx ->
            if (ctx.member.hasDungeons) level("Catacombs", "Catacombs", ctx.member.catacombsLevel, ctx.member.catacombsExperience) else null
        }
        out += InfoEntry("selected_class", "Selected Class", InfoLine("Mage", "38", 0.15)) { ctx ->
            ctx.member.dungeonClasses.firstOrNull { it.selected }?.let { level(it.displayName, it.displayName, it.level, it.experience) }
        }
        out += InfoEntry("class_average", "Class Average", InfoLine("Class Avg", "36.4")) { ctx ->
            if (ctx.member.hasDungeons) {
                val value = String.format(Locale.ROOT, "%.1f", ctx.member.classAverage)
                val details = mutableListOf(title("Class Average", value))
                for (c in ctx.member.dungeonClasses) {
                    val entry = line("${c.displayName} ", c.level.level.toString())
                    if (c.selected) entry.append(Component.literal("  selected").withStyle(ChatFormatting.AQUA))
                    details += entry
                }
                InfoLine("Class Avg", value, details = details)
            } else {
                null
            }
        }
        out += InfoEntry("secrets", "Dungeon Secrets", InfoLine("Secrets", "12.4k")) { ctx ->
            ctx.member.dungeonSecrets?.let {
                InfoLine("Secrets", compact(it.toLong()), details = listOf(title("Dungeon Secrets", String.format(Locale.ROOT, "%,d", it))))
            }
        }
        for (def in XpTables.skills) {
            out += InfoEntry("skill_${def.key}", "${def.displayName} Level", InfoLine(def.displayName, "50", 0.5)) { ctx ->
                ctx.member.skillsCapped(ctx.caps).firstOrNull { it.def.key == def.key }?.let {
                    level(def.displayName, def.displayName, it.level, it.experience)
                }
            }
        }
        for (def in XpTables.slayers) {
            val label = SLAYER_LABELS[def.key] ?: def.bossName
            out += InfoEntry("slayer_${def.key}", "$label Slayer", InfoLine(label, "7", 0.3)) { ctx ->
                ctx.member.slayers.firstOrNull { it.def.key == def.key }?.let {
                    val stat = level(label, "$label Slayer", it.level, it.experience)
                    stat.copy(details = stat.details + line("Bosses killed ", String.format(Locale.ROOT, "%,d", it.totalKills)))
                }
            }
        }
        return out
    }

    /**
     * A leveled stat. The tooltip shows progress through the level, or the overflow past the cap.
     * [experience] is the total XP when the stat has one; SkyBlock Level only has its progress.
     */
    private fun level(label: String, name: String, info: LevelInfo, experience: Double?, unit: String = ""): InfoLine {
        val details = mutableListOf<Component>()
        val header = title(name, info.level.toString())
        if (info.maxed) header.append(Component.literal(" MAX").withStyle(ChatFormatting.GOLD))
        details += header
        if (info.maxed) {
            if (experience != null) {
                details += line("Total XP ", LevelCalculator.formatXp(experience))
                val overflow = experience - info.currentLevelXp
                if (overflow >= 1) details += Component.literal("+${LevelCalculator.formatXp(overflow)} overflow").withStyle(ChatFormatting.GREEN)
            }
        } else {
            val into = experience?.let { info.xpIntoLevel(it).toDouble() } ?: (info.progress * info.levelSpanXp)
            details += line(
                "Progress ",
                "${LevelCalculator.formatXp(into)}/${LevelCalculator.formatXp(info.levelSpanXp.toDouble())}$unit",
                String.format(Locale.ROOT, " (%.1f%%)", info.progressPct()),
            )
            details += line("Next level in ", LevelCalculator.formatXp(info.levelSpanXp - into) + unit)
            if (experience != null) details += line("Total XP ", LevelCalculator.formatXp(experience))
        }
        return InfoLine(label, info.level.toString(), if (info.maxed) 1.0 else info.progress, info.maxed, details)
    }

    private fun title(name: String, value: String) =
        Component.literal("$name ").withStyle(ChatFormatting.WHITE).append(Component.literal(value).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))

    private fun line(label: String, value: String, suffix: String = "") =
        Component.literal(label).withStyle(ChatFormatting.GRAY)
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(suffix).withStyle(ChatFormatting.DARK_GRAY))

    private fun compact(value: Long): String =
        if (value >= 1_000_000) String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
        else if (value >= 10_000) String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
        else value.toString()
}
