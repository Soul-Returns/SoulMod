package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.GearText
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.armorBlob
import com.soulreturns.data.hypixel.backpackBlobs
import com.soulreturns.data.hypixel.enderChestBlob
import com.soulreturns.data.hypixel.equipmentBlob
import com.soulreturns.data.hypixel.inventoryBlob
import com.soulreturns.data.hypixel.wardrobeBlob
import com.soulreturns.data.hypixel.wardrobeEquippedSet
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileItems
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.GearSection
import kotlinx.serialization.json.Json
import com.soulreturns.util.MessageDetector
import com.soulreturns.data.hypixel.armorSets
import com.soulreturns.data.hypixel.equipmentSets
import com.soulreturns.data.hypixel.loadouts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.SharedConstants
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures for the Gear and Pets sections: a base profile fixture with a member fragment merged in. */
internal object SpvGearFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    /** Deterministic text metrics: 5 px per character (6 bold). */
    class Recorder(override val state: ViewState = ViewState()) : KitCanvas {
        val texts = ArrayList<String>()
        override val viewTop = 0
        override val viewBottom = Int.MAX_VALUE
        override val nowMs = 1_800_000_000_000L
        override fun width(text: String, size: Float, bold: Boolean) = text.length * if (bold) 6 else 5
        override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean) = text.length * 6
        override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {}
        override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
            texts += text
        }
        override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
            texts += text
        }
        override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {}
        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {}
        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {}
        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int) = false
        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) {}
        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {}
        override fun itemTooltip(stack: ItemStack) {}
        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {}
        override fun openSection(id: String) {}

        /** Everything painted as one line, § codes stripped. */
        fun all(): String = texts.joinToString(" ").replace(Regex("§."), "").replace(Regex("\\s+"), " ")
    }

    fun resource(path: String): JsonObject =
        json.parseToJsonElement(SpvGearFixtures::class.java.getResource(path)!!.readText()).jsonObject

    fun fragment(name: String): JsonObject = resource("/profileviewer/fragments/$name.json")

    /** Top-level keys of [fragment] replace the member's, except objects, which merge one level (`inventory` replaces). */
    private fun mergeMember(member: JsonObject, fragment: JsonObject): JsonObject {
        val out = member.toMutableMap()
        for ((key, value) in fragment) {
            val old = out[key]
            out[key] = if (key != "inventory" && old is JsonObject && value is JsonObject) JsonObject(old + value) else value
        }
        return JsonObject(out)
    }

    fun context(base: String, vararg fragments: String, isSelf: Boolean = false): ProfileContext {
        val root = resource("/profileviewer/fixtures/$base.json")
        val uuid = root["uuid"]!!.jsonPrimitive.content
        val profiles = root["profiles"]!!.jsonObject
        val merged = profiles["profiles"]!!.jsonArray.map { p ->
            val profile = p.jsonObject
            val members = profile["members"]!!.jsonObject
            val member = members[uuid]?.jsonObject
            if (member == null) p else JsonObject(profile + ("members" to JsonObject(members + (uuid to fragments.map(::fragment).fold(member, ::mergeMember)))))
        }
        val parsed = SkyblockProfiles.from(JsonObject(profiles + ("profiles" to JsonArray(merged))))
        val profile = parsed.pick(null)!!
        return ProfileContext(
            PlayerIdentity(uuid, root["name"]!!.jsonPrimitive.content), parsed, profile, profile.member(uuid)!!, isSelf = isSelf,
            caps = null, capsLoaded = true, soulAnswer = null, nowMs = 1_800_000_000_000L, opener = {}, looker = {},
        )
    }

    /** Lays [ui] out at [w] and paints it; returns the canvas and the height. */
    fun paint(ui: Ui, w: Int = 969): Pair<Recorder, Int> {
        val c = Recorder()
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(c, w)
        root.paint(c, 0, 0, w, h)
        return c to h
    }
}

