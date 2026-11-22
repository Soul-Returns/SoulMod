package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.GearText
import com.soulreturns.data.hypixel.Loadout
import com.soulreturns.data.hypixel.LoadoutSets
import com.soulreturns.data.hypixel.armorSets
import com.soulreturns.data.hypixel.equipmentSets
import com.soulreturns.data.hypixel.hasLoadouts
import com.soulreturns.data.hypixel.loadouts
import com.soulreturns.data.hypixel.armorBlob
import com.soulreturns.data.hypixel.backpackBlobs
import com.soulreturns.data.hypixel.enderChestBlob
import com.soulreturns.data.hypixel.equipmentBlob
import com.soulreturns.data.hypixel.inventoryBlob
import com.soulreturns.data.hypixel.path
import com.soulreturns.data.hypixel.wardrobeBlob
import com.soulreturns.data.hypixel.wardrobeEquippedSet
import com.soulreturns.data.prices.MarketItemKey
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.CustomNode
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.framed
import com.soulreturns.features.profileviewer.gui.kit.slot
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.toLegacyText
import kotlinx.serialization.json.JsonObject
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Armor, equipment, wardrobe and the weapons and tools found in the inventory, Ender Chest and backpacks
 * (mock `gear`). Items are real decoded stacks with their vanilla tooltips; the Inventory API being off
 * shows the notice instead of empty slots.
 */
