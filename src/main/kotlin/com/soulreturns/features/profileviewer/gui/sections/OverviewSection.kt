package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.HotmTreeClient
import com.soulreturns.data.hypixel.KUUDRA_TIERS
import com.soulreturns.data.hypixel.KUUDRA_TIER_NAMES
import com.soulreturns.data.hypixel.activeMemberCount
import com.soulreturns.data.hypixel.armorBlob
import com.soulreturns.data.hypixel.bankBalance
import com.soulreturns.data.hypixel.bestiaryMilestone
import com.soulreturns.data.hypixel.fairySouls
import com.soulreturns.data.hypixel.firstJoinMs
import com.soulreturns.data.hypixel.hotm
import com.soulreturns.data.hypixel.kuudraCompletions
import com.soulreturns.data.hypixel.magicalPower
import com.soulreturns.data.hypixel.purse
import com.soulreturns.data.hypixel.selectedPower
import com.soulreturns.features.pets.PetState
import com.soulreturns.features.profileviewer.MarketValueEstimates
import com.soulreturns.features.profileviewer.gui.LocalSoulCards
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
import com.soulreturns.features.profileviewer.gui.kit.Inline
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Mini
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.PlayerModel
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.RowNode
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.bar
import com.soulreturns.features.profileviewer.gui.kit.framed
import com.soulreturns.features.profileviewer.gui.kit.slot
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.max

/**
 * Who this player is at a glance (mock `overview`): the figure column, the identity card with the
 * Market value ([MarketValueEstimates]), five headline tiles, then three columns of cards — gear,
 * skills, Soul + highlights.
 */
object OverviewSection : ProfileSection {
    override val id = "overview"
    override val title = "Overview"
    override val group = ProfileSection.GROUP_TOP
    override val icon = "pv_home"

    /** `.ov-top`: the figure column's width. */
    private const val FIGURE_W = 118

    /** `.figure`'s `--u`: one skin pixel. The body is 16 × 32 of them. */
    private const val U = 4

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

    /**
     * `.figure`: the full-body model in the armor they wear, as tall as the identity card and the
     * tiles. While the skin loads, the head on a pulsing silhouette; if it fails, the silhouette alone.
     */
    private fun figure(ctx: ProfileContext, ui: Ui) {
        val uuid = ctx.identity.uuidUndashed
        val blob = ctx.member.armorBlob?.let { ctx.items.get(it) }
        // Hypixel lists boots first; the model wants helmet to boots. Still decoding: no armor yet.
        val armor: List<ItemStack?>? = if (blob == null) null else if (blob.ready) blob.stacks.reversed() else emptyList()
        ui.add(
            CustomNode({ _, _ -> 10 + 32 * U + 6 + 11 + 8 }) { c, x, y, w, h ->
                c.gradient(x, y, x + w, y + h, 0xFF1E1E1E.toInt(), 0xFF171717.toInt())
                val bodyBottom = y + h - 8 - 11 - 6
                val state = c.playerModel(uuid, ctx.name, x, y + 10, x + w, bodyBottom, armor)
                if (state != PlayerModel.READY) silhouette(c, uuid, x + w / 2, bodyBottom - 32 * U, state)
                val caption = if (state == PlayerModel.LOADING) "Loading skin" else if (state != PlayerModel.READY) "No skin" else if (ctx.member.apiAccessDisabled) "Armor hidden" else ""
                if (caption.isNotEmpty()) c.text(caption, x + (w - c.width(caption, KitStyle.TINY)) / 2, bodyBottom + 6, bodyBottom + 17, 0xFF666666.toInt(), KitStyle.TINY)
                val tip = if (state == PlayerModel.READY) "Their player model in the armor they wear now. It turns toward your mouse." else if (state == PlayerModel.LOADING) "Loading the skin…" else "The skin couldn't be loaded."
                c.tooltip(x, y, x + w, y + h, listOf(tip), "figure")
            },
        )
    }

