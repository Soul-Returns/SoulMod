package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.crimsonIsle
import com.soulreturns.data.hypixel.fishingCounts
import com.soulreturns.data.hypixel.gardenPlayer
import com.soulreturns.data.hypixel.jacobsContest
import com.soulreturns.data.hypixel.kuudraCompletions
import com.soulreturns.data.hypixel.riftProgress
import com.soulreturns.data.hypixel.trophyFishing
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Crimson Isle, Rift, Fishing and Farming: the accessors on the activities fragments
 * (`src/test/resources/profileviewer/fragments/activities-*.json`, member keys merged over the shared
 * fixtures of the same name) and the sections built from them.
 */
class ActivitiesSectionsTest {
    private val json = Json { ignoreUnknownKeys = true }

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

    private fun resource(path: String): JsonObject = json.parseToJsonElement(javaClass.getResource(path)!!.readText()).jsonObject

    private fun merge(base: JsonObject, over: JsonObject): JsonObject {
        val out = LinkedHashMap(base)
        for ((k, v) in over) {
            val old = out[k]
            out[k] = if (old is JsonObject && v is JsonObject) merge(old, v) else v
        }
        return JsonObject(out)
    }

    /** The shared fixture [name] with the activities fragment merged into its member. */
    private fun member(name: String): SkyblockProfileMember {
        val (_, member) = context(name)
        return member
    }

