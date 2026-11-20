package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.HotmData
import com.soulreturns.data.hypixel.HotmNodeState
import com.soulreturns.data.hypixel.HotmProgress
import com.soulreturns.data.hypixel.HotmTree
import com.soulreturns.data.hypixel.HotmTreeClient
import com.soulreturns.data.hypixel.glacite
import com.soulreturns.data.hypixel.hotm
import com.soulreturns.data.hypixel.nucleusCrystals
import com.soulreturns.data.hypixel.powder
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.recipes.RecipeCatalogClient
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.features.mining.mineshaft.MineshaftStatsSection
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileViewerState
import com.soulreturns.features.profileviewer.gui.SoulCards
import com.soulreturns.features.profileviewer.gui.kit.CardNode
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.sections.mining.AbilityNode
import com.soulreturns.features.profileviewer.gui.sections.mining.CrystalChip
import com.soulreturns.features.profileviewer.gui.sections.mining.CrystalsNode
import com.soulreturns.features.profileviewer.gui.sections.mining.ForgeRow
import com.soulreturns.features.profileviewer.gui.sections.mining.ForgeRowsNode
import com.soulreturns.features.profileviewer.gui.sections.mining.HotmLayoutNode
import com.soulreturns.features.profileviewer.gui.sections.mining.HotmLore
import com.soulreturns.features.profileviewer.gui.sections.mining.HotmTreeNode
import com.soulreturns.features.profileviewer.gui.sections.mining.LegendNode
import com.soulreturns.features.profileviewer.gui.sections.mining.PowderRow
import com.soulreturns.features.profileviewer.gui.sections.mining.PowdersNode
import com.soulreturns.features.search.forge.Forge
import com.soulreturns.features.search.forge.ForgeEngine
import com.soulreturns.features.search.forge.ForgeProcesses
import java.util.Locale

/**
 * Heart of the Mountain, powder, the Forge, Glacite, the Crystal Nucleus and the mineshaft odds (mock
 * `miningSec`). The tree's layout and costs come from the backend (`HotmTreeClient`); without it the
 * section lists the member's levels plainly. The mineshaft card is [MineshaftStatsSection]'s.
 */
object MiningSection : ProfileSection {
    override val id = "mining"
    override val title = "Mining"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_pick"
    override val subtitle = "Heart of the Mountain, powder, Forge and mineshafts"

    /** Powders shown when the tree (which names them) has not arrived: the API's three suffixes. */
    private val FALLBACK_POWDERS = listOf(Triple("mithril", "Mithril", '2'), Triple("gemstone", "Gemstone", 'd'), Triple("glacite", "Glacite", 'b'))

    /** Corpse types in the game's order; anything else the API names follows, never dropped. */
    private val CORPSE_ORDER = listOf("lapis", "umber", "tungsten", "vanguard")

    /** Nucleus crystal colors (mock `.cry --c`). */
    private val GEM_COLORS = mapOf("jade" to 'a', "amber" to '6', "amethyst" to '5', "sapphire" to 'b', "topaz" to 'e')

    /**
     * Cards built from this computer's `stats.json` (ONLY YOU), shown for yourself only, after the Crystal
     * Nucleus card: the Crystal Nucleus runs and the Metal Detector. Phase 3 adds them here, each one
     * function that appends a [CardNode.LOCAL] card and returns whether it had anything to show.
     */
    private val LOCAL_CARDS: List<(Ui, ProfileContext) -> Boolean> = emptyList()

    override fun badge(ctx: ProfileContext): String? {
        val tree = HotmTreeClient.tree
        val hotm = ctx.member.hotm ?: return null
        return if (tree.available) "T${tree.tierOf(hotm.xp)}" else null
    }

    override fun build(ctx: ProfileContext, ui: Ui) = build(ctx, ui, HotmTreeClient.tree)

