package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.BlobItem
import com.soulreturns.data.hypixel.ItemBlobs
import com.soulreturns.data.hypixel.SackLine
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.ownedSacks
import com.soulreturns.data.hypixel.sackKeyOf
import com.soulreturns.data.sacks.SackCatalogEntry
import com.soulreturns.data.sacks.SackCatalogIndex
import com.soulreturns.data.sacks.SackCatalogSnapshot
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.InventoriesSection
import com.soulreturns.features.profileviewer.gui.sections.InventoryLookups
import com.soulreturns.features.profileviewer.gui.sections.SackView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StringTag
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Owned sacks from a Sack of Sacks blob (synthetic data), the sack grouping and the Inventories › Sacks body built from them. */
class SackViewTest {
    private val price = InventoryLookups.price
    private val itemName = InventoryLookups.itemName
    private val catalog = InventoryLookups.sackCatalog

    @BeforeTest
    fun seams() {
        InventoryLookups.price = { id -> if (id == "COAL") 10L else 100L }
        InventoryLookups.itemName = { null }
    }

    @AfterTest
    fun restore() {
        InventoryLookups.price = price
        InventoryLookups.itemName = itemName
        InventoryLookups.sackCatalog = catalog
    }

    private fun sack(id: String, name: String, capacity: Long, rarity: String = "EPIC", stitches: Int = 0): CompoundTag = CompoundTag().apply {
        putShort("id", 397)
        putByte("Count", 1)
        putShort("Damage", 3)
        put("tag", CompoundTag().apply {
            put("display", CompoundTag().apply {
                putString("Name", name)
                put("Lore", ListTag().apply {
                    add(StringTag.valueOf("§7Capacity: §a${"%,d".format(capacity)} of each item"))
                    add(StringTag.valueOf(""))
                    add(StringTag.valueOf("§5§l$rarity SACK"))
                })
            })
            put("ExtraAttributes", CompoundTag().apply {
                putString("id", id)
                if (stitches > 0) putInt("sack_pss", stitches)
            })
        })
    }