    /** The fallback body (`.bd` without `.ready`): head 8, arms and legs 4 × 12, torso 8 × 12 skin pixels. */
    private fun silhouette(c: KitCanvas, uuid: String, cx: Int, top: Int, state: Int) {
        val gray = 0x2A2A2A
        val loading = state == PlayerModel.LOADING
        val t = (c.nowMs % 2400) / 1200f
        val k = if (t > 1f) 2f - t else t
        val body = if (loading) ((0.45f + 0.55f * k) * 255).toInt() shl 24 or gray else 0xFF shl 24 or gray
        c.rect(cx - 4 * U, top, cx + 4 * U, top + 8 * U, 0xFF shl 24 or gray, Corner.SMALL)
        if (state != PlayerModel.FAILED) c.face(uuid, cx - 4 * U, top, 8 * U)
        val mid = top + 8 * U
        c.rect(cx - 8 * U, mid, cx + 8 * U, mid + 12 * U, body, Corner.SQUARE)
        c.rect(cx - 4 * U, mid + 12 * U, cx + 4 * U, mid + 24 * U, body, Corner.SQUARE)
    }

    private fun identity(ctx: ProfileContext, ui: Ui) {
        val member = ctx.member
        ui.card(null, gap = 0, padX = 14, padTop = 12, padBottom = 12) {
            val lines = collect { identityLines(ctx, this) }
            add(RowNode(listOf(StackNode(lines, gap = 3), MarketValueNode(MarketValueEstimates.get(ctx.profile, member))), gap = 14))
        }
    }

