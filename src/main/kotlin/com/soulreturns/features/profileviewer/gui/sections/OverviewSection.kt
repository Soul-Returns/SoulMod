package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.KUUDRA_TIERS
import com.soulreturns.data.hypixel.KUUDRA_TIER_NAMES
import com.soulreturns.data.hypixel.activeMemberCount
import com.soulreturns.data.hypixel.bankBalance
import com.soulreturns.data.hypixel.bestiaryMilestone
import com.soulreturns.data.hypixel.fairySouls
import com.soulreturns.data.hypixel.firstJoinMs
import com.soulreturns.data.hypixel.kuudraCompletions
import com.soulreturns.data.hypixel.magicalPower
import com.soulreturns.data.hypixel.purse
import com.soulreturns.data.hypixel.selectedPower
import com.soulreturns.features.mining.mineshaft.MineshaftStats
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileViewerState
import com.soulreturns.features.profileviewer.gui.SoulCards
import com.soulreturns.features.profileviewer.gui.kit.CardNode
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.CustomNode
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Mini
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui
import java.util.Locale

/**
 * Who this player is at a glance (mock `overview`): the figure column, the identity card, five
 * headline tiles, then three columns of cards — gear, skills, Soul + highlights.
 *
 * Phase 3 fills [figure] (the full-body model), the market value in [identity], [gearCard] and the
 * Soul local-tracker rows in [soulCard]; every slot is already in place, so none of them moves the rest.
 */
object OverviewSection : ProfileSection {
    override val id = "overview"
    override val title = "Overview"
    override val group = ProfileSection.GROUP_TOP
    override val icon = "pv_home"

    /** `.ov-top`: the figure column's width. */
    private const val FIGURE_W = 118
    private const val HEAD = 48

    override fun build(ctx: ProfileContext, ui: Ui) {
        ui.split(FIGURE_W, { figure(ctx, this) }, {
            stack(8) {
                identity(ctx, this)
                headlineTiles(ctx, this)
            }
        })
        ui.columns {
            column { gearCard(ctx, this) }
            column { skillsCard(ctx, this) }
            column {
                soulCard(ctx, this)
                highlights(ctx, this)
            }
        }
    }

    /** The figure column. Phase 1: the face (the head fallback of the full-body model). */
    private fun figure(ctx: ProfileContext, ui: Ui) {
        ui.add(
            CustomNode({ _, _ -> HEAD + 20 }) { c, x, y, w, h ->
                c.rect(x, y, x + w, y + h, KitStyle.CARD_BG, Corner.MEDIUM)
                c.face(ctx.identity.uuidUndashed, x + (w - HEAD) / 2, y + (h - HEAD) / 2, HEAD)
            },
        )
    }

    private fun identity(ctx: ProfileContext, ui: Ui) {
        val member = ctx.member
        ui.card(null) {
            row(0) {
                text(ctx.name, KitStyle.TITLE, bold = true, wrap = false)
                if (ctx.isSelf) chips(Chip.you())
                grow()
            }
            val members = ctx.profile.activeMemberCount
            val badge = if (ctx.isSelf) {
                Chip.soul("SOUL · ${if (ctx.shareSoulStats) "SHARED" else "PRIVATE"}", "You use Soul. Your Soul stats are ${if (ctx.shareSoulStats) "shared with other Soul users" else "private"}.")
            } else if (ctx.soulState == ProfileViewerState.SOUL_DATA || ctx.soulState == ProfileViewerState.SOUL_SHARED_EMPTY) {
                Chip.soul(tip = "Uses Soul and shares what Soul records")
            } else {
                null
            }
            val chips = listOfNotNull(
                Chip.gameMode(ctx.profile.gameMode, withTip = true),
                if (members > 1) Chip.plain("CO-OP · $members") else null,
                badge,
            )
            if (chips.isNotEmpty()) chips(*chips.toTypedArray())
            val since = member.firstJoinMs?.let { " since ${Format.date(it)}" } ?: ""
            val main = member.selectedDungeonClass?.let { cls -> " · ${cls.replaceFirstChar { it.uppercase(Locale.ROOT) }} main" } ?: ""
            text("Playing §f${ctx.profile.cuteName}§r$since$main", KitStyle.SMALL, KitStyle.TEXT_DIM, wrap = false)
        }
    }

    private fun headlineTiles(ctx: ProfileContext, ui: Ui) {
        val m = ctx.member
        ui.tiles {
            val sb = m.skyblockLevel
            if (sb != null) {
                val into = (m.skyblockExperience - sb.level * 100).toInt().coerceIn(0, 100)
                tile("SkyBlock Level", "${sb.level}", sub = "$into / 100 XP to ${sb.level + 1}", bar = into / 100f, color = Format.levelColor(sb.level), tip = "leveling.experience / 100")
            } else {
                tile("SkyBlock Level", "—", sub = "not in the response")
            }
            val avg = m.skillAverageCapped(ctx.caps)
            if (avg != null) {
                val skills = m.skillsCapped(ctx.caps).filter { !it.def.cosmetic }
                tile(
                    "Skill Average", Format.avg(avg),
                    sub = "${skills.count { it.level.maxed }} of ${skills.size} skills maxed",
                    tip = "Non-cosmetic skills, with this profile's Farming and Taming caps",
                    onClick = { ctx.open(SkillsSection.id) },
                )
            } else {
                tile("Skill Average", "—", sub = "Skills API off", locked = true, onClick = { ctx.open(SkillsSection.id) })
            }
            val cata = m.catacombsLevel
            tile("Catacombs", "${cata.level}", sub = "Class average ${String.format(Locale.ROOT, "%.1f", m.classAverage)}", bar = cata.progress.toFloat(), maxed = cata.maxed, onClick = { ctx.open(DungeonsSection.id) })
            val slayers = SlayersSection.all(ctx)
            tile(
                "Slayers",
                Rich.of(slayers.joinToString(" §8·§r ") { "${it.level.level}" }),
                sub = "${Format.short(m.totalSlayerXp)} XP in total",
                onClick = { ctx.open(SlayersSection.id) },
            )
            val mp = m.magicalPower
            if (mp != null) {
                tile("Magical Power", Format.num(mp), sub = m.selectedPower?.let { "${it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }} power" } ?: "No power selected", onClick = { ctx.open(AccessoriesSection.id) })
            } else {
                tile("Magical Power", "—", sub = "Inventory API off", locked = true, onClick = { ctx.open(AccessoriesSection.id) })
            }
        }
    }