    private fun blob(vararg items: CompoundTag?): String {
        val list = ListTag()
        for (item in items) list.add(item ?: CompoundTag())
        val out = ByteArrayOutputStream()
        NbtIo.writeCompressed(CompoundTag().apply { put("i", list) }, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private val testBlob = blob(
        sack("LARGE_MINING_SACK", "§6Large Mining Sack", 22_176, stitches = 2),
        null,
        sack("MEDIUM_MINING_SACK", "§5Medium Mining Sack", 6_048, rarity = "RARE", stitches = 1),
        sack("LARGE_MINING_SACK", "§6Large Mining Sack", 22_176, rarity = "LEGENDARY"),
        sack("DWARVEN_MINES_SACK", "§5Dwarven Sack", 22_176),
        sack("RUNE_SACK", "§6Rune Sack", 4_096),
        CompoundTag().apply { putShort("id", 397); put("tag", CompoundTag().apply { put("ExtraAttributes", CompoundTag().apply { putString("id", "ENCHANTED_COAL") }) }) },
    )

    @Test
    fun `sizes fold into one sack, copies and capacity sum, stitches and rarity come along`() {
        val owned = ownedSacks(ItemBlobs.parse(testBlob)!!)
        assertEquals(listOf("MINING_SACK", "DWARVEN_MINES_SACK", "RUNE_SACK"), owned.map { it.key }, "the enchanted coal item is no sack")
        val mining = owned[0]
        assertEquals("Mining Sack", mining.name)
        assertEquals(listOf("Medium", "Large"), mining.sizes)
        assertEquals(3, mining.copies)
        assertEquals(22_176L + 6_048L + 22_176L, mining.capacity)
        assertEquals(3, mining.stitches)
        assertEquals("LEGENDARY", mining.rarity, "the highest rarity of its copies")
        assertEquals(0, mining.slot)
        val dwarven = owned[1]
        assertEquals("Dwarven Sack", dwarven.name)
        assertTrue(dwarven.sizes.isEmpty(), "a sack id without a size word has no size")
        assertEquals(1, dwarven.copies)
        assertEquals("Rune Sack", owned[2].name)
    }

    @Test
    fun `a sack key drops only the size prefix`() {
        assertEquals("MINING_SACK", sackKeyOf("SMALL_MINING_SACK"))
        assertEquals("ENCHANTED_COMBAT_SACK", sackKeyOf("LARGE_ENCHANTED_COMBAT_SACK"))
        assertEquals("SILVER_TROPHY_FROG_SACK", sackKeyOf("SILVER_TROPHY_FROG_SACK"))
        assertNull(sackKeyOf("LARGE_SACK_ITEM"))
        assertNull(sackKeyOf("ENCHANTED_COAL"))
    }

    @Test
    fun `an unreadable or missing blob is no sacks`() {
        assertEquals(emptyList(), ownedSacks(emptyList()))
        assertNull(ItemBlobs.parse("not a blob"))
    }

    private fun lines(vararg pairs: Pair<String, Long>, soul: Set<String> = emptySet()) = pairs.map { SackLine(it.first, it.second, it.first in soul) }

    private fun index(vararg entries: SackCatalogEntry) = SackCatalogIndex.of(SackCatalogSnapshot(1, entries.toList()))

    @Test
    fun `an item several owned sacks list is in each of them and counted once in the totals`() {
        val owned = ownedSacks(ItemBlobs.parse(testBlob)!!)
        val catalog = index(
            SackCatalogEntry("MINING_SACK", "Mining Sack", listOf("COAL", "MAGMA_CREAM")),
            SackCatalogEntry("DWARVEN_MINES_SACK", "Dwarven Sack", listOf("MAGMA_CREAM", "MITHRIL_ORE")),
            SackCatalogEntry("NETHER_SACK", "Nether Sack", listOf("QUARTZ")),
        )
        val sacks = lines("COAL" to 50L, "MAGMA_CREAM" to 7L, "MITHRIL_ORE" to 3L, "QUARTZ" to 9L, "DIRT" to 1L)
        val grouping = SackView.group(owned, catalog, sacks)
        assertEquals(listOf("MINING_SACK", "DWARVEN_MINES_SACK"), grouping.groups.map { it.key })
        assertEquals(listOf("COAL", "MAGMA_CREAM"), grouping.groups[0].lines.map { it.id })
        assertEquals(listOf("MAGMA_CREAM", "MITHRIL_ORE"), grouping.groups[1].lines.map { it.id })
        assertEquals(listOf("QUARTZ", "DIRT"), grouping.other!!.lines.map { it.id }, "the sack not owned lists QUARTZ: it falls to Other")
        assertEquals(listOf("RUNE_SACK"), grouping.unknown.map { it.key }, "an owned sack without a catalog entry (and no rune counts)")
        val totals = SackView.totals(sacks, InventoryLookups.price)
        assertEquals(50L * 10 + (7 + 3 + 9 + 1) * 100L, totals.value)
        assertEquals(50L + 7 + 3 + 9 + 1, totals.items)
        assertEquals(5, totals.kinds)
    }

    @Test
    fun `an empty catalog puts every held item under Other`() {
        val owned = ownedSacks(ItemBlobs.parse(testBlob)!!)
        val grouping = SackView.group(owned, SackCatalogIndex.EMPTY, lines("COAL" to 1L, "DIRT" to 0L))
        assertTrue(grouping.groups.isEmpty())
        assertEquals(listOf("COAL"), grouping.other!!.lines.map { it.id }, "a 0 under an id no sack lists is left out")
        assertEquals(owned.size, grouping.unknown.size)
        assertNull(SackView.group(owned, SackCatalogIndex.EMPTY, emptyList()).other)
    }

    @Test
    fun `the Rune Sack holds the RUNE_ tier counts`() {
        val owned = ownedSacks(ItemBlobs.parse(testBlob)!!)
        val grouping = SackView.group(owned, SackCatalogIndex.EMPTY, lines("RUNE_ZOMBIE_SLAYER_1" to 107L, "RUNE_BLOOD_2_1" to 110L, "RUNEBOOK" to 1L))
        val runes = grouping.groups.single { it.key == "RUNE_SACK" }
        assertEquals(listOf("RUNE_BLOOD_2_1", "RUNE_ZOMBIE_SLAYER_1"), runes.lines.map { it.id })
        assertEquals(listOf("RUNEBOOK"), grouping.other!!.lines.map { it.id })
        assertEquals("Zombie Slayer Rune I", SackView.runeName("RUNE_ZOMBIE_SLAYER_1"))
        assertEquals("BLOOD_2_RUNE;1", SackView.marketKey("RUNE_BLOOD_2_1"))
        assertEquals("COAL", SackView.marketKey("COAL"))
    }

    @Test
    fun `tiles follow the sort and filter, Other stays last`() {
        val owned = ownedSacks(ItemBlobs.parse(testBlob)!!)
        val catalog = index(
            SackCatalogEntry("MINING_SACK", "Mining Sack", listOf("COAL", "IRON")),
            SackCatalogEntry("DWARVEN_MINES_SACK", "Dwarven Sack", listOf("MITHRIL_ORE")),
        )
        val grouping = SackView.group(owned, catalog, lines("COAL" to 100L, "IRON" to 0L, "MITHRIL_ORE" to 50L, "DIRT" to 5L))
        val byValue = SackView.tiles(grouping, { true }, false, "value", { it }, InventoryLookups.price)
        // Mining 100 * 10 = 1,000 coins; Dwarven 50 * 100 = 5,000
        assertEquals(listOf("DWARVEN_MINES_SACK", "MINING_SACK", "OTHER"), byValue.map { it.key })
        assertEquals(listOf("COAL", "IRON"), byValue[1].rows.map { it.id }.sorted())
        val byName = SackView.tiles(grouping, { true }, false, "name", { it }, InventoryLookups.price)
        assertEquals(listOf("Dwarven Sack", "Mining Sack", "Other items"), byName.map { it.name })
        val filtered = SackView.tiles(grouping, { it.count > 0 && it.id == "COAL" }, true, "value", { it }, InventoryLookups.price)
        assertEquals(listOf("MINING_SACK"), filtered.map { it.key }, "tiles with no shown item vanish while a filter is on")
        val unfiltered = SackView.tiles(grouping, { it.count > 0 && it.id == "COAL" }, false, "value", { it }, InventoryLookups.price)
        assertEquals(3, unfiltered.size, "without a filter the empty tiles stay")
    }

    @Test
    fun `a row nearly at capacity is full and the tile counts them`() {
        val owned = ownedSacks(ItemBlobs.parse(testBlob)!!).filter { it.key == "DWARVEN_MINES_SACK" }
        val catalog = index(SackCatalogEntry("DWARVEN_MINES_SACK", "Dwarven Sack", listOf("MITHRIL_ORE", "COAL")))
        val grouping = SackView.group(owned, catalog, lines("MITHRIL_ORE" to 22_000L, "COAL" to 10L))
        val tile = SackView.tiles(grouping, { true }, false, "value", { it }, InventoryLookups.price).single()
        assertEquals(1, tile.full)
        assertEquals("", tile.sub)
    }

    // ------------------------------------------------------------------ the section

    private fun context(counts: Map<String, Long>, sacksBag: String? = testBlob): ProfileContext {
        val base = InvHarness.context("sam", "inventories-sam")
        val raw = base.member.raw
        val inv = raw["inventory"] as JsonObject
        val bags = inv["bag_contents"] as? JsonObject ?: JsonObject(emptyMap())
        val patchedBags = if (sacksBag == null) JsonObject(bags.filterKeys { it != "sacks_bag" }) else JsonObject(bags + ("sacks_bag" to JsonObject(mapOf("type" to JsonPrimitive(0), "data" to JsonPrimitive(sacksBag)))))
        val patchedInv = JsonObject(inv + ("bag_contents" to patchedBags) + ("sacks_counts" to JsonObject(counts.mapValues { JsonPrimitive(it.value) })))
        val member = SkyblockProfileMember(JsonObject(raw + ("inventory" to patchedInv)))
        return ProfileContext(base.identity, base.profiles, base.profile, member, false, null, true, null, 1_800_000_000_000L, {}, {})
    }

    private fun render(ctx: ProfileContext, vararg state: Pair<String, String>): String {
        val s = ViewState()
        s.set("inventories.container", "sacks")
        for ((k, v) in state) s.set("inventories.$k", v)
        return InvHarness.render(ctx, InventoriesSection, InvRecorder(s)).first.all()
    }

    private val counts = mapOf("COAL" to 50L, "MAGMA_CREAM" to 7L, "MITHRIL_ORE" to 3L, "DIRT" to 0L, "QUARTZ" to 9L)

    @Test
    fun `the sacks view shows summary tiles, sack tiles with their size line and Other`() {
        InventoryLookups.sackCatalog = {
            index(
                SackCatalogEntry("MINING_SACK", "Mining Sack", listOf("COAL", "MAGMA_CREAM")),
                SackCatalogEntry("DWARVEN_MINES_SACK", "Dwarven Sack", listOf("MITHRIL_ORE")),
            )
        }
        val text = render(context(counts))
        for (part in listOf("Total value", "Items stored", "Most valuable", "Top sack", "Mining Sack", "Medium + Large · ×3 · 3× stitched", "Dwarven Sack", "Other items", "Sort", "Hide empty")) {
            assertTrue(part in text, "missing '$part' in: $text")
        }
        assertTrue("Cobblestone" !in text)
        assertTrue("Rune Sack" !in text, "an owned sack without a catalog entry has no tile")
        assertTrue("have no item list yet" in text, text)
    }

    @Test
    fun `selecting a tile opens its drawer with item rows and capacity`() {
        InventoryLookups.sackCatalog = { index(SackCatalogEntry("MINING_SACK", "Mining Sack", listOf("COAL", "MAGMA_CREAM"))) }
        val text = render(context(counts), "sackSel" to "MINING_SACK")
        assertTrue("holds 50,400 of each (3 sacks summed)" in text, text)
        assertTrue("Coal" in text && "Magma Cream" in text, text)
        assertTrue("2 items" in text, text)
        val other = render(context(counts), "sackSel" to "OTHER")
        assertTrue("Mithril Ore" in other && "Quartz" in other, other)
        assertTrue("Dirt" !in other, "a 0 under an id no sack lists stays out of Other: $other")
    }

    @Test
    fun `with an empty catalog the whole view degrades to Other items`() {
        InventoryLookups.sackCatalog = { SackCatalogIndex.EMPTY }
        val text = render(context(counts), "sackSel" to "OTHER")
        assertTrue("Other items" in text && "Coal" in text && "Quartz" in text, text)
        assertTrue("Mining Sack" !in text, text)
        assertTrue("has not learned which items belong to which sack" in text, text)
        assertTrue("Top sack" !in text, "no sack to name")
    }

    @Test
    fun `without a Sack of Sacks blob every item is still listed`() {
        InventoryLookups.sackCatalog = { index(SackCatalogEntry("MINING_SACK", "Mining Sack", listOf("COAL"))) }
        val text = render(context(counts, sacksBag = null), "sackSel" to "OTHER")
        assertTrue("Other items" in text && "Coal" in text && "Quartz" in text, text)
    }

    @Test
    fun `the Soul note and empty states stay`() {
        InventoryLookups.sackCatalog = { SackCatalogIndex.EMPTY }
        assertTrue("No sack counts" in render(context(emptyMap())))
        assertTrue("No sack item matches" in render(context(counts)).let { render(context(counts)) + " " + renderSearch(context(counts), "zzz") })
    }

    private fun renderSearch(ctx: ProfileContext, query: String): String {
        val s = ViewState()
        s.set("inventories.container", "sacks")
        s.field("inventories.q").set(query)
        return InvHarness.render(ctx, InventoriesSection, InvRecorder(s)).first.all()
    }
}