    private fun identityLines(ctx: ProfileContext, ui: Ui) = with(ui) {
        val member = ctx.member
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

    /** Armor beside equipment with their names, the weapons row and the active pet (mock `ov-gear`). */
    private fun gearCard(ctx: ProfileContext, ui: Ui) {
        val pet = PetState.apiPets(ctx.member).filter { it.active }.takeIf { it.isNotEmpty() }
            ?.let { PetsSection.rows(it, PetsSection.liveReader(ctx), PetsSection::heldName).firstOrNull() }
        if (ctx.member.apiAccessDisabled) {
            ui.card("Gear") {
                empty("pv_lock", "Gear is hidden", "${ctx.possessive} Inventory API is off.")
                pet?.let { add(PetLineNode(it)) }
            }
            return
        }
        val v = GearSection.view(ctx)
        ui.card("Gear", sub = if (v.equipped.ready) GearSection.setName(v.armor) else null) {
            if (v.equipped.loading) skeleton(4) else add(GearRowNode(v.armor, v.equipment))
            add(WeaponsNode(if (v.weaponsPart.loading) null else v.weapons.take(6).map { it.stack }) { ctx.open(GearSection.id) })
            pet?.let { add(PetLineNode(it)) }
        }
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

    /** The Soul summary (mock `soulOverview`): the shared mineshaft rows, and for yourself the local trackers. */
    private fun soulCard(ctx: ProfileContext, ui: Ui) {
        val local = if (ctx.isSelf) LocalSoulCards.overviewRows(ctx) else emptyList()
        if (ctx.soulState == ProfileViewerState.SOUL_LOADING && local.isEmpty()) {
            ui.card("Soul", kind = CardNode.SHARED) { skeleton(3) }
            return
        }
        val payload = ctx.soulNamespace("mineshaft")
        val all = payload?.let { MineshaftStats.from(it) }
        val m = all?.let { it.forProfile(ctx.profile.profileId) ?: it }?.takeIf { it.totalVisits > 0 }
        if (m == null && local.isEmpty()) {
            ui.card("Soul", kind = CardNode.EMPTY) { SoulCards.empty(this, ctx, compact = true) }
            return
        }
        ui.card("Soul", kind = SoulCards.kind(ctx), sub = SoulCards.sharedBy(ctx), meta = { link("Open") { ctx.open(SoulSection.id) } }) {
            kvs {
                if (m != null) {
                    kv("Mineshaft visits", Format.num(m.totalVisits))
                    kv("Littlefoot found", "${Format.num(m.littlefootFound)} §8(${Format.pct(m.littlefootFindRate)})")
                    m.byCorpse["VANGUARD"]?.let { kv("Vanguard corpse odds", "${Format.pct(it.spawnRate)} §8per visit", tip = "Visits where a Vanguard corpse spawned") }
                }
                for ((label, value) in local) kv(label, value)
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
                val hotm = m.hotm
                val tree = HotmTreeClient.tree
                if (hotm != null && tree.available) kv("Heart of the Mountain", "Tier ${tree.tierOf(hotm.xp)}")
                m.bestiaryMilestone?.let { kv("Bestiary milestone", Format.num(it)) }
                m.fairySouls?.let { kv("Fairy souls", Format.num(it)) }
                m.purse?.let { kv("Purse", "§6${coins(it)}") }
                val bank = ctx.profile.bankBalance
                if (bank != null) kv("Bank", "§6${coins(bank)}") else kv("Bank", "§8Banking API off")
            }
        }
    }

    /** Mock `coins`: short from 10k up, exact below. */
    internal fun coins(v: Double): String = if (v >= 1e4) Format.short(v) else Format.num(v)
}

private val DIVIDER = 0xFF262626.toInt()

/** `.gsub` / `.petline` top: 8 above (2 beyond the card's gap), the 1 px rule, 7 below. */
private const val RULE_H = 2 + 1 + 7

private fun KitCanvas.rule(x: Int, y: Int, w: Int) = rect(x, y + 2, x + w, y + 3, DIVIDER, Corner.SQUARE)

/** `.hero-v`: the Market value at the hero's right, its breakdown in the tooltip. */
private class MarketValueNode(private val state: MarketValueEstimates.State) : Node(), Inline {
    private val estimate = state.estimate
    private val value = estimate?.let { Format.short(it.total) }
    private val sub = if (state.noPrices) "prices unavailable" else if (estimate?.itemsHidden == true) "coins and pets only" else "items at base price"

    override fun inlineWidth(m: KitMeasure): Int =
        max(max(m.width(LABEL, S.CAPTION), value?.let { m.width(it, S.HERO, true) } ?: 64), m.width(sub, S.TINY) + 3 + INFO)

    override fun measure(m: KitMeasure, w: Int): Int = 12 + 24 + 12

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val right = x + w
        c.text(LABEL, right - c.width(LABEL, S.CAPTION), y, y + 12, S.TEXT_DIM, S.CAPTION)
        if (value != null) {
            c.text(value, right - c.width(value, S.HERO, true), y + 12, y + 36, S.COIN, S.HERO, bold = true)
        } else if (state.noPrices) {
            c.text("—", right - c.width("—", S.HERO, true), y + 12, y + 36, S.TEXT_DIM, S.HERO, bold = true)
        } else {
            val t = (c.nowMs % 2200) / 1100f
            val shade = 0x26 + ((if (t > 1f) 2f - t else t) * 10).toInt()
            c.rect(right - 64, y + 17, right, y + 31, (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade, Corner.SMALL)
        }
        c.icon("pv_info", right - INFO, y + 37, INFO, S.TEXT_FAINT)
        c.text(sub, right - INFO - 3 - c.width(sub, S.TINY), y + 36, y + 48, S.TEXT_FAINT, S.TINY)
        c.tooltip(x, y, right, y + h, tipLines(), "market-value")
    }

    private fun tipLines(): List<String> {
        if (state.noPrices) return listOf("Market value", "§8Prices couldn't be loaded from Soul's server.")
        val e = estimate ?: return listOf("Market value", "§8Pricing the items…")
        val lines = ArrayList<String>()
        lines += "Market value, items at base price:"
        for (p in e.parts) lines += "§7${p.label}: §f${OverviewSection.coins(p.coins)}"
        for (h in e.hidden) lines += "§8$h, not counted"
        if (e.unpriced > 0) lines += "§8${Format.num(e.unpriced)} ${if (e.unpriced == 1) "item" else "items"} without a price"
        lines += "§8Enchantments, stars and upgrades are not priced"
        return lines
    }

    private companion object {
        const val LABEL = "Market value"
        const val INFO = 10
    }
}

/** `.gear-row`: armor and equipment slots in two columns, the names beside them (equipment under armor). */
private class GearRowNode(private val armor: List<ItemStack?>, private val equipment: List<ItemStack?>) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 4 * S.SLOT + 3 * 2

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val nx = x + 2 * S.SLOT + 6 + 4
        val nw = x + w - nx
        for (i in 0 until 4) {
            val ry = y + i * (S.SLOT + 2)
            c.slot(armor.getOrNull(i), x, ry, S.SLOT)
            c.slot(equipment.getOrNull(i), x + S.SLOT + 6, ry, S.SLOT)
            val name = armor.getOrNull(i)?.let { Rich.of(GearSection.plainName(it)) } ?: Rich.colored("Empty", S.TEXT_FAINT)
            val small = equipment.getOrNull(i)?.let { Rich.of(GearSection.plainName(it)) }
            if (small == null) {
                name.ellipsize(c, S.SMALL, nw).draw(c, nx, ry, ry + S.SLOT, S.SMALL, S.TEXT)
            } else {
                name.ellipsize(c, S.SMALL, nw).draw(c, nx, ry - 1, ry + 10, S.SMALL, S.TEXT)
                small.ellipsize(c, S.LABEL, nw).draw(c, nx, ry + 10, ry + 20, S.LABEL, S.TEXT_DIM)
            }
        }
    }
}

