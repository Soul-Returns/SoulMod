package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.autopetRuleCount
import com.soulreturns.data.hypixel.katTypesSacrificed
import com.soulreturns.data.hypixel.petMarketKey
import com.soulreturns.data.hypixel.petScore
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.items.ItemIcons
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.pets.ApiPet
import com.soulreturns.features.pets.PetItems
import com.soulreturns.features.pets.PetHeads
import com.soulreturns.features.pets.PetLevels
import com.soulreturns.features.pets.PetState
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.LH_BODY
import com.soulreturns.features.profileviewer.gui.kit.LH_CAPTION
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.bar
import com.soulreturns.features.profileviewer.gui.kit.framed
import com.soulreturns.features.profileviewer.gui.kit.itemSizeFor
import com.soulreturns.util.MessageDetector
import net.minecraft.world.item.ItemStack
import kotlin.math.max

/**
 * The active pet and every pet (mock `petsSec`): level from XP with the Pet HUD's own math (`PetLevels`),
 * held item and skin, the pet score, Kat's care and the autopet rules. The pets themselves come from
 * `pets_data.pets[]` through `PetState.apiPets`; for you, XP the Pet HUD read live is newer and wins.
 */
object PetsSection : ProfileSection {
    override val id = "pets"
    override val title = "Pets"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_paw"

    override fun badge(ctx: ProfileContext): String = PetState.apiPets(ctx.member).size.toString()

    // ------------------------------------------------------------------ data

    /** One pet, with its level worked out. */
    internal class PetRow(
        val name: String,
        val rarity: SkyblockRarity?,
        val level: Int,
        val maxLevel: Int,
        val maxed: Boolean,
        val into: Double,
        val need: Long,
        val overflow: Double,
        val xp: Double,
        /** The held item's display name, null when it holds none. */
        val held: String?,
        /** The skin's display name, null without one. */
        val skin: String?,
        val active: Boolean,
        /** The XP is the Pet HUD's live reading, not the API's. */
        val live: Boolean,
        /** The market key (`TYPE;tier`), null for an unknown tier. */
        val priceKey: String?,
        /** The pet's real head ([PetHeads]), null = the rarity tile. */
        val head: ItemStack? = null,
        /** The held item's icon ([ItemIcons]), null = text only. */
        val heldIcon: ItemStack? = null,
    ) {
        val fraction: Float get() = if (maxed) 1f else if (need > 0) (into / need).toFloat().coerceIn(0f, 1f) else 0f
        val color: Int get() = rarity?.color ?: S.TEXT
    }

    /** A Tier Boost held item lifts the shown rarity one step (the base tier stays the market key's). */
    private fun shownRarity(tier: String?, heldItem: String?): SkyblockRarity? {
        val base = SkyblockRarity.forName(tier)
        if (base != null && heldItem == "PET_ITEM_TIER_BOOST") return SkyblockRarity.entries.getOrNull(base.ordinal + 1) ?: base
        return base
    }

    /**
     * Every pet of [pets] as a row. [live] gives the Pet HUD's XP reading for a pet (null = none); it counts only
     * when it is ahead of the API's. [heldName] turns a held item id into its display name, [head] gives a pet's
     * head and [heldIcon] a held item's icon (both null when unknown).
     */
    internal fun rows(
        pets: List<ApiPet>,
        live: (ApiPet) -> Double?,
        head: (ApiPet) -> ItemStack? = { null },
        heldIcon: (String) -> ItemStack? = { null },
        heldName: (String) -> String,
    ): List<PetRow> = pets.map { p ->
        val name = PetItems.titleOf(p.type)
        val rarity = shownRarity(p.tier, p.heldItem)
        val liveXp = live(p)?.takeIf { it > p.exp }
        val xp = liveXp ?: p.exp
        val lv = PetLevels.levelOf(xp, rarity, name)
        PetRow(
            name = name, rarity = rarity, level = lv.level, maxLevel = PetLevels.maxLevel(name), maxed = lv.maxed,
            into = lv.into, need = lv.need, overflow = lv.overflow, xp = xp,
            held = p.heldItem?.let(heldName),
            skin = p.skin?.let { PetItems.titleOf(it.removePrefix("PET_SKIN_")) },
            active = p.active, live = liveXp != null, priceKey = petMarketKey(p.type, p.tier, lv.level),
            head = head(p), heldIcon = p.heldItem?.let(heldIcon),
        )
    }

    private val RARITY_ORDER = listOf("MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON")

    /** The mock's three sorts: level (then XP), rarity (then level), name; [query] filters by name. */
    internal fun sorted(rows: List<PetRow>, sort: String, query: String): List<PetRow> {
        val list = rows.filter { query.isEmpty() || it.name.lowercase().contains(query) }
        val byLevel = compareByDescending<PetRow> { it.level }.thenByDescending { it.xp }
        val comparator: Comparator<PetRow> = if (sort == "name") {
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        } else if (sort == "rarity") {
            compareBy<PetRow> { r -> r.rarity?.name?.let { RARITY_ORDER.indexOf(it) } ?: -1 }.thenByDescending { it.level }
        } else {
            byLevel
        }
        return list.sortedWith(comparator)
    }

