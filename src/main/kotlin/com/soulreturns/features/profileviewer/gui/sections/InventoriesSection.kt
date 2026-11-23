package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.BlobItem
import com.soulreturns.data.hypixel.SackSummary
import com.soulreturns.data.hypixel.ItemBlobs
import com.soulreturns.data.hypixel.backpackBlobs
import com.soulreturns.data.hypixel.enderChestBlob
import com.soulreturns.data.hypixel.enderChestPages
import com.soulreturns.data.hypixel.fishingBagBlob
import com.soulreturns.data.hypixel.humanizeItemId
import com.soulreturns.data.hypixel.inventoryBlob
import com.soulreturns.data.hypixel.inventoryDisplayOrder
import com.soulreturns.data.hypixel.mergeSacks
import com.soulreturns.data.hypixel.ownedSacks
import com.soulreturns.data.hypixel.sacksBagBlob
import com.soulreturns.data.hypixel.personalVaultBlob
import com.soulreturns.data.hypixel.potionBagBlob
import com.soulreturns.data.hypixel.quiverBlob
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceCatalogClient
import com.soulreturns.data.sacks.SackCatalogClient
import com.soulreturns.data.sacks.SackCatalogIndex
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.LH_SMALL
import com.soulreturns.features.profileviewer.gui.kit.Mark
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.SkeletonNode
import com.soulreturns.features.profileviewer.gui.kit.SlotGridNode
import com.soulreturns.features.profileviewer.gui.kit.SplitNode
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.TextNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.slot
import com.soulreturns.features.sacks.SackSource
import com.soulreturns.features.sacks.SackState
import com.soulreturns.features.storage.StorageOverlay
import com.soulreturns.features.storage.StoragePageView
import com.soulreturns.features.storage.storageScope
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.MojangUuid
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import kotlin.math.max

/**
 * Catalog reads of the section, behind seams so a headless test can run the section without a client
 * (the catalogs cannot initialize there).
 */
internal object InventoryLookups {
    /** What one unit of [id] is worth, 0 when no price is known. */
    @Volatile
    var price: (String) -> Long = { id -> try { PriceCache.value(id) } catch (_: LinkageError) { 0L } }

    /** The catalog's display name of a SkyBlock id, null when the catalog does not know it. */
    @Volatile
    var itemName: (String) -> String? = { id -> try { ItemCatalogClient.byId(id)?.displayName } catch (_: LinkageError) { null } }

    /** The in-game name the backend saw for a market key (runes: `ZOMBIE_SLAYER_RUNE;1`), null when it has none. */
    @Volatile
    var marketName: (String) -> String? = { key -> try { PriceCatalogClient.name(key) } catch (_: LinkageError) { null } }

    /** Which items each sack holds (the backend's sack catalog); empty before the first download. */
    @Volatile
    var sackCatalog: () -> SackCatalogIndex = { try { SackCatalogClient.index } catch (_: LinkageError) { SackCatalogIndex.EMPTY } }
}

/**
 * Every container, searchable at once, plus sacks (mock `inventories`; sacks per `mocks/pv-sacks/` design b). The API
 * and the local counts are per item id, so the tiles group them by the backend's sack catalog and the player's owned
 * sacks ([SackView]); an item no known sack lists, or any item while the catalog is empty, sits under "Other items".
 */