    private fun context(name: String): Pair<ProfileContext, SkyblockProfileMember> {
        val f = resource("/profileviewer/fixtures/$name.json")
        val identity = PlayerIdentity(f["uuid"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content)
        val profiles = SkyblockProfiles.from(f["profiles"]!!.jsonObject)
        val profile = profiles.pick(null)!!
        val base = profile.member(identity.uuidUndashed)!!
        val member = SkyblockProfileMember(merge(base.raw, resource("/profileviewer/fragments/activities-$name.json")))
        val ctx = ProfileContext(
            identity, profiles, profile, member, isSelf = false, caps = null, capsLoaded = true, soulAnswer = null,
            nowMs = 1_800_000_000_000L, opener = {}, looker = {},
        )
        return ctx to member
    }

    private fun render(fixture: String, sectionId: String, w: Int = 969): Pair<Recorder, Int> {
        val ctx = context(fixture).first
        val section = ProfileSections.byId(sectionId)!!
        val c = Recorder()
        val ui = Ui(section.id, c.state)
        section.build(ctx, ui)
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(c, w)
        root.paint(c, 0, 0, w, h)
        return c to h
    }

    // ------------------------------------------------------------------ accessors

    @Test
    fun `crimson isle reads faction, reputation, dojo, pearls and contacts`() {
        val m = member("sam")
        val c = assertNotNull(m.crimsonIsle)
        assertEquals("mages", c.faction)
        assertEquals(8120, c.magesReputation)
        assertEquals(-1250, c.barbariansReputation)
        assertEquals(742L, c.matriarchPearls)
        assertEquals(6, c.abiphoneContacts)
        assertEquals(7, c.dojo.size)
        assertEquals(1000, c.dojo.first { it.name == "Force" }.points)
        assertEquals(420, c.dojo.first { it.name == "Control" }.points)
        assertEquals(319, m.kuudraCompletions.values.sum())
    }

    @Test
    fun `crimson isle absent stays null and untried tests have no points`() {
        assertNull(member("joe").crimsonIsle)
        val builder = assertNotNull(member("builder").crimsonIsle)
        assertNull(builder.faction)
        assertNull(builder.matriarchPearls)
        assertNull(builder.abiphoneContacts)
        assertTrue(builder.dojo.all { it.points == null })
    }

    @Test
    fun `trophy fish lists every known fish with tiers and skips frogs`() {
        val t = assertNotNull(member("sam").trophyFishing)
        assertEquals(18, t.fish.size)
        assertEquals(t.fish.map { it.name }.sorted(), t.fish.map { it.name })
        val karate = t.fish.first { it.name == "Karate Fish" }
        assertEquals(listOf(61, 40, 16, 3), karate.tiers)
        assertEquals(3, karate.bestTier)
        assertEquals(-1, t.fish.first { it.name == "Slugfish" }.bestTier)
        assertEquals(3, t.bestTier)
        assertNull(member("builder").trophyFishing)

        val raw = json.parseToJsonElement("""{"trophy_fish":{"mystery_fish":4,"mystery_fish_gold":4,"tree_frog_bronze":9,"total_caught":4}}""").jsonObject
        val odd = assertNotNull(SkyblockProfileMember(raw).trophyFishing)
        assertEquals(19, odd.fish.size)
        assertEquals(listOf(0, 0, 4, 0), odd.fish.first { it.name == "Mystery Fish" }.tiers)
        assertFalse(odd.fish.any { "Frog" in it.name })
    }

    @Test
    fun `fishing counters add treasure and large treasure`() {
        val c = member("sam").fishingCounts
        assertEquals(31_870L, c.seaCreaturesKilled)
        assertEquals(86_420L, c.itemsFished)
        assertEquals(2_052L, c.treasure)
        val none = member("builder").fishingCounts
        assertNull(none.seaCreaturesKilled)
        assertNull(none.treasure)
    }

    @Test
    fun `jacob's contests read medals, perks, brackets and bests`() {
        val j = assertNotNull(member("sam").jacobsContest)
        assertEquals(Triple(74, 31, 12), Triple(j.medalsGold, j.medalsSilver, j.medalsBronze))
        assertEquals(15, j.doubleDrops)
        assertEquals(10, j.farmingLevelCapPerk)
        assertEquals(4, j.contests)
        assertEquals("gold", j.crops.first { it.name == "Wheat" }.bracket)
        assertEquals(1_450_000L, j.crops.first { it.name == "Wheat" }.personalBest)
        assertEquals("platinum", j.crops.first { it.name == "Potato" }.bracket)
        assertEquals("diamond", j.crops.first { it.name == "Nether Wart" }.bracket)
        assertEquals("diamond", j.crops.first { it.name == "Sunflower" }.bracket)
        assertEquals(12, j.crops.size)
        assertTrue(j.crops.none { it.name == "Wild Rose" })
        val g = assertNotNull(member("sam").gardenPlayer)
        assertEquals(9_340L, g.copper)
        assertEquals(6L, g.larvaConsumed)
        assertNull(member("builder").jacobsContest)
    }

    @Test
    fun `a crop never contested has no bracket and no best`() {
        val j = assertNotNull(member("joe").jacobsContest)
        val pumpkin = j.crops.first { it.name == "Pumpkin" }
        assertNull(pumpkin.bracket)
        assertNull(pumpkin.personalBest)
        assertEquals("silver", j.crops.first { it.name == "Carrot" }.bracket)
        assertEquals(10, j.crops.size)
    }

    @Test
    fun `rift reads motes, souls, cats, timecharms, eyes and the prism`() {
        val r = assertNotNull(member("sam").riftProgress)
        assertEquals(48_213.0, r.motes)
        assertEquals(1_530_000L, r.lifetimeMotes)
        assertEquals(5, r.enigmaSouls)
        assertEquals(7, r.catsFound)
        assertEquals(3, r.timecharms)
        assertEquals(3, r.witherCageEyes)
        assertTrue(r.prismUsed)
        assertTrue(r.items.any)
        assertNotNull(r.items.inventory)
        assertNotNull(r.items.enderChest)
    }

    @Test
    fun `rift without the inventory api has no item blobs and an unentered rift is null`() {
        val r = assertNotNull(member("builder").riftProgress)
        assertFalse(r.items.any)
        assertFalse(r.prismUsed)
        assertNull(r.motes)
        assertNull(member("joe").riftProgress)
    }

    // ------------------------------------------------------------------ sections

    @Test
    fun `crimson isle section shows faction, reputation, kuudra tiers and dojo`() {
        val text = render("sam", "crimson").first.all()
        for (s in listOf("Mages", "8,120", "FACTION", "742", "Test of Force", "Test of Tenacity", "Infernal", "Basic", "319")) {
            assertTrue(s in text, "missing $s in $text")
        }
        assertTrue("No Crimson Isle progress" in render("joe", "crimson").first.all())
    }

    @Test
    fun `rift section is hidden before the rift and shows tiles and the api notice`() {
        assertFalse(ProfileSections.byId("rift")!!.visible(context("joe").first))
        val sam = render("sam", "rift").first.all()
        for (s in listOf("Enigma Souls", "7 / 9", "3 / 8", "3 / 7", "Used", "Rift inventory", "Armor and equipment", "Rift Ender Chest")) {
            assertTrue(s in sam, "missing $s in $sam")
        }
        val builder = render("builder", "rift").first.all()
        assertTrue("Inventory API is off" in builder, builder)
        assertTrue("Rift Ender Chest" !in builder)
    }

    @Test
    fun `fishing section lists every trophy fish and the counters`() {
        val text = render("sam", "fishing").first.all()
        for (s in listOf("Trophy Fish", "Karate Fish", "Volcanic Stonefish", "Slugfish", "Bronze", "Diamond", "31,870", "2,052 treasures", "Trophy Fishing")) {
            assertTrue(s in text, "missing $s in $text")
        }
        assertTrue("Frog" !in text)
        assertTrue("No trophy fish yet" in render("builder", "fishing").first.all())
    }

    @Test
    fun `farming section shows contests, medals, brackets and the garden placeholder`() {
        val text = render("sam", "farming").first.all()
        for (s in listOf("Contests", "15 / 15", "9,340", "6 larva consumed", "Wheat", "GOLD", "DIAMOND", "1,450,000", "Garden", "COMING LATER")) {
            assertTrue(s in text, "missing $s in $text")
        }
        assertTrue("2 / 15" in render("joe", "farming").first.all())
        assertTrue("No Jacob's contests entered" in render("builder", "farming").first.all())
    }

    @Test
    fun `every activities section builds at both widths and narrower is taller`() {
        for (fixture in listOf("sam", "joe", "builder")) {
            for (id in listOf("crimson", "rift", "fishing", "farming")) {
                val (_, wide) = render(fixture, id, 969)
                val (_, narrow) = render(fixture, id, 476)
                assertTrue(wide > 0 && narrow > 0, "$fixture/$id empty")
                assertTrue(narrow >= wide, "$fixture/$id narrow $narrow < wide $wide")
            }
        }
        assertTrue(render("sam", "fishing", 476).second > render("sam", "fishing", 969).second)
    }
}