object GearSection : ProfileSection {
    override val id = "gear"
    override val title = "Gear"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_helmet"

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.apiAccessDisabled) "Inventory API off" else null

    /** The mock's rail badge: how many weapons and tools were found; null until every container is decoded. */
    override fun badge(ctx: ProfileContext): String? {
        if (ctx.member.apiAccessDisabled) return null
        val found = weapons(ctx)
        return if (found.loading) null else found.list.size.toString()
    }

    // ------------------------------------------------------------------ data

    /** One weapon or tool and where it was found (`Hotbar 2`, `Ender Chest 3`, `Backpack 1`). */
    internal class Weapon(val stack: ItemStack, val where: String)

    /** A group of blobs: still decoding, or one of them could not be read. */
    internal class Part(val loading: Boolean = false, val failed: Boolean = false) {
        val ready: Boolean get() = !loading && !failed
    }

    /**
     * One card of sets (the wardrobe's armor sets, or the equipment sets). [present] is whether Hypixel's data
     * has any source for it (it can have neither with the Inventory API on); [slotCount]/[filled]/[unreadable]
     * what the source held: slots, occupied slots that decoded, occupied slots that could not be decoded.
     */
    internal class Wardrobe(
        /** Four pieces per set, helmet first; nine sets per page, as many pages as the data has; column n is set n + 1. */
        val sets: List<List<ItemStack?>> = emptyList(),
        /** 0-based worn set, always inside [sets] when set. */
        val worn: Int? = null,
        val present: Boolean = true,
        val slotCount: Int = 0,
        val filled: Int = 0,
        val unreadable: Int = 0,
        /** Where the sets came from: `loadout` (`loadout.armor`) or `blob` (the old `wardrobe_contents`), for the log and the notice. */
        val source: String = "blob",
        /** What the source looked like, for the log (`blob 1234 chars, 72 slots`). */
        val detail: String = "",
        /** 0-based column to the names of the loadouts that use that set. */
        val usedBy: Map<Int, List<String>> = emptyMap(),
        /** False when the worn set's own entry holds pieces (or ones that did not decode): the body's items never replace those. */
        val wornEntryEmpty: Boolean = true,
        /** True when the worn column shows the items on the body instead of its own (empty) entry. */
        val wornFromBody: Boolean = false,
    ) {
        val pages: Int get() = sets.size / GearText.WARDROBE_COLUMNS
        val setsWithPieces: Int get() = sets.count { set -> set.any { it != null } }

        /** Nothing to draw: no source at all, or a source without a single slot or set. */
        val noData: Boolean get() = !present || slotCount == 0

        /**
         * The worn set's column filled with the items on the body ([worn]: `inv_armor` helmet first, or
         * `equipment_contents` in slot order) when the set's own entry has no pieces; the set's own pieces always win.
         * Display only: the Market value counts the physical items once, from the body.
         */
        fun withWorn(items: List<ItemStack?>): Wardrobe {
            val n = worn ?: return this
            if (!wornEntryEmpty || sets.getOrNull(n)?.all { it == null } != true || items.none { it != null }) return this
            val filledSets = sets.toMutableList()
            filledSets[n] = List(4) { items.getOrNull(it) }
            return Wardrobe(filledSets, worn, present, slotCount, filled, unreadable, source, detail, usedBy, wornEntryEmpty, wornFromBody = true)
        }
    }

    internal class View(
        /** Helmet, chestplate, leggings, boots. */
        val armor: List<ItemStack?>,
        /** Necklace, cloak, belt, gloves. */
        val equipment: List<ItemStack?>,
        val equipped: Part,
        val wardrobe: Wardrobe,
        val wardrobePart: Part,
        val weapons: List<Weapon>,
        val weaponsPart: Part,
        /** The equipment sets (`loadout.equipment`); null when the member has none, which hides their card. */
        val equipmentSets: Wardrobe? = null,
        val equipmentSetsPart: Part = Part(),
    )

    private class Found(val list: List<Weapon>, val loading: Boolean, val failed: Boolean)

    private class Memo(val raw: JsonObject, val found: Found)

    /** The last finished weapon scan: walking ~500 slots' lore every frame would be wasteful. */
    @Volatile private var memo: Memo? = null

    private class Pass {
        var loading = false
        var failed = false
        fun part() = Part(loading, failed)
    }

    private fun slots(ctx: ProfileContext, data: String?, pass: Pass): List<ItemStack?> {
        if (data == null) return emptyList()
        val blob = ctx.items.get(data)
        if (blob.loading) {
            pass.loading = true
            return emptyList()
        }
        if (blob.failed) {
            pass.failed = true
            return emptyList()
        }
        return blob.stacks.map { s -> s?.takeUnless { it.isEmpty } }
    }

    internal fun typeLine(stack: ItemStack): String =
        stack.get(DataComponents.LORE)?.lines()?.lastOrNull()?.toLegacyText()?.let(GearText::typeLine).orEmpty()

    /** The item's full name with its colors, without the star glyphs the font lacks. */
    internal fun plainName(stack: ItemStack): String = GearText.cleanName(stack.hoverName.toLegacyText())

    private fun weapons(ctx: ProfileContext): Found {
        memo?.takeIf { it.raw === ctx.member.raw }?.let { return it.found }
        val pass = Pass()
        val out = ArrayList<Weapon>()
        fun scan(data: String?, place: (Int) -> String) {
            val stacks = slots(ctx, data, pass)
            for ((i, stack) in stacks.withIndex()) {
                if (stack != null && GearText.isWeapon(typeLine(stack))) out += Weapon(stack, place(i))
            }
        }
        scan(ctx.member.inventoryBlob, GearText::inventoryPlace)
        scan(ctx.member.enderChestBlob, GearText::enderPlace)
        for (bp in ctx.member.backpackBlobs) scan(bp.data) { "Backpack ${bp.index + 1}" }
        val found = Found(out, pass.loading, pass.failed)
        if (!found.loading) memo = Memo(ctx.member.raw, found)
        return found
    }

    /**
     * Wardrobe slots (36 per page: row = piece, column = set) as sets of four, helmet first; one page of nine sets
     * per 36 slots, at least two pages, and enough pages to hold the [worn] set.
     */
    internal fun wardrobeSets(slots: List<ItemStack?>, worn: Int? = null): List<List<ItemStack?>> =
        List(GearText.wardrobeSetCount(slots.size, worn)) { n ->
            val page = n / GearText.WARDROBE_COLUMNS
            val column = n % GearText.WARDROBE_COLUMNS
            val pieces = List(4) { row -> slots.getOrNull(page * GearText.WARDROBE_PAGE + row * GearText.WARDROBE_COLUMNS + column) }
            GearText.orderArmor(pieces, reverseFallback = false) { typeLine(it) }
        }

    private val logger = SoulLogger("Soul/SpvGear")

    /** The last wardrobe line logged, so a frame that changes nothing logs nothing. */
    @Volatile private var lastLogged: String? = null

    /** What an API element is, for the log when the wardrobe is not where it should be. */
    private fun shape(e: kotlinx.serialization.json.JsonElement?): String = when {
        e == null -> "missing"
        e is JsonObject -> "object" + e.entries.joinToString(",", "{", "}") { (k, v) -> "$k:" + (if (v is JsonObject) "object" else if (v is kotlinx.serialization.json.JsonArray) "array" else "value") }
        e is kotlinx.serialization.json.JsonArray -> "array(${e.size})"
        else -> "value"
    }

    private fun describe(w: Wardrobe?): String = when {
        w == null -> "none"
        !w.present -> "absent"
        else -> "${w.source} ${w.detail}, ${w.filled} decoded, ${w.unreadable} unreadable, " +
            "${w.setsWithPieces} of ${w.sets.size} sets with a piece (${w.pages} pages), worn=${w.worn?.plus(1)}" + if (w.wornFromBody) " (shown from the body)" else ""
    }

    private fun looksWrong(w: Wardrobe?) = w != null && (!w.present || w.slotCount == 0 || w.filled == 0 || w.unreadable > 0)

    /** One line per change of the wardrobe's and the equipment sets' state, at WARN when anything looks wrong (INFO is hidden unless debug mode is on). */
    private fun logWardrobe(ctx: ProfileContext, w: Wardrobe, equipment: Wardrobe?) {
        val inv = ctx.member.raw.path("inventory")
        val where = if (!w.present) {
            ", inventory keys=${inv?.keys?.sorted()}, wardrobe_contents=${shape(inv?.get("wardrobe_contents"))}, loadout=${shape(ctx.member.raw["loadout"])}"
        } else {
            ""
        }
        val line = "wardrobe: ${describe(w)}$where; equipment sets: ${describe(equipment)}; wardrobe_equipped_slot=${inv?.get("wardrobe_equipped_slot")}"
        if (line == lastLogged) return
        lastLogged = line
        if (looksWrong(w) || looksWrong(equipment)) logger.warn(line) else logger.info(line)
    }

    /** Loadout names per 0-based set column, from [pick] (a loadout's armor or equipment set id). */
    private fun usedBy(ctx: ProfileContext, pick: (Loadout) -> Int?): Map<Int, List<String>> =
        ctx.member.loadouts.mapNotNull { l -> pick(l)?.let { (it - 1) to l.name } }.groupBy({ it.first }, { it.second })

    /**
     * Sets from the new Loadouts data: column n is set id n + 1 (ids are sparse), pieces straight from their keys.
     * The set count is the highest id rounded up to a page and never below [minPages] pages (nor below the worn set).
     */
    private fun fromSets(ctx: ProfileContext, sets: LoadoutSets, tag: String, worn: Int?, used: Map<Int, List<String>>, pass: Pass, minPages: Int): Wardrobe {
        val blob = ctx.items.getPieces(ctx.member.raw, tag) { sets.blobs }
        if (blob.loading) {
            pass.loading = true
            return Wardrobe(source = "loadout")
        }
        val count = GearText.wardrobeSetCount(sets.highestId * 4, worn, minPages)
        val columns = MutableList<List<ItemStack?>>(count) { List(4) { null } }
        var next = 0
        var pieces = 0
        var filled = 0
        var unreadable = 0
        for (set in sets.sets) {
            val row = set.pieces.map { blobText ->
                if (blobText == null) {
                    null
                } else {
                    pieces++
                    val stack = blob.stacks.getOrNull(next++)
                    if (stack == null) {
                        unreadable++
                        null
                    } else if (stack.isEmpty) {
                        null
                    } else {
                        filled++
                        stack
                    }
                }
            }
            if (set.id - 1 < count) columns[set.id - 1] = row
        }
        val wornEntryEmpty = worn == null || sets.sets.firstOrNull { it.id - 1 == worn }?.pieces?.all { it == null } != false
        return Wardrobe(
            columns, worn?.takeIf { it < count }, slotCount = sets.sets.size * 4, filled = filled, unreadable = unreadable,
            source = "loadout", detail = "${sets.sets.size} sets (highest id ${sets.highestId}), $pieces pieces", usedBy = used, wornEntryEmpty = wornEntryEmpty,
        )
    }

    /** The old wardrobe: `inventory.wardrobe_contents`, kept for members whose data has not been migrated. */
    private fun fromBlob(ctx: ProfileContext, pass: Pass): Wardrobe {
        val data = ctx.member.wardrobeBlob
            ?: return if (ctx.member.armorSets != null) Wardrobe(source = "loadout", detail = "no sets") else Wardrobe(present = false)
        val blob = ctx.items.get(data)
        if (blob.loading) {
            pass.loading = true
            return Wardrobe()
        }
        if (blob.failed) {
            pass.failed = true
            val line = "wardrobe: blob ${data.length} chars could not be read"
            if (line != lastLogged) {
                lastLogged = line
                logger.warn(line)
            }
            return Wardrobe()
        }
        val worn = ctx.member.wardrobeEquippedSet
        val stacks = blob.stacks.map { s -> s?.takeUnless { it.isEmpty } }
        val sets = wardrobeSets(stacks, worn)
        // the worn set's slots in the blob, to tell an empty worn column from one whose items did not decode
        val wornEntryEmpty = worn == null || List(4) { row -> blob.stacks.getOrNull((worn / GearText.WARDROBE_COLUMNS) * GearText.WARDROBE_PAGE + row * GearText.WARDROBE_COLUMNS + worn % GearText.WARDROBE_COLUMNS) }.all { it != null && it.isEmpty }
        return Wardrobe(
            sets, worn?.takeIf { it < sets.size }, slotCount = blob.stacks.size, filled = stacks.count { it != null },
            unreadable = blob.stacks.count { it == null }, detail = "blob ${data.length} chars, ${blob.stacks.size} slots", wornEntryEmpty = wornEntryEmpty,
        )
    }

    /**
     * The wardrobe card: the Loadouts armor sets when present, else the old blob. The worn set is `equipped_set`
     * (`wardrobe_equipped_slot` is the selected loadout there); a member migrated without it and without
     * `loadouts` still has the old meaning of `wardrobe_equipped_slot`.
     */
    private fun wardrobe(ctx: ProfileContext, pass: Pass): Wardrobe {
        val m = ctx.member
        val sets = m.armorSets?.takeIf { it.sets.isNotEmpty() } ?: return fromBlob(ctx, pass)
        val worn = sets.equippedId?.minus(1) ?: if (m.hasLoadouts) null else m.wardrobeEquippedSet
        return fromSets(ctx, sets, "armor", worn, usedBy(ctx) { it.armorSetId }, pass, minPages = 2)
    }

    /** The equipment sets card; null when the member has none (the card is then not drawn). */
    private fun equipmentSets(ctx: ProfileContext, pass: Pass): Wardrobe? {
        val sets = ctx.member.equipmentSets?.takeIf { it.sets.isNotEmpty() } ?: return null
        return fromSets(ctx, sets, "equipment", sets.equippedId?.minus(1), usedBy(ctx) { it.equipmentSetId }, pass, minPages = 1)
    }

    internal fun view(ctx: ProfileContext): View {
        val eq = Pass()
        val armorApi = slots(ctx, ctx.member.armorBlob, eq)
        val armor = GearText.orderArmor(armorApi.take(4), reverseFallback = true) { typeLine(it) }
        val equipmentApi = slots(ctx, ctx.member.equipmentBlob, eq)
        val equipment = List(4) { equipmentApi.getOrNull(it) }
        val wd = Pass()
        val wardrobe = wardrobe(ctx, wd).let { if (eq.part().ready) it.withWorn(armor) else it }
        val es = Pass()
        val equipmentSets = equipmentSets(ctx, es)?.let { if (eq.part().ready) it.withWorn(equipment) else it }
        if (!wd.loading && !wd.failed && !es.loading) logWardrobe(ctx, wardrobe, equipmentSets)
        val found = weapons(ctx)
        return View(armor, equipment, eq.part(), wardrobe, wd.part(), found.list, Part(found.loading, found.failed), equipmentSets, es.part())
    }

    // ------------------------------------------------------------------ build

    override fun build(ctx: ProfileContext, ui: Ui) {
        if (ctx.member.apiAccessDisabled) {
            ui.apiOff(ctx.name, ctx.isSelf, "Inventory API", "armor, equipment, wardrobe and weapons")
            return
        }
        render(ui, view(ctx))
    }

    private fun price(stack: ItemStack): Long? = MarketItemKey.of(stack)?.let { PriceCache.value(it) }?.takeIf { it > 0 }

    /** One `.gli` line: slot, rarity-colored name over a faint type line, the price at the right. */
    private fun Ui.gearLine(stack: ItemStack?, emptyLabel: String, where: String? = null) {
        if (stack == null) {
            itemLine(null, "§8Empty", sub = emptyLabel)
            return
        }
        val type = typeLine(stack)
        itemLine(stack, plainName(stack), sub = if (where != null) "$type · $where" else type, right = price(stack)?.let { Format.short(it) })
    }

    private fun Ui.unreadable() = text("Hypixel's item data for this part could not be read.", size = S.SMALL, color = S.TEXT_DIM)

    /** `"Necron's set"` or `"mixed armor"` for helmet-to-boots [armor] (Overview and Equipped). */
    internal fun setName(armor: List<ItemStack?>): String = GearText.setName(
        armor.map { s -> s?.let { GearText.withoutReforge(MessageDetector.stripColorCodes(plainName(it)).trim(), modifier(it)) } },
    )

    internal fun render(ui: Ui, v: View) {
        ui.card("Equipped", sub = if (v.equipped.ready) setName(v.armor) else null) {
            if (v.equipped.loading) {
                skeleton(4)
            } else if (v.equipped.failed) {
                unreadable()
            } else {
                columns {
                    column {
                        label("Armor")
                        for (i in 0 until 4) gearLine(v.armor[i], GearText.ARMOR_PIECES[i])
                    }
                    column {
                        label("Equipment")
                        for (i in 0 until 4) gearLine(v.equipment[i], GearText.EQUIPMENT_PIECES[i])
                    }
                }
            }
        }
        val wd = v.wardrobe
        ui.card("Wardrobe", sub = if (v.wardrobePart.ready && !wd.noData) "${wd.setsWithPieces} of ${wd.sets.size} sets" else null) {
            if (v.wardrobePart.loading) {
                skeleton(4)
            } else if (v.wardrobePart.failed) {
                unreadable()
            } else if (wd.noData) {
                empty(
                    "pv_helmet", "No wardrobe data",
                    if (!wd.present) {
                        "Hypixel did not send a wardrobe for this profile, although the Inventory API is on."
                    } else if (wd.source == "loadout") {
                        "Hypixel's wardrobe for this profile holds no sets."
                    } else {
                        "Hypixel's wardrobe for this profile holds no slots."
                    },
                )
            } else {
                add(wardrobeNode(wd, "wardrobe"))
                if (wd.unreadable > 0) text("${wd.unreadable} wardrobe ${if (wd.unreadable == 1) "item" else "items"} could not be read.", size = S.SMALL, color = S.TEXT_DIM)
            }
        }
        val es = v.equipmentSets
        if (es != null) {
            ui.card("Equipment sets", sub = if (v.equipmentSetsPart.ready) "${es.setsWithPieces} of ${es.sets.size} sets" else null) {
                if (v.equipmentSetsPart.loading) {
                    skeleton(4)
                } else {
                    add(wardrobeNode(es, "equipment"))
                    if (es.unreadable > 0) text("${es.unreadable} equipment ${if (es.unreadable == 1) "item" else "items"} could not be read.", size = S.SMALL, color = S.TEXT_DIM)
                }
            }
        }
        ui.card("Weapons and tools", sub = "found in inventory, Ender Chest and backpacks") {
            if (v.weaponsPart.loading) {
                skeleton(3)
            } else {
                if (v.weapons.isEmpty()) {
                    empty("pv_sword", "No weapons or tools found", "Nothing in the inventory, Ender Chest or backpacks.")
                } else {
                    grid(240) { for (w in v.weapons) gearLine(w.stack, "", w.where) }
                }
                if (v.weaponsPart.failed) unreadable()
            }
        }
    }

    private fun modifier(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("modifier", "")?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ wardrobe

    // `.wset`: padding 3 2 2, four 18 px slots 1 px apart, the set number under them; nine sets 3 px apart per page.
    private const val SET_W = 22
    private const val SET_H = 92
    private const val SET_GAP = 3
    private const val PAGE_W = 9 * SET_W + 8 * SET_GAP
    private const val PAGE_GAP = 18
    private const val PAGE_HEAD = 16
    private const val WORN_BG = 0x243B82F6

    /** Pages side by side while they fit (never more than there are), the rest wrap to the next row. */
    internal fun pagesPerRow(w: Int, pages: Int): Int = ((w + PAGE_GAP) / (PAGE_W + PAGE_GAP)).coerceIn(1, maxOf(1, pages))

    private fun wardrobeNode(w: Wardrobe, keyPrefix: String): CustomNode {
        val pages = w.sets.size / GearText.WARDROBE_COLUMNS
        return CustomNode(
            heightAt = { _, width ->
                val rows = (pages + pagesPerRow(width, pages) - 1) / pagesPerRow(width, pages)
                rows * (PAGE_HEAD + SET_H) + (rows - 1) * PAGE_GAP
            },
        ) { c, x, y, width, _ -> paintWardrobe(c, x, y, width, w, keyPrefix) }
    }

    /** The hover lines of a set column: whether it is worn and which loadouts use it; empty when neither. */
    internal fun setTooltip(isWorn: Boolean, usedBy: List<String>?): List<String> = buildList {
        if (isWorn) add("Equipped right now")
        if (!usedBy.isNullOrEmpty()) add("Used by: ${usedBy.joinToString(", ")}")
    }

    private fun paintWardrobe(c: KitCanvas, x: Int, y: Int, width: Int, w: Wardrobe, keyPrefix: String) {
        val sets = w.sets
        val pages = sets.size / GearText.WARDROBE_COLUMNS
        val perRow = pagesPerRow(width, pages)
        for (page in 0 until pages) {
            val px = x + (page % perRow) * (PAGE_W + PAGE_GAP)
            val py = y + (page / perRow) * (PAGE_HEAD + SET_H + PAGE_GAP)
            val label = "PAGE ${page + 1}"
            c.tracked(label, px + c.trackedWidth(label, S.LABEL, S.CHIP_TRACKING) / 2, py + 6, S.TEXT_FAINT, S.LABEL, S.CHIP_TRACKING)
            for (col in 0 until GearText.WARDROBE_COLUMNS) {
                val n = page * GearText.WARDROBE_COLUMNS + col
                val sx = px + col * (SET_W + SET_GAP)
                val sy = py + PAGE_HEAD
                val isWorn = n == w.worn
                if (isWorn) c.framed(sx, sy, sx + SET_W, sy + SET_H, WORN_BG, S.ACCENT, Corner.SMALL)
                val set = sets.getOrNull(n)
                for (row in 0 until 4) c.slot(set?.getOrNull(row), sx + 2, sy + 3 + row * (S.SLOT_SM + 1), S.SLOT_SM)
                val numTop = sy + 3 + 4 * (S.SLOT_SM + 1)
                val text = "${n + 1}"
                c.text(text, sx + (SET_W - c.width(text, S.TINY)) / 2, numTop, numTop + 11, if (isWorn) S.ACCENT_LIGHT else S.TEXT_FAINT, S.TINY)
                val lines = setTooltip(isWorn, w.usedBy[n])
                if (lines.isNotEmpty()) c.tooltip(sx, numTop, sx + SET_W, sy + SET_H, lines, "$keyPrefix:$n")
            }
        }
    }
}