    /** The Pet HUD's reading only counts for your own profile that is also the one you are playing. */
    private fun playing(ctx: ProfileContext) = ctx.isSelf && ctx.profile.cuteName.equals(SkyblockProfileState.current, ignoreCase = true)

    internal fun liveReader(ctx: ProfileContext): (ApiPet) -> Double? {
        if (!playing(ctx)) return { null }
        return { p -> p.uuid?.let { PetState.tracker.record(it) }?.takeIf { it.expLive }?.exp }
    }

    /** Real heads; the head a Pets menu showed counts only for the profile you are playing (the Pet HUD's records). */
    internal fun headReader(ctx: ProfileContext): (ApiPet) -> ItemStack? {
        val own = playing(ctx)
        return { p -> PetHeads.icon(p.type, p.tier, p.skin, if (own) p.uuid?.let { PetState.tracker.record(it) }?.icon else null) }
    }

    internal fun heldIcon(id: String): ItemStack? = ItemIcons.icon(id)

    internal fun heldName(id: String): String {
        val named = ItemCatalogClient.byId(id)?.displayName?.takeIf { it.isNotBlank() }?.let(MessageDetector::stripColorCodes)
        return named ?: PetItems.titleOf(id)
    }

    // ------------------------------------------------------------------ build

    override fun build(ctx: ProfileContext, ui: Ui) {
        val rows = rows(PetState.apiPets(ctx.member), liveReader(ctx), headReader(ctx), ::heldIcon, ::heldName)
        render(ctx, ui, rows, ui.value("sort", "level"), ui.query("query"))
    }

    internal fun render(ctx: ProfileContext, ui: Ui, rows: List<PetRow>, sort: String, query: String) {
        val active = rows.firstOrNull { it.active }
        val value = rows.sumOf { r -> r.priceKey?.let { PriceCache.value(it) } ?: 0L }
        ui.grid(240) {
            card("Active pet", span = 2) {
                if (active == null) empty("pv_paw", "No active pet") else add(PetNode(active, hero = true))
            }
            card("Pet facts") {
                kvs {
                    kv("Pets", "${rows.size} · ${rows.count { it.maxed }} max level")
                    kv("Pet score", ctx.member.petScore?.let { Format.num(it) } ?: "—", tip = "leveling.highest_pet_score")
                    kv("Kat pet care", "${ctx.member.katTypesSacrificed.coerceAtMost(10)} of 10 types", tip = "Each unique pet type sacrificed to Kat raises the Taming cap by 1")
                    kv("Autopet rules", Format.num(ctx.member.autopetRuleCount))
                    kv("Pets value", Rich.colored(if (value > 0) Format.short(value) else "—", S.COIN))
                }
            }
        }
        val shown = sorted(rows, sort, query)
        ui.row {
            seg("sort", "level", listOf("level" to "Level", "rarity" to "Rarity", "name" to "Name"))
            search("query", "Filter pets", count = if (query.isNotEmpty()) "${shown.size}" else null)
        }
        ui.grid(210) {
            for (r in shown) add(PetNode(r, hero = false))
        }
    }
}

/** The tooltip lines of a pet, as the mock's `regPet` writes them (no pet category: the API does not carry it). */
internal fun petTooltipLines(p: PetsSection.PetRow): List<String> {
    val code = rarityCode(p.rarity)
    val lines = ArrayList<String>()
    lines += "§7[Lvl ${p.level}] §$code${p.name}"
    lines += ""
    if (p.maxed) {
        lines += "§bMAX LEVEL"
        if (p.overflow > 0) lines += "§8${Format.num(p.overflow)} overflow XP"
    } else {
        lines += "§7Progress to Level ${p.level + 1}: §e${Format.pct(p.fraction.toDouble())}"
        lines += "§8${Format.num(p.into)} / ${Format.short(p.need)} XP"
    }
    lines += ""
    if (p.held != null) {
        lines += "§6Held Item: §5${p.held}"
        lines += ""
    }
    if (p.skin != null) lines += "§dSkin: ${p.skin}"
    lines += "§$code§l${p.rarity?.name ?: "COMMON"}"
    return lines
}

private fun rarityCode(r: SkyblockRarity?): Char = if (r == null) 'f' else "fa956dbcc4"[r.ordinal.coerceIn(0, 9)]

/**
 * The pet's tile: its real head ([PetsSection.PetRow.head]) on a tint of its rarity with a rarity edge; without a
 * known head the paw in the rarity's color.
 */
