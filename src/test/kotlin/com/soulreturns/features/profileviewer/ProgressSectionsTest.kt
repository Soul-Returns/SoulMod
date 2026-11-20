package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.CollectionTable
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.auctionStats
import com.soulreturns.data.hypixel.bestiaryDeaths
import com.soulreturns.data.hypixel.bestiaryMobs
import com.soulreturns.data.hypixel.collectionGroups
import com.soulreturns.data.hypixel.craftedMinions
import com.soulreturns.data.hypixel.essenceAmounts
import com.soulreturns.data.hypixel.experimentBests
import com.soulreturns.data.hypixel.giftStats
import com.soulreturns.data.hypixel.hasCollections
import com.soulreturns.data.hypixel.highestCriticalHit
import com.soulreturns.data.hypixel.lifetimeKills
import com.soulreturns.data.hypixel.mythologicalKills
import com.soulreturns.data.hypixel.soulflowAmount
import com.soulreturns.data.hypixel.splitTierEntry
import com.soulreturns.data.hypixel.uniqueMinionCrafts
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.world.item.ItemStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Collections, Bestiary and Misc: the accessors over fixture members (the shared fixtures with
 * `fragments/progress-<name>.json` merged in) and the three sections painted on a recording canvas.
 * `sam` is a co-op profile with everything, `joe` a solo ironman with little, `builder` has the
 * Collections and Banking APIs off.
 */
class ProgressSectionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Deterministic text metrics: 5 px per character (6 bold). */
    private class Recorder(override val state: ViewState = ViewState()) : KitCanvas {
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

        fun all(): String = texts.joinToString(" ").replace(Regex("§."), "").replace(Regex("\\s+"), " ")
    }

    private fun resource(path: String): JsonObject =
        json.parseToJsonElement(javaClass.getResource("/profileviewer/$path.json")!!.readText()).jsonObject

    private fun deepMerge(a: JsonObject, b: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(a)
        for ((k, v) in b) {
            val old = out[k]
            out[k] = if (old is JsonObject && v is JsonObject) deepMerge(old, v) else v
        }
        return JsonObject(out)
    }

    /** The fixture with its first profile (the selected one) merged with the progress fragment. */
    private fun merged(name: String): JsonObject {
        val f = resource("fixtures/$name")
        val frag = resource("fragments/progress-$name")
        val uuid = f["uuid"]!!.jsonPrimitive.content
        val envelope = f["profiles"]!!.jsonObject
        val list = envelope["profiles"]!!.jsonArray
        val first = list[0].jsonObject
        val members = first["members"]!!.jsonObject
        val member = deepMerge(members[uuid]!!.jsonObject, frag["member"]!!.jsonObject)
        var newFirst = JsonObject(first + ("members" to JsonObject(members + (uuid to member))))
        frag["profile"]?.jsonObject?.let { newFirst = deepMerge(newFirst, it) }
        val newEnvelope = JsonObject(envelope + ("profiles" to JsonArray(listOf<JsonElement>(newFirst) + list.drop(1))))
        return JsonObject(f + ("profiles" to newEnvelope))
    }

    private fun context(name: String): ProfileContext {
        val f = merged(name)
        val identity = PlayerIdentity(f["uuid"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content)
        val profiles = SkyblockProfiles.from(f["profiles"]!!.jsonObject)
        val profile = profiles.pick(null)!!
        return ProfileContext(
            identity, profiles, profile, profile.member(identity.uuidUndashed)!!, isSelf = false,
            caps = null, capsLoaded = true, soulAnswer = null,
            nowMs = 1_800_000_000_000L, opener = {}, looker = {},
        )
    }

    private fun member(name: String): SkyblockProfileMember = context(name).member

    /** Builds [sectionId] for the fixture (after [setup] on the state), lays it out at [w], paints it. */
    private fun render(fixture: String, sectionId: String, w: Int = 969, setup: (ViewState) -> Unit = {}): Pair<Recorder, Int> {
        val ctx = context(fixture)
        val section = ProfileSections.byId(sectionId)!!
        val c = Recorder()
        setup(c.state)
        val ui = Ui(section.id, c.state)
        section.build(ctx, ui)
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(c, w)
        root.paint(c, 0, 0, w, h)
        return c to h
    }

    // ------------------------------------------------------------------ accessors

    @Test
    fun `tier entries split at the last underscore`() {
        assertEquals("INK_SACK:3" to 9, splitTierEntry("INK_SACK:3_9"))
        assertEquals("COAL" to -1, splitTierEntry("COAL_-1"))
        assertEquals("MAGMA_CUBE" to 12, splitTierEntry("MAGMA_CUBE_12"))
        assertNull(splitTierEntry("NOUNDERSCORE"))
        assertNull(splitTierEntry("COAL_x"))
        assertNull(splitTierEntry("_5"))
    }

    @Test
    fun `collections take the highest stated tier and group like the mock`() {
        val groups = member("sam").collectionGroups()!!.associateBy { it.category }
        assertEquals(CollectionTable.CATEGORIES + CollectionTable.OTHER, groups.keys.toList())
        val farming = groups.getValue("Farming")
        assertEquals(17, farming.entries.size)
        val wheat = farming.entries.first { it.id == "WHEAT" }
        assertEquals(2_417_533L, wheat.count)
        assertEquals(9, wheat.tier)
        val cocoa = farming.entries.first { it.name == "Cocoa Beans" }
        assertEquals(9, cocoa.tier)
        assertEquals(5, farming.unlocked)
        assertEquals(6, groups.getValue("Mining").unlocked)
        // A started collection whose only tier entry is -1 has no tier.
        val magmafish = groups.getValue("Fishing").entries.first { it.id == "MAGMA_FISH" }
        assertEquals(7810L, magmafish.count)
        assertEquals(0, magmafish.tier)
        // An unstarted listed collection is still there, with nothing.
        val seeds = farming.entries.first { it.id == "SEEDS" }
        assertEquals(0L, seeds.count)
        assertEquals(0, seeds.tier)
    }

    @Test
    fun `unknown collection ids land in Other, crafted counters do not`() {
        val groups = member("sam").collectionGroups()!!
        val other = groups.first { it.category == CollectionTable.OTHER }
        assertEquals(listOf("DOUBLE_PLANT"), other.entries.map { it.id })
        assertEquals("Double Plant", other.entries[0].name)
        assertEquals(3, other.entries[0].tier)
        assertTrue(groups.flatMap { it.entries }.none { it.id == "ENCHANTED_DIAMOND" })
        // The item catalog names an id when it knows it.
        val named = member("sam").collectionGroups { if (it == "DOUBLE_PLANT") "§aSunflower" else null }!!
        assertEquals("Sunflower", named.first { it.category == CollectionTable.OTHER }.entries[0].name)
    }

    @Test
    fun `collections api off is null, not zero`() {
        val m = member("builder")
        assertFalse(m.hasCollections)
        assertNull(m.collectionGroups())
    }

    @Test
    fun `minions keep the highest tier per type`() {
        val m = member("sam")
        val minions = m.craftedMinions!!
        assertEquals(10, minions.size)
        assertEquals(13, m.uniqueMinionCrafts)
        assertEquals("Cobblestone", minions[0].name)
        assertEquals(12, minions[0].tier)
        assertEquals(listOf("Coal", "Sugar Cane"), minions.filter { it.tier == 11 }.map { it.name })
        val names = minions.associate { it.id to it.name }
        assertEquals("Cocoa Beans", names["COCOA"])
        assertEquals("End Stone", names["ENDER_STONE"])
        assertEquals("Cave Spider", names["CAVESPIDER"])
        assertEquals("Nether Wart", names["NETHER_WARTS"])
        assertEquals("Sunflower", names["SUNFLOWER"])
    }

    @Test
    fun `bestiary sums variants and skips the last killed string`() {
        val m = member("sam")
        val mobs = m.bestiaryMobs
        assertEquals(7, mobs.size)
        assertEquals("Zealot" to 48_210L, mobs[0].name to mobs[0].kills)
        assertEquals(3000L, mobs.first { it.name == "Lapis Zombie" }.kills)
        assertEquals(74L, m.bestiaryDeaths)
        assertEquals(listOf("Zealot", "Enderman"), mobs.take(2).map { it.name })
    }

    @Test
    fun `misc numbers read the response`() {
        val m = member("sam")
        val essence = m.essenceAmounts!!
        assertEquals(listOf("WITHER", "DRAGON", "SPIDER", "UNDEAD", "DIAMOND", "GOLD", "ICE", "CRIMSON"), essence.map { it.type })
        assertEquals(41_230L, essence[0].amount)
        assertEquals(1_412_330L, m.lifetimeKills)
        assertEquals(40_212L, m.mythologicalKills)
        assertNotNull(m.highestCriticalHit)
        val a = m.auctionStats!!
        assertEquals(812L to 1204L, a.won to a.created)
        assertEquals(2_410_000_000L, a.coinsSpent)
        assertEquals(212L to 148L, m.giftStats)
        assertEquals(48_212L, m.soulflowAmount)
    }

    @Test
    fun `experiment bests`() {
        val sam = member("sam").experimentBests!!
        assertEquals("Metaphysical", sam.superpairsName)
        assertEquals(24, sam.chronomatronBest)
        assertEquals(17, sam.ultrasequencerBest)
        // Never played Superpairs and Ultrasequencer: null, not zero.
        val builder = member("builder").experimentBests!!
        assertNull(builder.superpairsTier)
        assertEquals(9, builder.chronomatronBest)
        assertNull(builder.ultrasequencerBest)
        assertNull(member("joe").experimentBests)
    }

    @Test
    fun `essence of a member without currencies is null and absent types are zero`() {
        assertEquals(0L, member("joe").essenceAmounts!!.first { it.type == "DRAGON" }.amount)
        assertEquals(210L, member("joe").essenceAmounts!!.first { it.type == "WITHER" }.amount)
        assertNull(member("joe").auctionStats)
    }

    // ------------------------------------------------------------------ sections

    @Test
    fun `collections section lists the groups with counts and tiers`() {
        val text = render("sam", "collections").first.all()
        for (s in listOf("Farming", "Mining", "Combat", "Foraging", "Fishing", "Other", "Cocoa Beans", "Double Plant", "5 of 17 unlocked", "2.4M", "IX")) {
            assertTrue(s in text, "missing '$s' in: $text")
        }
        assertTrue("Enchanted Diamond" !in text)
    }

    @Test
    fun `collections filter hides the rest`() {
        val text = render("sam", "collections") { it.field("collections.query").set("cocoa") }.first.all()
        assertTrue("Cocoa Beans" in text)
        assertTrue("Cobblestone" !in text)
        assertTrue("Mining" !in text)
        val none = render("sam", "collections") { it.field("collections.query").set("zzz") }.first.all()
        assertTrue("No collection matches" in none, none)
    }

    @Test
    fun `collections api off shows the notice`() {
        val text = render("builder", "collections").first.all()
        assertTrue("Collections API" in text, text)
        assertTrue("Farming" !in text)
        assertEquals("Collections API off", ProfileSections.byId("collections")!!.locked(context("builder")))
        assertNull(ProfileSections.byId("collections")!!.locked(context("sam")))
    }

    @Test
    fun `minions tab shows the tiles and pills`() {
        val text = render("sam", "collections") { it.set("collections.tab", "min") }.first.all()
        for (s in listOf("Unique minions", "13", "Types", "10", "At tier XI+", "Cobblestone", "XII", "Cocoa Beans", "highest tier crafted")) {
            assertTrue(s in text, "missing '$s' in: $text")
        }
        val filtered = render("sam", "collections") {
            it.set("collections.tab", "min")
            it.field("collections.query").set("coal")
        }.first.all()
        assertTrue("Coal" in filtered && "Cobblestone" !in filtered, filtered)
    }

    @Test
    fun `bestiary shows milestone, totals and every mob`() {
        val text = render("sam", "bestiary").first.all()
        for (s in listOf("Milestone", "318", "92.1k", "7 mob types", "74", "Zealot", "48,210", "Lapis Zombie", "3,000", "Water Hydra", "kills are exact")) {
            assertTrue(s in text, "missing '$s' in: $text")
        }
        assertTrue("last_killed" !in text && "Last Killed" !in text)
    }

    @Test
    fun `bestiary search filters by name`() {
        val text = render("sam", "bestiary") { it.field("bestiary.query").set("hydra") }.first.all()
        assertTrue("Water Hydra" in text && "Zealot" !in text, text)
    }

    @Test
    fun `misc for a co-op profile with everything`() {
        val text = render("sam", "misc").first.all()
        for (s in listOf(
            "Purse", "412,880,310", "Co-op bank", "2,412,000,000", "Personal bank", "34,000,000", "Essence", "Wither", "41.2k",
            "Highest critical hit", "148,320,41", "Mythological kills", "40,212", "2.4B", "3.1B", "Metaphysical", "24 rounds", "17 rounds",
            "Fairy souls", "253", "212 / 148", "Soulflow", "48,212", "Nov 22, 2021",
        )) {
            assertTrue(s in text, "missing '$s' in: $text")
        }
    }

    @Test
    fun `misc for a solo profile and for the apis off`() {
        val joe = render("joe", "misc").first.all()
        assertTrue("Co-op bank" !in joe && "Bank" in joe && "186,400,000" in joe, joe)
        assertTrue("Experimentation Table" in joe)
        val builder = render("builder", "misc").first.all()
        assertTrue("Banking API off" in builder, builder)
        assertTrue("640,210" in builder, builder)
    }

    @Test
    fun `the three sections build at both widths for every fixture`() {
        for (fixture in listOf("sam", "joe", "builder")) {
            for (id in listOf("collections", "bestiary", "misc")) {
                for (w in listOf(969, 476)) {
                    val (_, h) = render(fixture, id, w)
                    assertTrue(h > 0, "$fixture/$id at $w is empty")
                }
                val (_, minions) = render(fixture, id, 476) { it.set("$id.tab", "min") }
                assertTrue(minions > 0)
            }
        }
    }

    @Test
    fun `narrower is taller`() {
        assertTrue(render("sam", "misc", 476).second > render("sam", "misc", 969).second)
        assertTrue(render("sam", "collections", 476).second > render("sam", "collections", 969).second)
    }
}
