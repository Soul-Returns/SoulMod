package com.soulreturns.data.hypixel

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/** Where the backend's tree comes from: NotEnoughUpdates-REPO (MIT, Copyright (c) 2020 Moulberry), corrected by Soul's captures. */
@Serializable
data class HotmTreeSource(
    val name: String? = null,
    val url: String? = null,
    val files: List<String> = emptyList(),
    val license: String? = null,
    val copyright: String? = null,
)

/** A powder: API suffix (`mithril` → `mining_core.powder_mithril`), display name and its `§` color code. */
@Serializable
data class HotmPowder(val id: String = "", val name: String = "", val color: String = "7")

/** One tier: [xp] to reach it from the tier below (tier 1: 0), its token reward, total Forge slots and reward lines. */
@Serializable
data class HotmTier(
    val tier: Int = 0,
    val xp: Long = 0,
    val tokens: Int = 0,
    val forgeSlots: Int = 0,
    val verified: String = "",
    val rewards: List<String> = emptyList(),
)

/** Core of the Mountain level [level]: what reaching it costs (level 1 is free), in which powder, and its reward. */
@Serializable
data class HotmCoreLevel(
    val level: Int = 0,
    val cost: Long = 0,
    val powder: String? = null,
    val tokens: Int = 0,
    val reward: String = "",
)

@Serializable
data class HotmCore(val id: String = "", val override: String? = null, val levels: List<HotmCoreLevel> = emptyList())

/** Powder to reach level `L`: `floor((L + offset) ^ exponent)`. */
@Serializable
data class HotmCost(val offset: Int = 1, val exponent: Double = 1.0)

/**
 * One number in an effect text: `base + per × (x − from)`, `x` the perk level or (with [of] `tier`) the
 * HOTM tier; [atMax] replaces it at the perk's max level. [group]: a whole number with thousands
 * separators; otherwise [decimals] places, an all-zero fraction dropped when [trim].
 */
@Serializable
data class HotmEffectValue(
    val base: Double = 0.0,
    val per: Double = 0.0,
    val from: Double = 0.0,
    val of: String = "level",
    val decimals: Int = 0,
    val trim: Boolean = false,
    val group: Boolean = false,
    val atMax: Double? = null,
)

/** Lore text with `{0}`.. placeholders for [values]; `\n` separates paragraphs (an ability's cooldown line). */
@Serializable
data class HotmEffect(val text: String = "", val values: List<HotmEffectValue> = emptyList())

/**
 * One node. [kind]: `perk` (levels bought with [powder]), `static` / `ability` (one level for a token),
 * `core` (Core of the Mountain, levels in [HotmCore]). [id] is the API key under `skill_tree.nodes.mining`.
 * [requires] is the captured "Requires X" prerequisite (tiers 1 to 7 only).
 */
@Serializable
data class HotmNodeDef(
    val id: String = "",
    val name: String = "",
    val tier: Int = 0,
    val col: Int = 0,
    val max: Int = 1,
    val kind: String = "perk",
    val powder: String? = null,
    val cost: HotmCost? = null,
    val requires: String? = null,
    val verified: String = "",
    val override: String? = null,
    val effect: HotmEffect? = null,
) {
    val isAbility: Boolean get() = kind == "ability"
    val isCore: Boolean get() = kind == "core"
}

/** `GET /hotm/tree` (SkyBackend "Heart of the Mountain tree"). Empty until the first download. */
@Serializable
data class HotmTreeSnapshot(
    val schemaVersion: Int = 1,
    val version: Int? = null,
    val updatedAt: Long? = null,
    val source: HotmTreeSource? = null,
    val captures: String? = null,
    val powders: List<HotmPowder> = emptyList(),
    val tiers: List<HotmTier> = emptyList(),
    val core: HotmCore? = null,
    val nodes: List<HotmNodeDef> = emptyList(),
)

/** Lookups and the tree's math over one snapshot. Pure; built once per snapshot swap. */
class HotmTree(val snapshot: HotmTreeSnapshot) {
    val nodes: List<HotmNodeDef> = snapshot.nodes
    val tiers: List<HotmTier> = snapshot.tiers
    val core: HotmCore? = snapshot.core
    private val byId: Map<String, HotmNodeDef> = nodes.associateBy { it.id }
    private val powderById: Map<String, HotmPowder> = snapshot.powders.associateBy { it.id }

    /** False before the first download (or for a body without a layout): the section shows the plain list. */
    val available: Boolean get() = nodes.isNotEmpty() && tiers.isNotEmpty()

    val maxTier: Int get() = tiers.size

    val powders: List<HotmPowder> get() = snapshot.powders

    fun node(id: String): HotmNodeDef? = byId[id]

    fun powder(id: String?): HotmPowder? = id?.let { powderById[it] }

    /** Powder that raises [node] to [level] (≥ 2 for a perk, ≥ 2 for the Core); null for a token-only step. */
    fun costToReach(node: HotmNodeDef, level: Int): Long? {
        if (node.isCore) return core?.levels?.getOrNull(level - 1)?.takeIf { level >= 2 }?.cost
        val c = node.cost ?: return null
        if (level < 2 || level > node.max) return null
        return floor((level + c.offset).toDouble().pow(c.exponent)).toLong()
    }

    /** The powder [costToReach] is paid in. */
    fun powderToReach(node: HotmNodeDef, level: Int): HotmPowder? =
        if (node.isCore) powder(core?.levels?.getOrNull(level - 1)?.powder) else powder(node.powder)