internal fun KitCanvas.petTile(p: PetsSection.PetRow, x: Int, y: Int, size: Int) {
    val head = p.head
    if (head == null) {
        framed(x, y, x + size, y + size, S.SLOT_BG, S.SLOT_EDGE, Corner.SQUARE)
        val glyph = size * 3 / 5
        icon("pv_paw", x + (size - glyph) / 2, y + (size - glyph) / 2, glyph, p.color)
        return
    }
    framed(x, y, x + size, y + size, tint(S.SLOT_BG, p.color), (p.color and 0x00FFFFFF) or (0xB0 shl 24), Corner.SQUARE)
    val isz = itemSizeFor(size)
    item(head, x + (size - isz) / 2, y + (size - isz) / 2, isz, count = false)
}

/** [base] with 18 % of [color] mixed in, opaque. */
private fun tint(base: Int, color: Int): Int {
    fun mix(shift: Int) = (((base shr shift) and 0xFF) * 82 + ((color shr shift) and 0xFF) * 18) / 100
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** A pet line: its tile ([petTile]) with the name, level and XP bar. [hero] is the large Active pet form, otherwise the framed `.pet` row of the list. */
private class PetNode(private val p: PetsSection.PetRow, private val hero: Boolean) : Node() {
    private val iconSize get() = if (hero) S.SLOT_LG else S.SLOT
    private val padX get() = if (hero) 0 else 10
    private val padY get() = if (hero) 0 else 7
    private val gap get() = if (hero) 12 else 8
    private val titleH get() = if (hero) 16 else LH_BODY

    private val textH: Int
        get() = if (hero) titleH + 4 + S.BAR_H + 4 + LH_CAPTION + 4 + LH_CAPTION else titleH + 2 + LH_CAPTION + 3 + S.BAR_H

    override fun measure(m: KitMeasure, w: Int): Int = padY * 2 + max(iconSize, textH)

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (!hero) c.framed(x, y, x + w, y + h, S.CARD_BG, if (p.active) S.ACCENT else 0, Corner.MEDIUM)
        val ix = x + padX
        val iy = y + (h - iconSize) / 2
        c.petTile(p, ix, iy, iconSize)
        c.tooltip(ix, iy, ix + iconSize, iy + iconSize, petTooltipLines(p), "pet:${p.name}:${p.level}")

        val tx = ix + iconSize + gap
        val tw = x + w - padX - tx
        var cy = y + (h - textH) / 2
        val size = if (hero) S.HEADING else S.BODY
        val name = Rich.colored(p.name, p.color, bold = true).ellipsize(c, size, tw, true)
        var end = name.draw(c, tx, cy, cy + titleH, size, S.TEXT, true)
        if (hero) {
            val level = Rich.of("Lv ${p.level}${if (p.maxed) "" else " of ${p.maxLevel}"}")
            end = level.draw(c, end + 6, cy, cy + titleH, S.SMALL, S.TEXT_FAINT)
            if (p.live) {
                val chip = Chip.soul("SOUL · LIVE XP", "Your pet XP from the tab list and chat, newer than Hypixel's (Pet HUD cache).")
                if (end + 6 + chip.width(c) <= tx + tw) chip.paint(c, end + 6, cy + titleH / 2)
            }
            cy += titleH + 4
            c.bar(tx, cy, tw, p.fraction, p.maxed)
            cy += S.BAR_H + 4
            val xpLine = if (p.maxed) "Max level" + (if (p.overflow > 0) " · ${Format.short(p.overflow)} overflow XP" else "") else "${Format.num(p.into)} / ${Format.num(p.need)} XP to level ${p.level + 1}"
            Rich.of(xpLine).ellipsize(c, S.CAPTION, tw).draw(c, tx, cy, cy + LH_CAPTION, S.CAPTION, S.TEXT_DIM)
            cy += LH_CAPTION + 4
            val held = if (p.held != null) "Holding §6${p.held}" else "No pet item"
            var hx = tx
            if (p.heldIcon != null) {
                c.item(p.heldIcon, tx, cy + (LH_CAPTION - HELD_ICON) / 2, HELD_ICON, count = false)
                hx += HELD_ICON + 3
            }
            Rich.of(held + (if (p.skin != null) "§7 · ${p.skin} skin" else "")).ellipsize(c, S.CAPTION, tx + tw - hx).draw(c, hx, cy, cy + LH_CAPTION, S.CAPTION, S.TEXT_DIM)
        } else {
            if (p.active) Chip.accent("ACTIVE").paint(c, end + 6, cy + titleH / 2)
            cy += titleH + 2
            val sub = "Lv ${p.level}" + (if (p.maxed) "" else " · ${Format.pct(p.fraction.toDouble(), 0)}") + (if (p.held != null) " · ${p.held}" else "")
            Rich.of(sub).ellipsize(c, S.CAPTION, tw).draw(c, tx, cy, cy + LH_CAPTION, S.CAPTION, S.TEXT_DIM)
            cy += LH_CAPTION + 3
            c.bar(tx, cy, tw, p.fraction, p.maxed)
        }
    }

    private companion object {
        /** The held item's icon on the Active pet's caption line. */
        const val HELD_ICON = 12
    }
}
