package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.GearText
import com.soulreturns.data.hypixel.PlayerIdentity
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.SharedConstants
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import java.io.ByteArrayInputStream
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

    private fun view(worn: Int?, wardrobeSets: Int): GearSection.View {
        val names = listOf("Helmet", "Chestplate", "Leggings", "Boots")
        val armor = names.map { stack(Items.LEATHER_HELMET, "§6Ancient Necron's $it", "§6§lLEGENDARY DUNGEON ${it.uppercase()}", "ancient") }
        val set = List(4) { armor[it] }
        val sets = List(18) { if (it < wardrobeSets) set else listOf(null, null, null, null) }
        val weapons = listOf(
            GearSection.Weapon(stack(Items.IRON_SWORD, "§6Heroic Hyperion", "§6§lLEGENDARY DUNGEON SWORD"), "Hotbar 1"),
            GearSection.Weapon(stack(Items.FISHING_ROD, "§6Rod of the Sea", "§6§lLEGENDARY FISHING ROD"), "Ender Chest 3"),
        )
        return GearSection.View(
            armor, listOf(stack(Items.PAPER, "§5Implosion Belt", "§5§lEPIC BELT"), null, null, null), GearSection.Part(),
            sets, worn, GearSection.Part(), weapons, GearSection.Part(),
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
        assertTrue("PAGE 1" in text && "PAGE 2" in text, text)
    }

    @Test
    fun `an empty wardrobe and no weapons still build`() {
        val v = view(worn = null, wardrobeSets = 0)
        val empty = GearSection.View(v.armor, v.equipment, v.equipped, v.wardrobe, null, v.wardrobePart, emptyList(), GearSection.Part())
        val text = built(empty).first.all()
        assertTrue("0 of 18 sets" in text, text)
        assertTrue("No weapons or tools found" in text, text)
    }

    @Test
    fun `unreadable parts say so instead of showing empty slots`() {
        val v = view(worn = 0, wardrobeSets = 3)
        val broken = GearSection.View(v.armor, v.equipment, GearSection.Part(failed = true), v.wardrobe, v.worn, GearSection.Part(loading = true), v.weapons, v.weaponsPart)
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
}