    /** [build] against a given tree (tests pass the served payload). */
    internal fun build(ctx: ProfileContext, ui: Ui, tree: HotmTree) {
        val hotm = ctx.member.hotm
        val progress = if (hotm != null && tree.available) tree.resolve(hotm.levels, hotm.disabled, hotm.ability, hotm.xp, hotm.tokensSpent) else null
        ui.grid(300) {
            if (hotm == null) {
                card("Heart of the Mountain", span = 2) {
                    empty("pv_pick", "No Heart of the Mountain in the response", "Hypixel sent no skill_tree for ${ctx.name} on this profile.")
                }
            } else if (progress == null) {
                hotmPlain(this, ctx, hotm)
            } else {
                hotmTree(this, ctx, tree, progress)
            }
            glaciteCard(this, ctx)
            mineshaftCard(this, ctx)
            forgeCard(this, ctx, tree, progress)
            nucleusCard(this, ctx)
            if (ctx.isSelf) for (localCard in LOCAL_CARDS) localCard(this, ctx)
        }
    }

    // ------------------------------------------------------------------ Heart of the Mountain

    private fun hotmTree(ui: Ui, ctx: ProfileContext, tree: HotmTree, p: HotmProgress) {
        val states = HashMap<String, HotmNodeState>(tree.nodes.size * 2)
        val specs = HashMap<String, HotmLore.Spec>(tree.nodes.size * 2)
        for (def in tree.nodes) {
            val st = p.node(def)
            states[def.id] = st
            specs[def.id] = HotmLore.item(tree, p, st)
        }
        val abilityDef = p.ability?.let { tree.node(it) }
        val next = p.xpToNext
        val side = StackNode(ui.collect {
            row(8) {
                text("Tier ${p.tier}", KitStyle.VALUE, bold = true, wrap = false)
                text(
                    if (next == null) "max tier" else "${Format.num(p.xpIntoTier.toLong())} / ${Format.num(next)} XP to Tier ${p.tier + 1}",
                    KitStyle.CAPTION, KitStyle.TEXT_FAINT, wrap = false,
                )
            }
            bar(if (next == null || next == 0L) 1f else (p.xpIntoTier / next).toFloat(), maxed = next == null)
            kvs {
                kv("Tokens of the Mountain", "${p.tokensSpent} spent · ${if (p.tokensLeft > 0) "§5" else ""}${p.tokensLeft} to spend")
                val coreDef = tree.core?.id?.let { tree.node(it) }
                kv("Core of the Mountain", if (p.coreLevel > 0) "Level ${p.coreLevel}§8/${coreDef?.max ?: p.coreLevel}" else "§8Tier ${coreDef?.tier ?: 5}")
                kv("HOTM XP", Format.num(p.xp))
            }
            add(AbilityNode(abilityDef?.let { specs[it.id] }, abilityDef?.name))
            add(PowdersNode(tree.powders.map { pw -> powderRow(ctx, pw.id, pw.name, pw.color.firstOrNull() ?: '7') }))
            add(
                LegendNode(
                    listOf(
                        HotmLore.Spec("§aMaxed", emptyList(), HotmLore.DIAMOND) to "Maxed",
                        HotmLore.Spec("§eLeveling", emptyList(), HotmLore.EMERALD) to "Leveling",
                        HotmLore.Spec("§cNot unlocked", emptyList(), HotmLore.COAL) to "Not unlocked",
                        HotmLore.Spec("§aAbility", emptyList(), HotmLore.EMERALD_BLOCK) to "Ability",
                    ),
                ),
            )
        }, 8)
        ui.card("Heart of the Mountain", sub = "${p.bought} of ${tree.nodes.size} nodes", span = 2) {
            add(HotmLayoutNode(HotmTreeNode(tree, p, states, specs), side))
        }
    }

