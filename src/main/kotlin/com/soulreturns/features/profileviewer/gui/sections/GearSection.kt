package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.GearText
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
     * The wardrobe card's data. [present] is whether Hypixel's data has a wardrobe blob at all (it can be missing
     * with the Inventory API on), [slotCount]/[filled]/[unreadable] what the blob held: slots, occupied slots
     * that decoded, occupied slots that could not be decoded.
     */
    internal class Wardrobe(
        /** Four pieces per set, helmet first; nine sets per page, as many pages as the data has. */
        val sets: List<List<ItemStack?>> = emptyList(),
        /** 0-based worn set, always inside [sets] when set. */
        val worn: Int? = null,
        val present: Boolean = true,
        val slotCount: Int = 0,
        val filled: Int = 0,
        val unreadable: Int = 0,
        val blobChars: Int = 0,
    ) {
        val pages: Int get() = sets.size / GearText.WARDROBE_COLUMNS
        val setsWithPieces: Int get() = sets.count { set -> set.any { it != null } }

        /** No wardrobe to draw: no blob, or a blob without a single slot. */
        val noData: Boolean get() = !present || slotCount == 0
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

    /** What an API element is, for the log when the wardrobe blob is not where it should be. */
    private fun shape(e: kotlinx.serialization.json.JsonElement?): String = when {
        e == null -> "missing"
        e is JsonObject -> "object" + e.entries.joinToString(",", "{", "}") { (k, v) -> "$k:" + (if (v is JsonObject) "object" else if (v is kotlinx.serialization.json.JsonArray) "array" else "value") }
        e is kotlinx.serialization.json.JsonArray -> "array(${e.size})"
        else -> "value"
    }

    /** One line per change of the wardrobe's state, at WARN when anything looks wrong (INFO is hidden unless debug mode is on). */
    private fun logWardrobe(ctx: ProfileContext, w: Wardrobe) {
        val inv = ctx.member.raw.path("inventory")
        val line = if (!w.present) {
            "wardrobe: blob absent, inventory keys=${inv?.keys?.sorted()}, wardrobe_contents=${shape(inv?.get("wardrobe_contents"))}, " +
                "wardrobe_equipped_slot=${inv?.get("wardrobe_equipped_slot")}"
        } else {
            "wardrobe: blob ${w.blobChars} chars, ${w.slotCount} slots, ${w.filled} decoded, ${w.unreadable} unreadable, " +
                "${w.setsWithPieces} of ${w.sets.size} sets with a piece (${w.pages} pages), worn=${w.worn?.plus(1)}"
        }
        if (line == lastLogged) return
        lastLogged = line
        if (!w.present || w.slotCount == 0 || w.filled == 0 || w.unreadable > 0) logger.warn(line) else logger.info(line)
    }

    private fun wardrobe(ctx: ProfileContext, pass: Pass): Wardrobe {
        val data = ctx.member.wardrobeBlob ?: return Wardrobe(present = false)
        val blob = ctx.items.get(data)
        if (blob.loading) {
            pass.loading = true
            return Wardrobe(blobChars = data.length)
        }
        if (blob.failed) {
            pass.failed = true
            val line = "wardrobe: blob ${data.length} chars could not be read"
            if (line != lastLogged) {
                lastLogged = line
                logger.warn(line)
            }
            return Wardrobe(blobChars = data.length)
        }
        val worn = ctx.member.wardrobeEquippedSet
        val stacks = blob.stacks.map { s -> s?.takeUnless { it.isEmpty } }
        val sets = wardrobeSets(stacks, worn)
        return Wardrobe(
            sets, worn?.takeIf { it < sets.size }, slotCount = blob.stacks.size, filled = stacks.count { it != null },
            unreadable = blob.stacks.count { it == null }, blobChars = data.length,
        ).also { logWardrobe(ctx, it) }
    }

    internal fun view(ctx: ProfileContext): View {
        val eq = Pass()
        val armorApi = slots(ctx, ctx.member.armorBlob, eq)
        val armor = GearText.orderArmor(armorApi.take(4), reverseFallback = true) { typeLine(it) }
        val equipmentApi = slots(ctx, ctx.member.equipmentBlob, eq)
        val equipment = List(4) { equipmentApi.getOrNull(it) }
        val wd = Pass()
        val wardrobe = wardrobe(ctx, wd)
        if (!wardrobe.present) logWardrobe(ctx, wardrobe)
        val found = weapons(ctx)
        return View(armor, equipment, eq.part(), wardrobe, wd.part(), found.list, Part(found.loading, found.failed))
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
                    if (wd.present) "Hypixel's wardrobe for this profile holds no slots." else "Hypixel did not send a wardrobe for this profile, although the Inventory API is on.",
                )
            } else {
                add(wardrobeNode(wd.sets, wd.worn))
                if (wd.unreadable > 0) text("${wd.unreadable} wardrobe ${if (wd.unreadable == 1) "item" else "items"} could not be read.", size = S.SMALL, color = S.TEXT_DIM)
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

    private fun wardrobeNode(sets: List<List<ItemStack?>>, worn: Int?): CustomNode {
        val pages = sets.size / GearText.WARDROBE_COLUMNS
        return CustomNode(
            heightAt = { _, w ->
                val rows = (pages + pagesPerRow(w, pages) - 1) / pagesPerRow(w, pages)
                rows * (PAGE_HEAD + SET_H) + (rows - 1) * PAGE_GAP
            },
        ) { c, x, y, w, _ -> paintWardrobe(c, x, y, w, sets, worn) }
    }

    private fun paintWardrobe(c: KitCanvas, x: Int, y: Int, w: Int, sets: List<List<ItemStack?>>, worn: Int?) {
        val pages = sets.size / GearText.WARDROBE_COLUMNS
        val perRow = pagesPerRow(w, pages)
        for (page in 0 until pages) {
            val px = x + (page % perRow) * (PAGE_W + PAGE_GAP)
            val py = y + (page / perRow) * (PAGE_HEAD + SET_H + PAGE_GAP)
            val label = "PAGE ${page + 1}"
            c.tracked(label, px + c.trackedWidth(label, S.LABEL, S.CHIP_TRACKING) / 2, py + 6, S.TEXT_FAINT, S.LABEL, S.CHIP_TRACKING)
            for (col in 0 until GearText.WARDROBE_COLUMNS) {
                val n = page * GearText.WARDROBE_COLUMNS + col
                val sx = px + col * (SET_W + SET_GAP)
                val sy = py + PAGE_HEAD
                val isWorn = n == worn
                if (isWorn) c.framed(sx, sy, sx + SET_W, sy + SET_H, WORN_BG, S.ACCENT, Corner.SMALL)
                val set = sets.getOrNull(n)
                for (row in 0 until 4) c.slot(set?.getOrNull(row), sx + 2, sy + 3 + row * (S.SLOT_SM + 1), S.SLOT_SM)
                val numTop = sy + 3 + 4 * (S.SLOT_SM + 1)
                val text = "${n + 1}"
                c.text(text, sx + (SET_W - c.width(text, S.TINY)) / 2, numTop, numTop + 11, if (isWorn) S.ACCENT_LIGHT else S.TEXT_FAINT, S.TINY)
                if (isWorn) c.tooltip(sx, numTop, sx + SET_W, sy + SET_H, listOf("Equipped right now"), "wardrobe:$n")
            }
        }
    }
}