object InventoriesSection : ProfileSection {
    override val id = "inventories"
    override val title = "Inventories"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_chest"
    override val subtitle = "Every container, searchable at once"

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.apiAccessDisabled) "Inventory API off: only sacks" else null

    /** Mock `CONTAINERS`: key and label, in nav order. */
    private val CONTAINERS = listOf(
        "all" to "Everything", "inv" to "Inventory", "ender" to "Ender Chest", "bp" to "Backpacks", "vault" to "Personal Vault",
        "potion" to "Potion Bag", "fishing" to "Fishing Bag", "quiver" to "Quiver", "sacks" to "Sacks",
    )

    /** The page groups that are not sacks, in the order "Everything" lists them. */
    private val GROUPS = listOf("inv", "ender", "bp", "vault", "potion", "fishing", "quiver")

    private const val NAV_W = 128
    private const val NAV_GAP = 10
    private const val WALL_MIN = 212

    /**
     * One page card's data: [items] drive search and value, [stacks] the drawing (null while the blob
     * still decodes), [failed] a blob that could not be read.
     */
    private class Page(val title: String, val items: List<BlobItem?>, val stacks: List<ItemStack?>?, val icon: ItemStack?, val failed: Boolean = false)

    private class NavEntry(val key: String, val label: String, val on: Boolean, val off: Boolean, val zero: Boolean, val count: Int?, val tip: String?, val lock: Boolean)

    override fun build(ctx: ProfileContext, ui: Ui) {
        val apiOn = !ctx.member.apiAccessDisabled
        val q = ui.query("q")
        val typed = ui.state.text("${ui.sectionId}.q").trim()
        val storage = if (!apiOn && ctx.isSelf) SoulLocal.storage(ctx) else null
        val pages: Map<String, List<Page>>? = if (apiOn) apiPages(ctx) else storage?.let { linkedMapOf("ender" to it.ender, "bp" to it.backpacks) }

        val soulSacks = if (ctx.isSelf) SoulLocal.sacks(ctx) else null
        val sacks = mergeSacks(ctx.member.sacks, soulSacks)
        val hideEmpty = ui.flag("hideEmpty")
        val sackRows = sacks.lines.filter { (!hideEmpty || it.count > 0) && (q.isEmpty() || sackName(it.id).lowercase(Locale.ROOT).contains(q)) }
        val sackHits = sackRows.count { it.count > 0 }

        val counts = HashMap<String, Int>()
        if (pages != null) for ((key, list) in pages) counts[key] = list.sumOf { page -> page.items.count { it?.matches(q) == true } }
        val total = counts.values.sum() + sackHits

        fun available(key: String): Boolean = if (key == "sacks") true else if (key == "all") pages != null else pages?.get(key)?.isNotEmpty() == true
        var view = ui.value("container", "all")
        if (!available(view)) view = if (pages != null) "all" else "sacks"

        if (!apiOn) ui.apiOff(ctx.name, ctx.isSelf, "Inventory API", "inventories, Ender Chest and backpacks")
        ui.row {
            search("q", "Find an item in every container", width = 260, count = if (q.isNotEmpty()) "$total" else null)
            if (view == "sacks") {
                grow()
                text("Sort", size = S.CAPTION, color = S.TEXT_DIM, wrap = false)
                seg("sackSort", "value", listOf("value" to "Value", "count" to "Count", "name" to "Name"))
                toggle("Hide empty", hideEmpty) { ui.toggleFlag("hideEmpty") }
            }
        }

        val nav = CONTAINERS.map { (key, label) ->
            val off = !available(key)
            val vault = key == "vault"
            NavEntry(
                key, label, on = view == key, off = off, zero = q.isNotEmpty() && !off && (counts[key] ?: if (key == "sacks") sackHits else if (key == "all") total else 0) == 0,
                count = if (q.isNotEmpty() && !off) (if (key == "sacks") sackHits else if (key == "all") total else counts[key]) else null,
                tip = if (!off) null else if (vault) "Personal Vault API off" else if (apiOn) "Nothing stored here" else "Inventory API off",
                lock = off && (vault || !apiOn),
            )
        }
        val stateKey = "${ui.sectionId}.container"
        val body = ui.collect {
            if (view == "sacks") {
                sackBody(ctx, sacks, apiOn, sackRows.isNotEmpty(), q, hideEmpty, ui.value("sackSort", "value"), ui.value("sackSel", ""), "${ui.sectionId}.sackSel")
            } else {
                if (storage != null) {
                    val seen = storage.at?.let { " (${Format.ago(ctx.nowMs - it)})" }.orEmpty()
                    note("Your Inventory API is off, so these are the Ender Chest and backpack pages Soul saw in your game$seen.")
                }
                val list = if (view == "all") GROUPS.flatMap { pages?.get(it).orEmpty() } else pages?.get(view).orEmpty()
                val shown = if (q.isNotEmpty()) list.filter { page -> page.items.any { it?.matches(q) == true } } else list
                if (shown.isEmpty()) {
                    if (q.isNotEmpty()) empty("pv_search", "Nothing matches \"$typed\"", "Searched names and lore of every item.")
                    else empty("pv_chest", "Nothing stored here", "No item contents came with this profile.")
                } else {
                    grid(WALL_MIN) { for (page in shown) add(pageNode(page, q)) }
                }
            }
        }
        ui.add(SplitNode(NAV_W, StackNode(listOf(NavNode(nav, stateKey)), 0), StackNode(body, S.GAP), NAV_GAP))
    }

    // ------------------------------------------------------------------ pages from the API

    private fun apiPages(ctx: ProfileContext): Map<String, List<Page>> {
        val m = ctx.member
        val out = LinkedHashMap<String, List<Page>>()
        m.inventoryBlob?.let { out["inv"] = listOf(single(ctx, "Inventory", it, null, reorder = true)) }
        m.enderChestBlob?.let { out["ender"] = ender(ctx, it) }
        val bps = m.backpackBlobs.map { bp ->
            val icon = bp.iconBlob?.let { ctx.items.get(it) }?.takeIf { it.ready }?.stacks?.firstOrNull()
            single(ctx, "Backpack ${bp.slot}", bp.data, icon, reorder = false)
        }
        if (bps.isNotEmpty()) out["bp"] = bps
        m.personalVaultBlob?.let { out["vault"] = listOf(single(ctx, "Personal Vault", it, null, reorder = false)) }
        m.potionBagBlob?.let { out["potion"] = listOf(single(ctx, "Potion Bag", it, null, reorder = false)) }
        m.fishingBagBlob?.let { out["fishing"] = listOf(single(ctx, "Fishing Bag", it, null, reorder = false)) }
        m.quiverBlob?.let { out["quiver"] = listOf(single(ctx, "Quiver", it, null, reorder = false)) }
        return out
    }

    private fun single(ctx: ProfileContext, title: String, blob: String, icon: ItemStack?, reorder: Boolean): Page {
        val info = ItemBlobs.parse(blob) ?: return Page(title, emptyList(), null, icon, failed = true)
        val decoded = ctx.items.get(blob)
        val items = if (reorder) inventoryDisplayOrder(info) else info
        val stacks = if (!decoded.ready) null else if (reorder) inventoryDisplayOrder(decoded.stacks) else decoded.stacks
        return Page(title, items, stacks, icon, failed = decoded.failed)
    }

    private fun ender(ctx: ProfileContext, blob: String): List<Page> {
        val info = ItemBlobs.parse(blob) ?: return listOf(Page("Ender Chest", emptyList(), null, null, failed = true))
        val decoded = ctx.items.get(blob)
        val stacks = if (decoded.ready) enderChestPages(decoded.stacks) else null
        return enderChestPages(info).mapIndexed { i, items -> Page("Ender Chest ${i + 1}", items, stacks?.getOrNull(i), null, failed = decoded.failed) }
    }

    private fun coins(v: Long): String = if (v >= 10_000) Format.short(v) else Format.num(v)

    private fun sackName(id: String): String = InventoryLookups.itemName(id) ?: SackView.runeName(id) ?: humanizeItemId(id)

    private fun pageNode(page: Page, q: String): Node {
        val matches = page.items.count { it?.matches(q) == true }
        val marks = if (q.isEmpty() || page.stacks == null) null else IntArray(page.stacks.size) { i ->
            page.items.getOrNull(i)?.let { if (it.matches(q)) Mark.MATCH else Mark.DIM } ?: Mark.NONE
        }
        val body: Node = if (page.failed) {
            TextNode(Rich.of("This container could not be read."), S.SMALL, S.TEXT_DIM, lineH = LH_SMALL)
        } else if (page.stacks == null) {
            SkeletonNode(2)
        } else {
            SlotGridNode(page.stacks, 9, S.SLOT, marks)
        }
        val priced = page.items.filterNotNull().filter { it.id != null }
        val worth = priced.sumOf { InventoryLookups.price(it.id!!) * it.count }
        val value = if (worth > 0) coins(worth) else if (priced.isNotEmpty()) "—" else "0"
        val matchText = if (q.isEmpty()) null else "$matches match${if (matches == 1) "" else "es"}"
        return PageNode(page.title, page.icon, matchText, value, body, faded = q.isNotEmpty() && matches == 0)
    }

    private fun Ui.sackBody(
        ctx: ProfileContext,
        sacks: SackSummary,
        apiOn: Boolean,
        anyShown: Boolean,
        q: String,
        hideEmpty: Boolean,
        sort: String,
        selected: String,
        stateKey: String,
    ) {
        if (sacks.newestSoulAt != null) note("Counts from your sack menus (${Format.ago(ctx.nowMs - sacks.newestSoulAt)}), newer than Hypixel's.")
        if (!apiOn) note("Sacks still show: Hypixel sends sack counts even with the Inventory API off.", chip = null)
        if (sacks.lines.isEmpty() || !anyShown) {
            if (q.isNotEmpty()) empty("pv_search", "No sack item matches", "Try another word.")
            else empty("pv_chest", "No sack counts", "Hypixel sent none for this profile.")
            return
        }
        val owned = ctx.member.ownedSacks.orEmpty()
        val catalog = InventoryLookups.sackCatalog()
        val grouping = SackView.group(owned, catalog, sacks.lines)
        val price: (String) -> Long = { id -> InventoryLookups.price(SackView.marketKey(id)) }
        val name = ::sackName
        val totals = SackView.totals(sacks.lines, price)
        val topSack = SackView.tiles(grouping, { it.count > 0 }, false, "value", name, price).firstOrNull { it.key != SackView.OTHER_KEY && it.value > 0 }
        tiles {
            tile("Total value", if (totals.value > 0) coins(totals.value) else "—", "at bazaar sell price", color = S.COIN)
            tile("Items stored", Format.num(totals.items), "${Format.num(totals.kinds)} kinds")
            totals.top?.let { tile("Most valuable", name(it.id), if (totals.topValue > 0) "${coins(totals.topValue)} coins · ${Format.num(it.count)} stored" else "${Format.num(it.count)} stored") }
            if (topSack != null) tile("Top sack", topSack.name, "${coins(topSack.value)} coins worth", color = topSack.color)
        }
        if (catalog.isEmpty) note("Soul has not learned which items belong to which sack yet, so they are listed together.", chip = null)
        else if (grouping.unknown.isNotEmpty()) note("${grouping.unknown.size} of the sacks in this Sack of Sacks have no item list yet; their items sit under Other items.", chip = null)
        val tiles = SackView.tiles(grouping, { (!hideEmpty || it.count > 0) && (q.isEmpty() || name(it.id).lowercase(Locale.ROOT).contains(q)) }, hideEmpty || q.isNotEmpty(), sort, name, price)
        val decoded = ctx.member.sacksBagBlob?.let { ctx.items.get(it) }?.takeIf { it.ready }
        add(SackTilesNode(tiles, selected, stateKey) { tile -> tile.owned?.let { decoded?.stacks?.getOrNull(it.slot) } })
    }

    /** `.page`: a container card, a header (icon, title, matches, worth) over its slot grid. */
    private class PageNode(
        val title: String,
        val icon: ItemStack?,
        val matchText: String?,
        val value: String,
        val body: Node,
        val faded: Boolean,
    ) : Node() {
        override fun measure(m: KitMeasure, w: Int): Int = PAD_TOP + HEAD_H + HEAD_GAP + body.height(m, w - PAD_X * 2) + PAD_BOTTOM

        override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
            c.layer()
            c.rect(x, y, x + w, y + h, S.CARD_BG, Corner.MEDIUM)
            val ix = x + PAD_X
            val top = y + PAD_TOP
            val bottom = top + HEAD_H
            var cx = ix
            if (icon != null) {
                c.slot(icon, cx, top, 14, count = false)
                cx += 14 + 5
            }
            c.text(title, cx, top, bottom, S.TEXT, S.SMALL, true)
            cx += c.width(title, S.SMALL, true) + 5
            if (matchText != null) c.text(matchText, cx, top, bottom, S.ACCENT_LIGHT, S.CAPTION)
            c.text(value, x + w - PAD_X - c.width(value, S.CAPTION), top, bottom, S.COIN, S.CAPTION)
            body.paint(c, ix, bottom + HEAD_GAP, w - PAD_X * 2, body.height(c, w - PAD_X * 2))
            if (faded) c.rect(x, y, x + w, y + h, FADE, Corner.MEDIUM)
        }

        private companion object {
            const val PAD_X = 8
            const val PAD_TOP = 7
            const val PAD_BOTTOM = 8
            const val HEAD_H = 14
            const val HEAD_GAP = 5

            /** `.page.nomatch { opacity: .4 }`: nodes cannot be translucent, so the page body color goes over it. */
            val FADE = 0x99171717.toInt()
        }
    }

    /**
     * `.cnav`: the container list, one 24 px button per container; [stateKey] is the section's `container` value.
     * Sticky like the mock's (`position: sticky; top: 0`): it stays at the top of the body while the pages scroll,
     * within the [h] its split row gives it.
     */
    private class NavNode(val entries: List<NavEntry>, val stateKey: String) : Node() {
        override fun measure(m: KitMeasure, w: Int): Int = entries.size * ROW_H + (entries.size - 1) * GAP

        override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
            val top = max(y, minOf(c.viewTop, y + h - height(c, w)))
            for ((i, e) in entries.withIndex()) {
                val y0 = top + i * (ROW_H + GAP)
                val y1 = y0 + ROW_H
                val hover = !e.off && c.hovered(x, y0, x + w, y1)
                if (e.on) {
                    c.rect(x, y0, x + w, y1, ON_BG, Corner.SMALL)
                    c.rect(x, y0 + 2, x + 2, y1 - 2, S.ACCENT, Corner.SQUARE)
                } else if (hover) {
                    c.rect(x, y0, x + w, y1, S.FIELD_BG, Corner.SMALL)
                }
                val color = if (e.off) S.TEXT_GHOST else if (e.on) 0xFFFFFFFF.toInt() else if (hover) S.TEXT else S.TEXT_DIM
                c.text(e.label, x + 8, y0, y1, color, S.BODY)
                if (e.lock) c.icon("pv_lock", x + w - 8 - 8, y0 + (ROW_H - 8) / 2, 8, S.TEXT_GHOST)
                else if (e.count != null) {
                    val label = "${e.count}"
                    c.text(label, x + w - 8 - c.width(label, S.CAPTION), y0, y1, if (e.zero) S.TEXT_FAINT else S.ACCENT_LIGHT, S.CAPTION)
                }
                if (e.off) {
                    if (e.tip != null) c.tooltip(x, y0, x + w, y1, listOf(e.tip), "cn:${e.key}")
                } else {
                    c.onClick(x, y0, x + w, y1) { c.state.set(stateKey, e.key) }
                }
            }
        }

        private companion object {
            const val ROW_H = 24
            const val GAP = 1
            val ON_BG = 0xFF1C2331.toInt()
        }
    }

    // ------------------------------------------------------------------ the player's own local data

    /** Read-only fill-ins for the player's own profile: pages Soul saw in game and sack counts from its menus. */
    private object SoulLocal {
        class Storage(val ender: List<Page>, val backpacks: List<Page>, val at: Long?)

        /**
         * The Ender Chest and backpack pages `storage.json` holds, only while the loaded storage is this
         * very account and profile (the overlay keeps one scope at a time). Pages never opened are skipped.
         */
        fun storage(ctx: ProfileContext): Storage? {
            val scope = storageScope(ctx.identity.uuidUndashed, ctx.profile.profileId) ?: return null
            if (StorageOverlay.scopeKey() != scope) return null
            val seen = StorageOverlay.pages().filter { it.items != null }
            if (seen.isEmpty()) return null
            fun page(view: StoragePageView, title: String): Page {
                val stacks = view.items!!
                return Page(title, stacks.map { info(it) }, stacks, null)
            }
            val ender = seen.filter { it.id.kind == "ender" }.sortedBy { it.id.index }.map { page(it, "Ender Chest ${it.id.index}") }
            val bps = seen.filter { it.id.kind == "backpack" }.sortedBy { it.id.index }.map { page(it, "Backpack ${it.id.index}") }
            val at = try {
                Files.getLastModifiedTime(PlayerDataset.file("storage.json")).toMillis()
            } catch (_: IOException) {
                null
            }
            return Storage(ender, bps, at)
        }

        private fun info(stack: ItemStack): BlobItem? {
            if (stack.isEmpty) return null
            val id = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }
            val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string }.orEmpty()
            return BlobItem(id, stack.hoverName.string, lore, stack.count, recombobulated = false)
        }

        /** Counts read from the player's sack menus or `[Sacks]` chat (not the API prefill), when this profile is the one in game. */
        fun sacks(ctx: ProfileContext): Map<String, Pair<Long, Long>>? {
            val current = SkyblockProfileState.current ?: return null
            val joined = MojangUuid.undashedOrNull(SkyblockProfileState.profileId)
            val same = if (joined != null) joined.equals(MojangUuid.undashedOrNull(ctx.profile.profileId), ignoreCase = true) else current.equals(ctx.profile.cuteName, ignoreCase = true)
            if (!same) return null
            return SackState.allCounts().filterValues { it.source != SackSource.API && it.updatedAt > 0L }.mapValues { it.value.count to it.value.updatedAt }
        }
    }
}
