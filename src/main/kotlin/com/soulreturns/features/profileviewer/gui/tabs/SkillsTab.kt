package com.soulreturns.features.profileviewer.gui.tabs

import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.features.profileviewer.gui.ProfileCanvas
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileTab
import com.soulreturns.features.profileviewer.gui.formatAverage

/**
 * Skills, split into the ones that count toward Skill Average and the two cosmetic ones that do not
 * (Runecrafting and Social) — because a player reading a Skill Average wants to know what went into
 * it, and mixing the cosmetics in is how that number gets quoted wrong.
 *
 * A skill Hypixel sent no XP for is **omitted**, not shown as level 0: the response cannot tell
 * "never played" from "played to level 0", and claiming the latter would be inventing data. That is
 * also why the header says what the average was taken over.
 */
object SkillsTab : ProfileTab {
    override val id = "skills"
    override val label = "Skills"

    override fun draw(c: ProfileCanvas, ctx: ProfileContext) {
        val m = ctx.member
        val skills = m.skillsCapped(ctx.skillCaps)
        if (skills.isEmpty()) {
            c.section("Skills") {
                note(
                    "No skill data. Either this is a brand-new profile, or ${ctx.identity.name} has " +
                        "turned skill sharing off in game (SkyBlock Menu → Settings → API Settings).",
                )
            }
            return
        }

        val counted = skills.filter { !it.def.cosmetic }
        val cosmetic = skills.filter { it.def.cosmetic }
        val average = m.skillAverageCapped(ctx.skillCaps)

        if (counted.isNotEmpty()) {
            val subtitle = buildString {
                append("Skill Average ")
                append(if (average == null) "—" else formatAverage(average))
                append(" over ${counted.size} skill")
                if (counted.size != 1) append("s")
            }
            c.section("Skills", subtitle) {
                for (skill in counted) levelRow(skill.def.displayName, skill.level, skill.experience)
            }
        }

        if (cosmetic.isNotEmpty()) {
            c.section("Cosmetic skills", "Do not count toward Skill Average") {
                for (skill in cosmetic) levelRow(skill.def.displayName, skill.level, skill.experience)
            }
        }

        // Farming and Taming cap below their curves per account. Say so only when the lookup that
        // knows those ceilings did not answer — a correct number needs no footnote.
        val capped = listOf(SkillCapsClient.FARMING, SkillCapsClient.TAMING)
        val unknown = capped.filter { key ->
            counted.any { it.def.key == key } && ctx.skillCaps?.capFor(key) == null
        }
        if (unknown.isNotEmpty()) {
            c.section("A note on caps") {
                note(
                    "${unknown.joinToString(" and ") { it.replaceFirstChar { c -> c.uppercase() } }} " +
                        (if (unknown.size == 1) "caps" else "cap") + " below the published curve per " +
                        "account — Farming with Jacob's Contest perks, Taming with the unique pets " +
                        "sacrificed to Kat. That lookup did not answer for this profile, so " +
                        (if (unknown.size == 1) "it is" else "they are") + " shown against the full " +
                        "curve and may read above the level this account has actually unlocked.",
                )
            }
        }
    }
}