    /** Phase 3: armor, equipment, weapons and the active pet (mock `ov-gear`). */
    private fun gearCard(ctx: ProfileContext, ui: Ui) {
        ui.later("Gear", "Armor, equipment, weapons and the active pet show here once the Gear and Pets sections land.")
    }

    private fun skillsCard(ctx: ProfileContext, ui: Ui) {
        val skills = ctx.member.skillsCapped(ctx.caps).filter { !it.def.cosmetic }
        if (skills.isEmpty()) {
            ui.card("Skills") { empty("pv_lock", "Skills are hidden", "${ctx.possessive} Skills API is off.") }
            return
        }
        val avg = ctx.member.skillAverageCapped(ctx.caps) ?: 0.0
        ui.card("Skills", sub = "average ${Format.avg(avg)}", meta = { link("Details") { ctx.open(SkillsSection.id) } }) {
            minis(skills.map { s -> Mini(s.def.displayName, "${s.level.level}", s.level.progress.toFloat(), s.level.maxed) { ctx.open(SkillsSection.id) } })
        }
    }

    /** The Soul summary (mock `soulOverview`). Phase 3 adds the local-tracker rows (nucleus, fishing, dragons). */
    private fun soulCard(ctx: ProfileContext, ui: Ui) {
        if (ctx.soulState == ProfileViewerState.SOUL_LOADING) {
            ui.card("Soul", kind = CardNode.SHARED) { skeleton(3) }
            return
        }
        val payload = ctx.soulNamespace("mineshaft")
        val all = payload?.let { MineshaftStats.from(it) }
        val m = all?.let { it.forProfile(ctx.profile.profileId) ?: it }
        if (m == null || m.totalVisits == 0) {
            ui.card("Soul", kind = CardNode.EMPTY) { SoulCards.empty(this, ctx, compact = true) }
            return
        }
        ui.card("Soul", kind = SoulCards.kind(ctx), sub = SoulCards.sharedBy(ctx), meta = { link("Open") { ctx.open(SoulSection.id) } }) {
            kvs {
                kv("Mineshaft visits", Format.num(m.totalVisits))
                kv("Littlefoot found", "${Format.num(m.littlefootFound)} §8(${Format.pct(m.littlefootFindRate)})")
                m.byCorpse["VANGUARD"]?.let { kv("Vanguard corpse odds", "${Format.pct(it.spawnRate)} §8per visit", tip = "Visits where a Vanguard corpse spawned") }
            }
        }
    }

    private fun highlights(ctx: ProfileContext, ui: Ui) {
        val m = ctx.member
        ui.card("Highlights") {
            kvs {
                val master = m.masterCatacombsFloors.lastOrNull { it.completions > 0 }
                val best = master ?: m.catacombsFloors.lastOrNull { it.completions > 0 }
                if (best != null) {
                    val (grade, color) = DungeonsSection.grade(best.bestScore)
                    val name = if (master != null) "M${best.tier}" else if (best.tier == 0) "Entrance" else "F${best.tier}"
                    val time = if (best.fastestSPlusTimeMs > 0) " ${Format.dur(best.fastestSPlusTimeMs)}" else ""
                    kv("Best dungeon floor", Rich.join(Rich.of("$name · "), Rich.colored(grade, color, bold = true), Rich.of(time)))
                }
                m.dungeonSecrets?.let { kv("Secrets found", Format.num(it)) }
                val kuudra = m.kuudraCompletions
                KUUDRA_TIERS.lastOrNull { (kuudra[it] ?: 0) > 0 }?.let { tier ->
                    val n = kuudra[tier] ?: 0
                    kv("Highest Kuudra tier", "${KUUDRA_TIER_NAMES[tier]} · ${Format.num(n)} ${if (n == 1) "run" else "runs"}")
                }
                m.bestiaryMilestone?.let { kv("Bestiary milestone", Format.num(it)) }
                m.fairySouls?.let { kv("Fairy souls", Format.num(it)) }
                m.purse?.let { kv("Purse", "§6${coins(it)}") }
                val bank = ctx.profile.bankBalance
                if (bank != null) kv("Bank", "§6${coins(bank)}") else kv("Bank", "§8Banking API off")
            }
        }
    }

    /** Mock `coins`: short from 10k up, exact below. */
    private fun coins(v: Double): String = if (v >= 1e4) Format.short(v) else Format.num(v)
}