    /** Without the tree table: the member's numbers and levels as a list, names from the API ids. */
    private fun hotmPlain(ui: Ui, ctx: ProfileContext, hotm: HotmData) {
        ui.card("Heart of the Mountain", sub = "${hotm.levels.count { it.value > 0 }} nodes", span = 2) {
            text("Tree layout unavailable: Soul's server hasn't sent the Heart of the Mountain table yet.", KitStyle.SMALL, KitStyle.TEXT_DIM)
            kvs(cols = 2) {
                kv("HOTM XP", Format.num(hotm.xp))
                hotm.tokensSpent?.let { kv("Tokens spent", Format.num(it)) }
                hotm.levels[CORE_ID]?.let { kv("Core of the Mountain", "Level $it") }
                kv("Selected ability", hotm.ability?.let(::titleCase) ?: "§8None")
            }
            add(PowdersNode(FALLBACK_POWDERS.map { (id, name, color) -> powderRow(ctx, id, name, color) }))
            label("Levels")
            kvs(cols = 2) {
                for ((id, level) in hotm.levels) {
                    if (id == CORE_ID) continue
                    kv(titleCase(id), if (id in hotm.disabled) "$level §8off" else level.toString())
                }
            }
        }
    }

    private const val CORE_ID = "core_of_the_mountain"

    private fun powderRow(ctx: ProfileContext, id: String, name: String, code: Char): PowderRow {
        val amount = ctx.member.powder(id)
        val color = Rich.mcColor(code) ?: KitStyle.TEXT
        if (amount == null) return PowderRow(name, color, "—", null)
        return PowderRow(name, color, Format.num(amount.current), "${Format.short(amount.total)} total · ${Format.short(amount.spent)} spent")
    }

    // ------------------------------------------------------------------ Glacite, mineshafts

    private fun glaciteCard(ui: Ui, ctx: ProfileContext) {
        val g = ctx.member.glacite
        ui.card("Glacite") {
            if (g == null) {
                empty("pv_pick", "Nothing from the Glacite Tunnels", "No glacite_player_data on this profile.")
                return@card
            }
            kvs {
                g.mineshaftsEntered?.let { kv("Mineshafts entered", Format.num(it)) }
                val known = CORPSE_ORDER.filter { it in g.corpsesLooted }
                for (type in known + (g.corpsesLooted.keys - known.toSet())) {
                    kv("${titleCase(type)} corpses looted", Format.num(g.corpsesLooted.getValue(type)))
                }
                g.fossilsDonated?.let { kv("Fossils donated", Format.num(it)) }
                g.fossilDust?.let { kv("Fossil dust", Format.num(it)) }
            }
        }
    }

    /** The soul-stats `mineshaft` summary, or the Soul empty state in its place (mock `mineshaftCard(ctx, false)`). */
    private fun mineshaftCard(ui: Ui, ctx: ProfileContext) {
        val state = ctx.soulState
        if (state == ProfileViewerState.SOUL_LOADING) {
            ui.card("Mineshafts", kind = SoulCards.kind(ctx), span = 2) { skeleton(4) }
            return
        }
        val payload = ctx.soulNamespace("mineshaft")
        if (payload != null && MineshaftStatsSection.card(ui, ctx, payload, full = false)) return
        ui.card("Mineshaft odds", kind = CardNode.EMPTY, span = 2) {
            if (state == ProfileViewerState.SOUL_DATA) {
                empty("pv_soul", "No mineshafts recorded", "Soul hasn't recorded a Glacite Mineshaft for ${ctx.name} yet.")
            } else {
                SoulCards.empty(this, ctx, compact = true)
            }
        }
    }

    // ------------------------------------------------------------------ Forge

    /**
     * Your own profile: the end times Soul read from your Forge menu (exact). Anyone else, or before Soul has
     * seen your Forge: the API's start times plus the recipe's base time at the member's Quick Forge level.
     */
    private fun forgeCard(ui: Ui, ctx: ProfileContext, tree: HotmTree, p: HotmProgress?) {
        val local = if (ctx.isSelf && SkyblockProfileState.current.equals(ctx.profile.cuteName, ignoreCase = true)) localForge(ctx) else null
        val rows = local ?: apiForge(ctx, tree, p)
        ui.card(
            "Forge",
            sub = if (local == null) "times from the start time Hypixel reports" else null,
            meta = if (local == null) null else ({ chips(Chip.soul("SOUL · EXACT", tip = "Exact end times Soul read from your Forge menu")) }),
        ) {
            if (rows.isEmpty()) empty("pv_pick", "Forge not unlocked") else add(ForgeRowsNode(rows))
        }
    }

