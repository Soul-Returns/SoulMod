package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.HotmTreeClient
import com.soulreturns.data.hypixel.HotmTreeSnapshot
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.data.hypixel.SkillCapsEnvelope
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.soulreturns.features.profileviewer.gui.ProfileItems
import com.soulreturns.features.profileviewer.gui.kit.ItemLineNode
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.SlotGridNode
import com.soulreturns.features.profileviewer.gui.kit.SlotNode
import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every registered section built against the shared fixtures (`src/test/resources/profileviewer/fixtures/`,
 * the same files `/soul dev spv fixture` plays in the client), measured at the 1280x720 and narrow body
 * widths and painted on a recording canvas. This is the template for a section's own data + layout test:
 * build it from a fixture, then assert on the text it paints and on its height.
 */
class SectionBuildTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sections build real vanilla [ItemStack]s (decoded items, node icons): the registries must exist, and
     * item default components bound (a real game binds them on the data pack load; a bare bootstrap does not).
     */
    @BeforeTest
    fun boot() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        bindItemComponents
        ProfileItems.synchronous = true
    }

    private companion object {
        val bindItemComponents: Unit by lazy {
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach { it.apply() }
        }
    }

    /** Deterministic text metrics: 5 px per character (6 bold), so layout numbers are stable. */
    private class Recorder(override val state: ViewState = ViewState()) : KitCanvas {
        val texts = ArrayList<String>()
        val items = ArrayList<ItemStack>()
        val itemTooltips = ArrayList<ItemStack>()
        var hoverAll = false
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
        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {
            items += stack
        }
        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {}
        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int) = hoverAll
        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) {}
        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {}
        override fun itemTooltip(stack: ItemStack) {
            itemTooltips += stack
        }
        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {}
        override fun openSection(id: String) {}

        /** Everything painted as one line (wrapped lines rejoined), § codes stripped. */
        fun all(): String = texts.joinToString(" ").replace(Regex("§."), "").replace(Regex("\\s+"), " ")
    }

    private fun fixture(name: String): JsonObject =
        json.parseToJsonElement(javaClass.getResource("/profileviewer/fixtures/$name.json")!!.readText()).jsonObject

    private fun context(name: String): ProfileContext {
        val f = fixture(name)
        val identity = PlayerIdentity(f["uuid"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content)
        val profiles = SkyblockProfiles.from(f["profiles"]!!.jsonObject)
        val profile = profiles.pick(null)!!
        val soul = (f["soulStats"] as? JsonObject)?.let { spec ->
            val status = spec["status"]!!.jsonPrimitive.int
            if (status == 200) {
                SoulStatsAnswer(json.decodeFromJsonElement(SoulStatsEnvelope.serializer(), spec["body"]!!), SoulStatsClient.CODE_OK)
            } else {
                SoulStatsAnswer(null, if (status == 403) SoulStatsClient.CODE_NOT_SHARED else SoulStatsClient.CODE_NOT_A_USER)
            }
        }
        val caps = (f["caps"] as? JsonObject)?.let { SkillCapsClient.fromEnvelope(json.decodeFromJsonElement(SkillCapsEnvelope.serializer(), it)) }
        return ProfileContext(
            identity, profiles, profile, profile.member(identity.uuidUndashed)!!, isSelf = false,
            caps = caps?.forProfile(profile.profileId, profile.selected), capsLoaded = true, soulAnswer = soul,
            nowMs = 1_800_000_000_000L, opener = {}, looker = {},
        )
    }

    /** Builds [sectionId] for the fixture, lays it out at [w] and paints it; returns the canvas and height. */
    private fun render(fixture: String, sectionId: String, w: Int = 969): Pair<Recorder, Int> {
        val ctx = context(fixture)
        val section = ProfileSections.byId(sectionId)!!
        val c = Recorder()
        val ui = Ui(section.id, c.state)
        section.build(ctx, ui)
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(c, w)
        root.paint(c, 0, 0, w, h)
        return c to h
    }

    @Test
    fun `every section builds for every fixture at both widths`() {
        for (fixture in listOf("sam", "joe", "builder")) {
            val ctx = context(fixture)
            for (section in ProfileSections.ALL) {
                if (!section.visible(ctx) || section.locked(ctx) != null) continue
                for (w in listOf(969, 476)) {
                    val (_, h) = render(fixture, section.id, w)
                    assertTrue(h > 0, "$fixture/${section.id} at $w is empty")
                }
            }
        }
    }

    @Test
    fun `slayers lists every boss with its level`() {
        val text = render("sam", "slayers").first.all()
        for (boss in listOf("Revenant Horror", "Tarantula Broodfather", "Sven Packmaster", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend")) {
            assertTrue(boss in text, "missing $boss")
        }
    }

    @Test
    fun `skills and dungeons read the fixture`() {
        val skills = render("sam", "skills").first.all()
        assertTrue("Farming" in skills && "Runecrafting" in skills)
        val dungeons = render("sam", "dungeons").first.all()
        assertTrue("Catacombs" in dungeons)
        assertTrue("Floor 7" in dungeons || "F7" in dungeons)
    }

    @Test
    fun `skills api off shows the notice, not zeros`() {
        val text = render("builder", "skills").first.all()
        assertTrue("Skills API" in text, text)
        assertTrue("Farming" !in text)
    }

    @Test
    fun `soul section distinguishes shared, private and not a user`() {
        assertTrue("Mineshaft" in render("sam", "soul").first.all())
        assertTrue("Builder42 keeps their Soul stats private" in render("builder", "soul").first.all())
        assertTrue("Fisher_Joe doesn't use Soul" in render("joe", "soul").first.all())
    }

    @Test
    fun `overview shows gear, the active pet, the market value block and the highlights`() {
        HotmTreeClient.preview(json.decodeFromString(HotmTreeSnapshot.serializer(), javaClass.getResource("/profileviewer/fragments/hotm-tree.json")!!.readText()))
        try {
            val sam = render("sam", "overview").first.all()
            for (want in listOf("Gear", "Weapons", "All gear", "Golden Dragon", "Holding", "Market value", "items at base price", "Heart of the Mountain", "Tier ", "Fairy souls")) {
                assertTrue(want in sam, "missing '$want' in $sam")
            }
            val builder = render("builder", "overview").first.all()
            for (want in listOf("Gear is hidden", "Builder42's Inventory API is off.", "Rabbit", "No pet item", "Banking API off")) {
                assertTrue(want in builder, "missing '$want' in $builder")
            }
        } finally {
            HotmTreeClient.preview(HotmTreeSnapshot())
        }
    }

    @Test
    fun `layout is deterministic and narrower means taller`() {
        val (_, wide) = render("sam", "slayers", 969)
        val (_, again) = render("sam", "slayers", 969)
        val (_, narrow) = render("sam", "slayers", 476)
        assertEquals(wide, again)
        assertTrue(narrow > wide, "narrow $narrow should exceed wide $wide")
    }

    @Test
    fun `slot nodes paint real item stacks and offer their tooltip`() {
        val sword = ItemStack(Items.DIAMOND_SWORD)
        val pick = ItemStack(Items.IRON_PICKAXE, 3)
        val c = Recorder()
        c.hoverAll = true
        val nodes = listOf(
            SlotNode(sword),
            SlotGridNode(listOf(pick, null, ItemStack.EMPTY, sword), cols = 9),
            ItemLineNode(pick, Rich.of("§6Iron Pickaxe"), sub = Rich.of("Mining")),
        )
        val root = StackNode(nodes, KitStyle.GAP)
        val h = root.height(c, 300)
        root.paint(c, 0, 0, 300, h)
        assertEquals(listOf(sword, pick, sword, pick), c.items)
        assertEquals(c.items, c.itemTooltips)
        assertTrue("Iron Pickaxe" in c.all())
    }
}