    /** [node]'s effect text at [level] for a player at [tier], placeholders filled; empty for the Core. */
    fun effect(node: HotmNodeDef, level: Int, tier: Int): String {
        val e = node.effect ?: return ""
        var text = e.text
        for ((i, v) in e.values.withIndex()) text = text.replace("{$i}", format(v, level, tier, node.max))
        return text
    }

    /** XP needed from tier 1 to reach [tier]. */
    fun xpForTier(tier: Int): Long = tiers.take(tier.coerceAtLeast(0)).sumOf { it.xp }

    /** HOTM tier for [xp] total HOTM XP (1 with none). */
    fun tierOf(xp: Double): Int {
        var tier = 1
        var acc = 0L
        for (i in 1 until tiers.size) {
            acc += tiers[i].xp
            if (xp >= acc) tier = i + 1 else break
        }
        return tier
    }

    /** XP from [tier] to the next one, null at the top tier. */
    fun xpToNext(tier: Int): Long? = tiers.getOrNull(tier)?.xp

    /** Tokens of the Mountain earned at [tier] with Core of the Mountain at [coreLevel]. */
    fun tokensAt(tier: Int, coreLevel: Int): Int =
        tiers.take(tier.coerceAtLeast(0)).sumOf { it.tokens } + (core?.levels?.take(coreLevel.coerceAtLeast(0))?.sumOf { it.tokens } ?: 0)

    /** Forge slots unlocked at [tier] (0 before the Forge). */
    fun forgeSlots(tier: Int): Int = tiers.getOrNull(tier - 1)?.forgeSlots ?: 0

    /**
     * A member's tree resolved against the table. [levels] are `skill_tree.nodes.mining` (no `toggle_` keys),
     * [tokensSpentReported] the API's `tokens_spent.mountain` (null = count the bought nodes instead).
     */
    fun resolve(levels: Map<String, Int>, disabled: Set<String>, ability: String?, xp: Double, tokensSpentReported: Int?): HotmProgress =
        HotmProgress(this, levels, disabled, ability, xp, tokensSpentReported)

    companion object {
        /** One effect value as the mock's `n` / `pct` / `toFixed` print it. */
        fun format(v: HotmEffectValue, level: Int, tier: Int, max: Int): String {
            val byTier = v.of == "tier"
            val value = if (!byTier && v.atMax != null && level >= max) v.atMax else v.base + v.per * ((if (byTier) tier else level) - v.from)
            if (v.group) return String.format(Locale.ROOT, "%,d", Math.round(value))
            val s = String.format(Locale.ROOT, "%.${v.decimals}f", value)
            val dot = s.indexOf('.')
            return if (v.trim && dot >= 0 && s.substring(dot + 1).all { it == '0' }) s.substring(0, dot) else s
        }
    }
}

/** One node of a member's tree, as the menu would draw it. */
class HotmNodeState(
    val def: HotmNodeDef,
    /** 0 = not unlocked. */
    val level: Int,
    /** The player's tier reaches this node's tier. */
    val open: Boolean,
    val maxed: Boolean,
    /** The selected pickaxe ability. */
    val selected: Boolean,
    /** `toggle_<id>: false`: bought but switched off. */
    val disabled: Boolean,
    /** Not unlocked and its captured prerequisite is not either. */
    val missing: HotmNodeDef?,
)

/** A member's HOTM: tier, XP, tokens and every node's state. Pure. */
class HotmProgress(
    val tree: HotmTree,
    val levels: Map<String, Int>,
    val disabled: Set<String>,
    val ability: String?,
    val xp: Double,
    tokensSpentReported: Int?,
) {
    val tier: Int = tree.tierOf(xp)
    val coreLevel: Int = tree.core?.id?.let { levels[it] } ?: 0
    val tokensEarned: Int = tree.tokensAt(tier, coreLevel)

    /** The API's count when it sent one; otherwise one token per bought node except the Core (bought with powder). */
    val tokensSpent: Int = tokensSpentReported ?: levels.count { (id, lv) -> lv > 0 && tree.node(id)?.isCore == false }
    val tokensLeft: Int = max(0, tokensEarned - tokensSpent)

    /** XP gathered toward the next tier and what that tier needs (null at the top tier). */
    val xpIntoTier: Double = xp - tree.xpForTier(tier)
    val xpToNext: Long? = tree.xpToNext(tier)

    /** Nodes of the table the member has bought (the Core included), for "N of M nodes". */
    val bought: Int = tree.nodes.count { (levels[it.id] ?: 0) > 0 }

    fun node(def: HotmNodeDef): HotmNodeState {
        val level = levels[def.id] ?: 0
        val parent = def.requires?.let { tree.node(it) }
        return HotmNodeState(
            def = def,
            level = level,
            open = def.tier <= tier,
            maxed = level >= def.max,
            selected = def.isAbility && ability == def.id,
            disabled = def.id in disabled,
            missing = if (level == 0 && parent != null && (levels[parent.id] ?: 0) == 0) parent else null,
        )
    }

    /** Whether both ends of a prerequisite edge are bought (the connector lights up). */
    fun edgeOn(child: HotmNodeDef): Boolean {
        val parent = child.requires ?: return false
        return (levels[child.id] ?: 0) > 0 && (levels[parent] ?: 0) > 0
    }
}