class GearSectionTest {
    private fun bound(item: net.minecraft.world.item.Item): net.minecraft.world.item.Item {
        val holder = item.builtInRegistryHolder()
        if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY)
        return item
    }

    @BeforeTest
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        ProfileItems.clear()
    }

    @AfterTest
    fun cleanup() = ProfileItems.clear()

    /** The strings of a blob's NBT, for checking the fixtures without Minecraft's item datafixer. */
    private fun blobText(base64: String): String =
        String(GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(base64))).readBytes(), Charsets.UTF_8)

    /** The rarity lines (`§lLEGENDARY DUNGEON BOOTS`) in a blob's raw bytes; bold enchant and ability lines do not count. */
    private fun typeLines(base64: String): List<String> =
        Regex("§l((?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL)\\b[A-Z ]*)").findAll(blobText(base64)).map { it.groupValues[1].trim() }.toList()

    private fun stack(item: net.minecraft.world.item.Item, name: String, typeLine: String?, modifier: String? = null): ItemStack {
        val s = ItemStack(bound(item))
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name))
        if (typeLine != null) s.set(DataComponents.LORE, ItemLore(listOf(Component.literal("§7Damage: §c+100"), Component.literal(typeLine))))
        if (modifier != null) s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("modifier", modifier) }))
        return s
    }

    // ------------------------------------------------------------------ data

    @Test
    fun `sam fixture has every blob and the worn set`() {
        val m = SpvGearFixtures.context("sam", "gear-sam").member
        assertFalse(m.apiAccessDisabled)
        assertNotNull(m.armorBlob)
        assertNotNull(m.equipmentBlob)
        assertNotNull(m.wardrobeBlob)
        assertNotNull(m.inventoryBlob)
        assertNotNull(m.enderChestBlob)
        assertEquals(listOf(0), m.backpackBlobs.map { it.index })
        assertEquals(0, m.wardrobeEquippedSet)
    }

    @Test
    fun `joe wears the second set and builder has no blobs at all`() {
        assertEquals(1, SpvGearFixtures.context("joe", "gear-joe").member.wardrobeEquippedSet)
        val b = SpvGearFixtures.context("builder", "gear-builder").member
        assertTrue(b.apiAccessDisabled)
        assertNull(b.armorBlob)
        assertNull(b.inventoryBlob)
        assertNull(b.wardrobeEquippedSet)
        assertTrue(b.backpackBlobs.isEmpty())
    }

    @Test
    fun `an absent wardrobe slot reads as none`() {
        assertNull(com.soulreturns.data.hypixel.SkyblockProfileMember(JsonObject(emptyMap())).wardrobeEquippedSet)
    }

    @Test
    fun `fixture armor is stored boots first and orders back to helmet first`() {
        val armor = SpvGearFixtures.context("sam", "gear-sam").member.armorBlob!!
        val lines = typeLines(armor)
        assertEquals(listOf("LEGENDARY DUNGEON BOOTS", "LEGENDARY DUNGEON LEGGINGS", "LEGENDARY DUNGEON CHESTPLATE", "LEGENDARY DUNGEON HELMET"), lines)
        val ordered = GearText.orderArmor(lines, reverseFallback = true) { it }
        assertEquals(listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"), ordered.map { it!!.substringAfterLast(' ') })
    }

    @Test
    fun `the weapon rule finds what the fixtures hold`() {
        val ender = blobText(SpvGearFixtures.context("sam", "gear-sam").member.enderChestBlob!!)
        assertTrue("Livid Dagger" in ender && "Axe of the Shredded" in ender)
        val inv = typeLines(SpvGearFixtures.context("sam", "gear-sam").member.inventoryBlob!!)
        assertEquals(setOf("LEGENDARY DUNGEON SWORD", "LEGENDARY DUNGEON BOW", "EPIC SWORD", "MYTHIC DRILL", "LEGENDARY FISHING ROD", "RARE ITEM"), inv.toSet())
        assertEquals(6, inv.count { GearText.isWeapon(it) })
    }

    // ------------------------------------------------------------------ text rules

    @Test
    fun `type lines lose the recombobulator markers and codes`() {
        assertEquals("MYTHIC DUNGEON SWORD", GearText.typeLine("§d§l§ka§r§d§l §d§lMYTHIC DUNGEON SWORD §d§l§ka"))
        assertEquals("EPIC BOW", GearText.typeLine("§5§lEPIC BOW"))
        assertEquals("", GearText.typeLine(""))
    }

    @Test
    fun `armor pieces and weapons are recognized by the type line`() {
        assertEquals(0, GearText.armorPiece("LEGENDARY DUNGEON HELMET"))
        assertEquals(3, GearText.armorPiece("EPIC BOOTS"))
        assertNull(GearText.armorPiece("EPIC NECKLACE"))
        for (w in listOf("LEGENDARY DUNGEON SWORD", "EPIC BOW", "MYTHIC DRILL", "EPIC PICKAXE", "RARE AXE", "LEGENDARY FISHING ROD", "EPIC WAND", "RARE LONGSWORD")) assertTrue(GearText.isWeapon(w), w)
        for (w in listOf("EPIC ACCESSORY", "RARE ITEM", "LEGENDARY DUNGEON CHESTPLATE", "COMMON PET ITEM", "")) assertFalse(GearText.isWeapon(w), w)
    }

    @Test
    fun `armor falls back to the positional order when a type is unknown`() {
        assertEquals(listOf("a", "b", "c", "d"), GearText.orderArmor(listOf("d", "c", "b", "a"), reverseFallback = true) { "ITEM" })
        assertEquals(listOf("d", "c", "b", "a"), GearText.orderArmor(listOf("d", "c", "b", "a"), reverseFallback = false) { "ITEM" })
        val partial = GearText.orderArmor(listOf("EPIC BOOTS", null, "EPIC CHESTPLATE"), reverseFallback = true) { it }
        assertEquals(listOf(null, "EPIC CHESTPLATE", null, "EPIC BOOTS"), partial)
    }

    @Test
    fun `set names ignore the reforge and the piece word`() {
        assertEquals("Necron's set", GearText.setName(listOf("Necron's Helmet", "Necron's Chestplate", "Necron's Leggings", "Necron's Boots")))
        assertEquals("Super Heavy set", GearText.setName(listOf("Super Heavy Helmet", "Super Heavy Chestplate", "Super Heavy Leggings", "Super Heavy Boots")))
        assertEquals("mixed armor", GearText.setName(listOf("Necron's Helmet", "Storm's Chestplate", "Necron's Leggings", "Necron's Boots")))
        assertEquals("mixed armor", GearText.setName(listOf("Necron's Helmet", null, "Necron's Leggings", "Necron's Boots")))
        assertEquals("Necron's Helmet", GearText.withoutReforge("Ancient Necron's Helmet", "ancient"))
        assertEquals("Very Wise Helmet", GearText.withoutReforge("Very Wise Helmet", "ancient"))
        assertEquals("Heavy Helmet", GearText.withoutReforge("Very Wise Heavy Helmet", "very_wise"))
    }

    @Test
    fun `places name the container`() {
        assertEquals("Hotbar 1", GearText.inventoryPlace(0))
        assertEquals("Hotbar 9", GearText.inventoryPlace(8))
        assertEquals("Inventory", GearText.inventoryPlace(9))
        assertEquals("Ender Chest 1", GearText.enderPlace(44))
        assertEquals("Ender Chest 3", GearText.enderPlace(90))
        assertEquals("Heroic Hyperion", GearText.cleanName("Heroic Hyperion ✪✪✪"))
    }

    // ------------------------------------------------------------------ section

    @Test
    fun `inventory api off shows the notice, not empty slots`() {
        val ctx = SpvGearFixtures.context("builder", "gear-builder")
        assertEquals("Inventory API off", GearSection.locked(ctx))
        assertNull(GearSection.badge(ctx))
        val ui = Ui(GearSection.id, ViewState())
        GearSection.build(ctx, ui)
        val text = SpvGearFixtures.paint(ui).first.all()
        assertTrue("Builder42's Inventory API is off" in text, text)
        assertFalse("Wardrobe" in text)
    }

    @Test
    fun `the first frame draws the cards while the blobs decode`() {
        val ctx = SpvGearFixtures.context("sam", "gear-sam")
        val ui = Ui(GearSection.id, ViewState())
        GearSection.build(ctx, ui)
        val (canvas, h) = SpvGearFixtures.paint(ui)
        val text = canvas.all()
        assertTrue("Equipped" in text && "Wardrobe" in text && "Weapons and tools" in text, text)
        assertTrue(h > 0)
    }

    private fun view(worn: Int?, wardrobeSets: Int, pages: Int = 2): GearSection.View {
        val names = listOf("Helmet", "Chestplate", "Leggings", "Boots")
        val armor = names.map { stack(Items.LEATHER_HELMET, "§6Ancient Necron's $it", "§6§lLEGENDARY DUNGEON ${it.uppercase()}", "ancient") }
        val set = List(4) { armor[it] }
        val sets = List(pages * 9) { if (it < wardrobeSets) set else listOf(null, null, null, null) }
        val weapons = listOf(
            GearSection.Weapon(stack(Items.IRON_SWORD, "§6Heroic Hyperion", "§6§lLEGENDARY DUNGEON SWORD"), "Hotbar 1"),
            GearSection.Weapon(stack(Items.FISHING_ROD, "§6Rod of the Sea", "§6§lLEGENDARY FISHING ROD"), "Ender Chest 3"),
        )
        return GearSection.View(
            armor, listOf(stack(Items.PAPER, "§5Implosion Belt", "§5§lEPIC BELT"), null, null, null), GearSection.Part(),
            GearSection.Wardrobe(sets, worn, slotCount = pages * 36, filled = wardrobeSets * 4), GearSection.Part(), weapons, GearSection.Part(),
        )
    }

    private fun built(v: GearSection.View, w: Int = 969): Pair<SpvGearFixtures.Recorder, Int> {
        val ui = Ui(GearSection.id, ViewState())
        GearSection.render(ui, v)
        return SpvGearFixtures.paint(ui, w)
    }

    @Test
    fun `decoded gear paints names, type lines, places and the set name`() {
        val text = built(view(worn = 0, wardrobeSets = 3)).first.all()
        assertTrue("Necron's set" in text, text)
        assertTrue("Ancient Necron's Helmet" in text, text)
        assertTrue("LEGENDARY DUNGEON HELMET" in text, text)
        assertTrue("Implosion Belt" in text && "EPIC BELT" in text, text)
        assertTrue("Empty" in text, text)
        assertTrue("Necklace" in text || "Cloak" in text, text)
        assertTrue("Heroic Hyperion" in text, text)
        assertTrue("LEGENDARY DUNGEON SWORD · Hotbar 1" in text, text)
        assertTrue("LEGENDARY FISHING ROD · Ender Chest 3" in text, text)
        assertTrue("3 of 18 sets" in text, text)
        assertTrue("PAGE 1" in text && "PAGE 2" in text && "PAGE 3" !in text, text)
    }

    @Test
    fun `an empty wardrobe and no weapons still build`() {
        val v = view(worn = null, wardrobeSets = 0)
        val empty = GearSection.View(v.armor, v.equipment, v.equipped, v.wardrobe, v.wardrobePart, emptyList(), GearSection.Part())
        val text = built(empty).first.all()
        assertTrue("0 of 18 sets" in text, text)
        assertTrue("PAGE 2" in text, text)
        assertTrue("No weapons or tools found" in text, text)
    }

    @Test
    fun `unreadable parts say so instead of showing empty slots`() {
        val v = view(worn = 0, wardrobeSets = 3)
        val broken = GearSection.View(v.armor, v.equipment, GearSection.Part(failed = true), v.wardrobe, GearSection.Part(loading = true), v.weapons, v.weaponsPart)
        val text = built(broken).first.all()
        assertTrue("could not be read" in text, text)
        assertFalse("Necron's set" in text)
    }

    @Test
    fun `wardrobe pages sit side by side when there is room and stack when there is not`() {
        val wide = built(view(0, 3), 969).second
        val narrow = built(view(0, 3), 476).second
        assertTrue(narrow > wide, "narrow $narrow wide $wide")
        assertEquals(built(view(0, 3), 969).second, wide)
    }

    @Test
    fun `a third page joins the row when it fits and wraps to a second row when it does not`() {
        assertEquals(3, GearSection.pagesPerRow(969, 3))
        assertEquals(2, GearSection.pagesPerRow(476, 3))
        assertEquals(1, GearSection.pagesPerRow(300, 3))
        assertEquals(2, GearSection.pagesPerRow(969, 2))
        val wide = built(view(0, 3, pages = 3), 969)
        val narrow = built(view(0, 3, pages = 3), 476)
        assertTrue("PAGE 3" in wide.first.all() && "PAGE 3" in narrow.first.all())
        assertTrue("3 of 27 sets" in wide.first.all(), wide.first.all())
        assertEquals(built(view(0, 3, pages = 2), 969).second, wide.second)
        assertTrue(narrow.second > wide.second)
    }

    @Test
    fun `wardrobe slots map column to set and row to piece, per page`() {
        val slots = MutableList<ItemStack?>(72) { null }
        val a = stack(Items.LEATHER_HELMET, "A", "§6§lEPIC HELMET")
        val b = stack(Items.LEATHER_BOOTS, "B", "§6§lEPIC BOOTS")
        val c = stack(Items.LEATHER_HELMET, "C", "§6§lEPIC HELMET")
        slots[2] = a // page 1, column 3 (set 3), helmet row
        slots[27 + 2] = b // same set, boots row
        slots[36 + 8] = c // page 2, column 9 (set 18)
        val sets = GearSection.wardrobeSets(slots)
        assertEquals(18, sets.size)
        assertTrue(sets[2][0] === a && sets[2][3] === b)
        assertTrue(sets[17][0] === c)
        assertTrue(sets[0].all { it == null })
    }

    @Test
    fun `the set count follows the data's pages and always holds the worn set`() {
        assertEquals(18, GearText.wardrobeSetCount(0, null))
        assertEquals(18, GearText.wardrobeSetCount(72, null))
        assertEquals(27, GearText.wardrobeSetCount(108, null))
        assertEquals(36, GearText.wardrobeSetCount(144, 12))
        assertEquals(27, GearText.wardrobeSetCount(72, 20))
        assertEquals(18, GearText.wardrobeSetCount(72, 17))
        assertEquals(450, GearText.wardrobeSetCount(0, 1_000_000))
    }

    // ------------------------------------------------------------------ wardrobe blobs through the real decoder

    /** A 1.8 item: numeric [id] (leather armor 298..301), name and the rarity line Hypixel puts last in the lore. */
    private fun legacyItem(id: Int, name: String, typeLine: String, uuid: String? = null): CompoundTag = CompoundTag().apply {
        putShort("id", id.toShort())
        putByte("Count", 1)
        putShort("Damage", 0)
        put("tag", CompoundTag().apply {
            put("display", CompoundTag().apply {
                putString("Name", name)
                put("Lore", ListTag().apply { add(StringTag.valueOf("§7Defense: §a+10")); add(StringTag.valueOf(typeLine)) })
            })
            put("ExtraAttributes", CompoundTag().apply { putString("id", "TEST_PIECE"); if (uuid != null) putString("uuid", uuid) })
        })
    }

    private val pieceIds = listOf(298, 299, 300, 301)
    private val pieceNames = listOf("Helmet", "Chestplate", "Leggings", "Boots")

    /** The four pieces of a test set named [set]; slot of piece [p] in a column is `page * 36 + row(p) * 9 + column`. */
    private fun piece(set: String, p: Int) = legacyItem(pieceIds[p], "§6$set ${pieceNames[p]}", "§6§lLEGENDARY ${pieceNames[p].uppercase()}")

    /** A Hypixel blob of [count] slots, empty except [filled]. */
    private fun blob(count: Int, filled: Map<Int, CompoundTag>): String {
        val list = ListTag()
        repeat(count) { list.add(filled[it] ?: CompoundTag()) }
        val out = ByteArrayOutputStream()
        NbtIo.writeCompressed(CompoundTag().apply { put("i", list) }, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** The sam fixture's member with its wardrobe replaced (null = key absent); every other blob stays. */
    private fun contextWith(wardrobe: String?, worn: Long? = null, loadout: JsonObject? = null): ProfileContext {
        val base = SpvGearFixtures.context("sam", "gear-sam")
        val raw = base.member.raw
        val inv = raw["inventory"] as JsonObject
        val kept = inv.filterKeys { it != "wardrobe_contents" && it != "wardrobe_equipped_slot" }
        val patched = buildMap {
            putAll(kept)
            if (wardrobe != null) put("wardrobe_contents", JsonObject(mapOf("type" to JsonPrimitive(0), "data" to JsonPrimitive(wardrobe))))
            if (worn != null) put("wardrobe_equipped_slot", JsonPrimitive(worn))
        }
        val member = SkyblockProfileMember(JsonObject(raw + ("inventory" to JsonObject(patched)) + (if (loadout != null) mapOf("loadout" to loadout) else emptyMap())))
        return ProfileContext(base.identity, base.profiles, base.profile, member, false, null, true, null, 1_800_000_000_000L, {}, {})
    }

    private fun <T> synchronously(block: () -> T): T {
        SectionBuildTest.bindItemComponents
        val before = ProfileItems.synchronous
        ProfileItems.synchronous = true
        try {
            return block()
        } finally {
            ProfileItems.synchronous = before
        }
    }

    private fun names(set: List<ItemStack?>) = set.map { it?.let { s -> GearSection.plainName(s) } }

    @Test
    fun `a 108-slot wardrobe gives 27 sets with their pieces in the right set, helmet first`() = synchronously {
        val filled = HashMap<Int, CompoundTag>()
        // set 22 (page 3, column 4), stored boots-first in the rows to prove the type line orders them
        for (row in 0..3) filled[72 + row * 9 + 3] = piece("Third", 3 - row)
        // set 2 (page 1, column 2) in the normal order, and set 13 (page 2, column 4) helmet only
        for (row in 0..3) filled[row * 9 + 1] = piece("First", row)
        filled[36 + 3] = piece("Second", 0)
        val ctx = contextWith(blob(108, filled), worn = 13)
        val v = GearSection.view(ctx)
        assertTrue(v.wardrobePart.ready)
        assertEquals(27, v.wardrobe.sets.size)
        assertEquals(3, v.wardrobe.pages)
        assertEquals(12, v.wardrobe.worn)
        assertEquals(3, v.wardrobe.setsWithPieces)
        assertEquals(9, v.wardrobe.filled)
        assertEquals(0, v.wardrobe.unreadable)
        assertEquals(listOf("§6First Helmet", "§6First Chestplate", "§6First Leggings", "§6First Boots"), names(v.wardrobe.sets[1]).map { it!! })
        assertEquals(listOf("§6Second Helmet", null, null, null), names(v.wardrobe.sets[12]))
        assertEquals(listOf("§6Third Helmet", "§6Third Chestplate", "§6Third Leggings", "§6Third Boots"), names(v.wardrobe.sets[21]).map { it!! })
        assertTrue(v.wardrobe.sets[0].all { it == null } && v.wardrobe.sets[26].all { it == null })
        val text = built(v).first.all()
        assertTrue("3 of 27 sets" in text && "PAGE 3" in text, text)
    }

    @Test
    fun `a 72-slot wardrobe gives 18 sets`() = synchronously {
        val filled = mapOf(0 to piece("Only", 0), 9 to piece("Only", 1), 36 + 8 to piece("Last", 0))
        val v = GearSection.view(contextWith(blob(72, filled)))
        assertEquals(18, v.wardrobe.sets.size)
        assertEquals(2, v.wardrobe.setsWithPieces)
        assertNull(v.wardrobe.worn)
        val text = built(v).first.all()
        assertTrue("2 of 18 sets" in text && "PAGE 2" in text && "PAGE 3" !in text, text)
    }

    @Test
    fun `a worn set past the blob's pages is still drawn`() = synchronously {
        val v = GearSection.view(contextWith(blob(72, emptyMap()), worn = 21))
        assertEquals(27, v.wardrobe.sets.size)
        assertEquals(20, v.wardrobe.worn)
    }

    @Test
    fun `no wardrobe blob with the Inventory API on is not an empty wardrobe`() = synchronously {
        val ctx = contextWith(null, worn = 13)
        assertFalse(ctx.member.apiAccessDisabled)
        val v = GearSection.view(ctx)
        assertFalse(v.wardrobe.present)
        assertTrue(v.wardrobe.noData)
        assertNull(v.wardrobe.worn)
        val text = built(v).first.all()
        assertTrue("No wardrobe data" in text && "did not send a wardrobe" in text, text)
        assertFalse("of 18 sets" in text || "PAGE 1" in text, text)
    }

    @Test
    fun `a blob without slots says so`() = synchronously {
        val v = GearSection.view(contextWith(blob(0, emptyMap())))
        assertTrue(v.wardrobe.present && v.wardrobe.noData)
        val text = built(v).first.all()
        assertTrue("holds no slots" in text && "PAGE 1" !in text, text)
    }

    @Test
    fun `a wardrobe whose occupied slots cannot be decoded says so instead of drawing empty frames`() = synchronously {
        val bad = legacyItem(9999, "Unknown", "§6§lLEGENDARY HELMET")
        val filled = mapOf(0 to bad, 9 to piece("Good", 1), 36 + 1 to bad)
        val v = GearSection.view(contextWith(blob(72, filled)))
        assertEquals(72, v.wardrobe.slotCount)
        assertEquals(1, v.wardrobe.filled)
        assertEquals(2, v.wardrobe.unreadable)
        val text = built(v).first.all()
        assertTrue("1 of 18 sets" in text && "2 wardrobe items could not be read." in text, text)
    }

    @Test
    fun `a genuinely empty wardrobe is shown as empty slots`() = synchronously {
        val v = GearSection.view(contextWith(blob(108, emptyMap())))
        assertEquals(27, v.wardrobe.sets.size)
        assertEquals(0, v.wardrobe.unreadable)
        val text = built(v).first.all()
        assertTrue("0 of 27 sets" in text && "could not be read" !in text, text)
    }

    // ------------------------------------------------------------------ Loadouts (loadout.armor / equipment / loadouts)

    private fun obj(vararg e: Pair<String, JsonElement>) = JsonObject(mapOf(*e))

    /** A set piece as Hypixel sends it: `{type: 0, data: <one-item blob>}`. */
    private fun pieceJson(item: CompoundTag) = obj("type" to JsonPrimitive(0), "data" to JsonPrimitive(blob(1, mapOf(0 to item))))

    private fun lset(id: Int, vararg pieces: Pair<String, CompoundTag>) =
        obj("id" to JsonPrimitive(id), *pieces.map { (key, item) -> key to pieceJson(item) }.toTypedArray())

    /** `loadout.armor` or `loadout.equipment`: sets keyed by id (in the order given) plus `equipped_set`. */
    private fun setsJson(equipped: Int?, vararg sets: JsonObject): JsonObject = JsonObject(
        buildMap {
            for (s in sets) put(s["id"]!!.jsonPrimitive.content, s)
            if (equipped != null) put("equipped_set", JsonPrimitive(equipped))
        },
    )

    private fun loadoutJson(id: Int, armorSet: Int?, equipmentSet: Int?) = obj(
        "id" to JsonPrimitive(id), "name" to JsonPrimitive("Loadout $id"),
        *listOfNotNull(armorSet?.let { "armor_set_id" to JsonPrimitive(it) }, equipmentSet?.let { "equipment_set_id" to JsonPrimitive(it) }).toTypedArray(),
    )

    private fun loadoutOf(armor: JsonObject? = null, equipment: JsonObject? = null, loadouts: JsonObject? = null) = JsonObject(
        buildMap {
            if (armor != null) put("armor", armor)
            if (equipment != null) put("equipment", equipment)
            if (loadouts != null) put("loadouts", loadouts)
        },
    )

    private fun plain(s: ItemStack?) = s?.let { MessageDetector.stripColorCodes(GearSection.plainName(it)) }

    @Test
    fun `loadout armor sets fill their columns by id with pieces in the right rows, sparse ids and the worn set`() = synchronously {
        val armor = setsJson(
            12,
            // keys in scrambled order; the helmet key holds an item whose type line says BOOTS: the key decides the row
            lset(1, "BOOTS" to piece("One", 3), "HELMET" to legacyItem(298, "§6One Helmet", "§6§lLEGENDARY BOOTS"), "LEGGINGS" to piece("One", 2), "CHESTPLATE" to piece("One", 1)),
            lset(3, "CHESTPLATE" to piece("Three", 1)),
            lset(5),
            lset(12),
        )
        val v = GearSection.view(contextWith(null, worn = 7, loadout = loadoutOf(armor)))
        assertTrue(v.wardrobePart.ready)
        assertEquals("loadout", v.wardrobe.source)
        assertEquals(18, v.wardrobe.sets.size)
        assertEquals(11, v.wardrobe.worn) // equipped_set 12, not wardrobe_equipped_slot 7
        assertEquals(3, v.wardrobe.setsWithPieces)
        assertEquals(5, v.wardrobe.filled)
        assertEquals(0, v.wardrobe.unreadable)
        assertEquals(listOf("One Helmet", "One Chestplate", "One Leggings", "One Boots"), v.wardrobe.sets[0].map { plain(it) })
        assertEquals(listOf(null, "Three Chestplate", null, null), v.wardrobe.sets[2].map { plain(it) })
        for (empty in listOf(1, 3, 4, 5, 17)) assertTrue(v.wardrobe.sets[empty].all { it == null }, "column $empty")
        // the worn set's own entry is empty: its column shows the worn armor, helmet first
        assertTrue(v.armor.all { it != null })
        assertEquals(v.armor.map { plain(it) }, v.wardrobe.sets[11].map { plain(it) })
        val text = built(v).first.all()
        assertTrue("3 of 18 sets" in text && "PAGE 2" in text && "PAGE 3" !in text, text)
    }

    @Test
    fun `the set count is the highest id rounded up to a page, never below two pages, and holds the worn set`() = synchronously {
        fun count(highest: Int, worn: Int? = null): Int {
            val v = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(worn, lset(1, "HELMET" to piece("A", 0)), lset(highest)))))
            return v.wardrobe.sets.size
        }
        assertEquals(18, count(9))
        assertEquals(18, count(18))
        assertEquals(27, count(19))
        assertEquals(27, count(20))
        assertEquals(36, count(28))
        assertEquals(27, count(5, worn = 22))
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(null, lset(20, "HELMET" to piece("Last", 0))))))
        assertEquals("Last Helmet", plain(v.wardrobe.sets[19][0]))
        assertTrue("1 of 27 sets" in built(v).first.all(), built(v).first.all())
    }

    @Test
    fun `equipment sets are a second card of four-slot columns with their own worn set`() = synchronously {
        val equipment = setsJson(
            2,
            lset(1, "EQUIPMENT_SLOT_4" to piece("Gloves", 0), "EQUIPMENT_SLOT_1" to piece("Necklace", 1)),
            lset(2),
            lset(4, "EQUIPMENT_SLOT_2" to piece("Cloak", 2)),
        )
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(1, lset(1)), equipment)))
        val es = v.equipmentSets!!
        assertEquals(9, es.sets.size) // one page: highest id 4 rounded up
        assertEquals(1, es.worn)
        assertEquals(listOf("Necklace Chestplate", null, null, "Gloves Helmet"), es.sets[0].map { plain(it) })
        assertEquals(listOf(null, "Cloak Leggings", null, null), es.sets[3].map { plain(it) })
        assertEquals(3, es.setsWithPieces) // sets 1 and 4, plus the worn set 2 shown from equipment_contents
        assertEquals(v.equipment.map { plain(it) }, es.sets[1].map { plain(it) })
        val text = built(v).first.all()
        assertTrue("Equipment sets" in text && "3 of 9 sets" in text, text)
        assertEquals(2, Regex("PAGE 1").findAll(text).count(), text) // wardrobe's and equipment's first page
        assertFalse("PAGE 3" in text, text)
    }

    @Test
    fun `a member with armor sets only has no equipment sets card`() = synchronously {
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(1, lset(1, "HELMET" to piece("A", 0))))))
        assertNull(v.equipmentSets)
        assertFalse("Equipment sets" in built(v).first.all())
    }

    @Test
    fun `the worn set follows equipped_set, and the selected loadout in wardrobe_equipped_slot never moves it`() = synchronously {
        val armor = setsJson(9, lset(1, "HELMET" to piece("A", 0)), lset(9))
        val withLoadouts = loadoutOf(armor, loadouts = obj("13" to loadoutJson(13, 9, 8)))
        assertEquals(8, GearSection.view(contextWith(null, worn = 13, loadout = withLoadouts)).wardrobe.worn)
        // sets migrated without equipped_set: the old meaning of wardrobe_equipped_slot (the worn set, 1-based) ...
        val migrated = loadoutOf(setsJson(null, lset(1, "HELMET" to piece("A", 0)), lset(13)))
        assertEquals(13, GearSection.view(contextWith(null, worn = 14, loadout = migrated)).wardrobe.worn)
        // ... but with loadouts present that number is a loadout id, so nothing is outlined
        val unknown = loadoutOf(setsJson(null, lset(1, "HELMET" to piece("A", 0))), loadouts = obj("1" to loadoutJson(1, 1, null)))
        assertNull(GearSection.view(contextWith(null, worn = 14, loadout = unknown)).wardrobe.worn)
    }

    @Test
    fun `set columns list the loadouts that use them`() = synchronously {
        val armor = setsJson(2, lset(1, "HELMET" to piece("A", 0)), lset(2))
        val loadouts = obj("3" to loadoutJson(3, 1, 2), "5" to loadoutJson(5, 1, null), "7" to loadoutJson(7, null, null), "9" to loadoutJson(9, 2, 1))
        val equipment = setsJson(1, lset(1), lset(2, "EQUIPMENT_SLOT_1" to piece("E", 0)))
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(armor, equipment, loadouts)))
        assertEquals(listOf("Loadout 3", "Loadout 5"), v.wardrobe.usedBy[0])
        assertEquals(listOf("Loadout 9"), v.wardrobe.usedBy[1])
        assertNull(v.wardrobe.usedBy[2])
        assertEquals(listOf("Loadout 9"), v.equipmentSets!!.usedBy[0])
        assertEquals(listOf("Loadout 3"), v.equipmentSets.usedBy[1])
        assertEquals(listOf("Equipped right now", "Used by: Loadout 3, Loadout 5"), GearSection.setTooltip(true, v.wardrobe.usedBy[0]))
        assertEquals(listOf("Used by: Loadout 9"), GearSection.setTooltip(false, v.wardrobe.usedBy[1]))
        assertTrue(GearSection.setTooltip(false, null).isEmpty())
    }

    @Test
    fun `an old wardrobe blob is the fallback, and loadout sets win when both exist`() = synchronously {
        val oldBlob = blob(72, mapOf(0 to piece("Old", 0)))
        val old = GearSection.view(contextWith(oldBlob, worn = 3))
        assertEquals("blob", old.wardrobe.source)
        assertEquals("Old Helmet", plain(old.wardrobe.sets[0][0]))
        assertEquals(2, old.wardrobe.worn)
        // loadout.armor present but without a single set: the blob is still used
        val emptySets = GearSection.view(contextWith(oldBlob, loadout = loadoutOf(setsJson(null))))
        assertEquals("blob", emptySets.wardrobe.source)
        assertEquals("Old Helmet", plain(emptySets.wardrobe.sets[0][0]))
        val both = GearSection.view(contextWith(oldBlob, loadout = loadoutOf(setsJson(null, lset(1, "HELMET" to piece("New", 0))))))
        assertEquals("loadout", both.wardrobe.source)
        assertEquals("New Helmet", plain(both.wardrobe.sets[0][0]))
    }

    @Test
    fun `neither loadout sets nor a blob is the notice, and an empty loadout object says it holds no sets`() = synchronously {
        val none = GearSection.view(contextWith(null, worn = 13))
        assertFalse(none.wardrobe.present)
        val text = built(none).first.all()
        assertTrue("No wardrobe data" in text && "did not send a wardrobe" in text && "PAGE 1" !in text, text)
        val hollow = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(null))))
        assertTrue(hollow.wardrobe.noData && hollow.wardrobe.present)
        assertTrue("holds no sets" in built(hollow).first.all())
    }

    @Test
    fun `an undecodable loadout piece is counted and said, never a lost set`() = synchronously {
        val bad = legacyItem(9999, "Unknown", "§6§lLEGENDARY HELMET")
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(null, lset(1, "HELMET" to bad, "BOOTS" to piece("Ok", 3))))))
        assertEquals(1, v.wardrobe.unreadable)
        assertEquals(1, v.wardrobe.filled)
        assertEquals("Ok Boots", plain(v.wardrobe.sets[0][3]))
        assertTrue("1 wardrobe item could not be read." in built(v).first.all())
    }

    @Test
    fun `the loadout readers skip what they cannot read and ignore unknown keys`() {
        val raw = obj(
            "loadout" to obj(
                "armor" to obj(
                    "1" to JsonPrimitive(5), // not an object
                    "abc" to lset(99, "HELMET" to piece("X", 0)), // not a numeric key
                    "2" to obj("id" to JsonPrimitive(2), "HELMET" to obj("data" to JsonPrimitive("")), "BOOTS" to JsonPrimitive("x"), "NEW_KEY" to JsonPrimitive(1)),
                    "7" to lset(7, "LEGGINGS" to piece("Y", 2)),
                    "equipped_set" to JsonPrimitive(7.0),
                ),
                "equipment" to obj("equipped_set" to JsonPrimitive(-1)),
                "loadouts" to obj("4" to obj("name" to JsonPrimitive("Mine"), "armor_set_id" to JsonPrimitive(7)), "x" to JsonPrimitive(1), "6" to JsonPrimitive(2)),
                "future" to obj("a" to JsonPrimitive(1)),
            ),
        )
        val m = SkyblockProfileMember(raw)
        val armor = m.armorSets!!
        assertEquals(listOf(2, 7), armor.sets.map { it.id })
        assertEquals(7, armor.equippedId)
        assertEquals(7, armor.highestId)
        assertEquals(1, armor.blobs.size) // blank data and a non-object piece are empty slots
        assertEquals(listOf(null, null, null, null), armor.sets[0].pieces)
        assertEquals(listOf(null, null, armor.sets[1].pieces[2], null), armor.sets[1].pieces)
        assertNotNull(armor.sets[1].pieces[2])
        val equipment = m.equipmentSets!!
        assertTrue(equipment.sets.isEmpty())
        assertNull(equipment.equippedId)
        assertEquals(listOf(4), m.loadouts.map { it.id })
        assertEquals("Mine", m.loadouts[0].name)
        assertEquals(7, m.loadouts[0].armorSetId)
        assertNull(m.loadouts[0].equipmentSetId)
        assertNull(SkyblockProfileMember(JsonObject(emptyMap())).armorSets)
        assertTrue(SkyblockProfileMember(JsonObject(emptyMap())).loadouts.isEmpty())
    }

    @Test
    fun `loadout pieces decode together, in set and row order, each item once`() = synchronously {
        val armor = setsJson(null, lset(2, "BOOTS" to piece("B", 3), "HELMET" to piece("B", 0)), lset(1, "CHESTPLATE" to piece("A", 1)))
        val m = contextWith(null, loadout = loadoutOf(armor)).member
        val sets = m.armorSets!!
        val blob = ProfileItems.getPieces(m.raw, "armor") { sets.blobs }
        assertEquals(listOf("A Chestplate", "B Helmet", "B Boots"), blob.stacks.map { plain(it) })
        assertTrue(ProfileItems.getPieces(m.raw, "armor") { error("memoized") } === blob)
    }

    // ------------------------------------------------------------------ the worn set's column shows what is on the body

    @Test
    fun `an empty worn armor set shows the worn armor, helmet first, and counts in the header`() = synchronously {
        val armor = setsJson(3, lset(1, "HELMET" to piece("One", 0)), lset(3))
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(armor)))
        assertEquals(2, v.wardrobe.worn)
        assertTrue(v.wardrobe.wornFromBody)
        assertTrue(v.armor.all { it != null })
        val shown = v.wardrobe.sets[2]
        assertEquals(v.armor.map { it?.let(GearSection::typeLine) }, shown.map { it?.let(GearSection::typeLine) })
        assertEquals(listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"), shown.map { it!!.let(GearSection::typeLine).substringAfterLast(' ') })
        assertEquals(2, v.wardrobe.setsWithPieces)
        assertTrue("2 of 18 sets" in built(v).first.all())
        assertEquals(1, v.wardrobe.filled) // the decode count is the set data's own: the body's items are display only
    }

    @Test
    fun `an empty worn equipment set shows equipment_contents in slot order`() = synchronously {
        val v = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(1, lset(1)), setsJson(2, lset(1, "EQUIPMENT_SLOT_1" to piece("Own", 0)), lset(2)))))
        val es = v.equipmentSets!!
        assertTrue(es.wornFromBody)
        assertTrue(v.equipment.any { it != null })
        assertEquals(v.equipment.map { plain(it) }, es.sets[1].map { plain(it) })
        assertEquals(listOf("Own Helmet", null, null, null), es.sets[0].map { plain(it) })
        assertTrue("2 of 9 sets" in built(v).first.all())
    }

    @Test
    fun `the worn set's own pieces always win over the body's items`() = synchronously {
        val own = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(3, lset(3, "CHESTPLATE" to piece("Own", 1))))))
        assertFalse(own.wardrobe.wornFromBody)
        assertEquals(listOf(null, "Own Chestplate", null, null), own.wardrobe.sets[2].map { plain(it) })
        // an own piece that did not decode still counts as the set having pieces: nothing is drawn over it
        val bad = legacyItem(9999, "Unknown", "§6§lLEGENDARY HELMET")
        val unreadable = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(3, lset(3, "HELMET" to bad)))))
        assertFalse(unreadable.wardrobe.wornFromBody)
        assertTrue(unreadable.wardrobe.sets[2].all { it == null })
        assertEquals(1, unreadable.wardrobe.unreadable)
        val ownEquipment = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(1, lset(1)), setsJson(2, lset(2, "EQUIPMENT_SLOT_3" to piece("Own", 2))))))
        assertFalse(ownEquipment.equipmentSets!!.wornFromBody)
        assertEquals(listOf(null, null, "Own Leggings", null), ownEquipment.equipmentSets.sets[1].map { plain(it) })
    }

    @Test
    fun `without a worn set nothing is drawn from the body, and the old blob's empty worn column is filled too`() = synchronously {
        val noWorn = GearSection.view(contextWith(null, loadout = loadoutOf(setsJson(null, lset(1, "HELMET" to piece("One", 0))))))
        assertNull(noWorn.wardrobe.worn)
        assertFalse(noWorn.wardrobe.wornFromBody)
        assertEquals(1, noWorn.wardrobe.setsWithPieces)
        // old blob, worn slot 3 (1-based) with an empty column: filled from inv_armor; set 5's own pieces would win
        val oldBlob = blob(72, mapOf(0 to piece("Old", 0), 4 to piece("Five", 0)))
        val old = GearSection.view(contextWith(oldBlob, worn = 3))
        assertEquals("blob", old.wardrobe.source)
        assertTrue(old.wardrobe.wornFromBody)
        assertEquals(old.armor.map { plain(it) }, old.wardrobe.sets[2].map { plain(it) })
        assertEquals(3, old.wardrobe.setsWithPieces)
        val ownWins = GearSection.view(contextWith(oldBlob, worn = 5))
        assertFalse(ownWins.wardrobe.wornFromBody)
        assertEquals("Five Helmet", plain(ownWins.wardrobe.sets[4][0]))
        assertNull(ownWins.wardrobe.sets[4][1])
    }

    @Test
    fun `the market value's wardrobe group holds the sets' own items only, so the worn items are not valued twice`() = synchronously {
        val armor = setsJson(3, lset(1, "HELMET" to piece("One", 0), "BOOTS" to piece("One", 3)), lset(3))
        val equipment = setsJson(2, lset(1, "EQUIPMENT_SLOT_2" to piece("E", 1)), lset(2))
        val ctx = contextWith(null, loadout = loadoutOf(armor, equipment))
        val v = GearSection.view(ctx)
        assertTrue(v.wardrobe.wornFromBody && v.equipmentSets!!.wornFromBody)
        val groups = MarketValueEstimates.decodedGroups(ctx.member)!!.associate { it.first to it.second }
        val wardrobeItems = groups["Wardrobe"]!!.flatMap { it.stacks }.filter { it != null && !it.isEmpty }
        assertEquals(listOf("One Helmet", "One Boots", "E Chestplate"), wardrobeItems.map { plain(it) })
        val body = groups["Armor and equipment"]!!.flatMap { it.stacks }.filter { it != null && !it.isEmpty }
        assertEquals(8, body.size) // inv_armor and equipment_contents, valued once, there
    }
}