/** `.gsub`: up to six weapons in small slots and the `All gear` link; [weapons] null while decoding. */
private class WeaponsNode(private val weapons: List<ItemStack>?, private val onAll: () -> Unit) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = RULE_H + S.SLOT_SM

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.rule(x, y, w)
        val ry = y + RULE_H
        c.text("Weapons", x, ry, ry + S.SLOT_SM, S.TEXT_FAINT, S.CAPTION)
        var sx = x + c.width("Weapons", S.CAPTION) + 6
        if (weapons != null && weapons.isEmpty()) c.text("none found", sx, ry, ry + S.SLOT_SM, S.TEXT_GHOST, S.CAPTION)
        for (stack in weapons.orEmpty()) {
            c.slot(stack, sx, ry, S.SLOT_SM)
            sx += S.SLOT_SM + 2
        }
        val link = "All gear"
        val lw = c.width(link, S.CAPTION)
        val lx = x + w - lw
        val hover = c.hovered(lx, ry, lx + lw, ry + S.SLOT_SM)
        c.text(link, lx, ry, ry + S.SLOT_SM, if (hover) 0xFFFFFFFF.toInt() else S.ACCENT_LIGHT, S.CAPTION)
        c.onClick(lx, ry, lx + lw, ry + S.SLOT_SM, onAll)
    }
}

/** `.petline`: the active pet's tile, name, level and held item over its XP bar. */
private class PetLineNode(private val p: PetsSection.PetRow) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = RULE_H + 24 + 2 + S.BAR_H

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.rule(x, y, w)
        val top = y + RULE_H
        val iy = top + (24 - S.SLOT) / 2
        c.framed(x, iy, x + S.SLOT, iy + S.SLOT, S.SLOT_BG, S.SLOT_EDGE, Corner.SQUARE)
        c.icon("pv_paw", x + 4, iy + 4, 12, p.color)
        c.tooltip(x, iy, x + S.SLOT, iy + S.SLOT, petTooltipLines(p), "pet:${p.name}:${p.level}")
        val tx = x + S.SLOT + 8
        val tw = x + w - tx
        val end = Rich.colored(p.name, p.color, bold = true).ellipsize(c, S.BODY, tw, true).draw(c, tx, top, top + 13, S.BODY, S.TEXT, true)
        c.text("Lv ${p.level}", end + 4, top, top + 13, S.TEXT_FAINT, S.SMALL)
        Rich.of(if (p.held != null) "Holding ${p.held}" else "No pet item").ellipsize(c, S.TINY, tw).draw(c, tx, top + 13, top + 24, S.TINY, S.TEXT_DIM)
        c.bar(tx, top + 26, tw, p.fraction, p.maxed)
    }
}