    private fun localForge(ctx: ProfileContext): List<ForgeRow>? {
        val qf = Forge.quickForge()
        val slots = Forge.slots(ctx.nowMs, qf.fraction)
        if (slots.isEmpty()) return null
        return slots.mapIndexed { i, s ->
            if (s is Forge.Busy) ForgeRow(i + 1, itemName(s.outputId), "${if (s.approx) "~" else ""}${Format.hm(s.secondsLeft(ctx.nowMs))}", false)
            else if (s is Forge.Done) ForgeRow(i + 1, itemName(s.outputId), "Ready", true)
            else if (s is Forge.Unknown) ForgeRow(i + 1, s.outputId?.let(::itemName) ?: "§8Not seen yet", null, false)
            else ForgeRow(i + 1, null, null, false)
        }
    }

    private fun apiForge(ctx: ProfileContext, tree: HotmTree, p: HotmProgress?): List<ForgeRow> {
        val procs = ForgeProcesses.apiProcesses(ctx.member.raw).associateBy { it.slot }
        val unlocked = if (p != null) tree.forgeSlots(p.tier) else 0
        val count = maxOf(unlocked, procs.keys.maxOrNull() ?: 0).coerceAtMost(ForgeEngine.SLOTS)
        val qf = ForgeProcesses.percentOfLevel(ForgeProcesses.apiQuickForgeLevel(ctx.member.raw) ?: 0) / 100
        val recipes = RecipeCatalogClient.index.forge
        return (1..count).map { n ->
            val proc = procs[n] ?: return@map ForgeRow(n, null, null, false)
            val base = recipes[proc.id]?.baseSeconds
            if (base == null) return@map ForgeRow(n, itemName(proc.id), null, false)
            val end = proc.startMs + ForgeEngine.shownSeconds(base, qf) * 1000
            if (end <= ctx.nowMs) ForgeRow(n, itemName(proc.id), "Ready", true)
            else ForgeRow(n, itemName(proc.id), Format.hm((end - ctx.nowMs + 999) / 1000), false)
        }
    }

    private fun itemName(id: String): String = ItemCatalogClient.byId(id)?.displayName?.takeIf { it.isNotBlank() } ?: titleCase(id)

    // ------------------------------------------------------------------ Crystal Nucleus

    private fun nucleusCard(ui: Ui, ctx: ProfileContext) {
        val crystals = ctx.member.nucleusCrystals
        val placed = crystals?.mapNotNull { it.placed }?.takeIf { it.isNotEmpty() }?.sum()
        ui.card("Crystal Nucleus", sub = placed?.let { "${Format.num(it)} crystals placed" }) {
            if (crystals == null) {
                empty("pv_pick", "No Crystal Hollows data", "No mining_core.crystals on this profile.")
                return@card
            }
            add(
                CrystalsNode(
                    crystals.map { c ->
                        val state = c.state?.uppercase(Locale.ROOT)
                        CrystalChip(
                            name = titleCase(c.gem),
                            color = GEM_COLORS[c.gem]?.let { Rich.mcColor(it) } ?: KitStyle.TEXT,
                            state = if (state == "PLACED") "placed" else if (state == "FOUND") "found" else if (state == null || state == "NOT_FOUND") "missing" else state.lowercase(Locale.ROOT).replace('_', ' '),
                            missing = state == null || state == "NOT_FOUND",
                        )
                    },
                ),
            )
        }
    }

    /** `mining_speed_boost` / `MITHRIL_PLATE` → `Mining Speed Boost` / `Mithril Plate`. */
    private fun titleCase(id: String): String =
        id.lowercase(Locale.ROOT).split('_', '-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase(Locale.ROOT) } }.ifEmpty { id }
}
