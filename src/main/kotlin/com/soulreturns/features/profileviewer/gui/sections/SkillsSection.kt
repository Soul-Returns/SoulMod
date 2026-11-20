package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.SkillProgress
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Cell
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Col
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.TableRow
import com.soulreturns.features.profileviewer.gui.kit.Ui
import java.util.Locale

/**
 * Every skill with its level, cap, progress and XP (mock `skillsSec`). Farming and Taming caps come
 * from Soul's server (`SkillCapsClient`); when it does not answer, both say CAP UNKNOWN and use the
 * published cap, which over-states an account without the unlocks (the safe direction).
 */
object SkillsSection : ProfileSection {
    override val id = "skills"
    override val title = "Skills"
    override val group = ProfileSection.GROUP_PROGRESS
    override val icon = "pv_bars"

    /** `.sk`: name 96 | level 58 | bar ≥ 80 | xp 128 | chips ≤ 190, 12 px apart, 26 px rows. */
    private val COLUMNS = listOf(
        Col(width = 96),
        Col(width = 58),
        Col(grow = true, min = 80),
        Col(width = 128, align = 1),
        Col(grow = true, min = 0, max = 190),
    )
    private const val ROW_H = 26
    private const val COL_GAP = 12

    /** `s.L.overflow > 1e5` in the mock: overflow XP is only worth a note past this. */
    private const val OVERFLOW_NOTE = 100_000.0

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.skillsCapped(ctx.caps).isEmpty()) "Skills API off" else null

    override fun badge(ctx: ProfileContext): String? =
        ctx.member.skillAverageCapped(ctx.caps)?.let { String.format(Locale.ROOT, "%.1f", it) }

    override fun build(ctx: ProfileContext, ui: Ui) {
        val skills = ctx.member.skillsCapped(ctx.caps)
        if (skills.isEmpty()) {
            ui.apiOff(ctx.name, ctx.isSelf, "Skills API", "skill levels and the Skill Average")
            return
        }
        val main = skills.filter { !it.def.cosmetic }
        val cosmetic = skills.filter { it.def.cosmetic }
        ui.tiles {
            tile("Skill Average", Format.avg(ctx.member.skillAverageCapped(ctx.caps) ?: 0.0), sub = "over ${main.size} skills, caps applied")
            tile("Maxed", "${main.count { it.level.maxed }} / ${main.size}", sub = "non-cosmetic skills")
            tile("Total skill XP", Format.short(skills.sumOf { it.experience }), sub = "all skills")
        }
        ui.card("Skills", span = Node.FULL) {
            table(COLUMNS, main.map { row(ctx, it) }, rowH = ROW_H, gap = COL_GAP)
            if (cosmetic.isNotEmpty()) {
                space(4)
                label("Cosmetic, not in the average")
                table(COLUMNS, cosmetic.map { row(ctx, it) }, rowH = ROW_H, gap = COL_GAP)
            }
        }
    }

    private fun row(ctx: ProfileContext, s: SkillProgress): TableRow {
        val l = s.level
        val cap = ctx.member.effectiveCap(s.def, ctx.caps)
        val into = (s.experience - l.currentLevelXp).coerceAtLeast(0.0)
        val xp = if (l.maxed) {
            Cell(Rich.colored("MAX", KitStyle.MAXED, bold = true), suffix = if (into > OVERFLOW_NOTE) Rich.of("+${Format.short(into)} XP") else null, suffixColor = KitStyle.TEXT_DIM)
        } else {
            Cell(Rich.of(Format.short(into)), suffix = Rich.of("/ ${Format.short(l.levelSpanXp)} XP"), suffixColor = KitStyle.TEXT_DIM)
        }
        return TableRow(
            listOf(
                Cell(Rich.of(s.def.displayName), bold = true, size = KitStyle.BODY),
                Cell(Rich.of("${l.level}"), bold = true, size = KitStyle.HEADING, color = if (l.maxed) KitStyle.MAXED else null, suffix = Rich.of("/ $cap")),
                Cell(bar = l.progress.toFloat(), barMaxed = l.maxed),
                xp,
                Cell(chips = capChips(ctx, s.def.key, cap)),
            ),
        )
    }

    /** Farming and Taming name where their cap comes from, or that it is unknown. */
    private fun capChips(ctx: ProfileContext, key: String, cap: Int): List<Chip> {
        if (key != "farming" && key != "taming") return emptyList()
        if (!ctx.capsKnown) return listOf(Chip.warn("CAP UNKNOWN", "Soul's server did not answer; the published cap is used"))
        val bonus = cap - 50
        return if (key == "farming") {
            listOf(Chip.plain("CAP $cap · JACOB'S PERKS $bonus/10", "Farming caps at 50 + Anita's farming level perks, answered by Soul's server"))
        } else {
            listOf(Chip.plain("CAP $cap · KAT $bonus/10", "Taming caps at 50 + unique pet types sacrificed to Kat, answered by Soul's server"))
        }
    }
}
